package com.openminis.app.xposed

import android.util.Log

/**
 * [T-eta-xposed-entry] The module's logging seam: the entry class owns the tag and the sink, a hook
 * group only writes lines.
 *
 * Ported from Eta `core/ModuleLogger.kt` (Mangi-11/Eta @ c15de97); attribution in
 * THIRD_PARTY_LICENSES.md. A group's messages are prefixed with the group name so a logcat line
 * says which feature area produced it, and a lazy message keeps a hot path from building strings
 * that are never printed.
 */
class HookLogger private constructor(
    private val sink: (priority: Int, message: String) -> Unit,
    private val scope: String?,
) {
    constructor(sink: (priority: Int, message: String) -> Unit) : this(sink, null)

    private val throttle = LogThrottle()

    fun scoped(group: String): HookLogger =
        HookLogger(sink, scope?.let { "$it/$group" } ?: group)

    fun debug(message: () -> String) = write(Log.DEBUG, message())

    fun info(message: String) = write(Log.INFO, message)

    fun warn(message: String) = write(Log.WARN, message)

    fun error(message: String) = write(Log.ERROR, message)

    /**
     * For paths that run per touch or per frame: one line per key per window. The window is a
     * parameter because a caller that is deliberately noisy about a slow repair wants its own
     * cadence rather than the hot-path default.
     */
    fun warnThrottled(key: String, windowMs: Long = DEFAULT_THROTTLE_WINDOW_MS, message: () -> String) {
        if (throttle.shouldLog(throttleKey("warn", key), windowMs)) warn(message())
    }

    /** The info-level counterpart: state that repeats per gesture but is not a failure. */
    fun infoThrottled(key: String, windowMs: Long = DEFAULT_THROTTLE_WINDOW_MS, message: () -> String) {
        if (throttle.shouldLog(throttleKey("info", key), windowMs)) info(message())
    }

    fun errorThrottled(key: String, windowMs: Long = DEFAULT_THROTTLE_WINDOW_MS, message: () -> String) {
        if (throttle.shouldLog(throttleKey("error", key), windowMs)) error(message())
    }

    private fun throttleKey(level: String, key: String): String =
        listOfNotNull(scope, level, key).joinToString(":")

    private fun write(priority: Int, message: String) {
        val line = scope?.let { "[$it] $message" } ?: message
        runCatching { sink(priority, line) }
    }

    companion object {
        /** The window a throttled line uses when the caller does not ask for its own. */
        const val DEFAULT_THROTTLE_WINDOW_MS = 60_000L
    }
}
