package com.openminis.app.xposed.hyperos

/**
 * [T-eta-xposed-groups] Whether a HyperOS shortcut dispatch is the assistant long press.
 *
 * Ported from Eta `hook/hyperos/HyperOsPowerPolicy.kt` (Mangi-11/Eta @ c15de97); attribution in
 * THIRD_PARTY_LICENSES.md. Both halves are required: the same dispatcher carries the power menu,
 * SOS and every other shortcut, so matching the function alone would hijack them.
 */
object HyperOsPowerPolicy {

    const val ASSISTANT_FUNCTION = "launch_voice_assistant"

    /** The two power-key gestures HyperOS reports for the assistant shortcut. */
    val POWER_KEY_SOURCES = setOf("long_press_power_key", "imperceptible_press_power_key")

    fun isAssistantShortcut(function: String?, source: String?): Boolean =
        function == ASSISTANT_FUNCTION && source in POWER_KEY_SOURCES
}
