package com.openminis.app.ui.chat

import android.util.Log
import com.openminis.app.agent.AgentContextBudget
import com.openminis.app.agent.AgentContextCompactor
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.withTimeout

/**
 * The summarise-and-verify half of a compaction, moved out of [ChatViewModel] unchanged: it produces
 * the marker or says why nothing was compacted. Applying the result (summary state, message list,
 * notices) stays with the caller.
 */
internal object CompactionExecution {
    private const val TAG = "ChatViewModel"

    sealed interface Result {
        /** The marker was written; [cutoffId] is the last compacted message. */
        data class Done(val marker: CompactMarkerEntity, val summary: String, val cutoffId: String) : Result

        /** Nothing was compacted; [message] is for the user. */
        data class Stopped(val message: String) : Result
    }

    /**
     * Runs the summary calls for [plan] and decides whether the result may replace the compacted range:
     * the summary gate, the no-reduction gate, and the check that the anchor is really persisted and
     * does not split a tool batch. On success the marker has been written through [insertMarker] (a
     * failed write is logged and ignored, the in-memory marker still takes effect). Provider and
     * timeout failures are thrown for the caller to report. [history] is the snapshot [plan] was made from.
     */
    suspend fun run(
        plan: CompactionPlanner.Outcome.Ready,
        history: List<LLMMessage>,
        summarizer: CompactionSummarizer,
        timeoutMs: Long,
        sessionId: String,
        loadMessageIds: suspend () -> Set<String>,
        insertMarker: suspend (CompactMarkerEntity) -> Unit,
    ): Result {
        val sid = sessionId
        val toCompact = plan.toCompact
        val compactEndIdx = plan.compactEndIdx
        val chunks = plan.chunks
        val existingSummary = plan.existingSummary
        val summaryCharCap = plan.summaryCharCap
        val summaryMaxInputTokens = plan.summaryMaxInputTokens
        // [C3-android-compaction-guards] Eta's summary loop: one
        // summary call per planned chunk, chained through
        // `previousSummary`, and every chunk answer gated before it is
        // carried forward. A chunk that overflows the provider is
        // still halved on a complete-batch boundary inside
        // generateCompactSummaryWithSplitting, bounded by the same
        // MAX_OVERFLOW_ATTEMPTS depth cap as before.
        val draft = withTimeout(timeoutMs) {
            summarizer.summarizeCompactionChunks(
                chunks = chunks,
                previousSummary = existingSummary,
                summaryCharCap = summaryCharCap,
                summaryMaxInputTokens = summaryMaxInputTokens,
            )
        }
        val rawSummary = draft.text.trim()
        val summary = summarizer.appendAuthoritativeFileActivity(
            summary = rawSummary,
            previousSummary = existingSummary,
            messages = toCompact,
        )
        // [C3-android-compaction-guards] Eta's summary gate, re-run on
        // the decorated text that actually reaches the marker: it only
        // becomes a summary when the model finished normally, the
        // result is present, bounded and free of tool calls.
        summarizer.requireValidSummary(summary, draft.stopReason, summaryCharCap)
        // [C3-android-compaction-guards] Eta's CONTEXT_NO_REDUCTION
        // gate, priced on both sides with the ported estimator: what is
        // replaced is the summarized range plus the previous summary it
        // supersedes; the replacement is the new summary message.
        val previousSummaryTokens = existingSummary
            ?.takeIf { it.isNotBlank() }
            ?.let { AgentContextBudget.rawEstimate(listOf(summaryAsMessage(it))) }
            ?: 0
        val beforeTokens = AgentContextBudget.rawEstimate(toCompact) + previousSummaryTokens
        val afterTokens = AgentContextBudget.rawEstimate(listOf(summaryAsMessage(summary)))
        if (!AgentContextCompactor.hasReduction(beforeTokens, afterTokens)) {
            val message = AgentContextCompactor.noReductionMessage(beforeTokens, afterTokens)
            Log.w(
                TAG,
                "[Compact] rejected ${AgentContextCompactor.FAILURE_NO_REDUCTION}: $message",
            )
            return Result.Stopped(message)
        }

                // v2 marker: lastCompactedMessageId IS the anchor — single
        // source of truth. The anchor we resolved above is guaranteed
        // to have a persisted dbMessageId. Legacy fields (firstKept /
        // boundary / sortOrder) stay null/MAX so a downgraded reader
        // sees "everything compacted, nothing kept" as a graceful
        // fallback rather than a stale boundary.
        // Re-resolve anchor: now that we're inside an IO coroutine
        // we can read the messages DB to verify the dbMessageId is
        // actually persisted, not just set on the in-memory
        // LLMMessage. iOS does this belt-and-suspenders check
        // (AIChatViewModel+Compaction.swift:644-657). Walk back from
        // the guard-selected compactEndIdx until we find an entry whose
        // id is both non-empty AND present in rawDbIds.
        val rawDbIds: Set<String> = try {
            loadMessageIds()
        } catch (e: Exception) {
            Log.w(TAG, "[Compact] loadMessages for raw-id verify failed: ${e.message}")
            emptySet()
        }
        val verifiedAnchorIdx = CompactionPlanner.verifiedAnchorIndex(history, compactEndIdx, rawDbIds)
        if (verifiedAnchorIdx < 0) {
            Log.w(TAG, "[Compact] No agentHistory entry has a DB-persisted dbMessageId; aborting")
            return Result.Stopped("Compact failed: could not anchor to a persisted message.")
        }
        // [C3-android-compaction-guards] The DB walk-back must not land
        // inside a tool batch — an anchor that splits a tool_use from
        // its tool_result is exactly the split this guard exists to
        // prevent. (compactEndIdx can never be history.lastIndex: range
        // selection always protects a non-empty tail, so this re-check
        // always looks at a real prefix boundary.)
        if (verifiedAnchorIdx + 1 < history.size &&
            !AgentContextCompactor.canSplit(history, verifiedAnchorIdx + 1)
        ) {
            Log.w(TAG, "[Compact] anchor walk-back landed inside a tool batch; aborting")
            return Result.Stopped("Nothing safe to compact — the anchor would split a tool batch in half.")
        }
        if (verifiedAnchorIdx != compactEndIdx) {
            AppLogger.warning(
                TAG,
                "[Compact] anchor walked back from idx=$compactEndIdx to idx=$verifiedAnchorIdx " +
                    "(closest with id in rawDbIds). Unsynced tail entries will fall on the active side of the divider.",
            )
        }
        val lastCompactedDbId = history[verifiedAnchorIdx].dbMessageId
            ?: run {
                Log.w(TAG, "[Compact] verified anchor at idx=$verifiedAnchorIdx lost dbMessageId; aborting")
                return Result.Stopped("Compact failed: anchor message id unavailable.")
            }
        val marker = CompactMarkerEntity(
            id = java.util.UUID.randomUUID().toString(),
            sessionId = sid,
            summary = summary,
            firstKeptSortOrder = Int.MAX_VALUE,   // legacy field; v2 ignores
            compactedCount = toCompact.size,
            createdAt = System.currentTimeMillis(),
            uiBoundarySortOrder = null,
            boundaryMessageId = null,
            firstKeptMessageId = null,
            lastCompactedMessageId = lastCompactedDbId,
            version = 2,
        )
        runCatching { insertMarker(marker) }
            .onFailure {
                Log.w(TAG, "Failed to persist compact marker: ${it.message}")
            }
        return Result.Done(marker, summary, lastCompactedDbId)
    }
}
