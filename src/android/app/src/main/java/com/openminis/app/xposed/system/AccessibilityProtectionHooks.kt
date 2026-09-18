package com.openminis.app.xposed.system

import android.content.Context
import android.os.Handler
import com.openminis.app.xposed.HookInstallEntry
import com.openminis.app.xposed.HookInstallReport
import com.openminis.app.xposed.HookInstallStatus
import com.openminis.app.xposed.HookLogger
import com.openminis.app.xposed.HookRegistrar
import com.openminis.app.xposed.HookSupport
import io.github.libxposed.api.XposedModule

/**
 * [T-eta-xposed-groups] How the accessibility enforcer gets into system_server.
 *
 * Ported from Eta `hook/system/AccessibilityProtectionHooks.kt` (Mangi-11/Eta @ c15de97);
 * attribution in THIRD_PARTY_LICENSES.md. The enforcer is attached at the end of
 * `startOtherServices`: by then the settings provider, the user manager and the activity manager
 * are up, which is what its first reconciliation reads. The method is deoptimized first, so a
 * compiled copy of the very start-up path being hooked cannot make the hook unreachable.
 *
 * Everything the enforcer needs is resolved here and handed over; a missing piece is a ledger row
 * and no enforcer, never an exception into the platform.
 */
object AccessibilityProtectionHooks {

    private const val GROUP = "AccessibilityProtection"

    private const val SYSTEM_SERVER_CLASS = "com.android.server.SystemServer"
    private const val TIMINGS_CLASS = "com.android.server.utils.TimingsTraceAndSlog"
    private const val BACKGROUND_THREAD_CLASS = "com.android.internal.os.BackgroundThread"

    @Volatile
    private var enforcer: AccessibilityServiceEnforcer? = null

    fun install(module: Any, classLoader: ClassLoader, log: (String) -> Unit): HookInstallReport {
        val xposed = module as? XposedModule
        if (xposed == null) {
            return HookInstallReport(
                GROUP,
                listOf(
                    HookInstallEntry(
                        group = GROUP,
                        id = "system.accessibility-protection",
                        description = "SystemServer.startOtherServices",
                        status = HookInstallStatus.FAILED,
                        detail = "the module entry did not pass a libxposed module instance",
                    ),
                ),
            )
        }
        val hooks = HookRegistrar(xposed, HookLogger { _, message -> log(message) }, GROUP)
        return hooks.install {
            val systemServer = HookSupport.findClassOrNull(classLoader, SYSTEM_SERVER_CLASS)
            val timings = HookSupport.findClassOrNull(classLoader, TIMINGS_CLASS)
            val startOtherServices = if (systemServer != null && timings != null) {
                HookSupport.findMethod(systemServer, "startOtherServices", timings)
            } else {
                null
            }
            if (startOtherServices == null) {
                missing(
                    "system.accessibility-protection",
                    "SystemServer.startOtherServices",
                    "AccessibilityProtection: startOtherServices(TimingsTraceAndSlog) not found",
                )
                return@install
            }
            HookSupport.deoptimize(
                xposed,
                hooks.logger,
                startOtherServices,
                "SystemServer.startOtherServices(TimingsTraceAndSlog)",
            )
            intercept(
                "system.accessibility-protection",
                startOtherServices,
                "SystemServer.startOtherServices",
            ) { chain ->
                val result = chain.proceed()
                startEnforcer(
                    systemServer = chain.getThisObject(),
                    classLoader = classLoader,
                    logger = hooks.logger,
                )
                result
            }
        }.report
    }

    @Synchronized
    private fun startEnforcer(systemServer: Any, classLoader: ClassLoader, logger: HookLogger) {
        if (enforcer != null) return
        val context = SystemServerContextResolver.resolve(systemServer)
        if (context == null) {
            logger.warn("AccessibilityProtection: system_server is up but has no system context")
            return
        }
        val handler = resolveBackgroundHandler(classLoader)
        if (handler == null) {
            logger.warn(
                "AccessibilityProtection: no Android BackgroundThread, " +
                    "leaving the accessibility protection off",
            )
            return
        }
        AccessibilityServiceEnforcer(handler = handler, logger = logger).also {
            enforcer = it
            it.start(context)
        }
    }

    /**
     * Android's own per-process background thread, reused so component checks and settings I/O stay
     * off the system_server main thread without the module creating a thread of its own.
     */
    private fun resolveBackgroundHandler(classLoader: ClassLoader): Handler? = runCatching {
        val backgroundThread = HookSupport.findClassOrNull(classLoader, BACKGROUND_THREAD_CLASS)
            ?: return@runCatching null
        HookSupport.findMethod(backgroundThread, "getHandler")?.invoke(null) as? Handler
    }.getOrNull()
}

/**
 * [T-eta-xposed-groups] The system_server context, from the object at hand or from ActivityThread.
 *
 * Ported from Eta `hook/system/AccessibilityProtectionHooks.kt` (Mangi-11/Eta @ c15de97). Both
 * owners and both names are tried because the field is `mSystemContext` on some releases and
 * `mContext` on others, and a caller may hold either the server or one of its services.
 */
object SystemServerContextResolver {

    private val CONTEXT_FIELDS = arrayOf("mSystemContext", "mContext")
    private val CONTEXT_METHODS = arrayOf("getSystemContext", "getContext")

    fun resolve(owner: Any?): Context? {
        contextFromOwner(owner)?.let { return it }
        return runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val current = activityThread
                .getDeclaredMethod("currentActivityThread")
                .apply { isAccessible = true }
                .invoke(null)
                ?: return@runCatching null
            activityThread
                .getDeclaredMethod("getSystemContext")
                .apply { isAccessible = true }
                .invoke(current) as? Context
        }.getOrNull()
    }

    private fun contextFromOwner(owner: Any?): Context? {
        if (owner == null) return null
        CONTEXT_FIELDS.forEach { name ->
            HookSupport.findField(owner.javaClass, name)?.let { field ->
                runCatching { field.get(owner) as? Context }.getOrNull()?.let { return it }
            }
        }
        CONTEXT_METHODS.forEach { name ->
            HookSupport.findMethod(owner.javaClass, name)?.let { method ->
                runCatching { method.invoke(owner) as? Context }.getOrNull()?.let { return it }
            }
        }
        return null
    }
}
