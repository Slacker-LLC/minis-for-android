package com.openminis.app.tools

/**
 * [T-eta-xposed-groups] Turning "turn Wi-Fi off" into one argv for the privileged path.
 *
 * Ported from Eta `agent/tool/AgentStructuredDeviceTools.kt` (`set_device_state`, Mangi-11/Eta @
 * c15de97); attribution in THIRD_PARTY_LICENSES.md. Eta builds a shell string here; this project's
 * privileged surface takes arguments instead, so the same two system commands are expressed as argv
 * - and an unknown target is refused rather than guessed, because Wi-Fi and Bluetooth are two
 * different system services and the wrong one would be toggled silently.
 */
object DeviceStatePolicy {
    const val TARGET_WIFI = "wifi"
    const val TARGET_BLUETOOTH = "bluetooth"

    /** The targets the tool offers, in the order its schema states them. */
    val TARGETS: List<String> = listOf(TARGET_WIFI, TARGET_BLUETOOTH)

    /** Null when the target is not one of [TARGETS]. */
    fun argv(target: String?, enabled: Boolean): List<String>? =
        when (target?.trim()?.lowercase()) {
            TARGET_WIFI -> listOf("svc", "wifi", if (enabled) "enable" else "disable")
            TARGET_BLUETOOTH ->
                listOf("cmd", "bluetooth_manager", if (enabled) "enable" else "disable")
            else -> null
        }
}
