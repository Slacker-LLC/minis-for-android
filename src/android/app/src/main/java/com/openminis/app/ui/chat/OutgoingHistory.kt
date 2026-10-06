package com.openminis.app.ui.chat

import android.util.Log
import com.openminis.app.data.BPETokenizer
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.logging.AppLogger

/**
 * What is actually sent to the model from the conversation history: the compaction summary spliced
 * in after a verbatim warm-up window, orphaned tool calls and results repaired, and the token
 * estimates that size the window. Functions of the history (and the compact marker), moved out of
 * [ChatViewModel] unchanged so they can be tested without one.
 */
internal object OutgoingHistory {
    private const val TAG = "OutgoingHistory"

    /** How many recent tokens of conversation before the compaction anchor are still sent verbatim. */
    const val COMPACT_KEEP_RECENT_TOKENS = 20_000

    /** Hard cap on the number of messages in that verbatim window. */
    const val COMPACT_KEEP_RECENT_MESSAGE_CAP = 100

    fun effective(
        agentHistory: List<LLMMessage>,
        summary: String?,
        marker: CompactMarkerEntity?,
    ): List<LLMMessage> {
        // No compact in play → return full history untouched.
        if (summary.isNullOrBlank() || marker == null) return agentHistory.toList()

        val summaryWrappedText = "<context-summary>\n" +
            "The following is a summary of the earlier conversation that was compacted to save context space.\n" +
            "Treat it as background context only. The user's most recent message (below or in the next turn) takes precedence — if it changes the task, the goal, or any numbers/scope, follow the new instruction and do not resume the old plan from this summary. Do not re-run discovery (reading memory, scanning skills, re-reading files) unless the new instruction requires it.\n\n" +
            summary +
            "\n</context-summary>"

        // ─── v2 markers (id-only anchor model) ─────────────────────────
        //
        // anchor = lastCompactedMessageId. What we send to the model:
        //   1. as many complete recent user-text rounds as fit inside
        //      [COMPACT_KEEP_RECENT_TOKENS] before the anchor — verbatim warm-up
        //   2. the summary, INLINED as a `<context-summary>` text part
        //      prepended to the first user message AFTER anchor (preserves
        //      strict role alternation — no synthetic standalone user turn)
        //   3. all messages strictly after anchor (the kept-tail "active"
        //      region — typically empty right after compact, populated as
        //      the user sends new prompts)
        //
        // If anchor unresolvable, degrade to full history (over-inform
        // beats summary-only; the M-Team session bug taught us that a lone
        // summary message paired with hot tools makes the model loop).
        if (marker.version >= 2) {
            val anchorId = marker.lastCompactedMessageId?.takeIf { it.isNotEmpty() }
            val anchorIdx = anchorId?.let { id ->
                agentHistory.indexOfLast { it.dbMessageId == id }
            } ?: -1
            if (anchorIdx < 0) {
                Log.w(TAG, "[Compact] effectiveAgentHistory v2: anchorId=${anchorId?.take(8) ?: "nil"} not in agentHistory(size=${agentHistory.size}) — degrading to full history (no summary)")
                return agentHistory.toList()
            }

            // Step 1: walk back from anchor collecting complete user rounds. Stop
            // when adding the next round would exceed the recent token budget,
            // or when preAnchor would grow beyond the hard message cap. Decisions
            // happen only at user-message boundaries so we never split a
            // user/assistant/tool round in half (which would orphan a
            // tool_use with no matching tool_result).
            //
            // [T-compact-preanchor-prune, port iOS 8b76cd74]
            val keepTokenBudget = COMPACT_KEEP_RECENT_TOKENS
            val walkBack = walkBackRecentTokenBudget(
                agentHistory = agentHistory,
                anchorIdx = anchorIdx,
                tokenBudget = keepTokenBudget,
                maxMessages = COMPACT_KEEP_RECENT_MESSAGE_CAP,
            )
            val priorIdxResolved: Int? = walkBack.priorIdx
            val priorIdx = walkBack.priorIdx ?: (anchorIdx + 1) // empty preAnchor sentinel
            if (walkBack.stopReason != "budgetFilled" && walkBack.stopReason != "reachedStart") {
                AppLogger.info(TAG, "[CompactDiag] eAH v2 token walk-back stopped: reason=${walkBack.stopReason} priorIdx=$priorIdx estimatedTokens=${walkBack.estimatedTokens} preAnchorMsgs=${walkBack.messageCount}")
            }

            // PRE-ANCHOR PRUNE (tool-heavy session fix):
            // The walk-back-N-user-text strategy pulls in everything between
            // the Nth-last and last user-text turn — in a heavy tool-call
            // session that can be many messages of tool_result / tool_use,
            // tens of thousands of tokens that the summary already covers.
            // Drop any tool_result > 1000 chars in the preAnchor slice and
            // strip the matching tool_use part (same id) from the assistant
            // message so the model never sees a dangling tool_use/result.
            val preAnchorRaw: List<LLMMessage> =
                if (priorIdx <= anchorIdx) agentHistory.subList(priorIdx, anchorIdx + 1).toList()
                else emptyList()

            val droppedToolIds = mutableSetOf<String>()
            var droppedToolResultCount = 0
            for (msg in preAnchorRaw) {
                for (part in msg.contentParts) {
                    if (part is AgentContentPart.ToolResult && part.content.length > 1000) {
                        droppedToolIds.add(part.id)
                        droppedToolResultCount += 1
                    }
                }
            }

            val preAnchorPruned: MutableList<LLMMessage> = ArrayList(preAnchorRaw.size)
            for (msg in preAnchorRaw) {
                if (msg.contentParts.isEmpty()) {
                    // Plain text-only message — nothing to prune.
                    preAnchorPruned.add(msg)
                    continue
                }
                val kept = msg.contentParts.filter { part ->
                    when (part) {
                        is AgentContentPart.ToolUse -> !droppedToolIds.contains(part.id)
                        is AgentContentPart.ToolResult -> !droppedToolIds.contains(part.id)
                        else -> true
                    }
                }
                if (kept.isEmpty()) continue // skip empty shells
                preAnchorPruned.add(msg.copy(contentParts = kept))
            }

            if (droppedToolResultCount > 0) {
                AppLogger.info(TAG, "[CompactDiag] eAH v2 preAnchor prune: dropped $droppedToolResultCount toolResult(>1kc) + paired toolUse, ${preAnchorRaw.size - preAnchorPruned.size} messages emptied; pruned slice=${preAnchorPruned.size}")
            }

            // ROLE ALIGNMENT: the API requires the first message to be `user`.
            // After clamp (cap may land on assistant) and after prune (the
            // head user may have been emptied), peel any leading non-user
            // messages so preAnchor starts on a user turn.
            while (preAnchorPruned.isNotEmpty() && preAnchorPruned.first().role != LLMMessage.Role.USER) {
                preAnchorPruned.removeAt(0)
            }

            // Step 2 & 3: copy the lookback window (post-prune), then splice
            // in the summary as parts[0] of the first post-anchor user msg.
            val result = mutableListOf<LLMMessage>()
            result.addAll(preAnchorPruned)

            val postAnchor = if (anchorIdx + 1 < agentHistory.size) {
                agentHistory.subList(anchorIdx + 1, agentHistory.size)
            } else {
                emptyList()
            }

            // DIAG: explain how the slice was sized using post-prune /
            // post-alignment counts so the log reflects what actually
            // reaches the model.
            val preAnchorRawCount = maxOf(0, anchorIdx - priorIdx + 1)
            val priorIdxSource =
                if (priorIdxResolved == null) "fallback=empty(tokenBudget=$keepTokenBudget or cap hit)"
                else "tokenBudgetWalkBack(${walkBack.estimatedTokens}/$keepTokenBudget tokens)"
            AppLogger.info(TAG, "[CompactDiag] eAH v2 slice: priorIdx=$priorIdx anchorIdx=$anchorIdx agentHistory.size=${agentHistory.size} → preAnchorRaw=$preAnchorRawCount preAnchorSent=${preAnchorPruned.size} postAnchor=${postAnchor.size} summaryChars=${summary.length} priorIdxSource=$priorIdxSource markerId=${marker.id.take(8)}")

            val firstUserOffset = postAnchor.indexOfFirst { it.role == LLMMessage.Role.USER }
            if (firstUserOffset >= 0) {
                if (firstUserOffset > 0) {
                    result.addAll(postAnchor.subList(0, firstUserOffset))
                }
                val target = postAnchor[firstUserOffset]
                // Prepend `<context-summary>...` to the user content. We
                // edit `content` directly because Android LLMMessage uses
                // `content: String` as the canonical text payload; any
                // contentParts the message also carries get preserved.
                val injected = target.copy(
                    content = summaryWrappedText + "\n\n" + target.content,
                )
                result.add(injected)
                if (firstUserOffset + 1 < postAnchor.size) {
                    result.addAll(postAnchor.subList(firstUserOffset + 1, postAnchor.size))
                }
            } else {
                // Rare: no user message after anchor. Append everything
                // post-anchor (typically empty) then a standalone summary
                // user turn. Safe — no later user follows it to break
                // alternation.
                result.addAll(postAnchor)
                result.add(LLMMessage(role = LLMMessage.Role.USER, content = summaryWrappedText))
            }
            return result
        }

        // ─── v1 (legacy) markers ──────────────────────────────────────
        //
        // Original behavior preserved unchanged so old markers keep
        // rendering / sending data the same way they always did.
        val summaryHead = LLMMessage(role = LLMMessage.Role.USER, content = summaryWrappedText)
        val firstKeptId = (marker.firstKeptMessageId?.takeIf { it.isNotEmpty() })
            ?: (marker.boundaryMessageId?.takeIf { it.isNotEmpty() })

        if (firstKeptId != null) {
            val keepStart = agentHistory.indexOfFirst { it.dbMessageId == firstKeptId }
            if (keepStart >= 0) {
                return buildList(agentHistory.size - keepStart + 1) {
                    add(summaryHead)
                    addAll(agentHistory.subList(keepStart, agentHistory.size))
                }
            }
            // Fall through to safety net.
        } else {
            val lcmId = marker.lastCompactedMessageId?.takeIf { it.isNotEmpty() }
            val lcmIdx = lcmId?.let { id ->
                agentHistory.indexOfLast { it.dbMessageId == id }
            } ?: -1
            val postCompactStart = lcmIdx + 1
            return buildList(agentHistory.size - postCompactStart + 1) {
                add(summaryHead)
                if (postCompactStart < agentHistory.size) {
                    addAll(agentHistory.subList(postCompactStart, agentHistory.size))
                }
            }
        }

        Log.w(TAG, "[Compact] effectiveAgentHistory: marker ${marker.id.take(8)} unresolvable in agentHistory (size=${agentHistory.size}); returning full history")
        return agentHistory.toList()
    }

