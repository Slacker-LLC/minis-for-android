package com.openminis.app.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [T-eta-xposed-entry] Ported from Eta `core/LogSafety.kt` (Mangi-11/Eta @ c15de97).
 */
class LogSafetyTest {

    @Test
    fun `an exception is logged as its type, never with its message`() {
        val secret = "sensitive-runtime-message"

        val rendered = IllegalStateException(secret).safeLogType()

        assertEquals("IllegalStateException", rendered)
        assertFalse(rendered.contains(secret))
    }

    @Test
    fun `stable ascii tokens are kept`() {
        assertEquals("tool.read_file-1", "tool.read_file-1".toSafeLogToken())
        assertEquals("RESULT_OK", "RESULT_OK".toSafeLogToken())
    }

    @Test
    fun `untrusted or high-cardinality values become unknown`() {
        assertEquals("unknown", null.toSafeLogToken())
        assertEquals("unknown", "".toSafeLogToken())
        assertEquals("unknown", "tool\nforged-entry".toSafeLogToken())
        assertEquals("unknown", "包含用户内容".toSafeLogToken())
        assertEquals("unknown", "a".repeat(65).toSafeLogToken())
    }
}
