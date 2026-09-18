package com.openminis.app.browser

import org.json.JSONObject

/**
 * Ported from Eta `agent/browser/BrowserPayloadLimiter.kt` (Mangi-11/Eta @ c15de97).
 *
 * The final gate for the model-facing browser payload: a [MAX_BYTES] UTF-8 budget
 * enforced by a deterministic ladder, so an oversized page can never reach the
 * model whole. Trailing `elements` rows are dropped first (the surviving count is
 * recorded), then the long `text` field is cut at a surrogate-safe prefix and
 * re-labelled with the offset the caller resumes from, and only if that is still
 * too big does the envelope collapse to its identifying fields.
 *
 * [boundText] is this repository's addition, not upstream's: Eta's browser results
 * are always JSON envelopes, while a raw `execute_js` return and the backbone dump
 * arrive here as plain text, so the same budget is applied to a String. The
 * paging keys (`offset`, `next_offset`, `returned_chars`, `text_length`) joined the
 * minimal-envelope list for the same reason: our truncation is resumable, and
 * dropping the resume offset would make it a dead end.
 */
internal object BrowserPayloadLimiter {
    const val MAX_BYTES = 12 * 1024

    /** Characters of a string field kept by the minimal-envelope fallback (upstream's 240). */
    private const val MINIMAL_STRING_CHARS = 240

    /**
     * Upstream's own key list, plus this repository's paging fields.
     */
    private val MINIMAL_KEYS = listOf(
        "ok", "tool", "action", "status", "code", "message", "content_source",
        "network_policy", "url", "display_url", "host", "title", "http_status",
        "risk_challenge",
        "offset", "next_offset", "returned_chars", "text_length",
    )

    /**
     * Upstream's contract: the serialized envelope is at most [maxBytes] UTF-8 bytes.
     * Mutates [envelope] in place (as upstream does) and returns the same instance,
     * so the caller's following formatting step sees the truncation too.
     */
    fun bound(envelope: JSONObject, maxBytes: Int = MAX_BYTES): JSONObject {
        require(maxBytes >= 512) { "maxBytes must be at least 512" }

        fun encodedSize(): Int = envelope.toString().toByteArray(Charsets.UTF_8).size

        envelope.optJSONArray("elements")?.let { elements ->
            if (encodedSize() > maxBytes) {
                val originalCount = elements.length()
                envelope.put("elements_truncated", true)
                envelope.put("truncated", true)
                while (elements.length() > 0 && encodedSize() > maxBytes) {
                    elements.remove(elements.length() - 1)
                    envelope.put("element_count", elements.length())
                }
                if (elements.length() == originalCount) {
                    envelope.remove("elements_truncated")
                }
            }
        }

        if (encodedSize() > maxBytes && envelope.has("text")) {
            val original = envelope.optString("text")
            val offset = envelope.optInt("offset", 0).coerceAtLeast(0)
            envelope.put("payload_truncated", true)
            envelope.put("truncated", true)
            envelope.put("text", "")
            envelope.put("returned_chars", 0)
            envelope.put("next_offset", offset)

            var bestEnd = 0
            var low = 0
            var high = original.length
            while (low <= high) {
                val midpoint = (low + high) ushr 1
                val safeEnd = safePrefixEnd(original, midpoint)
                envelope.put("text", original.substring(0, safeEnd))
                envelope.put("returned_chars", safeEnd)
                envelope.put("next_offset", offset + safeEnd)
                if (encodedSize() <= maxBytes) {
                    bestEnd = safeEnd
                    low = midpoint + 1
                } else {
                    high = midpoint - 1
                }
            }
            envelope.put("text", original.substring(0, bestEnd))
            envelope.put("returned_chars", bestEnd)
            envelope.put("next_offset", offset + bestEnd)
        }

        if (encodedSize() <= maxBytes) return envelope

        val minimal = JSONObject()
        MINIMAL_KEYS.forEach { key ->
            if (!envelope.has(key)) return@forEach
            val value = envelope.opt(key)
            minimal.put(key, if (value is String) value.take(MINIMAL_STRING_CHARS) else value)
        }
        minimal.put("payload_truncated", true)
        envelope.keys().asSequence().toList().forEach { envelope.remove(it) }
        minimal.keys().forEach { envelope.put(it, minimal.opt(it)) }
        return envelope
    }

    /**
     * Upstream's serializer contract: the returned JSON string stays within
     * [maxBytes] UTF-8 bytes.
     */
    fun serialize(envelope: JSONObject, maxBytes: Int = MAX_BYTES): String =
        bound(envelope, maxBytes).toString()

    /**
     * Bounds a plain-text payload that never became a JSON envelope. The cut is
     * surrogate-safe and the marker states how much of the original survived, so
     * the model can tell a short answer from a clipped one.
     */
    fun boundText(text: String, maxBytes: Int = MAX_BYTES): String {
        require(maxBytes >= 512) { "maxBytes must be at least 512" }
        if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return text

        fun marker(kept: Int): String =
            "\n[payload truncated: $kept of ${text.length} chars kept to fit $maxBytes bytes]"

        var bestEnd = 0
        var low = 0
        var high = text.length
        while (low <= high) {
            val midpoint = (low + high) ushr 1
            val safeEnd = safePrefixEnd(text, midpoint)
            val kept = text.substring(0, safeEnd) + marker(safeEnd)
            if (kept.toByteArray(Charsets.UTF_8).size <= maxBytes) {
                bestEnd = safeEnd
                low = midpoint + 1
            } else {
                high = midpoint - 1
            }
        }
        return text.substring(0, bestEnd) + marker(bestEnd)
    }

    /**
     * The whole-result form of [boundText], applied at the browser tab pool's
     * single exit so every action — including the ones that assemble text
     * without a JSON envelope — leaves under the same budget.
     */
    fun boundResult(result: BrowserActionResult, maxBytes: Int = MAX_BYTES): BrowserActionResult {
        val bounded = boundText(result.text, maxBytes)
        return if (bounded == result.text) result else result.copy(text = bounded)
    }

    /**
     * Upstream's cut rule: never leave a lone high surrogate behind, which would
     * serialize to broken UTF-8 in the request body.
     */
    private fun safePrefixEnd(value: String, requestedEnd: Int): Int {
        var end = requestedEnd.coerceIn(0, value.length)
        if (
            end in 1 until value.length &&
            Character.isHighSurrogate(value[end - 1]) &&
            Character.isLowSurrogate(value[end])
        ) {
            end--
        }
        return end
    }
}