    /**
     * [T-android-compact-orphan-toolcall] Last line of defence before a history
     * slice becomes a provider request: every ToolResult (function_call_output)
     * must have a matching ToolUse (function_call) in the same slice, and vice
     * versa.
     *
     * An unmatched pair is a hard 400 on OpenAI-compatible APIs —
     *     No tool call found for function call output with call_id …
     * — and because the slice is recomputed deterministically, it repeats on
     * every retry AND every fallback model, wedging the conversation until the
     * user clears the session. Port of iOS `dropOrphanedToolParts`
     * (AIChatViewModel+Persistence.swift, c7f6a299e).
     *
     * Any orphan reaching here is an upstream bug (the walk-back boundary is
     * supposed to preserve pairing), so this logs loudly rather than silently
     * papering over it:
     *   - orphaned result → drop it; its call is gone from the slice and
     *     nothing can reconstruct it.
     *   - orphaned call → synthesise an error result rather than deleting the
     *     call, because deleting would silently discard the assistant's own
     *     reasoning. The placeholder keeps the turn intact and tells the model
     *     that round failed.
     * Messages emptied by the drop are removed — a parts-less message is itself
     * invalid on several providers.
     */
    fun dropOrphanedToolParts(history: List<LLMMessage>): List<LLMMessage> {
        val toolUseIds = HashSet<String>()
        val toolResultIds = HashSet<String>()
        for (msg in history) {
            for (part in msg.contentParts) {
                when (part) {
                    is AgentContentPart.ToolUse -> toolUseIds.add(part.id)
                    is AgentContentPart.ToolResult -> toolResultIds.add(part.id)
                    else -> {}
                }
            }
        }
        val orphanedResults = toolResultIds - toolUseIds
        val orphanedUses = HashSet(toolUseIds - toolResultIds)

        // [T-android-compact-orphan-toolcall] IN-FLIGHT EXEMPTION (iOS
        // 5d346dc2e). The tool_uses in the FINAL assistant message are not
        // orphans while the loop sits between "model asked for tools" and
        // "results appended" — agentHistory legitimately looks unpaired for
        // that whole window (the assistant turn is appended at ~7500 and its
        // tool results only at ~7517). Any snapshot taken inside that gap would
        // otherwise carry fabricated "interrupted" results for tools that were
        // about to run normally, telling the model its tools had failed.
        // Trailing unanswered calls need no repair anyway: a request ending on
        // an assistant tool_use is exactly what the API expects mid-round.
        val last = history.lastOrNull()
        if (last != null && last.role == LLMMessage.Role.ASSISTANT) {
            for (part in last.contentParts) {
                if (part is AgentContentPart.ToolUse) orphanedUses.remove(part.id)
            }
        }

        if (orphanedResults.isEmpty() && orphanedUses.isEmpty()) return history

        AppLogger.warning(
            TAG,
            "[CompactDiag] orphan tool parts in OUTGOING history — repairing. " +
                "orphanedOutputs=${orphanedResults.size} [${orphanedResults.sorted().take(3).joinToString(",")}] " +
                "orphanedCalls=${orphanedUses.size} [${orphanedUses.sorted().take(3).joinToString(",")}] " +
                "historyCount=${history.size}",
        )

        val cleaned = ArrayList<LLMMessage>(history.size)
        for (msg in history) {
            val kept = msg.contentParts.filter { part ->
                if (part is AgentContentPart.ToolResult) !orphanedResults.contains(part.id) else true
            }
            // Only drop the message when it HAD parts and lost them all. A
            // plain text message legitimately carries no contentParts and must
            // survive untouched.
            if (kept.isEmpty() && msg.contentParts.isNotEmpty()) continue
            cleaned.add(if (kept.size == msg.contentParts.size) msg else msg.copy(contentParts = kept))

            // Follow an assistant turn holding orphaned calls with the
            // placeholder results it never got, so the pair is complete.
            if (msg.role != LLMMessage.Role.ASSISTANT) continue
            val unanswered = kept.filterIsInstance<AgentContentPart.ToolUse>()
                .filter { orphanedUses.contains(it.id) }
            if (unanswered.isNotEmpty()) {
                cleaned.add(
                    LLMMessage(
                        role = LLMMessage.Role.USER,
                        content = "",
                        contentParts = unanswered.map {
                            AgentContentPart.ToolResult(
                                id = it.id,
                                name = it.name,
                                content = "Tool execution was interrupted by an unexpected error.",
                                isError = true,
                            )
                        },
                    ),
                )
            }
        }
        return cleaned
    }

