package com.openminis.app.tools.android

/**
 * [T-eta-ui-system-panel] The system-level actions `android_ui` can perform, ported from
 * Eta's `open_system_panel` family (`agent/model/AgentDeviceToolCatalog.kt`,
 * Mangi-11/Eta @ c15de97 — attribution in THIRD_PARTY_LICENSES.md) and aligned with the
 * guest `android-a11y-cli input key` set this app already supports.
 *
 * The wire name, the label echoed in the tool result and the platform constant each
 * action resolves to live in one list, so the tool schema, the executor and the trace
 * cannot drift apart. The constants are resolved by the executor, not here: unit tests
 * run against the Android stub where every `GLOBAL_ACTION_*` is 0.
 */
enum class UiGlobalAction(
    val wireName: String,
    val label: String,
    /** Spellings Eta accepts for the same panel, kept so either prompt works. */
    val aliases: List<String> = emptyList(),
) {
    BACK("back", "Back"),
    HOME("home", "Home"),
    RECENTS("recents", "Recents"),
    NOTIFICATIONS("notifications", "Notification shade", listOf("notification")),
    QUICK_SETTINGS("quick_settings", "Quick settings", listOf("quicksettings", "settings")),
    ;

    companion object {
        /** Values the model may send — the tool schema and [parse] share this list. */
        val wireValues: List<String> = entries.map { it.wireName }

        /** Unknown names return null so the executor refuses instead of guessing. */
        fun parse(raw: String?): UiGlobalAction? {
            val value = raw?.trim() ?: return null
            return entries.firstOrNull { action ->
                action.wireName.equals(value, ignoreCase = true) ||
                    action.aliases.any { it.equals(value, ignoreCase = true) }
            }
        }
    }
}
