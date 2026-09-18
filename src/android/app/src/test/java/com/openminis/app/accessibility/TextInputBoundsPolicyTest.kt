package com.openminis.app.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-eta-text-insert] Ported from Eta `RootShellDeviceController` (Mangi-11/Eta @ c15de97). The
 * bounds are what keeps a mistaken whole-document paste out of somebody's search box, and the empty
 * case is the one that differs between an insertion and a whole-value write.
 */
class TextInputBoundsPolicyTest {

    @Test
    fun `an insertion may not be empty and may not be oversized`() {
        assertEquals("INVALID_ARGUMENT", TextInputBoundsPolicy.insertRefusal("")?.code)
        assertNull(
            TextInputBoundsPolicy.insertRefusal("a".repeat(TextInputBoundsPolicy.MAX_INSERT_CHARS)),
        )
        assertEquals(
            "TEXT_TOO_LONG",
            TextInputBoundsPolicy.insertRefusal(
                "a".repeat(TextInputBoundsPolicy.MAX_INSERT_CHARS + 1),
            )?.code,
        )
    }

    @Test
    fun `a whole-value write may be empty, but not longer than its bound`() {
        assertNull("clearing a field is a write of nothing", TextInputBoundsPolicy.replaceRefusal(""))
        assertNull(
            TextInputBoundsPolicy.replaceRefusal("a".repeat(TextInputBoundsPolicy.MAX_REPLACE_CHARS)),
        )
        assertEquals(
            "TEXT_TOO_LONG",
            TextInputBoundsPolicy.replaceRefusal(
                "a".repeat(TextInputBoundsPolicy.MAX_REPLACE_CHARS + 1),
            )?.code,
        )
    }
}
