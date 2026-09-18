package com.openminis.app.xposed.system

import android.content.Context
import android.os.Binder
import android.os.IBinder
import com.openminis.app.xposed.HookInstallEntry
import com.openminis.app.xposed.HookInstallReport
import com.openminis.app.xposed.HookInstallStatus
import com.openminis.app.xposed.HookLogger
import com.openminis.app.xposed.HookRegistrar
import com.openminis.app.xposed.HookSupport
import com.openminis.app.xposed.ModulePrefs
import com.openminis.app.xposed.ModuleTargets
import io.github.libxposed.api.XposedModule

/**
 * [T-eta-xposed-groups] The system's own contextual search, made available and reachable where the
 * gesture already belongs to it.
 *
 * Ported from Eta `hook/system/ContextualSearchHooks.kt` (Mangi-11/Eta @ c15de97); attribution in
 * THIRD_PARTY_LICENSES.md. A ROM that does not ship the contextual-search configuration never
 * starts the service and never lets the navigation surface call it, so the gesture the user already
 * has ends up doing nothing. Three repairs, each reported on its own: the boot gate is answered
 * (`deviceHasConfigString`), the service is started at the end of `startOtherServices` if the ROM
 * still did not bring it up, and the two service-side answers - which package provides the search,
 * and who may start it - are given.
 *
 * The permission path is the one that has to stay narrow, and it does: see
 * [ContextualSearchCallerPolicy]. Everything runs in system_server, so a failure here is a missing
 * feature and never an exception into the platform.
 */
object ContextualSearchHooks {

    private const val GROUP = "ContextualSearch"

    private const val SYSTEM_SERVER_CLASS = "com.android.server.SystemServer"
    private const val TIMINGS_CLASS = "com.android.server.utils.TimingsTraceAndSlog"
    private const val SERVICE_CLASS = "com.android.server.contextualsearch.ContextualSearchManagerService"
    private const val STRING_RESOURCES_CLASS = "com.android.internal.R\$string"
    private const val CONFIG_RESOURCE_FIELD = "config_defaultContextualSearchPackageName"
    private const val PACKAGE_NAME_METHOD = "getContextualSearchPackageName"
    private const val PERMISSION_METHOD = "enforcePermission"
    private const val START_FUNCTION = "startContextualSearch"

    fun install(module: Any, classLoader: ClassLoader, log: (String) -> Unit): HookInstallReport {
        val xposed = module as? XposedModule
        if (xposed == null) {
            return HookInstallReport(
                GROUP,
                listOf(
                    HookInstallEntry(
                        group = GROUP,
                        id = "system.contextual-search",
                        description = "system contextual search",
                        status = HookInstallStatus.FAILED,
                        detail = "the module entry did not pass a libxposed module instance",
                    ),
                ),
            )
        }
        val hooks = HookRegistrar(xposed, HookLogger { _, message -> log(message) }, GROUP)
        return hooks.install {
            hookConfigString(hooks, classLoader)
            hookBootstrap(xposed, hooks, classLoader)
            hookPackageName(hooks, classLoader)
            hookPermission(hooks, classLoader)
        }.report
    }

    /**
     * The ROM's own switch: the platform only starts contextual search when its configuration names
     * a provider. Answering that one resource makes the ordinary boot path work, which is a smaller
     * intervention than starting somebody else's service by hand.
     */
    private fun hookConfigString(hooks: HookRegistrar, classLoader: ClassLoader) {
        val resourceId = runCatching {
            HookSupport.findClassOrNull(classLoader, STRING_RESOURCES_CLASS)
                ?.let { HookSupport.findField(it, CONFIG_RESOURCE_FIELD)?.getInt(null) }
        }.getOrNull()
        val method = HookSupport.findClassOrNull(classLoader, SYSTEM_SERVER_CLASS)?.let { systemServer ->
            HookSupport.findMethod(
                systemServer,
                "deviceHasConfigString",
                Context::class.java,
                Int::class.javaPrimitiveType!!,
            )
        }
        if (resourceId == null || resourceId == 0 ||
            method == null ||
            method.returnType != Boolean::class.javaPrimitiveType
        ) {
            hooks.skipped(
                "system.contextual-search-config",
                "SystemServer.deviceHasConfigString",
                "ContextualSearch: this build has no config-string gate; the start-up path below " +
                    "still brings the service up",
            )
            return
        }
        hooks.intercept("system.contextual-search-config", method, "SystemServer.deviceHasConfigString") { chain ->
            if (chain.getArg(1) == resourceId) true else chain.proceed()
        }
    }

