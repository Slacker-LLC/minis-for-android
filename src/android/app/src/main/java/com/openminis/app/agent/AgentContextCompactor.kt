package com.openminis.app.agent

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * Port of Eta "agent/model/AgentContextCompactor.kt" (Mangi-11/Eta @ c15de97):
 * the compaction boundary rules and the two summary gates. Attribution and
 * licence of the original are recorded centrally in PROVENANCE.md /
 * THIRD_PARTY_LICENSES.md; no per-file licence header is added on purpose.
 *
 * Ported as-is (same rules, same failure codes):
 *  - [canSplit] / [completeGroups] — a cut may only land between complete tool
 *    exchanges, never directly after a user turn and never directly before a
 *    tool result;
 *  - [planRange] — upstream's initialEnd (keep
 *    [AgentContextBudget.RECENT_MESSAGES] messages), its RECENT_RATIO walk over
 *    the uncompacted tail, and the newest-user-turn protection;
 *  - [chunkForSummary] — pack complete batches into summary calls and refuse a
 *    single batch that does not fit ([FAILURE_ITEM_TOO_LARGE]);
 *  - [summaryCharCap] / [validateSummary] — upstream's min(12_000, 0.6 x
 *    window) cap plus its [FAILURE_SUMMARY_INVALID] conditions (not a normal
 *    finish, blank, literal "null", over-long, carrying tool calls);
 *  - [hasReduction] — upstream's [FAILURE_NO_REDUCTION] gate.
 *
 * Conflict points (Eta types Minis does not have, so only the logic landed):
 *  - Upstream compact() is an I/O-bound class owning an AgentProviderClient, an
 *    AgentRunController and an AgentModelRetry, and it mutates a JSONArray of
 *    provider-shaped messages. Minis keeps the provider call, its retry policy
 *    and the recursive split in ChatViewModel + CompactBudget; this file is
 *    pure decision logic over [LLMMessage] instead.
 *  - Upstream carries a summarized range's newest user message out of the
 *    summary and re-emits it verbatim (its protectedUser). Minis' v2 marker can
 *    only express "everything after the marker is kept", so the range is
 *    clamped to end before the newest user turn instead: the message stays
 *    verbatim (a superset of the upstream guarantee) at the cost of not
 *    summarizing completed batches after it.
 *  - Upstream compares a normalized AssistantStopReason.END_TURN; Minis sees
 *    raw provider strings ("stop" / "end_turn" / ...) and providers may report
 *    no reason at all, so [isNormalFinish] accepts a missing reason and rejects
 *    only reasons that name a truncated or tool-call finish.
 */
internal object AgentContextCompactor {

    // Upstream failure codes, kept verbatim so logs and tests stay greppable.
    const val FAILURE_NOT_COMPACTABLE: String = "CONTEXT_NOT_COMPACTABLE"
    const val FAILURE_ITEM_TOO_LARGE: String = "CONTEXT_ITEM_TOO_LARGE"
    const val FAILURE_SUMMARY_INVALID: String = "CONTEXT_SUMMARY_INVALID"
    const val FAILURE_NO_REDUCTION: String = "CONTEXT_NO_REDUCTION"

    /** Upstream maxInput: share of the window one summary call may carry. */
    const val SUMMARY_INPUT_RATIO: Double = 0.60

    /** Upstream fallback when the model window is unknown. */
    const val SUMMARY_INPUT_FALLBACK_TOKENS: Int = 32_000

    /** Upstream summary character cap and its floor. */
    const val SUMMARY_CHAR_CAP_HARD: Int = 12_000
    const val SUMMARY_CHAR_CAP_MIN: Int = 256

    /** Fixed framing charge for the summary system prompt + request wrapper. */
    const val SUMMARY_FRAMING_TOKENS: Int = 256

    /** Serialized tool-call syntax — upstream's parseToolCalls(...).isNotEmpty(). */
    private val TOOL_CALL_MARKERS = listOf(
        "<tool_call",
        "</tool_call",
        "<tool_use",
        "</tool_use",
        "<function_call",
        "<invoke",
        "\"tool_calls\"",
    )

    /**
     * Provider stop reasons that mean the answer finished on its own. Minis
     * receives raw provider strings, so the union of the OpenAI-compatible and
     * Anthropic spellings is accepted.
     */
    private val NORMAL_STOP_REASONS = setOf(
        "stop",
        "end_turn",
        "stop_sequence",
        "completed",
        "complete",
        "finished",
    )

    /** Result of choosing the range one compaction pass may summarize. */
    sealed class Plan {
        /** The half-open range that may be summarized; nothing after it may. */
        data class Compactable(val startIndex: Int, val endExclusive: Int) : Plan() {
            val messageCount: Int get() = endExclusive - startIndex
        }

        data class Rejected(val code: String, val message: String) : Plan()
    }

