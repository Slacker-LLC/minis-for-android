package com.openminis.app.browser

import org.json.JSONObject

/**
 * [T-browser-element-rows-android] The model-facing rows of `find_elements`.
 *
 * The row shape is Eta's `describe()` (Mangi-11/Eta @ c15de97): the selector that
 * was verified to match exactly one node, the accessibility fields, and the box in
 * viewport pixels. Printing them turns "there is a button whose text says 登录" into
 * "click `#login-submit`", which is the difference between a guess and a click.
 *
 * Pure formatting so the row contract is unit-tested; the page side that produces
 * the fields is [BrowserDomScripts].
 */
internal object BrowserElementListFormatter {
    /** Longest element text printed on a row. */
    private const val MAX_TEXT_CHARS = 120

    /** Longest selector / accessible label printed on a detail line. */
    private const val MAX_FIELD_CHARS = 160

    /** Longest href printed on a row. */
    private const val MAX_HREF_CHARS = 240

    fun format(json: JSONObject): String {
        val elements = json.optJSONArray("elements") ?: return ""
        val count = json.optInt("element_count", json.optInt("count", elements.length()))
        val scanned = if (json.has("scanned_elements") && !json.isNull("scanned_elements")) {
            json.optInt("scanned_elements")
        } else {
            null
        }
        val selectorUsed = text(json, "selector_used", MAX_FIELD_CHARS)
        val truncated = json.optBoolean("truncated", false)

        return buildString {
            appendLine(header(count, elements.length(), scanned, selectorUsed))
            for (index in 0 until elements.length()) {
                val element = elements.optJSONObject(index) ?: continue
                appendLine(row(index, element))
                detail(element)?.let { appendLine(it) }
            }
            if (truncated) {
                append("  Note: the page matched more elements than these ")
                append(count)
                append("; the scan stopped early (3000 matches, 16 rows or 500 ms).")
            }
        }.trimEnd()
    }

    private fun header(count: Int, shown: Int, scanned: Int?, selectorUsed: String): String = buildString {
        append("Found ").append(count).append(" element(s)")
        append(" (showing ").append(shown)
        if (scanned != null) append(" of ").append(scanned).append(" scanned")
        append(')')
        if (selectorUsed.isNotEmpty()) append(" for ").append(selectorUsed)
        append(':')
    }

    private fun row(index: Int, element: JSONObject): String = buildString {
        append("  [").append(index).append("] <")
        append(text(element, "tag", 32).ifEmpty { "?" }.lowercase())
        append('>')
        text(element, "text", MAX_TEXT_CHARS).takeIf { it.isNotEmpty() }?.let {
            append(" \"").append(it).append('"')
        }
        text(element, "href", MAX_HREF_CHARS).takeIf { it.isNotEmpty() }?.let {
            append(" -> ").append(it)
        }
    }

    /** The selectors and boxes, printed only for the fields the element actually has. */
    private fun detail(element: JSONObject): String? {
        val parts = mutableListOf<String>()
        text(element, "selector", MAX_FIELD_CHARS).takeIf { it.isNotEmpty() }?.let { parts += "sel=$it" }
        text(element, "role", 48).takeIf { it.isNotEmpty() }?.let { parts += "role=$it" }
        text(element, "aria_label", MAX_FIELD_CHARS).takeIf { it.isNotEmpty() }?.let { parts += "aria=\"$it\"" }
        text(element, "placeholder", MAX_FIELD_CHARS).takeIf { it.isNotEmpty() }?.let { parts += "ph=\"$it\"" }
        text(element, "type", 32).takeIf { it.isNotEmpty() }?.let { parts += "type=$it" }
        box(element)?.let { parts += "box=$it" }
        return if (parts.isEmpty()) null else "      " + parts.joinToString(" ")
    }

    private fun box(element: JSONObject): String? {
        val bounds = element.optJSONObject("bounds") ?: element.optJSONObject("rect") ?: return null
        val x = bounds.optInt("x")
        val y = bounds.optInt("y")
        val width = bounds.optInt("width")
        val height = bounds.optInt("height")
        return "$x,$y ${width}x$height"
    }

    /**
     * A field of the row, whitespace-collapsed and stripped of the quotes that
     * would break the row out of its own quoting. JSON null (the page side sends
     * null for absent role / href / placeholder) reads as absent, not as "null".
     */
    private fun text(element: JSONObject, key: String, limit: Int): String {
        if (!element.has(key) || element.isNull(key)) return ""
        return element.optString(key, "")
            .replace(WHITESPACE, " ")
            .replace('"', '\'')
            .trim()
            .take(limit)
    }

    private val WHITESPACE = Regex("\\s+")
}
