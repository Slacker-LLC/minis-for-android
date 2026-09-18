package com.openminis.app.xposed

import android.content.SharedPreferences

/**
 * [T-eta-xposed-entry] The read side of the module's settings, as seen from inside somebody else's
 * process.
 *
 * Ported from Eta `config/Prefs.kt` (Mangi-11/Eta @ c15de97); attribution in
 * THIRD_PARTY_LICENSES.md. The framework hands every hooked process a read-only view of the
 * module's settings; a hook reads the switch it cares about at interception time, so a toggle
 * takes effect without restarting anything.
 *
 * Two rules are kept from Eta and matter more than the plumbing. Every switch has its own default,
 * and a switch that would take over a system gesture defaults to OFF - installing the module must
 * not silently change what a button does. And a settings read that fails, or a process that never
 * got the settings at all, falls back to that default instead of guessing: a hook that cannot tell
 * whether it is wanted leaves the system alone.
 */
interface ModulePrefReader {
    /** Null when the key is absent; the caller applies the default. */
    fun getBoolean(key: String): Boolean?

    /** Null when the key is absent or not a string. */
    fun getString(key: String): String?
}

object ModulePrefs {

    /** The group both the app and every hooked process must agree on. */
    const val GROUP = "minis_prefs"

    object Keys {
        /** Which assistant a power-key long press should open: oem, minis or gemini. */
        const val POWER_KEY_ASSISTANT_TARGET = "power_key_assistant_target"

        const val GESTURE_BAR_CIRCLE_TO_SEARCH = "gesture_bar_circle_to_search"
        const val DOUBLE_FINGER_CIRCLE_TO_SEARCH = "double_finger_circle_to_search"
        const val POWER_KEY_TAKEOVER = "power_key_takeover"
        const val HOTWORD_SELF_HEAL = "hotword_self_heal"
        const val ASSISTANT_AUTO_CONFIG = "assistant_auto_config"
    }

    /**
     * Defaults per switch. The two gesture takeovers are what Eta ships enabled, because there the
     * gesture bar's screen search is routed to the system's own search and the user asked for the
     * module; everything that replaces a button or an assistant defaults to off here, so an
     * installed-but-unconfigured module behaves like an uninstalled one.
     */
    val BOOLEAN_DEFAULTS: Map<String, Boolean> = mapOf(
        Keys.GESTURE_BAR_CIRCLE_TO_SEARCH to true,
        Keys.DOUBLE_FINGER_CIRCLE_TO_SEARCH to false,
        Keys.POWER_KEY_TAKEOVER to false,
        Keys.HOTWORD_SELF_HEAL to false,
        Keys.ASSISTANT_AUTO_CONFIG to false,
    )

    @Volatile
    private var reader: ModulePrefReader? = null

    /** Called by the entry class when the framework hands over the settings view. */
    fun attach(reader: ModulePrefReader?) {
        this.reader = reader
    }

    fun attachSharedPreferences(preferences: SharedPreferences?) {
        if (preferences == null) {
            attach(null)
            return
        }
        attach(object : ModulePrefReader {
            override fun getBoolean(key: String): Boolean? =
                if (preferences.contains(key)) preferences.getBoolean(key, false) else null

            override fun getString(key: String): String? =
                if (preferences.contains(key)) preferences.getString(key, null) else null
        })
    }

    fun defaultOf(key: String): Boolean = BOOLEAN_DEFAULTS[key] ?: false

    fun isEnabled(key: String): Boolean {
        val value = runCatching { reader?.getBoolean(key) }.getOrNull()
        return value ?: defaultOf(key)
    }

    /** A free-form setting, with the caller's fallback when settings are absent or unreadable. */
    fun string(key: String, fallback: String): String {
        val value = runCatching { reader?.getString(key) }.getOrNull()
        return value?.takeIf { it.isNotBlank() } ?: fallback
    }
}
