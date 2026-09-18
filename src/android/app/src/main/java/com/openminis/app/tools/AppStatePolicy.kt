package com.openminis.app.tools

/**
 * [T-eta-xposed-groups] The three app-state actions Eta ships, as argv.
 *
 * Ported from Eta `agent/tool/AgentStructuredDeviceTools.kt` (`app_state_control`, Mangi-11/Eta @
 * c15de97); attribution in THIRD_PARTY_LICENSES.md. Eta writes them as shell strings; this project's
 * privileged surface takes arguments, so the same commands are expressed as argv - and an action
 * this policy does not know is refused instead of being run as something else. The package name is
 * validated by the caller before it reaches here.
 */
object AppStatePolicy {
    const val FORCE_STOP = "force_stop"
    const val FREEZE = "freeze"
    const val UNFREEZE = "unfreeze"

    /** The actions, in the order the tool's schema states them. */
    val ACTIONS: List<String> = listOf(FORCE_STOP, FREEZE, UNFREEZE)

    /** Null for an unknown action. */
    fun argv(action: String?, packageName: String, userId: Int?): List<String>? {
        val user = userId?.toString() ?: "current"
        return when (action?.trim()?.lowercase()) {
            FORCE_STOP -> listOf("am", "force-stop", "--user", user, packageName)
            // Eta's "freeze": the package keeps its data but cannot run until it is enabled again.
            FREEZE -> listOf("pm", "disable-user", "--user", user, packageName)
            UNFREEZE -> listOf("pm", "enable", "--user", user, packageName)
            else -> null
        }
    }
}
