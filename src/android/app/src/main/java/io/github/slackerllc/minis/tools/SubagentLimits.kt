package io.github.slackerllc.minis.tools

import android.content.Context

/**
 * User-tunable limits for the `subagent` delegation tool.
 *
 * DeepSeek Harness-style knobs, kept intentionally small: how deep a
 * delegation tree may grow and how long one child run may take. The child
 * still inherits the parent's model group unless a sub group is configured
 * (see `provider.groups.setSubDefault`), matching the "inherit from parent"
 * composition model.
 */
object SubagentLimits {
    private const val PREFS = "minis_subagent_prefs"
    private const val KEY_MAX_DEPTH = "max_depth"
    private const val KEY_TIMEOUT_MINUTES = "timeout_minutes"

    const val DEFAULT_MAX_DEPTH = 3
    const val DEFAULT_TIMEOUT_MINUTES = 10L
    val MAX_DEPTH_RANGE = 1..5
    val TIMEOUT_MINUTES_RANGE = 1L..30L

    private fun prefs(context: Context): android.content.SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun maxDepth(context: Context): Int =
        prefs(context).getInt(KEY_MAX_DEPTH, DEFAULT_MAX_DEPTH)
            .coerceIn(MAX_DEPTH_RANGE.first, MAX_DEPTH_RANGE.last)

    fun timeoutMs(context: Context): Long {
        val minutes = prefs(context).getLong(KEY_TIMEOUT_MINUTES, DEFAULT_TIMEOUT_MINUTES)
            .coerceIn(TIMEOUT_MINUTES_RANGE.first, TIMEOUT_MINUTES_RANGE.last)
        return minutes * 60_000L
    }

    fun save(context: Context, maxDepth: Int, timeoutMinutes: Long) {
        prefs(context).edit()
            .putInt(KEY_MAX_DEPTH, maxDepth.coerceIn(MAX_DEPTH_RANGE.first, MAX_DEPTH_RANGE.last))
            .putLong(KEY_TIMEOUT_MINUTES, timeoutMinutes.coerceIn(TIMEOUT_MINUTES_RANGE.first, TIMEOUT_MINUTES_RANGE.last))
            .apply()
    }
}
