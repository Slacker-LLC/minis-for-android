package com.openminis.app.xposed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-eta-xposed-entry] Ported from Eta `core/LogThrottle.kt` (Mangi-11/Eta @ c15de97). A hook that
 * logs per touch must not push the one line that mattered out of logcat.
 */
class LogThrottleTest {

    @Test
    fun `the first line for a key is always allowed`() {
        val throttle = LogThrottle(windowMs = 1_000L) { 0L }

        assertTrue(throttle.shouldLog("a"))
    }

    @Test
    fun `a second line inside the window is dropped and the next one outside is kept`() {
        var now = 0L
        val throttle = LogThrottle(windowMs = 1_000L) { now }

        assertTrue(throttle.shouldLog("a"))
        now = 400L
        assertFalse(throttle.shouldLog("a"))
        now = 999L
        assertFalse(throttle.shouldLog("a"))
        now = 1_000L
        assertTrue("the window is a duration, not a bucket", throttle.shouldLog("a"))
    }

    @Test
    fun `keys are throttled independently`() {
        var now = 0L
        val throttle = LogThrottle(windowMs = 1_000L) { now }

        assertTrue(throttle.shouldLog("a"))
        now = 10L
        assertTrue(throttle.shouldLog("b"))
        assertFalse(throttle.shouldLog("a"))
    }

    @Test
    fun `a custom window overrides the default`() {
        var now = 0L
        val throttle = LogThrottle(windowMs = 10_000L) { now }

        assertTrue(throttle.shouldLog("a"))
        now = 5_000L
        assertFalse("the default window would have allowed this", throttle.shouldLog("a"))
        now = 6_000L
        assertTrue(throttle.shouldLog("a", windowMs = 1_000L))
    }
}
