package com.openminis.app.tools

import android.content.Context

/** Process-wide switch for deferred tool loading (see [ToolDeferral]); on unless turned off. */
object ToolDeferralPrefs {
    private const val PREFS = "minis_tool_deferral"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)
    } catch (_: Exception) {
        true
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
