package com.openminis.app.data

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import java.util.ArrayDeque
import kotlin.math.abs

/**
 * Finds message boundaries at which a compacted transcript can be divided
 * without cutting through an active protocol or document structure.
 *
 * A split is only considered between complete [LLMMessage] entries. The
 * predicate then verifies three independent pieces of state for the prefix:
 * tool calls have no unmatched result, Markdown fences are closed, and the
 * JSON-like bracket stack is empty. User-turn boundaries are preferred because
 * they preserve conversational meaning, but a structurally safe message
 * boundary remains a valid fallback when a transcript has no plain user turn.
 */
internal object CompactSplitPredicate {

    /**
     * Return the safest boundary near [preferredIndex], or null when no
     * structurally safe boundary exists.
     *
     * [preferredIndex] is a boundary index, not a message index: the first
     * half is `messages.subList(0, result)` and the second starts at `result`.
     */
    fun findSafeSplit(messages: List<LLMMessage>, preferredIndex: Int): Int? {
        if (messages.size < 2) return null

        val safe = (1 until messages.size).filter { isSafeBoundary(messages, it) }
        if (safe.isEmpty()) return null

        val semantic = safe.filter { isPlainUserTurn(messages[it]) }
        val candidates = if (semantic.isNotEmpty()) semantic else safe
        val preferred = preferredIndex.coerceIn(1, messages.lastIndex)

        // Stable tie-break toward the older side keeps repeated compaction
        // deterministic when two user turns are equally close to the middle.
        return candidates.minWithOrNull(compareBy<Int>({ abs(it - preferred) }, { it }))
    }

    /** True when [boundary] is a valid split point in [messages]. */
    fun isSafeBoundary(messages: List<LLMMessage>, boundary: Int): Boolean {
        if (boundary !in 1 until messages.size) return false

        val pendingToolUses = mutableSetOf<String>()
        val structure = StructureState()
        for (message in messages.subList(0, boundary)) {
            for (part in message.contentParts) {
                when (part) {
                    is AgentContentPart.ToolUse -> pendingToolUses.add(part.id)
                    is AgentContentPart.ToolResult -> pendingToolUses.remove(part.id)
                    is AgentContentPart.Text,
                    is AgentContentPart.ImageData,
                    -> Unit
                }
            }
            scanMessageText(message, structure)
        }

        return pendingToolUses.isEmpty() && structure.isClosed
    }

    /** Alias useful to callers that describe the operation as a boundary check. */
    fun isBoundarySafe(messages: List<LLMMessage>, boundary: Int): Boolean =
        isSafeBoundary(messages, boundary)

    private fun isPlainUserTurn(message: LLMMessage): Boolean {
        if (message.role != LLMMessage.Role.USER) return false
        if (message.contentParts.any { it is AgentContentPart.ToolResult }) return false
        return message.content.isNotBlank() || message.contentParts.any {
            it is AgentContentPart.Text && it.text.isNotBlank()
        }
    }

    private fun scanMessageText(message: LLMMessage, state: StructureState) {
        scanText(message.content, state)
        for (part in message.contentParts) {
            when (part) {
                is AgentContentPart.Text -> scanText(part.text, state)
                is AgentContentPart.ToolUse -> scanText(part.input.toString(), state)
                is AgentContentPart.ToolResult -> scanText(part.content, state)
                is AgentContentPart.ImageData -> Unit
            }
        }
    }

    private fun scanText(text: String, state: StructureState) {
        var index = 0
        while (index < text.length) {
            val current = text[index]

            // Quotes only have JSON meaning while a JSON-like container is
            // open. This avoids treating an apostrophe in ordinary prose as a
            // string that leaks into a later message.
            if (state.inJsonString) {
                when {
                    state.jsonEscape -> state.jsonEscape = false
                    current == '\\' -> state.jsonEscape = true
                    current == '"' -> state.inJsonString = false
                }
                index++
                continue
            }

            if (current == '\\' && index + 1 < text.length && text[index + 1] == '`') {
                index += 2
                continue
            }

            if (current == '`') {
                var end = index
                while (end < text.length && text[end] == '`') end++
                if (end - index >= 3) state.inCodeFence = !state.inCodeFence
                index = end
                continue
            }

            if (current == '"' && state.jsonBrackets.isNotEmpty()) {
                state.inJsonString = true
                state.jsonEscape = false
                index++
                continue
            }

            when (current) {
                '{', '[' -> state.jsonBrackets.addLast(current)
                '}', ']' -> {
                    val expected = if (current == '}') '{' else '['
                    if (state.jsonBrackets.peekLast() == expected) {
                        state.jsonBrackets.removeLast()
                    } else {
                        // A mismatched close cannot be made safe by choosing a
                        // boundary later in the same prefix.
                        state.invalidJson = true
                    }
                }
            }
            index++
        }
    }

    private class StructureState {
        var inCodeFence: Boolean = false
        var inJsonString: Boolean = false
        var jsonEscape: Boolean = false
        var invalidJson: Boolean = false
        val jsonBrackets: ArrayDeque<Char> = ArrayDeque()

        val isClosed: Boolean
            get() = !inCodeFence && !inJsonString && !invalidJson && jsonBrackets.isEmpty()
    }
}
