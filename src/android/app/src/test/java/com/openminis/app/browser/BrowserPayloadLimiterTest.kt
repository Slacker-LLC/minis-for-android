package com.openminis.app.browser

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-browser-payload-budget-android] The ported Eta payload gate
 * (`agent/browser/BrowserPayloadLimiter.kt` @ c15de97): the ladder that keeps a
 * browser result inside its UTF-8 budget, and the honest labels it leaves behind.
 *
 * The WebView side (what a real page hands the script) is device-verified only;
 * what is pinned here is the arithmetic that decides what the model receives.
 */
class BrowserPayloadLimiterTest {

    private fun bytes(json: JSONObject): Int = json.toString().toByteArray(Charsets.UTF_8).size

    private fun bytes(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    @Test
    fun `a payload under the budget is returned untouched`() {
        val envelope = JSONObject().put("action", "get_text").put("text", "short page")

        val bounded = BrowserPayloadLimiter.bound(envelope)

        assertSame(envelope, bounded)
        assertEquals("short page", bounded.getString("text"))
        assertFalse(bounded.has("truncated"))
        assertFalse(bounded.has("payload_truncated"))
    }

    @Test
    fun `the ladder drops trailing element rows before it touches the text`() {
        val elements = JSONArray()
        repeat(400) { index ->
            elements.put(
                JSONObject()
                    .put("index", index)
                    .put("tag", "a")
                    .put("text", "row $index " + "x".repeat(120))
                    .put("href", "/path/$index"),
            )
        }
        val envelope = JSONObject()
            .put("action", "find_elements")
            .put("count", 400)
            .put("shown", 400)
            .put("elements", elements)

        BrowserPayloadLimiter.bound(envelope)

        assertTrue(bytes(envelope) <= BrowserPayloadLimiter.MAX_BYTES)
        assertTrue(envelope.getBoolean("elements_truncated"))
        assertTrue(envelope.getBoolean("truncated"))
        val kept = envelope.getJSONArray("elements")
        assertEquals(kept.length(), envelope.getInt("element_count"))
        assertTrue(kept.length() in 1 until 400)
        // The head survives: dropping starts at the tail.
        assertEquals(0, kept.getJSONObject(0).getInt("index"))
    }

    @Test
    fun `element rows that fit are left alone`() {
        val elements = JSONArray().put(JSONObject().put("index", 0).put("tag", "a"))
        val envelope = JSONObject()
            .put("action", "find_elements")
            .put("count", 1)
            .put("elements", elements)

        BrowserPayloadLimiter.bound(envelope)

        assertEquals(1, envelope.getJSONArray("elements").length())
        assertFalse(envelope.has("elements_truncated"))
        assertFalse(envelope.has("element_count"))
    }

    @Test
    fun `an oversized text field is cut at a prefix and re-labelled with the resume offset`() {
        val envelope = JSONObject()
            .put("action", "get_text")
            .put("offset", 4000)
            .put("text_length", 90_000)
            .put("text", "y".repeat(80_000))

        BrowserPayloadLimiter.bound(envelope)

        assertTrue(bytes(envelope) <= BrowserPayloadLimiter.MAX_BYTES)
        assertTrue(envelope.getBoolean("payload_truncated"))
        assertTrue(envelope.getBoolean("truncated"))
        val text = envelope.getString("text")
        val returned = envelope.getInt("returned_chars")
        assertEquals(text.length, returned)
        assertEquals(4000 + returned, envelope.getInt("next_offset"))
        assertTrue(returned > 0)
        assertTrue("y".repeat(returned) == text)
    }

    @Test
    fun `the text cut never splits a surrogate pair`() {
        // Four-byte characters put the byte boundary in the middle of a pair.
        val envelope = JSONObject()
            .put("action", "get_text")
            .put("text", "\uD83D\uDE00".repeat(20_000))

        BrowserPayloadLimiter.bound(envelope)

        assertTrue(bytes(envelope) <= BrowserPayloadLimiter.MAX_BYTES)
        val text = envelope.getString("text")
        assertTrue(text.isNotEmpty())
        assertFalse(Character.isHighSurrogate(text.last()))
        assertTrue(text.length % 2 == 0)
    }

    @Test
    fun `a payload that still does not fit collapses to the identifying fields`() {
        val envelope = JSONObject()
            .put("action", "get_text")
            .put("url", "https://example.com/")
            .put("next_offset", 12_345)
            .put("message", "z".repeat(60_000))
            .put("junk", "x".repeat(60_000))

        BrowserPayloadLimiter.bound(envelope)

        assertTrue(bytes(envelope) <= BrowserPayloadLimiter.MAX_BYTES)
        assertTrue(envelope.getBoolean("payload_truncated"))
        assertEquals("get_text", envelope.getString("action"))
        assertEquals(12_345, envelope.getInt("next_offset"))
        assertFalse(envelope.has("junk"))
        assertEquals(240, envelope.getString("message").length)
    }

    @Test
    fun `serialize keeps upstream's string contract`() {
        val envelope = JSONObject()
            .put("action", "find_elements")
            .put("count", 200)
            .put("elements", JSONArray().apply {
                repeat(200) { index -> put(JSONObject().put("index", index).put("text", "e".repeat(200))) }
            })

        val serialized = BrowserPayloadLimiter.serialize(envelope)

        assertTrue(bytes(serialized) <= BrowserPayloadLimiter.MAX_BYTES)
        val parsed = JSONObject(serialized)
        assertEquals("find_elements", parsed.getString("action"))
        assertTrue(parsed.getBoolean("elements_truncated"))
    }

    @Test
    fun `text under the budget is returned as the same instance`() {
        val text = "short result"
        assertSame(text, BrowserPayloadLimiter.boundText(text))
    }

    @Test
    fun `an oversized raw script return is cut with an honest marker`() {
        val text = "r".repeat(40_000)

        val bounded = BrowserPayloadLimiter.boundText(text)

        assertTrue(bytes(bounded) <= BrowserPayloadLimiter.MAX_BYTES)
        assertTrue(bounded.startsWith("r".repeat(100)))
        assertTrue(bounded.contains("[payload truncated:"))
        assertTrue(bounded.contains("of 40000 chars kept"))
    }

    @Test
    fun `the raw text cut never splits a surrogate pair`() {
        val bounded = BrowserPayloadLimiter.boundText("\uD83D\uDE00".repeat(20_000))

        assertTrue(bytes(bounded) <= BrowserPayloadLimiter.MAX_BYTES)
        val kept = bounded.substringBefore("\n[payload truncated:")
        assertFalse(Character.isHighSurrogate(kept.last()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a budget below the upstream floor is rejected`() {
        BrowserPayloadLimiter.bound(JSONObject(), maxBytes = 256)
    }

    @Test
    fun `boundResult leaves every non-text field alone`() {
        val result = BrowserActionResult(
            text = "t".repeat(40_000),
            success = true,
            imageFilePath = "/var/minis/browser/x.jpg",
            pageURL = "https://example.com/",
            tabId = 2,
        )

        val bounded = BrowserPayloadLimiter.boundResult(result)

        assertTrue(bytes(bounded.text) <= BrowserPayloadLimiter.MAX_BYTES)
        assertEquals(result.imageFilePath, bounded.imageFilePath)
        assertEquals(result.pageURL, bounded.pageURL)
        assertEquals(result.tabId, bounded.tabId)
        assertTrue(bounded.success)
    }

    @Test
    fun `boundResult is the identity for a result that already fits`() {
        val text = "Clicked <button>"
        val result = BrowserActionResult(text = text)
        assertSame(text, BrowserPayloadLimiter.boundResult(result).text)
    }
}
