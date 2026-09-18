package com.openminis.app.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-eta-text-insert] Ported from Eta `agent/accessibility/TextEditPlanner.kt` (Mangi-11/Eta @
 * c15de97). These are the cases that decide whether a write into a filled field is attempted at
 * all: a wrong reconstruction would silently overwrite text the user typed.
 */
class TextEditPlannerTest {

    @Test
    fun `an insertion replaces the selection and leaves the cursor after it`() {
        assertEquals(
            TextEditPlanner.Plan("Hello world", 11),
            TextEditPlanner.insertAtSelection("Hello ", "world", 6, 6),
        )
        assertEquals(
            "a selection is replaced, not appended to",
            TextEditPlanner.Plan("Hello there", 11),
            TextEditPlanner.insertAtSelection("Hello world", "there", 6, 11),
        )
        assertEquals(
            "a backwards selection is the same range",
            TextEditPlanner.Plan("Hello there", 11),
            TextEditPlanner.insertAtSelection("Hello world", "there", 11, 6),
        )
    }

    @Test
    fun `an empty field with no cursor inserts at the start`() {
        assertEquals(
            TextEditPlanner.Plan("abc", 3),
            TextEditPlanner.insertAtSelection("", "abc", -1, -1),
        )
    }

    @Test
    fun `a filled field with an impossible selection is refused`() {
        assertNull(TextEditPlanner.insertAtSelection("abc", "x", -1, -1))
        assertNull(TextEditPlanner.insertAtSelection("abc", "x", 0, 4))
        assertNull(TextEditPlanner.insertAtSelection("abc", "x", 5, 5))
    }

    @Test
    fun `only a field that hands over text and a selection may be reconstructed from`() {
        assertTrue(
            TextEditPlanner.canSafelyReconstruct(
                password = false,
                textAvailable = true,
                textLength = 3,
                selectionStart = 1,
                selectionEnd = 2,
            ),
        )
        assertTrue(
            "an empty field with a not-yet-created cursor is still usable",
            TextEditPlanner.canSafelyReconstruct(
                password = false,
                textAvailable = true,
                textLength = 0,
                selectionStart = -1,
                selectionEnd = -1,
            ),
        )
        assertFalse(
            "a password field is never reconstructed from",
            TextEditPlanner.canSafelyReconstruct(
                password = true,
                textAvailable = true,
                textLength = 3,
                selectionStart = 1,
                selectionEnd = 1,
            ),
        )
        assertFalse(
            TextEditPlanner.canSafelyReconstruct(
                password = false,
                textAvailable = false,
                textLength = 3,
                selectionStart = 1,
                selectionEnd = 1,
            ),
        )
        assertFalse(
            TextEditPlanner.canSafelyReconstruct(
                password = false,
                textAvailable = true,
                textLength = -1,
                selectionStart = 0,
                selectionEnd = 0,
            ),
        )
        assertFalse(
            TextEditPlanner.canSafelyReconstruct(
                password = false,
                textAvailable = true,
                textLength = 3,
                selectionStart = 0,
                selectionEnd = 4,
            ),
        )
    }
}