    /** Result of packing a range into summary-sized chunks. */
    sealed class ChunkPlan {
        data class Chunks(val chunks: List<List<LLMMessage>>) : ChunkPlan()
        data class Rejected(val code: String, val message: String) : ChunkPlan()
    }

    /** Result of validating one generated summary. */
    sealed class SummaryCheck {
        object Valid : SummaryCheck()
        data class Rejected(val code: String, val message: String) : SummaryCheck()
    }

    // ── batch boundaries ─────────────────────────────────────────────────

    /** Upstream role == "user": a real user turn, not a tool-result row. */
    fun isUserTurn(message: LLMMessage): Boolean =
        message.role == LLMMessage.Role.USER &&
            message.contentParts.none { it is AgentContentPart.ToolResult }

    /** Upstream role == "tool": Minis carries tool results as USER messages. */
    fun isToolMessage(message: LLMMessage): Boolean =
        message.contentParts.any { it is AgentContentPart.ToolResult }

    /**
     * Upstream canSplit: true when the prefix ending at [end] may stand alone.
     * A cut is refused directly after a user turn, directly before a tool
     * result, and while any tool call in the prefix is still unanswered.
     */
    fun canSplit(history: List<LLMMessage>, end: Int): Boolean {
        if (end <= 0 || end > history.size) return false
        val last = history[end - 1]
        if (isUserTurn(last)) return false
        if (history.getOrNull(end)?.let { isToolMessage(it) } == true) return false
        val open = linkedSetOf<String>()
        for (message in history.subList(0, end)) {
            for (part in message.contentParts) {
                when (part) {
                    is AgentContentPart.ToolUse -> open += part.id
                    is AgentContentPart.ToolResult -> open -= part.id
                    is AgentContentPart.Text, is AgentContentPart.ImageData -> Unit
                }
            }
        }
        return open.isEmpty()
    }

    /**
     * Upstream completeGroups: the messages split at every safe boundary, so
     * every group but the last is a complete tool exchange. The final partial
     * group is kept — upstream never drops it, it refuses instead.
     */
    fun completeGroups(messages: List<LLMMessage>): List<List<LLMMessage>> {
        val groups = mutableListOf<List<LLMMessage>>()
        var start = 0
        for (end in 1..messages.size) {
            if (canSplit(messages, end)) {
                groups += messages.subList(start, end).toList()
                start = end
            }
        }
        if (start < messages.size) groups += messages.subList(start, messages.size).toList()
        return groups
    }

    /**
     * Upstream's overflow recovery splits the group list in half. Returns the
     * message index of that midpoint, or null when the range is a single
     * complete batch and cannot be split without cutting it.
     */
    fun halfSplitBoundary(messages: List<LLMMessage>): Int? {
        val groups = completeGroups(messages)
        if (groups.size < 2) return null
        return groups.take(groups.size / 2).sumOf { it.size }
    }

    // ── range selection ──────────────────────────────────────────────────

    /**
     * Upstream range chooser, clamped for Minis' marker model.
     *
     * @param contextWindow the live model window, or null when unknown.
     * @param recentTokenLimit the token budget the uncompacted tail may keep;
     *   callers pass min(window x RECENT_RATIO, their own cap).
     */
    fun planRange(
        history: List<LLMMessage>,
        startIndex: Int,
        anchorIndex: Int,
        contextWindow: Int?,
        recentTokenLimit: Int?,
    ): Plan {
        if (history.isEmpty()) {
            return Plan.Rejected(
                FAILURE_NOT_COMPACTABLE,
                "Nothing to compact — the session is empty.",
            )
        }
        val start = startIndex.coerceIn(0, history.lastIndex)
        val anchor = anchorIndex.coerceIn(0, history.lastIndex)
        if (start > anchor) {
            return Plan.Rejected(
                FAILURE_NOT_COMPACTABLE,
                "Nothing to compact — everything up to this point is already covered by a summary.",
            )
        }

        val latestUser = history.indexOfLast { isUserTurn(it) }
        val safeEnds = (1..history.size).filter { canSplit(history, it) }
        val initialEnd = safeEnds
            .lastOrNull { it <= history.size - AgentContextBudget.RECENT_MESSAGES }
            ?: safeEnds.firstOrNull { it < history.size }
            ?: return Plan.Rejected(
                FAILURE_NOT_COMPACTABLE,
                "Nothing safe to compact — no complete tool batch ends before the protected recent " +
                    "messages.",
            )

        var end = initialEnd
        if (recentTokenLimit != null) {
            while (AgentContextBudget.rawEstimate(history.drop(end)) > recentTokenLimit) {
                end = safeEnds.firstOrNull { it > end && it < history.size } ?: break
            }
        }

        // Minis adaptation of upstream's protectedUser carry-over: the newest
        // user turn is never summarized, so the range ends before it.
        if (latestUser >= 0 && latestUser < end) {
            end = safeEnds.lastOrNull { it <= latestUser } ?: 0
        }
        if (end <= start) {
            return Plan.Rejected(
                FAILURE_NOT_COMPACTABLE,
                "Nothing safe to compact yet — the newest user turn is protected and nothing before " +
                    "it forms a complete batch worth summarizing.",
            )
        }
        return Plan.Compactable(start, end)
    }

