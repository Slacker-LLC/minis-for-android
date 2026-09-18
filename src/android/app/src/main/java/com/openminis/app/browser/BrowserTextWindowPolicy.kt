package com.openminis.app.browser

/**
 * Ported from Eta's `readPage` argument handling and the paging contract of
 * `agent/browser/BrowserDomScripts.kt` (`text` / `readable`, Mangi-11/Eta @ c15de97):
 * `offset` clamps into 0..[MAX_OFFSET], `max_chars` defaults to [DEFAULT_CHARS] and
 * clamps into [MIN_CHARS]..[MAX_CHARS], and a window reports how much it returned
 * plus the `next_offset` the caller resumes from (absent at the end of the
 * document).
 *
 * The page-side script is generated from the literals this object produces, so
 * the model-facing header and the slice it describes cannot disagree.
 */
internal object BrowserTextWindowPolicy {
    /** Default window when the caller does not ask for one (upstream's `DEFAULT_TEXT_CHARS`). */
    const val DEFAULT_CHARS = 8_000

    /** Smallest window a caller may ask for (upstream's floor). */
    const val MIN_CHARS = 256

    /** Largest window a caller may ask for (upstream's `MAX_TEXT_CHARS`). */
    const val MAX_CHARS = 12_000

    /** Largest starting offset a caller may ask for (upstream's clamp). */
    const val MAX_OFFSET = 200_000

    /** Largest document the page-side collector keeps before it calls itself truncated. */
    const val MAX_DOCUMENT_CHARS = 200_000

    data class Window(
        val start: Int,
        val end: Int,
        val returnedChars: Int,
        /** Where the next window starts, or null when this window reached the end. */
        val nextOffset: Int?,
        val truncated: Boolean,
    )

    fun offset(requested: Int?): Int = (requested ?: 0).coerceIn(0, MAX_OFFSET)

    fun maxChars(requested: Int?): Int =
        (requested ?: DEFAULT_CHARS).coerceIn(MIN_CHARS, MAX_CHARS)

    /** The same arithmetic the page-side script runs against the collected text. */
    fun window(offset: Int, maxChars: Int, totalChars: Int): Window {
        val total = totalChars.coerceAtLeast(0)
        val start = offset.coerceIn(0, total)
        val end = (start + maxChars).coerceAtMost(total)
        return Window(
            start = start,
            end = end,
            returnedChars = end - start,
            nextOffset = if (end < total) end else null,
            truncated = end < total,
        )
    }

    /**
     * The header printed above the window. `next_offset` is spelled out because
     * that is the literal argument the model passes back to `browser_use`; when the
     * page-side collector had to stop early, the header says so instead of
     * letting the model read a clipped page as a short one.
     */
    fun describe(window: Window, totalChars: Int, sourceTruncated: Boolean): String = buildString {
        append("Text (chars ").append(window.start).append('-').append(window.end)
        append(" of ").append(totalChars.coerceAtLeast(0))
        append(
            if (window.nextOffset != null) "; next_offset=" + window.nextOffset
            else "; end of document"
        )
        if (sourceTruncated) {
            append("; page text capped at ").append(MAX_DOCUMENT_CHARS).append(" chars")
        }
        append(')')
    }
}
