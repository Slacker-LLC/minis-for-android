package com.openminis.app.ui.chat

import com.openminis.app.agent.AgentContextBudget
import com.openminis.app.agent.AgentContextCompactor
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.logging.AppLogger

/**
 * The decisions a compaction makes before and after the summary calls, as functions of the history and
 * the compact marker: which range to summarize, whether the anchor is really persisted, and how the
 * visible message list changes. Moved out of [ChatViewModel] unchanged so they can be tested without one.
 */
internal object CompactionPlanner {
    private const val TAG = "CompactionPlanner"

    /** What [plan] decided: a refusal to show the user, or the range and summary chunks to run. */
    sealed interface Outcome {
        data class Reject(val message: String) : Outcome

        data class Ready(
            val toCompact: List<LLMMessage>,
            val compactEndIdx: Int,
            val chunks: List<List<LLMMessage>>,
            val existingSummary: String?,
            val summaryCharCap: Int,
            val summaryMaxInputTokens: Int,
        ) : Outcome
    }

    /**
     * Pick the range to compact. [anchorIdxOverride] is the entry a caller asked to compact up to
     * (null = the last persisted entry); [contextWindow] is the model's live window, if known.
     */
    fun plan(
        history: List<LLMMessage>,
        anchorIdxOverride: Int?,
        prev: CompactMarkerEntity?,
        existingSummary: String?,
        contextWindow: Int?,
    ): Outcome {
        // ─── v2 unified anchor model ───────────────────────────────────
        //
        // anchor = last active agentHistory entry. The compacted range is
        // `[prev marker anchor + 1, anchor]` (or `[0, anchor]` if no prev),
        // so each compact "extends" the latest summary forward to cover all
        // new turns. effectiveAgentHistory then re-injects the LAST N
        // user-text turns LEADING UP TO the anchor as fresh context, so the
        // model still sees recent verbatim content alongside the summary.
        //
        // Mirrors iOS post-Phase-v2: anchor = last active message, no
        // "auto-keep tail" baked into the compacted range — that's a
        // read-side decoration done by effectiveAgentHistory.
        //
        // anchor must be a persisted entry (have a non-null dbMessageId).
        // The strict iOS check also requires id ∈ rawMessages DB, but DAO
        // is suspend and we'd have to relocate range calculation into the
        // launch below. As a compromise we do the dbMessageId-non-empty
        // pre-check here (catches most stale-id cases at this stage), and
        // do the rawDbIds-membership check inside the launch before the
        // marker is written. Mirrors iOS AIChatViewModel+Compaction.swift:
        // 644-657 "walk back through agentHistory looking for dbMessageId
        // AND allRaw.contains" — split across two phases to honor suspend
        // boundaries.
        val anchorIdx: Int = if (anchorIdxOverride != null) {
            // compactBefore() supplied a specific anchor — walk back from
            // there to the closest entry with a dbMessageId (mirrors the
            // tail-walk-back logic but bounded to [0..override]).
            var i = anchorIdxOverride.coerceIn(0, history.lastIndex)
            while (i >= 0 && history[i].dbMessageId.isNullOrEmpty()) i -= 1
            i
        } else {
            // compactAll() — walk back from the tail to the closest
            // persisted entry. iOS compactAll calls compactBefore with the
            // last active UI message; we go through agentHistory directly
            // since Android's agentHistory and UI list are tighter-coupled.
            var i = history.lastIndex
            while (i >= 0 && history[i].dbMessageId.isNullOrEmpty()) i -= 1
            i
        }
        if (anchorIdx < 0) return Outcome.Reject("Cannot compact: no persisted messages yet.")

        // Slice to compact = (prev marker's anchor + 1) … anchorIdx inclusive.
        // For v2 prev markers, lastCompactedMessageId IS the prev anchor —
        // start at prevIdx + 1. For v1 prev markers, firstKeptMessageId points
        // at "first kept" — start AT prevIdx (it was exclusive on right edge).
        val effectiveStartIdx: Int = if (prev == null) {
            0
        } else {
            val prevAnchorOrFirstKept: String? = if (prev.version >= 2) {
                prev.lastCompactedMessageId?.takeIf { it.isNotEmpty() }
            } else {
                prev.firstKeptMessageId?.takeIf { it.isNotEmpty() }
                    ?: prev.boundaryMessageId?.takeIf { it.isNotEmpty() }
            }
            val prevIdx = prevAnchorOrFirstKept?.let { id ->
                history.indexOfFirst { it.dbMessageId == id }
            } ?: -1
            if (prevIdx < 0) 0   // prev anchor not in current history — restart from top
            else if (prev.version >= 2) prevIdx + 1
            else prevIdx
        }
        if (effectiveStartIdx > anchorIdx) return Outcome.Reject("Already compacted up to this point.")
        // [C3-android-compaction-guards] The range START has to sit on a batch
        // boundary too: planRange only guards the end, and a marker anchor can
        // be moved by the Phase-2.5 heal (applyCompactMarkerGraying), which
        // re-anchors by createdAt without knowing about tool batches. A summary
        // that opens with a tool_result whose tool_use it dropped is the same
        // split the end guard refuses, so clamp back to the nearest safe
        // boundary (0 = summarize the whole prefix, always structurally safe).
        val startIdx = if (effectiveStartIdx <= 0 ||
            AgentContextCompactor.canSplit(history, effectiveStartIdx)
        ) {
            effectiveStartIdx
        } else {
            val clamped = (effectiveStartIdx - 1 downTo 1)
                .firstOrNull { AgentContextCompactor.canSplit(history, it) } ?: 0
            AppLogger.warning(
                TAG,
                "[Compact] start index $effectiveStartIdx is inside a tool batch — clamped to $clamped",
            )
            clamped
        }
        // [C3-android-compaction-guards] Fail-closed range selection, ported
        // from Eta's AgentContextCompactor.planRange: the range may only end
        // between complete tool batches, the newest user turn (and every batch
        // after it) stays verbatim, and the tail keeps its RECENT_MESSAGES
        // floor plus the stricter of RECENT_RATIO x window and the existing
        // OutgoingHistory.COMPACT_KEEP_RECENT_TOKENS budget. A rejection leaves history and
        // marker untouched and surfaces an actionable message instead of
        // compacting something unsafe.
        val recentTokenLimit = contextWindow
            ?.let { minOf((it * AgentContextBudget.RECENT_RATIO).toInt(), OutgoingHistory.COMPACT_KEEP_RECENT_TOKENS) }
        val plan = AgentContextCompactor.planRange(
            history = history,
            startIndex = startIdx,
            anchorIndex = anchorIdx,
            contextWindow = contextWindow,
            recentTokenLimit = recentTokenLimit,
        )
        if (plan is AgentContextCompactor.Plan.Rejected) {
            AppLogger.info(
                TAG,
                "[Compact] rejected ${plan.code}: ${plan.message} " +
                    "(history=${history.size} start=$startIdx anchor=$anchorIdx)",
            )
            return Outcome.Reject(plan.message)
        }
        val range = plan as AgentContextCompactor.Plan.Compactable
        val compactEndIdx = range.endExclusive - 1
        val toCompact = history.subList(range.startIndex, range.endExclusive)
        if (toCompact.isEmpty()) return Outcome.Reject("Nothing to compact.")
        // [C3-android-compaction-guards] Eta packs the range into complete-batch
        // groups and then summarizes group by group. The plan is no longer a
        // pre-flight: the chunks below ARE the summary calls — one call per
        // chunk, each receiving the previous chunk's summary (Eta
        // AgentContextCompactor.summarize(chunk, previous, maxChars)). A single
        // complete batch that does not fit the summarizer is still refused
        // outright (CONTEXT_ITEM_TOO_LARGE) instead of being shredded at a
        // non-batch boundary.
        //
        // The previous summary is read once here and reused for the planning,
        // the carry-in of the first chunk and the reduction math, so the plan
        // and the calls it drives always price the same `previous`.
        val summaryCharCap = AgentContextCompactor.summaryCharCap(contextWindow)
        val summaryMaxInputTokens = AgentContextCompactor.summaryMaxInputTokens(contextWindow)
        val chunks = when (
            val chunkPlan = AgentContextCompactor.chunkForSummary(
                messages = toCompact,
                maxInputTokens = summaryMaxInputTokens,
                previousSummary = existingSummary.orEmpty(),
            )
        ) {
            is AgentContextCompactor.ChunkPlan.Rejected -> {
                AppLogger.info(TAG, "[Compact] rejected ${chunkPlan.code}: ${chunkPlan.message}")
                return Outcome.Reject(chunkPlan.message)
            }
            is AgentContextCompactor.ChunkPlan.Chunks -> {
                AppLogger.info(
                    TAG,
                    "[Compact] range ${toCompact.size} messages → ${chunkPlan.chunks.size} summary chunk(s)",
                )
                chunkPlan.chunks
            }
        }
        return Outcome.Ready(toCompact, compactEndIdx, chunks, existingSummary, summaryCharCap, summaryMaxInputTokens)
    }

