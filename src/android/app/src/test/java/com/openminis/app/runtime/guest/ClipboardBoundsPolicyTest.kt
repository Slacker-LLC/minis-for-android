package com.openminis.app.runtime.guest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-eta-clipboard-bounds] Ported from Eta `RootShellDeviceController` (Mangi-11/Eta @ c15de97).
 * The read side must never claim to be complete when it is not, and the write side must refuse
 * before the clipboard changes rather than truncate what the user asked to copy.
 */
class ClipboardBoundsPolicyTest {

    @Test
    fun `a short read is handed on unchanged`() {
        val read = ClipboardBoundsPolicy.read("hello")

        assertEquals("hello", read.text)
        assertFalse(read.truncated)
    }

    @Test
    fun `a long read is truncated and says so`() {
        val long = "a".repeat(ClipboardBoundsPolicy.MAX_READ_CHARS + 10)

        val read = ClipboardBoundsPolicy.read(long)

        assertEquals(ClipboardBoundsPolicy.MAX_READ_CHARS, read.text.length)
        assertTrue(read.truncated)
        assertEquals(
            "a read of exactly the bound is complete",
            false,
            ClipboardBoundsPolicy.read("a".repeat(ClipboardBoundsPolicy.MAX_READ_CHARS)).truncated,
        )
    }

    @Test
    fun `a write past the bound is refused, not truncated`() {
        assertNull(ClipboardBoundsPolicy.writeRefusal("a".repeat(ClipboardBoundsPolicy.MAX_WRITE_CHARS)))
        assertNull(ClipboardBoundsPolicy.writeRefusal(""))
        assertEquals(
            true,
            ClipboardBoundsPolicy.writeRefusal(
                "a".repeat(ClipboardBoundsPolicy.MAX_WRITE_CHARS + 1),
            )?.contains("20000") == true,
        )
    }
}
