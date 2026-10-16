package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The opened work panel is capped in height and scrolls inside itself. While the run works it keeps
 * its newest step in view, unless the user scrolled up to read an earlier one.
 */
class WorkPanelFollowTest {

    @Test
    fun `a running panel left at the bottom follows new content`() {
        assertTrue(shouldFollowPanelBottom(running = true, followBottom = true, value = 300, max = 420))
    }

    @Test
    fun `a panel the user scrolled up is not pulled back down`() {
        assertFalse(shouldFollowPanelBottom(running = true, followBottom = false, value = 100, max = 420))
    }

    @Test
    fun `a finished panel is left where it is`() {
        assertFalse(shouldFollowPanelBottom(running = false, followBottom = true, value = 0, max = 420))
    }

    @Test
    fun `nothing to scroll once the panel already shows the bottom`() {
        assertFalse(shouldFollowPanelBottom(running = true, followBottom = true, value = 420, max = 420))
    }

    @Test
    fun `within the slack of the end counts as the bottom, further up does not`() {
        assertTrue(isPanelAtBottom(value = 400, max = 420, slackPx = 24))
        assertTrue(isPanelAtBottom(value = 420, max = 420, slackPx = 24))
        assertFalse(isPanelAtBottom(value = 300, max = 420, slackPx = 24))
    }

    @Test
    fun `a panel that does not scroll counts as at the bottom`() {
        assertTrue(isPanelAtBottom(value = 0, max = 0, slackPx = 24))
    }
}
