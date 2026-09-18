package com.openminis.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-browser-read-image-android] The parse contract of a `browser_use` call: the
 * arguments the model sends become the fields the actions read.
 *
 * Pinned here because two of them are easy to get wrong in opposite directions:
 * upstream's `read_image` defaults to **true** (attach the screenshot), and the
 * timeout arrives under either `timeout_ms` (upstream's spelling, which the newer
 * actions document) or the older `timeout` this app's schema used.
 */
class BrowserActionInputTest {

    @Test
    fun `a screenshot is attached unless the caller says otherwise`() {
        assertTrue(parse("""{"action":"screenshot"}""")!!.readImage)
        assertTrue(parse("""{"action":"screenshot","read_image":true}""")!!.readImage)
        assertFalse(parse("""{"action":"screenshot","read_image":false}""")!!.readImage)
    }

    @Test
    fun `read_image also carries the auto-captured snapshots`() {
        val click = parse("""{"action":"click","selector":"#go","read_image":false}""")!!

        assertEquals(BrowserAction.CLICK, click.action)
        assertEquals("#go", click.selector)
        assertFalse(click.readImage)
    }

    @Test
    fun `the timeout arrives under either spelling`() {
        assertEquals(2_500, parse("""{"action":"wait_for_selector","selector":".x","timeout_ms":2500}""")!!.timeoutMs)
        assertEquals(1_500, parse("""{"action":"wait_for_dom_stable","timeout":1500}""")!!.timeoutMs)
        assertNull(parse("""{"action":"wait_for_dom_stable"}""")!!.timeoutMs)
    }

    @Test
    fun `the newer arguments survive the round trip`() {
        val input = parse(
            """{"action":"type","selector":"#q","text":"hi","submit":true,""" +
                """"read_image":false,"offset":40,"max_chars":512}""",
        )!!

        assertEquals(BrowserAction.TYPE, input.action)
        assertEquals("hi", input.text)
        assertTrue(input.submit)
        assertFalse(input.readImage)
        assertEquals(40, input.offset)
        assertEquals(512, input.maxChars)
    }

    @Test
    fun `an unknown action is not silently invented`() {
        assertNull(parse("""{"action":"teleport"}"""))
    }

    private fun parse(json: String): BrowserActionInput? = BrowserActionInput.parse(json)
}
