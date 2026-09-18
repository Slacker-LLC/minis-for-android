package com.openminis.app.data.repository

import java.security.MessageDigest

/**
 * Injection budget for persistent memory, ported from Eta's
 * `AgentMemoryContextBuilder` (see THIRD_PARTY_LICENSES.md).
 *
 * GLOBAL.md is user-maintained and can grow without limit; injecting it whole
 * spends context on every single turn. The budget scales with the model's
 * context window and is clamped, and when a file is cut the model still gets a
 * heading index so it knows what exists and can pull the rest with memory_get.
 */
internal object MemoryInjectionBudget {

    const val DEFAULT_CONTEXT_WINDOW = 128_000
    const val CONTEXT_WINDOW_DIVISOR = 16
    const val MIN_CORE_CHARS = 4_000
    const val MAX_CORE_CHARS = 32_000
    const val MAX_HEADING_INDEX_CHARS = 4_000

    private val HEADING = Regex("^#{1,2}\\s+.+$")

    fun coreBudgetChars(contextWindow: Int?): Int {
        val resolved = contextWindow?.takeIf { it > 0 } ?: DEFAULT_CONTEXT_WINDOW
        return (resolved / CONTEXT_WINDOW_DIVISOR).coerceIn(MIN_CORE_CHARS, MAX_CORE_CHARS)
    }

    /** Content revision in the sense of Eta's AgentMemorySnapshot.revision: a
     *  SHA-256 over the exact injected text. The injected fragment carries it so
     *  the model can tell "same memory, same bytes" from "the file changed",
     *  and so an unchanged file keeps the prompt cache prefix byte-stable. */
    fun revision(content: String): String = MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** Markdown headings of [content], capped, so a trimmed file still shows
     *  its shape. Empty when the file has no headings. */
    fun headingIndex(content: String): String = content.lineSequence()
        .filter { HEADING.matches(it.trimEnd()) }
        .joinToString("\n")
        .take(MAX_HEADING_INDEX_CHARS)

    /** Returns [content] unchanged when it fits, otherwise a truncated body plus
     *  the heading index of what was left out. */
    fun bound(content: String, contextWindow: Int?): String {
        val budget = coreBudgetChars(contextWindow)
        if (content.length <= budget) return content
        val index = headingIndex(content)
        val trimmed = buildString {
            append(content.take(budget))
            append("\n\n... (truncated at ").append(budget).append(" characters")
            if (index.isNotBlank()) {
                append("; headings in the full file:\n").append(index)
            } else {
                append(")")
            }
            append("\nUse memory_get to read the rest.")
        }
        return trimmed
    }
}
