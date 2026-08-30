package com.openminis.app.tools.android

import android.content.Context

/** User-owned Root authority level. Agent tool arguments never select this value. */
enum class PrivilegedAccessMode(val wireValue: String) {
    STANDARD("standard"),
    FULL_ACCESS("full"),
}

object PrivilegedAccessModeStore {
    private const val PREFS = "privileged_access_mode"
    private const val KEY_MODE = "mode"

    fun get(context: Context): PrivilegedAccessMode = parse(
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, null),
    )

    /** UI-only mutation seam. This function is intentionally not exposed as an Agent tool. */
    fun setFromUserSettings(context: Context, mode: PrivilegedAccessMode) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, mode.wireValue)
            .apply()
    }

    internal fun parse(raw: String?): PrivilegedAccessMode = when (raw) {
        PrivilegedAccessMode.FULL_ACCESS.wireValue -> PrivilegedAccessMode.FULL_ACCESS
        else -> PrivilegedAccessMode.STANDARD
    }
}
