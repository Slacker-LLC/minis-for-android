package com.openminis.app.browser

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-browser-element-rows-android] The rows `find_elements` hands the model: one
 * line per element plus the selector, accessibility fields and box that make the
 * follow-up click nameable (Eta's `describe()` shape, Mangi-11/Eta @ c15de97).
 */
class BrowserElementListFormatterTest {

    private fun row(
        tag: String = "button",
        text: String? = "Sign in",
        selector: String? = "#submit",
        role: String? = null,
        aria: String? = null,
        placeholder: String? = null,
        type: String? = null,
        href: String? = null,
        bounds: JSONObject? = JSONObject().put("x", 12).put("y", 340).put("width", 88).put("height", 44),
    ) = JSONObject().apply {
        put("tag", tag)
        put("text", text ?: JSONObject.NULL)
        put("selector", selector ?: JSONObject.NULL)
        put("role", role ?: JSONObject.NULL)
        put("aria_label", aria ?: JSONObject.NULL)
        put("placeholder", placeholder ?: JSONObject.NULL)
        put("type", type ?: JSONObject.NULL)
        put("href", href ?: JSONObject.NULL)
        if (bounds != null) put("bounds", bounds)
    }

    private fun envelope(vararg elements: JSONObject, truncated: Boolean = false, scanned: Int = 340) =
        JSONObject()
            .put("selector_used", "a,button,input")
            .put("element_count", elements.size)
            .put("scanned_elements", scanned)
            .put("truncated", truncated)
            .put("elements", JSONArray(elements.toList()))

    @Test
    fun `a row states the tag, its text, the selector and the box`() {
        val formatted = BrowserElementListFormatter.format(envelope(row()))

        assertEquals(
            listOf(
                "Found 1 element(s) (showing 1 of 340 scanned) for a,button,input:",
                "  [0] <button> \"Sign in\"",
                "      sel=#submit box=12,340 88x44",
            ),
            formatted.split("\n"),
        )
    }

    @Test
    fun `the accessibility fields are printed when the element has them`() {
        val formatted = BrowserElementListFormatter.format(
            envelope(
                row(
                    tag = "input",
                    text = null,
                    selector = null,
                    role = "textbox",
                    aria = "Search the site",
                    placeholder = "Search",
                    type = "search",
                    bounds = null,
                ),
            ),
        )

        assertTrue(formatted.contains("  [0] <input>"))
        assertTrue(formatted.contains("role=textbox"))
        assertTrue(formatted.contains("aria=\"Search the site\""))
        assertTrue(formatted.contains("ph=\"Search\""))
        assertTrue(formatted.contains("type=search"))
        // No bounds on this element, so no box token at all.
        assertFalse(formatted.contains("box="))
    }

    @Test
    fun `an absent field is absent, not the string null`() {
        val formatted = BrowserElementListFormatter.format(envelope(row(role = null, href = null, placeholder = null)))

        assertFalse(formatted.contains("null"))
        assertFalse(formatted.contains("role="))
    }

    @Test
    fun `a link row carries its href`() {
        val formatted = BrowserElementListFormatter.format(
            envelope(row(tag = "a", text = "Docs", selector = "a.docs", href = "https://example.com/docs")),
        )

        assertTrue(formatted.contains("  [0] <a> \"Docs\" -> https://example.com/docs"))
    }

    @Test
    fun `the header counts what is printed, not what the page matched`() {
        // The payload limiter drops trailing rows and rewrites element_count, so the
        // header has to agree with the rows below it.
        val trimmed = JSONObject()
            .put("selector_used", "button")
            .put("element_count", 2)
            .put("scanned_elements", 900)
            .put("truncated", true)
            .put("elements", JSONArray(listOf(row(), row(text = "Cancel"))))

        val formatted = BrowserElementListFormatter.format(trimmed)

        assertTrue(formatted.startsWith("Found 2 element(s) (showing 2 of 900 scanned) for button:"))
        assertTrue(formatted.contains("  [1] <button> \"Cancel\""))
    }

    @Test
    fun `a truncated scan says so instead of looking complete`() {
        val formatted = BrowserElementListFormatter.format(envelope(row(), truncated = true, scanned = 3000))

        assertTrue(formatted.endsWith("the scan stopped early (3000 matches, 16 rows or 500 ms)."))
    }

    @Test
    fun `row text cannot break out of its own quoting`() {
        val formatted = BrowserElementListFormatter.format(
            envelope(row(text = "He said \"hi\"\nand left", selector = "div.x")),
        )

        val line = formatted.split("\n")[1]
        assertEquals("  [0] <button> \"He said 'hi' and left\"", line)
    }

    @Test
    fun `an empty result still prints its header`() {
        val formatted = BrowserElementListFormatter.format(
            JSONObject().put("selector_used", ".nothing").put("element_count", 0).put("elements", JSONArray()),
        )

        assertEquals("Found 0 element(s) (showing 0) for .nothing:", formatted)
    }

    @Test
    fun `the older element shape still formats`() {
        // execute_js can hand back an {elements:[...]} the page built itself.
        val legacy = JSONObject()
            .put("count", 3)
            .put("shown", 3)
            .put(
                "elements",
                JSONArray(
                    listOf(
                        JSONObject()
                            .put("index", 0)
                            .put("tag", "A")
                            .put("text", "Home")
                            .put("href", "https://example.com/")
                            .put("rect", JSONObject().put("x", 0).put("y", 0).put("width", 40).put("height", 20)),
                    ),
                ),
            )

        val formatted = BrowserElementListFormatter.format(legacy)

        assertTrue(formatted.startsWith("Found 3 element(s) (showing 1):"))
        assertTrue(formatted.contains("  [0] <a> \"Home\" -> https://example.com/"))
        assertTrue(formatted.contains("box=0,0 40x20"))
    }

    @Test
    fun `an envelope without elements formats to nothing`() {
        assertEquals("", BrowserElementListFormatter.format(JSONObject().put("text", "hi")))
    }
}
