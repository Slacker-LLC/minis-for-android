package com.openminis.app.xposed

import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** [T-eta-xposed-groups] Which assistant a power-key long press opens. */
enum class PowerAssistantTarget(val wire: String) {
    OEM("oem"),
    MINIS("minis"),
    GEMINI("gemini"),
    ;

    companion object {
        /**
         * Anything unrecognised - an old value, a typo, settings that never arrived - means the
         * OEM assistant keeps the button. Fail-safe by construction: a hook must not take over a
         * power-key gesture because it could not read a preference.
         */
        fun parse(raw: String?): PowerAssistantTarget =
            entries.firstOrNull { it.wire.equals(raw?.trim(), ignoreCase = true) } ?: OEM
    }
}

/**
 * [T-eta-xposed-groups] Opening the chosen assistant from inside another process.
 *
 * Ported from the launch half of Eta `hook/system/PowerHooks.kt` and `hook/system/AssistantManager.kt`
 * (Mangi-11/Eta @ c15de97); attribution in THIRD_PARTY_LICENSES.md. Eta binds the assistant's
 * voice-interaction service and repairs a stale binding; this app already declares that contract
 * itself - the assistant activity answers ACTION_ASSIST and ACTION_VOICE_ASSIST, and the voice
 * interaction service is registered for the assistant role - so the port starts the declared
 * entry point instead of rebuilding Eta's binding machinery.
 *
 * The decision is a small value type so it can be tested: which action and package a target maps
 * to, and that the OEM target maps to nothing at all (the OEM path is the absence of a takeover).
 */
object AssistantLaunch {

    /** The standard assistant action, and the one HyperOS's own power menu sends. */
    const val ACTION_ASSIST = Intent.ACTION_ASSIST
    const val ACTION_VOICE_ASSIST = "android.intent.action.VOICE_ASSIST"

    const val GEMINI_PACKAGE = "com.google.android.googlequicksearchbox"
    const val GEMINI_ASSIST_COMPONENT = "com.google.android.googlequicksearchbox/.VoiceSearchActivity"

    data class Target(val packageName: String, val action: String)

    /** Null means "let the system handle it": the OEM target, or a target we cannot name. */
    fun targetFor(
        target: PowerAssistantTarget,
        ownPackage: String = ModuleTargets.OWN_PACKAGE,
    ): Target? = when (target) {
        PowerAssistantTarget.OEM -> null
        PowerAssistantTarget.MINIS -> Target(ownPackage, ACTION_VOICE_ASSIST)
        PowerAssistantTarget.GEMINI -> Target(GEMINI_PACKAGE, ACTION_ASSIST)
    }

    /**
     * Starts the assistant. False means nothing was started and the caller must let the original
     * behaviour run - a gesture is never swallowed by a launch that did not happen.
     */
    fun launch(context: Context, target: PowerAssistantTarget, logger: HookLogger, source: String): Boolean {
        val resolved = targetFor(target) ?: return false
        if (!HookSupport.isPackageInstalled(context, resolved.packageName)) {
            logger.warnThrottled("${source}_assistant_missing") {
                "$source: ${resolved.packageName} is not installed, keeping the system behaviour"
            }
            return false
        }
        val intent = Intent(resolved.action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .apply {
                if (target == PowerAssistantTarget.GEMINI) {
                    ComponentName.unflattenFromString(GEMINI_ASSIST_COMPONENT)?.let(::setComponent)
                } else {
                    setPackage(resolved.packageName)
                }
            }
        return try {
            context.startActivity(intent)
            logger.debug { "$source: opened $target assistant" }
            true
        } catch (exception: Exception) {
            logger.warnThrottled("${source}_assistant_launch_failed") {
                "$source: opening the $target assistant failed " +
                    "(${exception.javaClass.simpleName}), keeping the system behaviour"
            }
            false
        }
    }
}
