package com.openminis.app.tools.history

/**
 * [T-eta-conversation-history] Paging and search rules for re-reading the CURRENT session's
 * persisted transcript — Eta's `conversation_history` tool.
 *
 * Ported from Eta `agent/model/AgentConversationToolCatalog.kt` and the runtime history seam
 * that executes it (Mangi-11/Eta @ c15de97); attribution in THIRD_PARTY_LICENSES.md. The
 * contract kept from Eta: only the current session is readable (the model cannot name
 * another one), the read is bounded by `max_chars` (256-8000), continuation follows
 * `next_message_index` / `next_offset`, and the persisted history is what it is — sensitive
 * tool payloads and transient images were never written to it.
 */
object ConversationHistoryPolicy {

    const val DEFAULT_MAX_CHARS = 4_000
    const val MIN_MAX_CHARS = 256
    const val MAX_MAX_CHARS = 8_000

    /** Longest single entry that may enter a page, so one huge message cannot flood it. */
    const val MAX_ENTRY_CHARS = 2_000

    const val MAX_SEARCH_MATCHES = 20
    const val MAX_SEARCH_SNIPPET_CHARS = 400

    data class Entry(val index: Int, val role: String, val text: String)

    data class Window(
        val text: String,
        val nextMessageIndex: Int?,
        val nextOffset: Int?,
        val truncated: Boolean,
    )

    data class Match(val index: Int, val role: String, val snippet: String)

    fun clampMaxChars(raw: Int?): Int = (raw ?: DEFAULT_MAX_CHARS).coerceIn(MIN_MAX_CHARS, MAX_MAX_CHARS)

    fun boundedEntry(text: String, maxChars: Int = MAX_ENTRY_CHARS): String =
        if (text.length <= maxChars) text else text.take(maxChars)

    /**
     * The next page of the transcript, starting exactly at (startIndex, startOffset).
     * Continuation points are null only when the read reached the end of what was loaded,
     * so a caller that keeps following them cannot loop on the same page.
     */
    fun window(
        entries: List<Entry>,
        startIndex: Int,
        startOffset: Int,
        maxChars: Int,
    ): Window {
        val builder = StringBuilder()
        var next: Pair<Int, Int>? = null
        for (entry in entries.sortedBy { it.index }) {
            if (entry.index < startIndex) continue
            val skip = if (entry.index == startIndex) startOffset.coerceAtLeast(0) else 0
            if (skip >= entry.text.length) continue
            val head = header(entry)
            val separator = if (builder.isEmpty()) 0 else 1
            val available = maxChars - builder.length - separator - head.length
            if (available <= 0) {
                next = entry.index to skip
                break
            }
            val chunk = entry.text.substring(skip, (skip + available).coerceAtMost(entry.text.length))
            if (separator == 1) builder.append('\n')
            builder.append(head).append(chunk)
            val consumedTo = skip + chunk.length
            if (consumedTo < entry.text.length) {
                next = entry.index to consumedTo
                break
            }
        }
        return Window(
            text = builder.toString(),
            nextMessageIndex = next?.first,
            nextOffset = next?.second,
            truncated = next != null,
        )
    }

    /** Case-insensitive substring search over the entries, newest matches last. */
    fun search(entries: List<Entry>, query: String, maxMatches: Int = MAX_SEARCH_MATCHES): List<Match> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        return entries
            .sortedBy { it.index }
            .filter { it.text.contains(needle, ignoreCase = true) }
            .take(maxMatches)
            .map { entry ->
                val at = entry.text.indexOf(needle, ignoreCase = true).coerceAtLeast(0)
                val from = (at - MAX_SEARCH_SNIPPET_CHARS / 4).coerceAtLeast(0)
                val snippet = entry.text.substring(from, (from + MAX_SEARCH_SNIPPET_CHARS).coerceAtMost(entry.text.length))
                Match(index = entry.index, role = entry.role, snippet = snippet)
            }
    }

    private fun header(entry: Entry): String = "#${entry.index} ${entry.role}: "
}
