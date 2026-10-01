package com.openminis.app.ui.chat

import com.openminis.app.data.model.LLMStreamChunk

/**
 * Where the time of ONE model request went, measured at the single point every provider streams
 * through, so no provider has to be instrumented separately.
 *
 *  - firstEventMs: first chunk of any kind (usually `Started`, which some providers emit
 *    before the first byte arrives, so it is a lower bound on the wait).
 *  - ttftMs: first text / thinking / tool-use chunk; this is the number to call TTFT.
 *  - totalMs: until the stream finished or failed.
 *
 * Pure: the clock is injected, nothing here touches Android.
 */
class ModelTurnTelemetry(
    private val turnIndex: Int,
    private val clock: () -> Long,
) {
    private val startedAt = clock()
    private var firstEventAt = -1L
    private var firstContentAt = -1L
    private var endedAt = -1L
    private var inputTokens = -1
    private var outputTokens = -1
    private var cacheReadTokens = -1
    private var toolCalls = 0
    private var stopReason: String? = null

    fun onChunk(chunk: LLMStreamChunk) {
        val now = clock()
        if (firstEventAt < 0) firstEventAt = now
        when (chunk) {
            is LLMStreamChunk.Text, is LLMStreamChunk.ThinkingDelta, is LLMStreamChunk.ToolUseStart ->
                if (firstContentAt < 0) firstContentAt = now
            is LLMStreamChunk.ToolCallComplete -> {
                if (firstContentAt < 0) firstContentAt = now
                toolCalls++
            }
            is LLMStreamChunk.Usage -> {
                inputTokens = chunk.usage.inputTokens
                outputTokens = chunk.usage.outputTokens
                chunk.usage.cacheReadInputTokens?.let { cacheReadTokens = it }
            }
            is LLMStreamChunk.Finished -> stopReason = chunk.stopReason
            else -> Unit
        }
    }

    /** Idempotent; the first call fixes the end time. */
    fun finish() {
        if (endedAt < 0) endedAt = clock()
    }

    fun summary(): String {
        val end = if (endedAt >= 0) endedAt else clock()
        fun since(at: Long) = if (at < 0) "na" else (at - startedAt).toString()
        return "turn=$turnIndex firstEventMs=${since(firstEventAt)} ttftMs=${since(firstContentAt)} " +
            "totalMs=${end - startedAt} " +
            "streamMs=${if (firstContentAt < 0) "na" else (end - firstContentAt).toString()} " +
            "inTok=${tok(inputTokens)} outTok=${tok(outputTokens)} cacheReadTok=${tok(cacheReadTokens)} " +
            "toolCalls=$toolCalls stop=${stopReason ?: "na"}"
    }

    private fun tok(value: Int) = if (value < 0) "na" else value.toString()
}
