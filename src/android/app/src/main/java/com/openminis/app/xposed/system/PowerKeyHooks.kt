package com.openminis.app.xposed.system

import android.os.Handler
import android.content.Context
import android.os.Message
import android.os.SystemClock
import com.openminis.app.xposed.AssistantLaunch
import com.openminis.app.xposed.HookInstallEntry
import com.openminis.app.xposed.HookInstallReport
import com.openminis.app.xposed.HookInstallStatus
import com.openminis.app.xposed.HookLogger
import com.openminis.app.xposed.HookRegistrar
import com.openminis.app.xposed.HookSupport
import com.openminis.app.xposed.ModulePrefs
import com.openminis.app.xposed.PowerAssistantTarget
import com.openminis.app.xposed.safeLogType
import io.github.libxposed.api.XposedModule

/**
 * [T-eta-xposed-groups] ColorOS's power-key long press, which arrives as a message inside
 * system_server.
 *
 * Ported from Eta `hook/system/PowerHooks.kt` (Mangi-11/Eta @ c15de97); attribution in
 * THIRD_PARTY_LICENSES.md. On these ROMs the long press is not a method call a launcher makes but a
 * message the phone window manager's speech handler posts, so the hook sits on that handler, claims
 * only the assist message, and asks [AssistantLaunch] to open the assistant the user chose; anything
 * else falls through to the ROM. The ROM's own long-press haptic is replayed on success, and a
 * second press inside the dedup window is swallowed instead of opening the assistant twice.
 *
 * Eta first tries its own voice-interaction session and only then an activity intent, and it repairs
 * the assistant configuration in the background afterwards; this port has the activity half (see
 * AssistantLaunch) because it starts the entry point the app already declares, and it does not carry
 * the background repair, which belongs to Eta's assistant-selection machinery.
 */
object PowerKeyHooks {

    private const val GROUP = "Power"

    private const val SPEECH_HANDLER_CLASS =
        "com.android.server.policy.PhoneWindowManagerExtImpl\$OplusSpeechHandler"
    private const val PHONE_WINDOW_MANAGER_CLASS = "com.android.server.policy.PhoneWindowManager"

    @Volatile
    private var lastLaunchUptime = 0L

    fun install(module: Any, classLoader: ClassLoader, log: (String) -> Unit): HookInstallReport {
        val xposed = module as? XposedModule
        if (xposed == null) {
            return HookInstallReport(
                GROUP,
                listOf(
                    HookInstallEntry(
                        group = GROUP,
                        id = "system.power-assist-message",
                        description = "OplusSpeechHandler.handleMessage",
                        status = HookInstallStatus.FAILED,
                        detail = "the module entry did not pass a libxposed module instance",
                    ),
                ),
            )
        }
        val hooks = HookRegistrar(xposed, HookLogger { _, message -> log(message) }, GROUP)
        return hooks.install {
            val handlerClass = HookSupport.findClassOrNull(classLoader, SPEECH_HANDLER_CLASS)
            if (handlerClass == null) {
                skipped(
                    "system.power-assist-message",
                    "OplusSpeechHandler.handleMessage",
                    "Power: this build has no ColorOS speech handler",
                )
                return@install
            }
            val handleMessage = HookSupport.findMethod(handlerClass, "handleMessage", Message::class.java)
            if (handleMessage == null) {
                missing(
                    "system.power-assist-message",
                    "OplusSpeechHandler.handleMessage",
                    "Power: OplusSpeechHandler.handleMessage(Message) not found",
                )
                return@install
            }
            intercept(
                "system.power-assist-message",
                handleMessage,
                "PhoneWindowManagerExtImpl\$OplusSpeechHandler.handleMessage",
            ) { chain ->
                val message = chain.getArg(0) as? Message
                if (message == null || !PowerKeyPolicy.isAssistMessage(message.what)) {
                    return@intercept chain.proceed()
                }
                val target = PowerAssistantTarget.parse(
                    ModulePrefs.string(
                        ModulePrefs.Keys.POWER_KEY_ASSISTANT_TARGET,
                        PowerAssistantTarget.OEM.wire,
                    ),
                )
                if (target == PowerAssistantTarget.OEM) {
                    return@intercept chain.proceed()
                }
                val phoneWindowManager = resolvePhoneWindowManager(chain.getThisObject())
                if (phoneWindowManager == null) {
                    hooks.logger.warnThrottled("oplus_speech_missing_pwm") {
                        "Power: the speech handler has no PhoneWindowManager, keeping the ROM behaviour"
                    }
                    return@intercept chain.proceed()
                }
                val context = HookSupport.getFieldValue(phoneWindowManager, "mContext")
                    as? Context
                if (context == null) {
                    hooks.logger.warnThrottled("oplus_speech_missing_context") {
                        "Power: the window manager has no context, keeping the ROM behaviour"
                    }
                    return@intercept chain.proceed()
                }
                val now = SystemClock.uptimeMillis()
                if (PowerKeyPolicy.withinDedupWindow(now, lastLaunchUptime)) {
                    hooks.logger.debug { "Power: inside the dedup window, swallowing the press" }
                    return@intercept null
                }
                if (!AssistantLaunch.launch(context, target, hooks.logger, "OplusSpeechHandler")) {
                    return@intercept chain.proceed()
                }
                lastLaunchUptime = now
                replayOemHaptic(phoneWindowManager, hooks.logger)
                null
            }
        }.report
    }

    /**
     * The ROM's own long-press haptic, through the wrapper the phone window manager exposes; a
     * missing entry is a throttled warning, never a swallowed gesture.
     */
    private fun replayOemHaptic(phoneWindowManager: Any, logger: HookLogger) {
        val wrapper = HookSupport.invokeNoArgs(phoneWindowManager, "getWrapper")
        val method = wrapper?.let {
            HookSupport.findMethod(
                it.javaClass,
                "performHapticFeedback",
                Int::class.javaPrimitiveType!!,
                String::class.java,
            )
        }
        if (wrapper == null || method == null) {
            logger.warnThrottled("oplus_assistant_haptic_missing") {
                "Power: no ROM long-press haptic entry to replay"
            }
            return
        }
        val replayed = runCatching {
            method.invoke(
                wrapper,
                PowerKeyPolicy.OEM_ASSISTANT_HAPTIC_EFFECT_ID,
                PowerKeyPolicy.OEM_ASSISTANT_HAPTIC_REASON,
            )
            true
        }.getOrElse { failure ->
            logger.warnThrottled("oplus_assistant_haptic_failed") {
                "Power: replaying the ROM haptic failed (${failure.safeLogType()})"
            }
            false
        }
        if (replayed) {
            logger.debug { "Power: replayed the ROM's own long-press haptic" }
        }
    }

    /** The handler owns the window manager (`this$0`), which carries it in `mPhoneWindowManager`. */
    private fun resolvePhoneWindowManager(handlerInstance: Any): Any? {
        val owner = HookSupport.getFieldValue(handlerInstance, "this\$0") ?: return null
        HookSupport.findField(owner.javaClass, "mPhoneWindowManager")?.let { field ->
            return runCatching { field.get(owner) }.getOrNull()
        }
        var current: Class<*>? = owner.javaClass
        while (current != null) {
            current.declaredFields.forEach { field ->
                if (field.type.name == PHONE_WINDOW_MANAGER_CLASS) {
                    field.isAccessible = true
                    return runCatching { field.get(owner) }.getOrNull()
                }
            }
            current = current.superclass
        }
        return null
    }
}
