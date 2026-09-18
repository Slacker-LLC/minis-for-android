package com.openminis.app.agent

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * [T-tool-batch-repair-android] Keeps the conversation well-formed for the provider, and
 * honest to the model about what it does not know.
 *
 * Ported from Eta `agent/model/AgentToolBatchRecovery.kt` (Mangi-11/Eta @ c15de97), which
 * closes an interrupted batch with an explicit unknown-state result. The part worth
 * carrying over is the wording, not the mechanism: a call whose result never arrived may
 * already have taken effect, so the model has to be told the state is unknown and told not
 * to replay it. A placeholder that reads like an ordinary error invites exactly the retry
 * that would send the SMS or run the command twice — this repository's message used to say
 * only "interrupted by an unexpected error".
 *
 * Providers require the pairing itself: an assistant message with tool calls that the next
 * message does not answer is rejected (OpenAI) or malformed (Anthropic). This pass also
 * drops results whose call is gone, and drops a message that carried nothing else.
 */
object ToolBatchRepair {

    /** Token that identifies a synthesized result, in the text and in logs. */
    const val INTERRUPTED_MARKER = "TOOL_INTERRUPTED"

    /** What the model is told about a call that never reported a result. */
    val INTERRUPTED_RESULT_TEXT: String =
        "Tool execution was interrupted before its result was recorded " +
            "($INTERRUPTED_MARKER): the call may or may not have taken effect. " +
            "Check the current state before relying on it, and do not replay the call automatically."

    data class Result(
        val messages: List<LLMMessage>,
        val injected: Int,
        val droppedResults: Int,
        val droppedMessages: Int,
    ) {
        val changed: Boolean get() = injected > 0 || droppedResults > 0 || droppedMessages > 0
    }

    fun repair(messages: List<LLMMessage>): Result {
        val repaired = messages.toMutableList()
        var injected = 0

        var index = 0
        while (index < repaired.size) {
            val message = repaired[index]
            if (message.role != LLMMessage.Role.ASSISTANT) {
                index++
                continue
            }
            val toolUses = message.contentParts.filterIsInstance<AgentContentPart.ToolUse>()
            if (toolUses.isEmpty()) {
                index++
                continue
            }
            val answered = repaired.getOrNull(index + 1)?.contentParts
                ?.filterIsInstance<AgentContentPart.ToolResult>()
                ?.map { it.id }?.toSet() ?: emptySet()
            val missing = toolUses.filter { it.id !in answered }
            if (missing.isEmpty()) {
                index++
                continue
            }
            val placeholders = missing.map { use ->
                AgentContentPart.ToolResult(
                    id = use.id,
                    name = use.name,
                    content = INTERRUPTED_RESULT_TEXT,
                    isError = true,
                )
            }
            injected += placeholders.size

            val next = repaired.getOrNull(index + 1)
            if (next != null && next.role == LLMMessage.Role.USER &&
                next.contentParts.any { it is AgentContentPart.ToolResult }
            ) {
                repaired[index + 1] = next.copy(contentParts = next.contentParts + placeholders)
            } else {
                repaired.add(
                    index + 1,
                    LLMMessage(
                        role = LLMMessage.Role.USER,
                        content = "",
                        contentParts = placeholders,
                    ),
                )
            }
            index++
        }

        // A result whose call is gone has no meaning to the provider and is rejected by
        // some of them; drop it, and drop the message if that was all it carried.
        val knownCalls = repaired.flatMap { it.contentParts }
            .filterIsInstance<AgentContentPart.ToolUse>()
            .map { it.id }
            .toSet()
        var droppedResults = 0
        var droppedMessages = 0
        val iterator = repaired.listIterator()
        while (iterator.hasNext()) {
            val message = iterator.next()
            if (message.role != LLMMessage.Role.USER) continue
            val kept = message.contentParts.filter { part ->
                val drop = part is AgentContentPart.ToolResult && part.id !in knownCalls
                if (drop) droppedResults++
                !drop
            }
            if (kept.size == message.contentParts.size) continue
            if (kept.isEmpty() && message.content.isBlank()) {
                iterator.remove()
                droppedMessages++
            } else {
                iterator.set(message.copy(contentParts = kept))
            }
        }

        return Result(repaired.toList(), injected, droppedResults, droppedMessages)
    }
}
