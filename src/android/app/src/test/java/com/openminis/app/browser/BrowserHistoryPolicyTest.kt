package com.openminis.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [T-browser-history-actions-android] The headline and the refusal of Eta's
 * `go_back` / `go_forward` / `reload` actions (Mangi-11/Eta @ c15de97), which this
 * app's schema was missing: without them an agent that followed a link had to
 * re-navigate to a URL it remembered.
 *
 * The move itself needs a WebView and is device-verified only; what is pinned here
 * is that a tab with no history says so instead of reporting a move that never
 * happened.
 */
class BrowserHistoryPolicyTest {

    @Test
    fun `each move names its direction`() {
        assertEquals("Went back to the previous page.", BrowserHistoryPolicy.moved(backwards = true))
        assertEquals("Went forward to the next page.", BrowserHistoryPolicy.moved(backwards = false))
    }

    @Test
    fun `a reload says it reloaded`() {
        assertEquals("Reloaded the page.", BrowserHistoryPolicy.reloaded())
    }

    @Test
    fun `an empty history is a refusal, not a silent no-op`() {
        val back = BrowserHistoryPolicy.unavailable(backwards = true)
        val forward = BrowserHistoryPolicy.unavailable(backwards = false)

        assertEquals("Cannot go back: this tab has no earlier page in its history.", back)
        assertEquals("Cannot go forward: this tab has no later page in its history.", forward)
        assertNotEquals(back, forward)
    }

    @Test
    fun `the refusal cannot be mistaken for a successful move`() {
        assertNotEquals(
            BrowserHistoryPolicy.moved(backwards = true),
            BrowserHistoryPolicy.unavailable(backwards = true),
        )
    }
}
