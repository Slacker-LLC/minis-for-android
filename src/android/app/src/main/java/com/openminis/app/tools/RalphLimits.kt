package com.openminis.app.tools

import android.content.Context

/** Actual per-round Ralph budget. Keep the existing preference file/key for installed users. */
object RalphLimits {
    private const val PREFS = "minis_subagent_prefs"
    private const val KEY_TIMEOUT_MINUTES = "timeout_minutes"

    const val DEFAULT_TIMEOUT_MINUTES = 10L
    val TIMEOUT_MINUTES_RANGE = 1L..30L

    private fun prefs(context: Context): android.content.SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun timeoutMs(context: Context): Long {
        val minutes = prefs(context).getLong(KEY_TIMEOUT_MINUTES, DEFAULT_TIMEOUT_MINUTES)
            .coerceIn(TIMEOUT_MINUTES_RANGE.first, TIMEOUT_MINUTES_RANGE.last)
        return minutes * 60_000L
    }

    fun save(context: Context, timeoutMinutes: Long) {
        prefs(context).edit()
            .putLong(KEY_TIMEOUT_MINUTES, timeoutMinutes.coerceIn(TIMEOUT_MINUTES_RANGE.first, TIMEOUT_MINUTES_RANGE.last))
            .apply()
    }
}
