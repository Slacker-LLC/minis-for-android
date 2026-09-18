package com.openminis.app.xposed.system

import android.content.Context
import com.openminis.app.xposed.HookInstallEntry
import com.openminis.app.xposed.HookInstallReport
import com.openminis.app.xposed.HookInstallStatus
import com.openminis.app.xposed.HookLogger
import com.openminis.app.xposed.HookRegistrar
import com.openminis.app.xposed.HookSupport
import com.openminis.app.xposed.ModulePrefs
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * [T-eta-xposed-groups] ColorOS SystemUI's OCR long press on the navigation bar, handed to
 * contextual search.
 *
 * Ported from Eta `hook/system/SystemUiHooks.kt` (Mangi-11/Eta @ c15de97); attribution in
 * THIRD_PARTY_LICENSES.md. The ROM's own long press runs screen OCR through SystemUI; this group
 * offers the system's contextual search instead - and only when the user's gesture switch is on, the
 * surface hands over a context and the search entry really resolves. In every other case the ROM's
 * OCR runs as before. When the search does take over, the ROM's own long-press haptic is replayed
 * through SystemUI's vibration helper, so the gesture still feels like the one the device ships.
 */
object SystemUiHooks {

    private const val GROUP = "SystemUI"
    private const val OCR_BUSINESS_CLASS =
        "com.oplus.systemui.navigationbar.ocrscreen.OplusOcrScreenBusiness"
    private const val VIBRATION_HELPER_CLASS =
        "com.oplus.systemui.navigationbar.gesture.VibrationHelper"

    @Volatile
    private var systemUiClassLoader: ClassLoader? = null

    @Volatile
    private var vibrationHelperGetInstanceMethod: Method? = null

    @Volatile
    private var vibrationHelperVibrateCustomizedMethod: Method? = null

    fun install(module: Any, classLoader: ClassLoader, log: (String) -> Unit): HookInstallReport {
        val xposed = module as? XposedModule
        if (xposed == null) {
            return HookInstallReport(
                GROUP,
                listOf(
                    HookInstallEntry(
                        group = GROUP,
                        id = "systemui.ocr-long-press",
                        description = "OplusOcrScreenBusiness.onLongPressed",
                        status = HookInstallStatus.FAILED,
                        detail = "the module entry did not pass a libxposed module instance",
                    ),
                ),
            )
        }
        val hooks = HookRegistrar(xposed, HookLogger { _, message -> log(message) }, GROUP)
        return hooks.install {
            systemUiClassLoader = classLoader
            val businessClass = HookSupport.findClassOrNull(classLoader, OCR_BUSINESS_CLASS)
            val onLongPressed = businessClass?.let { HookSupport.findMethod(it, "onLongPressed") }
            if (onLongPressed == null) {
                missing(
                    "systemui.ocr-long-press",
                    "OplusOcrScreenBusiness.onLongPressed",
                    "SystemUI: no OplusOcrScreenBusiness.onLongPressed() on this build",
                )
                return@install
            }
            intercept(
                "systemui.ocr-long-press",
                onLongPressed,
                "OplusOcrScreenBusiness.onLongPressed",
            ) { chain ->
                // The switch is read here, so turning it off restores the ROM's OCR at once.
                if (!ModulePrefs.isEnabled(ModulePrefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH)) {
                    return@intercept chain.proceed()
                }
                val context = resolveContext(chain.getThisObject())
                if (context == null) {
                    hooks.logger.warnThrottled("systemui_context") {
                        "SystemUI: no context on the OCR surface, keeping the ROM's OCR"
                    }
                    return@intercept chain.proceed()
                }
                if (!CircleToSearchInvoker.isAvailable(
                        context,
                        hooks.logger,
                        "SystemUI",
                        "keeping the ROM's OCR",
                    )
                ) {
                    return@intercept chain.proceed()
                }
                if (CircleToSearchInvoker.trigger(hooks.logger, "SystemUI")) {
                    performOriginalLongPressHaptic(context, hooks.logger)
                    null
                } else {
                    chain.proceed()
                }
            }
        }.report
    }

    /** The accessor first, then the members the releases have carried, in that order. */
    private fun resolveContext(target: Any): Context? {
        SystemUiOcrPolicy.CONTEXT_METHOD_NAMES.forEach { name ->
            (HookSupport.invokeNoArgs(target, name) as? Context)?.let { return it }
        }
        SystemUiOcrPolicy.CONTEXT_FIELD_NAMES.forEach { name ->
            (HookSupport.getFieldValue(target, name) as? Context)?.let { return it }
        }
        return null
    }

    private fun performOriginalLongPressHaptic(context: Context, logger: HookLogger) {
        val vibrationHelper = resolveVibrationHelper(context) ?: run {
            logger.warnThrottled("systemui_cts_vibration_helper_missing") {
                "SystemUI: no VibrationHelper, skipping the navigation-bar long-press haptic"
            }
            return
        }
        if (!invokeVibrateCustomized(vibrationHelper, context)) {
            logger.warnThrottled("systemui_cts_linear_haptic_failed") {
                "SystemUI: calling the ROM's own long-press haptic failed"
            }
        }
    }

    private fun resolveVibrationHelper(context: Context): Any? {
        val classLoader = systemUiClassLoader ?: return null
        val helperClass = HookSupport.findClassOrNull(classLoader, VIBRATION_HELPER_CLASS)
            ?: return null
        val getInstance = vibrationHelperGetInstanceMethod
            ?: HookSupport.findMethod(helperClass, "getInstance", Context::class.java)
                ?.also { vibrationHelperGetInstanceMethod = it }
            ?: return null
        return runCatching { getInstance.invoke(null, context) }.getOrNull()
    }

    private fun invokeVibrateCustomized(vibrationHelper: Any, context: Context): Boolean {
        val method = vibrationHelperVibrateCustomizedMethod
            ?: HookSupport.findMethod(
                vibrationHelper.javaClass,
                "vibrateCustomized",
                Context::class.java,
                Int::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
            )?.also { vibrationHelperVibrateCustomizedMethod = it }
            ?: return false
        return runCatching {
            method.invoke(
                vibrationHelper,
                context,
                SystemUiOcrPolicy.OCR_LONG_PRESS_HAPTIC_EFFECT_ID,
                false,
            )
            true
        }.getOrDefault(false)
    }
}
