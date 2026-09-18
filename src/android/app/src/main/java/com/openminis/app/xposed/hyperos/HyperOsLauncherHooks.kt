package com.openminis.app.xposed.hyperos

import android.content.Context
import android.view.MotionEvent
import com.openminis.app.xposed.HookInstallEntry
import com.openminis.app.xposed.HookInstallReport
import com.openminis.app.xposed.HookInstallStatus
import com.openminis.app.xposed.HookLogger
import com.openminis.app.xposed.HookRegistrar
import com.openminis.app.xposed.HookSupport
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Modifier

/**
 * [T-eta-xposed-groups] The launcher's own long press on the navigation bar, handed to contextual
 * search.
 *
 * Ported from Eta `hook/hyperos/HyperOsLauncherHooks.kt` (Mangi-11/Eta @ c15de97); attribution in
 * THIRD_PARTY_LICENSES.md. The same gesture is published through several helper classes and a launcher
 * keeps whichever ones its version shipped, so the first entry point that matches is installed and the
 * rest are left alone - more than one would fire the same search twice from nested calls. Every entry
 * point keeps the ROM's behaviour when the search cannot be started.
 */
object HyperOsLauncherHooks {

    private const val GROUP = "HyperOsLauncher"

    fun install(module: Any, classLoader: ClassLoader, log: (String) -> Unit): HookInstallReport {
        val xposed = module as? XposedModule
        if (xposed == null) {
            return HookInstallReport(
                GROUP,
                listOf(
                    HookInstallEntry(
                        group = GROUP,
                        id = "hyperos.gesture-manager",
                        description = "HyperOS launcher long press",
                        status = HookInstallStatus.FAILED,
                        detail = "the module entry did not pass a libxposed module instance",
                    ),
                ),
            )
        }
        val hooks = HookRegistrar(xposed, HookLogger { _, message -> log(message) }, GROUP)
        return hooks.install {
            if (installGestureManager(hooks, classLoader)) return@install
            if (installOmni(hooks, classLoader)) return@install
            if (installEventHelper(hooks, classLoader)) return@install
            HyperOsLegacyGesture.install(hooks, classLoader)
        }.report
    }

    private fun installGestureManager(hooks: HookRegistrar, classLoader: ClassLoader): Boolean {
        val type = HookSupport.findClassOrNull(
            classLoader,
            "com.miui.home.recents.gesture.NavStubGestureEventManager",
        )
        val method = type?.let { HookSupport.findMethod(it, "handleLongPressEvent") }
        val application = HookSupport.findClassOrNull(classLoader, "com.miui.home.launcher.Application")
        val getInstance = application?.let { HookSupport.findMethod(it, "getInstance") }
        if (method?.returnType != Void.TYPE ||
            getInstance == null ||
            !Modifier.isStatic(getInstance.modifiers) ||
            !Context::class.java.isAssignableFrom(getInstance.returnType)
        ) {
            hooks.missing(
                "hyperos.gesture-manager",
                "NavStubGestureEventManager.handleLongPressEvent",
                "HyperOS: the gesture manager entry or Application.getInstance does not match",
            )
            return false
        }
        return hooks.intercept(
            "hyperos.gesture-manager",
            method,
            "NavStubGestureEventManager.handleLongPressEvent",
        ) { chain ->
            if (!HyperOsSearchTrigger.isEnabled()) return@intercept chain.proceed()
            val context = getInstance.invoke(null) as? Context
            if (HyperOsSearchTrigger.trigger(context, hooks.logger)) {
                HyperOsGesturePolicy.takeoverResult(method.returnType)
            } else {
                chain.proceed()
            }
        } != null
    }

    private fun installOmni(hooks: HookRegistrar, classLoader: ClassLoader): Boolean {
        val type = HookSupport.findClassOrNull(
            classLoader,
            "com.miui.home.recents.cts.CircleToSearchHelper",
        )
        val method = type?.let {
            HookSupport.findMethod(
                it,
                "invokeOmni",
                Context::class.java,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
            )
        }
        if (method == null || method.returnType !in setOf(Void.TYPE, Boolean::class.javaPrimitiveType)) {
            hooks.missing(
                "hyperos.invoke-omni",
                "CircleToSearchHelper.invokeOmni",
                "HyperOS: invokeOmni does not match",
            )
            return false
        }
        return hooks.intercept(
            "hyperos.invoke-omni",
            method,
            "CircleToSearchHelper.invokeOmni",
        ) { chain ->
            val context = chain.getArg(0) as? Context
            if (HyperOsSearchTrigger.trigger(context, hooks.logger)) {
                HyperOsGesturePolicy.takeoverResult(method.returnType)
            } else {
                chain.proceed()
            }
        } != null
    }

    private fun installEventHelper(hooks: HookRegistrar, classLoader: ClassLoader): Boolean {
        val type = HookSupport.findClassOrNull(
            classLoader,
            "com.miui.home.recents.cts.NavBarEventHelper",
        )
        val method = type?.let { HookSupport.findMethod(it, "onLongPress", MotionEvent::class.java) }
        val context = type?.let { HookSupport.findField(it, "mContext") }
        if (method?.returnType != Void.TYPE ||
            context == null ||
            !Context::class.java.isAssignableFrom(context.type)
        ) {
            hooks.missing(
                "hyperos.navbar-event",
                "NavBarEventHelper.onLongPress",
                "HyperOS: the navigation bar event entry or mContext does not match",
            )
            return false
        }
        return hooks.intercept(
            "hyperos.navbar-event",
            method,
            "NavBarEventHelper.onLongPress",
        ) { chain ->
            val ownerContext = context.get(chain.getThisObject()) as? Context
            if (HyperOsSearchTrigger.trigger(ownerContext, hooks.logger)) {
                HyperOsGesturePolicy.takeoverResult(method.returnType)
            } else {
                chain.proceed()
            }
        } != null
    }
}