    /**
     * Walk back from [compactEndIdx] to the closest entry whose id is non-empty AND present in
     * [rawDbIds] (belt-and-suspenders against ids that were set in memory but never persisted). An
     * empty [rawDbIds] means the DB read failed, so the in-memory index is trusted. -1 when none.
     */
    fun verifiedAnchorIndex(history: List<LLMMessage>, compactEndIdx: Int, rawDbIds: Set<String>): Int {
        if (rawDbIds.isEmpty()) return compactEndIdx
        var i = compactEndIdx
        while (i >= 0) {
            val id = history[i].dbMessageId
            if (!id.isNullOrEmpty() && id in rawDbIds) break
            i -= 1
        }
        return i
    }

    /** The visible list after a compaction: [messages] grayed out up to [cutoffId], plus how many bubbles that covered. */
    data class Divided(val messages: List<ChatMessage>, val compactedUiCount: Int)

    /**
     * Gray out everything in the compacted range; the kept tail (last N user turns + tool/assistant
     * follow-ups) stays full opacity. Determined by walking [messages] until we pass the row whose id
     * == [cutoffId]. Also drops any prior compact-divider system rows — a session shows at most one
     * divider (the latest marker). Those old dividers are stored as system messages with a "compact"
     * iconKind in toolBlocks[0].toolName.
     */
    fun markCompacted(messages: List<ChatMessage>, cutoffId: String): Divided {
        var passedCutoff = false   // anchor is guaranteed non-null in v2
        val cleaned = messages
            .filterNot { msg ->
                // Drop prior compact-divider rows; appendSystemInfo
                // below will re-add the new one.
                msg.role == "system" &&
                    msg.toolBlocks.firstOrNull()?.toolName == "compact"
            }
            .map { msg ->
                if (msg.role == "system") msg
                else if (passedCutoff) msg
                else {
                    val grayed = if (msg.isCompactedHistory) msg
                        else msg.copy(isCompactedHistory = true)
                    if (msg.id == cutoffId) passedCutoff = true
                    grayed
                }
            }
        // T84: count UI bubbles in this pass's compacted range.
        // Filters: role != system (dividers/notices don't count).
        // Range: everything up to and including the cutoff row,
        // since the kept-tail starts immediately after.
        // Falls back to "all non-system" when cutoffId is null
        // (compact-everything path), matching iOS dividerInsertIdx
        // == messages.count behavior.
        //
        // We deliberately do NOT exclude `isCompactedHistory` rows.
        // Back-to-back compacts (or compact after restoring a prior
        // marker on session reload) leave the in-range rows already
        // grayed; excluding them produced "0 messages compacted"
        // even though `toCompact.size` was nonzero. The divider's
        // count should reflect the size of THIS pass's range, not
        // the delta of newly-grayed rows.
        val cutoffIdx = cleaned.indexOfLast { it.id == cutoffId }
        val compactedUICount = if (cutoffIdx < 0) {
            cleaned.count { it.role != "system" }
        } else {
            cleaned.take(cutoffIdx + 1).count { it.role != "system" }
        }
        return Divided(cleaned, compactedUICount)
    }
}
