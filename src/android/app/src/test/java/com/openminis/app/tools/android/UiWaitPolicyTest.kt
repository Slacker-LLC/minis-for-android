package com.openminis.app.tools.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-eta-wait-match] Ported from Eta `RootShellDeviceController.matches` (Mangi-11/Eta @ c15de97).
 * The case rules are the point: a wait that matches "OK" when asked for "ok" is the behaviour
 * upstream ships for the default mode, and the exact mode is what a caller uses when that is wrong.
 */
class UiWaitPolicyTest {

    @Test
    fun `an unset mode is contains and an unknown one is refused`() {
        assertEquals(UiWaitPolicy.MODE_CONTAINS, UiWaitPolicy.parse(null))
        assertEquals(UiWaitPolicy.MODE_CONTAINS, UiWaitPolicy.parse("  "))
        assertEquals(UiWaitPolicy.MODE_REGEX, UiWaitPolicy.parse(" REGEX "))
        assertNull("a typo must not become a different search", UiWaitPolicy.parse("fuzzy"))
    }

    @Test
    fun `the default mode ignores case and matches inside the value`() {
        assertTrue(UiWaitPolicy.matches("Sending...", "send", UiWaitPolicy.MODE_CONTAINS))
        assertTrue(UiWaitPolicy.matches("Send", "send", UiWaitPolicy.MODE_CONTAINS))
        assertFalse(UiWaitPolicy.matches(null, "send", UiWaitPolicy.MODE_CONTAINS))
    }

    @Test
    fun `exact and prefix compare the value as it stands`() {
        assertTrue(UiWaitPolicy.matches("Send", "Send", UiWaitPolicy.MODE_EXACT))
        assertFalse(UiWaitPolicy.matches("Send", "send", UiWaitPolicy.MODE_EXACT))
        assertTrue(UiWaitPolicy.matches("Sending...", "Send", UiWaitPolicy.MODE_PREFIX))
        assertFalse(
            "prefix is not contains",
            UiWaitPolicy.matches("Please Send", "Send", UiWaitPolicy.MODE_PREFIX),
        )
    }

    @Test
    fun `a regex that does not compile simply does not match`() {
        assertTrue(UiWaitPolicy.matches("order #42", "order #\\d+", UiWaitPolicy.MODE_REGEX))
        assertFalse(UiWaitPolicy.matches("order #42", "order #(", UiWaitPolicy.MODE_REGEX))
    }
}