    data class TokenWalkBackResult(
        val priorIdx: Int?,
        val estimatedTokens: Int,
        val messageCount: Int,
        /** budgetFilled | messageCapWouldExceed | reachedStart | invalidAnchor */
        val stopReason: String,
    )

    /**
     * Pi-style compact look-back: retain complete recent user rounds according
     * to a token budget instead of a fixed turn count. Boundaries are only
     * accepted at real user-text messages (never tool-result pseudo-user
     * messages), which keeps tool_use/tool_result pairs intact.
     */
    fun walkBackRecentTokenBudget(
        agentHistory: List<LLMMessage>,
        anchorIdx: Int,
        tokenBudget: Int,
        maxMessages: Int,
    ): TokenWalkBackResult {
        if (anchorIdx !in agentHistory.indices) return TokenWalkBackResult(null, 0, 0, "invalidAnchor")
        var accepted: Int? = null
        var acceptedTokens = 0
        var acceptedMessages = 0
        var i = anchorIdx
        while (i >= 0) {
            val msg = agentHistory[i]
            val isUserBoundary = msg.role == LLMMessage.Role.USER &&
                msg.contentParts.none { it is AgentContentPart.ToolResult } &&
                (msg.content.isNotBlank() || msg.contentParts.any { it is AgentContentPart.Text && it.text.isNotBlank() })
            if (!isUserBoundary) { i -= 1; continue }
            val count = anchorIdx - i + 1
            if (count > maxMessages) {
                return TokenWalkBackResult(accepted, acceptedTokens, acceptedMessages, "messageCapWouldExceed")
            }
            val tokens = estimatePrunedRecentSliceTokens(agentHistory, i, anchorIdx)
            if (tokens > tokenBudget && accepted != null) {
                return TokenWalkBackResult(accepted, acceptedTokens, acceptedMessages, "budgetFilled")
            }
            // Always keep at least the most recent complete user round even if
            // one pathological round alone is over budget; later pre-anchor
            // pruning will strip oversized tool outputs from it.
            accepted = i
            acceptedTokens = tokens
            acceptedMessages = count
            if (tokens >= tokenBudget) return TokenWalkBackResult(accepted, tokens, count, "budgetFilled")
            i -= 1
        }
        return TokenWalkBackResult(accepted, acceptedTokens, acceptedMessages, "reachedStart")
    }