    // ── summary budget ───────────────────────────────────────────────────

    /** Upstream maxInput: 60% of the window, or 32k when it is unknown. */
    fun summaryMaxInputTokens(contextWindow: Int?): Int =
        contextWindow?.takeIf { it > 0 }
            ?.let { (it * SUMMARY_INPUT_RATIO).toInt() }
            ?: SUMMARY_INPUT_FALLBACK_TOKENS

    /** Upstream summaryChars: min(12_000, maxInput), floored at 256. */
    fun summaryCharCap(contextWindow: Int?): Int =
        minOf(SUMMARY_CHAR_CAP_HARD, summaryMaxInputTokens(contextWindow))
            .coerceAtLeast(SUMMARY_CHAR_CAP_MIN)

    /**
     * Upstream's summary loop: walk the complete groups, flush a chunk whenever
     * adding the next group would overflow the summary input budget, and refuse
     * a single group that does not fit on its own.
     */
    fun chunkForSummary(
        messages: List<LLMMessage>,
        maxInputTokens: Int,
        previousSummary: String = "",
    ): ChunkPlan {
        val chunks = mutableListOf<List<LLMMessage>>()
        var chunk = mutableListOf<LLMMessage>()
        for (group in completeGroups(messages)) {
            val candidate = chunk + group
            if (estimateSummaryInput(candidate, previousSummary) > maxInputTokens && chunk.isNotEmpty()) {
                chunks += chunk.toList()
                chunk = mutableListOf()
            }
            if (estimateSummaryInput(group, previousSummary) > maxInputTokens) {
                return ChunkPlan.Rejected(
                    FAILURE_ITEM_TOO_LARGE,
                    "A single complete message or tool batch is larger than the summarizer's capacity " +
                        "— shorten the input or switch to a model with a larger context window.",
                )
            }
            chunk.addAll(group)
        }
        if (chunk.isNotEmpty()) chunks += chunk.toList()
        return ChunkPlan.Chunks(chunks)
    }

    private fun estimateSummaryInput(chunk: List<LLMMessage>, previousSummary: String): Int =
        AgentContextBudget.rawEstimate(chunk) +
            AgentContextBudget.textTokens(previousSummary) +
            SUMMARY_FRAMING_TOKENS

    // ── summary validation ───────────────────────────────────────────────

    /**
     * Upstream's summary gate: a summary is only usable when the model finished
     * normally, returned text that is not blank or the literal "null", stayed
     * inside the character cap and carries no tool calls.
     */
    fun validateSummary(
        summary: String,
        stopReason: String?,
        maxChars: Int,
        hasToolCalls: Boolean = false,
    ): SummaryCheck {
        val trimmed = summary.trim()
        if (!isNormalFinish(stopReason) ||
            trimmed.isEmpty() ||
            trimmed == "null" ||
            trimmed.length > maxChars ||
            hasToolCalls
        ) {
            return SummaryCheck.Rejected(
                FAILURE_SUMMARY_INVALID,
                "Compaction did not return a complete, bounded summary — the original context is kept.",
            )
        }
        return SummaryCheck.Valid
    }

    /** Upstream parseToolCalls(...).isNotEmpty(), adapted to text responses. */
    fun containsToolCallSyntax(summary: String): Boolean =
        TOOL_CALL_MARKERS.any { summary.contains(it, ignoreCase = true) }

    /**
     * Upstream's AssistantStopReason.END_TURN test over Minis' raw provider
     * strings. A missing reason is accepted (Minis providers may finish a stream
     * without one); explicit truncation/tool reasons are not.
     */
    fun isNormalFinish(stopReason: String?): Boolean {
        val normalized = stopReason?.trim()?.lowercase()
        if (normalized.isNullOrEmpty()) return true
        return normalized in NORMAL_STOP_REASONS
    }

    // ── reduction gate ───────────────────────────────────────────────────

    /**
     * Upstream's final gate: the compacted form must be strictly smaller than
     * what it replaces, priced by the same estimator.
     */
    fun hasReduction(beforeTokens: Int, afterTokens: Int): Boolean = afterTokens < beforeTokens

    /** Message for the CONTEXT_NO_REDUCTION rejection, quoting both sides. */
    fun noReductionMessage(beforeTokens: Int, afterTokens: Int): String =
        "Compaction skipped (" + FAILURE_NO_REDUCTION + "): the summary would be ~" + afterTokens +
            " tokens against ~" + beforeTokens + " tokens it replaces, so it would not shrink the " +
            "context. The original context is kept."
}