    /**
     * The boot repair: after the platform's own service start-up has run, the service either exists
     * or is started here. The method is deoptimized first, because a compiled copy of the very
     * start-up path being repaired is what would make the hook unreachable.
     */
    private fun hookBootstrap(module: XposedModule, hooks: HookRegistrar, classLoader: ClassLoader) {
        val systemServer = HookSupport.findClassOrNull(classLoader, SYSTEM_SERVER_CLASS)
        val timings = HookSupport.findClassOrNull(classLoader, TIMINGS_CLASS)
        val startOtherServices = if (systemServer != null && timings != null) {
            HookSupport.findMethod(systemServer, "startOtherServices", timings)
        } else {
            null
        }
        if (startOtherServices == null) {
            hooks.missing(
                "system.contextual-search-bootstrap",
                "SystemServer.startOtherServices",
                "ContextualSearch: startOtherServices(TimingsTraceAndSlog) not found",
            )
            return
        }
        HookSupport.deoptimize(
            module,
            hooks.logger,
            startOtherServices,
            "SystemServer.startOtherServices(TimingsTraceAndSlog)",
        )
        hooks.intercept(
            "system.contextual-search-bootstrap",
            startOtherServices,
            "SystemServer.startOtherServices",
        ) { chain ->
            val result = chain.proceed()
            ensureServiceStarted(module, hooks, classLoader, chain.getThisObject())
            result
        }
    }

    /** The service asks the platform which app provides the search; on these devices that is Google. */
    private fun hookPackageName(hooks: HookRegistrar, classLoader: ClassLoader) {
        val method = HookSupport.findClassOrNull(classLoader, SERVICE_CLASS)
            ?.let { service -> HookSupport.findMethod(service, PACKAGE_NAME_METHOD) }
        if (method == null) {
            hooks.missing(
                "system.contextual-search-package",
                "ContextualSearchManagerService.getContextualSearchPackageName",
                "ContextualSearch: getContextualSearchPackageName() not found",
            )
            return
        }
        hooks.intercept(
            "system.contextual-search-package",
            method,
            "ContextualSearchManagerService.getContextualSearchPackageName",
        ) { ModuleTargets.GOOGLE_SEARCH_PACKAGE }
    }

    private fun hookPermission(hooks: HookRegistrar, classLoader: ClassLoader) {
        val method = HookSupport.findClassOrNull(classLoader, SERVICE_CLASS)
            ?.let { service -> HookSupport.findMethod(service, PERMISSION_METHOD, String::class.java) }
        if (method == null) {
            hooks.missing(
                "system.contextual-search-permission",
                "ContextualSearchManagerService.enforcePermission",
                "ContextualSearch: enforcePermission(String) not found",
            )
            return
        }
        hooks.intercept(
            "system.contextual-search-permission",
            method,
            "ContextualSearchManagerService.enforcePermission",
        ) { chain ->
            val function = chain.getArg(0) as? String
            if (function == START_FUNCTION && allowsCallingUid(chain.getThisObject())) {
                null
            } else {
                chain.proceed()
            }
        }
    }

    private fun allowsCallingUid(service: Any): Boolean {
        val context = (HookSupport.invokeNoArgs(service, "getContext") as? Context)
            ?: (HookSupport.getFieldValue(service, "mContext") as? Context)
            ?: return false
        // The interface is oneway AIDL, so the calling PID is not available; the UID is the
        // permission subject, and the packages behind it are the whole shared-UID boundary.
        val packages = runCatching {
            context.packageManager.getPackagesForUid(Binder.getCallingUid())
        }.getOrNull() ?: return false
        val callingPackages = packages.toList()
        val systemPackages = callingPackages.filterTo(mutableSetOf()) { packageName ->
            HookSupport.isSystemPackage(context, packageName)
        }
        return ContextualSearchCallerPolicy.allows(
            callingPackages = callingPackages,
            systemPackages = systemPackages,
            gestureEnabled = ModulePrefs.isEnabled(ModulePrefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH),
        )
    }

    private fun ensureServiceStarted(
        module: XposedModule,
        hooks: HookRegistrar,
        classLoader: ClassLoader,
        systemServer: Any,
    ) {
        val logger = hooks.logger
        if (isServiceAlive()) {
            logger.debug { "ContextualSearch: contextual_search is already up" }
            return
        }
        val manager = HookSupport.getFieldValue(systemServer, "mSystemServiceManager")
        if (manager == null) {
            logger.warn("ContextualSearch: no mSystemServiceManager, the service cannot be started")
            return
        }
        val serviceClass = HookSupport.findClassOrNull(classLoader, SERVICE_CLASS)
        if (serviceClass == null) {
            logger.warn("ContextualSearch: no ContextualSearchManagerService class to start")
            return
        }
        val startService = HookSupport.findMethod(manager.javaClass, "startService", Class::class.java)
        if (startService == null) {
            logger.warn("ContextualSearch: no SystemServiceManager.startService(Class) to call")
            return
        }
        val started = runCatching {
            // The framework's invoker is the supported path for a hidden method; a build that
            // refuses it still has plain reflection.
            module.getInvoker(startService).invoke(manager, serviceClass)
            true
        }.getOrElse {
            runCatching { startService.invoke(manager, serviceClass) }.isSuccess
        }
        when {
            started && isServiceAlive() ->
                logger.debug { "ContextualSearch: started ContextualSearchManagerService" }
            else ->
                logger.warn("ContextualSearch: startService(Class) did not bring contextual_search up")
        }
    }

    private fun isServiceAlive(): Boolean = runCatching {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val getService = serviceManager
            .getDeclaredMethod("getService", String::class.java)
            .apply { isAccessible = true }
        (getService.invoke(null, ModuleTargets.CONTEXTUAL_SEARCH_SERVICE) as? IBinder)?.isBinderAlive == true
    }.getOrDefault(false)
}
