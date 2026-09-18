package com.openminis.app.xposed

/**
 * [T-eta-xposed-entry] One line per key per window, for the paths that run on every touch.
 *
 * Ported from Eta `core/LogThrottle.kt` (Mangi-11/Eta @ c15de97); attribution in
 * THIRD_PARTY_LICENSES.md. A hook that logs inside a gesture or a frame callback would otherwise
 * fill logcat with the same line and hide the one occurrence that mattered; the clock is injected
 * so the rule can be tested.
 */
class LogThrottle(
    private val windowMs: Long = 60_000L,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lastLogged = mutableMapOf<String, Long>()

    @Synchronized
    fun shouldLog(key: String, windowMs: Long = this.windowMs): Boolean {
        val timestamp = now()
        val previous = lastLogged[key]
        if (previous != null && timestamp - previous < windowMs) return false
        lastLogged[key] = timestamp
        return true
    }
}
