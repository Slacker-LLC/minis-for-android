package com.openminis.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-browser-wait-for-selector-android] The ported wait budget of Eta's
 * `waitForSelector` (Mangi-11/Eta @ c15de97): default 5 s, clamped to 0.5–30 s, and
 * the outcome lines this app prints in place of upstream's JSON envelope.
 */
class BrowserSelectorWaitPolicyTest {

    @Test
    fun `an absent timeout waits five seconds`() {
        assertEquals(5_000L, BrowserSelectorWaitPolicy.timeout(null))
    }

    @Test
    fun `the timeout clamps to upstream's floor and ceiling`() {
        assertEquals(500L, BrowserSelectorWaitPolicy.timeout(10))
        assertEquals(30_000L, BrowserSelectorWaitPolicy.timeout(600_000))
        assertEquals(8_000L, BrowserSelectorWaitPolicy.timeout(8_000))
    }

    @Test
    fun `a found selector reports how long it took and whether it is clickable`() {
        assertEquals(
            "Selector #submit appeared after 750ms (visible, enabled).",
            BrowserSelectorWaitPolicy.found("#submit", elapsedMs = 750, enabled = true),
        )
        assertEquals(
            "Selector #submit appeared after 750ms (visible, disabled).",
            BrowserSelectorWaitPolicy.found("#submit", elapsedMs = 750, enabled = false),
        )
    }

    @Test
    fun `a timeout says which selector, for how long, and how often it looked`() {
        val text = BrowserSelectorWaitPolicy.notFound(".result-row", timeoutMs = 5_000)

        assertEquals(
            "Selector .result-row did not appear within 5000ms (polled every 250ms).",
            text,
        )
    }

    @Test
    fun `the poll interval stays the one upstream polls at`() {
        assertEquals(250L, BrowserSelectorWaitPolicy.POLL_INTERVAL_MS)
        assertTrue(BrowserSelectorWaitPolicy.MIN_TIMEOUT_MS < BrowserSelectorWaitPolicy.DEFAULT_TIMEOUT_MS)
        assertTrue(BrowserSelectorWaitPolicy.DEFAULT_TIMEOUT_MS < BrowserSelectorWaitPolicy.MAX_TIMEOUT_MS)
    }
}
