package com.openminis.app.xposed

import android.content.Context

/**
 * [T-eta-xposed-groups] The app side of the module's settings: the same preferences the framework
 * hands to every hooked process.
 *
 * Ported from Eta `config/Prefs.kt` and the settings store behind its module screen (Mangi-11/Eta @
 * c15de97); attribution in THIRD_PARTY_LICENSES.md. The group name and the keys come from
 * [ModulePrefs], so the screen cannot write a switch the module would not read, and the defaults a
 * missing value falls back to are the module's own - the one that matters is that every takeover
 * starts off.
 */
object ModuleSettingsStore {

    /** The switches the settings screen owns, in the order it shows them. */
    val SWITCHES: List<String> = listOf(
        ModulePrefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH,
        ModulePrefs.Keys.DOUBLE_FINGER_CIRCLE_TO_SEARCH,
        ModulePrefs.Keys.HOTWORD_SELF_HEAL,
    )

    fun isEnabled(context: Context, key: String): Boolean =
        preferences(context).getBoolean(key, ModulePrefs.defaultOf(key))

    fun setEnabled(context: Context, key: String, enabled: Boolean) {
        preferences(context).edit().putBoolean(key, enabled).apply()
    }

    fun assistantTarget(context: Context): PowerAssistantTarget = PowerAssistantTarget.parse(
        preferences(context).getString(ModulePrefs.Keys.POWER_KEY_ASSISTANT_TARGET, null),
    )

    fun setAssistantTarget(context: Context, target: PowerAssistantTarget) {
        preferences(context).edit()
            .putString(ModulePrefs.Keys.POWER_KEY_ASSISTANT_TARGET, target.wire)
            .apply()
    }

    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences(ModulePrefs.GROUP, Context.MODE_PRIVATE)
}
