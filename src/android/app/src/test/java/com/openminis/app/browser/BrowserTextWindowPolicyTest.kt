package com.openminis.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-browser-paged-text-android] The ported Eta read-page window policy
 * (`readPage` / `BrowserDomScripts.text` @ c15de97): argument clamps, the window
 * arithmetic the page-side script mirrors, and the header that tells the model
 * where in the document it is reading.
 */
class BrowserTextWindowPolicyTest {

    @Test
    fun `an absent offset starts at the top`() {
        assertEquals(0, BrowserTextWindowPolicy.offset(null))
    }

    @Test
    fun `a negative offset is clamped to the top and a huge one to upstream's ceiling`() {
        assertEquals(0, BrowserTextWindowPolicy.offset(-500))
        assertEquals(
            BrowserTextWindowPolicy.MAX_OFFSET,
            BrowserTextWindowPolicy.offset(BrowserTextWindowPolicy.MAX_OFFSET + 1_000),
        )
    }

    @Test
    fun `an absent window size defaults to 8000 and clamps into the allowed range`() {
        assertEquals(8_000, BrowserTextWindowPolicy.maxChars(null))
        assertEquals(256, BrowserTextWindowPolicy.maxChars(10))
        assertEquals(12_000, BrowserTextWindowPolicy.maxChars(999_999))
    }

    @Test
    fun `a window in the middle of the document ends at the next offset`() {
        val window = BrowserTextWindowPolicy.window(offset = 8_000, maxChars = 8_000, totalChars = 34_821)

        assertEquals(8_000, window.start)
        assertEquals(16_000, window.end)
        assertEquals(8_000, window.returnedChars)
        assertEquals(16_000, window.nextOffset)
        assertTrue(window.truncated)
    }

    @Test
    fun `the last window has no next offset`() {
        val window = BrowserTextWindowPolicy.window(offset = 32_000, maxChars = 8_000, totalChars = 34_821)

        assertEquals(32_000, window.start)
        assertEquals(34_821, window.end)
        assertEquals(2_821, window.returnedChars)
        assertNull(window.nextOffset)
        assertFalse(window.truncated)
    }

    @Test
    fun `an offset past the end returns an empty final window`() {
        val window = BrowserTextWindowPolicy.window(offset = 99_999, maxChars = 8_000, totalChars = 1_000)

        assertEquals(1_000, window.start)
        assertEquals(1_000, window.end)
        assertEquals(0, window.returnedChars)
        assertNull(window.nextOffset)
        assertFalse(window.truncated)
    }

    @Test
    fun `an empty document is not reported as truncated`() {
        val window = BrowserTextWindowPolicy.window(offset = 0, maxChars = 8_000, totalChars = 0)

        assertEquals(0, window.returnedChars)
        assertNull(window.nextOffset)
        assertFalse(window.truncated)
    }

    @Test
    fun `the header spells out the resume offset the model has to pass back`() {
        val window = BrowserTextWindowPolicy.window(0, 8_000, 34_821)

        val header = BrowserTextWindowPolicy.describe(window, totalChars = 34_821, sourceTruncated = false)

        assertEquals("Text (chars 0-8000 of 34821; next_offset=8000)", header)
    }

    @Test
    fun `the header says when the window reached the end of the document`() {
        val window = BrowserTextWindowPolicy.window(32_000, 8_000, 34_821)

        val header = BrowserTextWindowPolicy.describe(window, totalChars = 34_821, sourceTruncated = false)

        assertEquals("Text (chars 32000-34821 of 34821; end of document)", header)
    }

    @Test
    fun `a page-side collection that stopped early is admitted in the header`() {
        val window = BrowserTextWindowPolicy.window(0, 8_000, BrowserTextWindowPolicy.MAX_DOCUMENT_CHARS)

        val header = BrowserTextWindowPolicy.describe(
            window,
            totalChars = BrowserTextWindowPolicy.MAX_DOCUMENT_CHARS,
            sourceTruncated = true,
        )

        assertTrue(header.contains("page text capped at 200000 chars"))
    }
}
