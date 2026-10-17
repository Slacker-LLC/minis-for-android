package com.openminis.app.tools.runtime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** `shell_execute` declares `delay`; the handler must honour it, bounded. */
class ShellDelayTest {
    @Test
    fun missingDelayIsZero() {
        assertEquals(0L, shellDelayMs(JSONObject()))
    }

    @Test
    fun delayIsSecondsInMillis() {
        assertEquals(5_000L, shellDelayMs(JSONObject().put("delay", 5)))
    }

    @Test
    fun negativeDelayIsZero() {
        assertEquals(0L, shellDelayMs(JSONObject().put("delay", -3)))
    }

    @Test
    fun oversizedDelayIsClamped() {
        assertEquals(MAX_SHELL_DELAY_SEC * 1_000L, shellDelayMs(JSONObject().put("delay", 100_000)))
    }
}