    /** Estimate the token footprint of the exact pre-anchor form we will send.
     * Large tool results (>1k chars) and their paired tool_use parts are pruned
     * later, so exclude them here too; otherwise one 50k build log would consume
     * the whole 20k recent budget and leave only a tiny post-prune warm-up. */
    private fun estimatePrunedRecentSliceTokens(agentHistory: List<LLMMessage>, startIdx: Int, endIdx: Int): Int {
        val droppedToolIds = mutableSetOf<String>()
        for (idx in startIdx..endIdx) {
            for (part in agentHistory[idx].contentParts) {
                if (part is AgentContentPart.ToolResult && part.content.length > 1000) droppedToolIds += part.id
            }
        }
        var total = 0
        for (idx in startIdx..endIdx) total += estimateMessageTokens(agentHistory[idx], droppedToolIds)
        return total
    }

    private fun estimateMessageTokens(msg: LLMMessage, ignoredToolIds: Set<String> = emptySet()): Int {
        // Most agent messages mirror their text/images into contentParts, so
        // counting both msg.content + all parts would double-count ordinary
        // turns. Prefer structured parts, but still include msg.content when no
        // Text part exists (defensive for synthetic/tool-only messages).
        var tokens = 0
        if (msg.contentParts.isNotEmpty()) {
            var hasTextPart = false
            var hasImagePart = false
            for (part in msg.contentParts) {
                if (part is AgentContentPart.ToolUse && part.id in ignoredToolIds) continue
                if (part is AgentContentPart.ToolResult && part.id in ignoredToolIds) continue
                tokens += countPartTokens(part)
                if (part is AgentContentPart.Text) hasTextPart = true
                if (part is AgentContentPart.ImageData) hasImagePart = true
            }
            if (!hasTextPart && msg.content.isNotBlank()) tokens += BPETokenizer.countTokens(msg.content)
            if (!hasImagePart) for (image in msg.imageParts) tokens += BPETokenizer.countImageTokens(image.data)
        } else {
            tokens += BPETokenizer.countTokens(msg.content)
            for (image in msg.imageParts) tokens += BPETokenizer.countImageTokens(image.data)
        }
        msg.reasoningContent?.takeIf { it.isNotBlank() }?.let { tokens += BPETokenizer.countTokens(it) }
        // Audio tokenization is provider-specific. Base64 chars / 4 is a
        // conservative budget estimate that prevents huge audio turns from
        // defeating compaction without pretending to know provider billing.
        for (audio in msg.audioParts) tokens += (audio.base64Data.length / 4).coerceAtLeast(1)
        return tokens
    }

    /**
     * Approximate token count for a single agent content part. Used to rank
     * offload candidates by size. Matches iOS `BPETokenizer.countPartTokens`
     * — text uses BPE, images use the grid-cell heuristic.
     */
    fun countPartTokens(part: AgentContentPart): Int = when (part) {
        is AgentContentPart.Text -> BPETokenizer.countTokens(part.text)
        is AgentContentPart.ToolUse -> BPETokenizer.countTokens(part.input.toString())
        is AgentContentPart.ToolResult -> {
            BPETokenizer.countTokens(part.content) +
                (part.imageData?.let { BPETokenizer.countImageTokens(it) } ?: 0)
        }
        is AgentContentPart.ImageData -> BPETokenizer.countImageTokens(part.data)
    }
}
