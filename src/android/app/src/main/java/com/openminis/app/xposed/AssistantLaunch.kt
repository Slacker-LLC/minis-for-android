package com.openminis.app.xposed

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

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
 * itself - the assistant activity answers `ACTION_ASSIST` and `ACTION_VOICE_ASSIST`, and the voice
 * interaction service is registered for the assistant role - so the port starts the declared entry
 * point instead of rebuilding Eta's binding machinery.
 *
 * Two of upstream's rules are kept because they are what stops a gesture from being swallowed:
 * its own assistant is only opened once the system's assistant role actually points at it
 * (`AssistantManager.isAssistantConfigured`), and an action is only started after the target really
 * answers it (`resolvesActivity` in `PowerHooks`). For Gemini both actions are tried, as upstream
 * does.
 *
 * The decision is a small value type so it can be tested: which action and package a target maps
 * to, and that the OEM target maps to nothing at all (the OEM path is the absence of a takeover).
 */
object AssistantLaunch {

    /** The standard assistant action, and the one HyperOS's own power menu sends. */
    const val ACTION_ASSIST = Intent.ACTION_ASSIST

    /** What this app declares for its own assistant activity. */
    const val ACTION_VOICE_ASSIST = "android.intent.action.VOICE_ASSIST"

    /** The action Google's own power-menu path answers as well. */
    const val ACTION_VOICE_COMMAND = Intent.ACTION_VOICE_COMMAND

    const val GEMINI_PACKAGE = "com.google.android.googlequicksearchbox"
    const val GEMINI_ASSIST_COMPONENT =
        "com.google.android.googlequicksearchbox/.VoiceSearchActivity"

    /**
     * One way to reach an assistant. [requiresAssistantRole] is upstream's configured-assistant
     * gate; [component] is set only where an action alone would resolve to the wrong surface.
     */
    data class Target(
        val packageName: String,
        val actions: List<String>,
        val component: String? = null,
        val requiresAssistantRole: Boolean = false,
    )

    /** Null means "let the system handle it": the OEM target, or a target we cannot name. */
    fun targetFor(
        target: PowerAssistantTarget,
        ownPackage: String = ModuleTargets.OWN_PACKAGE,
    ): Target? = when (target) {
        PowerAssistantTarget.OEM -> null
        PowerAssistantTarget.MINIS -> Target(
            packageName = ownPackage,
            actions = listOf(ACTION_VOICE_ASSIST, ACTION_ASSIST),
            requiresAssistantRole = true,
        )
        PowerAssistantTarget.GEMINI -> Target(
            packageName = GEMINI_PACKAGE,
            actions = listOf(ACTION_ASSIST, ACTION_VOICE_COMMAND),
            component = GEMINI_ASSIST_COMPONENT,
        )
    }

    /**
     * Whether the system's assistant role currently points at this app. Upstream asks the same
     * question before it opens its own assistant from the power key.
     */
    fun isConfiguredAssistant(context: Context, ownPackage: String = ModuleTargets.OWN_PACKAGE): Boolean =
        runCatching {
            Settings.Secure.getString(context.contentResolver, SECURE_ASSISTANT)
                ?.contains(ownPackage) == true
        }.getOrDefault(false)

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
        if (resolved.requiresAssistantRole && !isConfiguredAssistant(context)) {
            logger.warnThrottled("${source}_assistant_not_configured") {
                "$source: ${resolved.packageName} is not the device's assistant, " +
                    "keeping the system behaviour"
            }
            return false
        }
        resolved.actions.forEach { action ->
            val intent = Intent(action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .apply {
                    if (action == ACTION_ASSIST && resolved.component != null) {
                        ComponentName.unflattenFromString(resolved.component)?.let(::setComponent)
                    } else {
                        setPackage(resolved.packageName)
                    }
                }
            if (!HookSupport.resolvesActivity(context, intent)) {
                logger.warnThrottled("${source}_${action}_missing") {
                    "$source: ${resolved.packageName} does not answer $action"
                }
                return@forEach
            }
            return try {
                context.startActivity(intent)
                logger.debug { "$source: opened the $target assistant through $action" }
                true
            } catch (exception: Exception) {
                logger.warnThrottled("${source}_assistant_launch_failed") {
                    "$source: opening the $target assistant failed " +
                        "(${exception.javaClass.simpleName}), keeping the system behaviour"
                }
                false
            }
        }
        return false
    }

    private const val SECURE_ASSISTANT = "assistant"
}
