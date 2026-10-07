package com.openminis.app.ui.chat

import android.content.Context
import android.util.Log
import androidx.compose.material.icons.outlined.Build
import androidx.lifecycle.viewModelScope
import com.openminis.app.R
import com.openminis.app.data.ContextPolicy
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.logging.AppLogger
import com.openminis.app.tools.ContextPressure
import com.openminis.app.tools.ToolCheckpointStore
import com.openminis.app.ui.chat.ChatViewModel.CompactProgress
import com.openminis.app.ui.chat.ChatViewModel.Companion.MAX_COMPACT_LLM_CALLS
import com.openminis.app.ui.chat.ChatViewModel.Companion.TAG
import com.openminis.app.ui.chat.ChatViewModel.Companion.compactTimeoutMsFor
import com.openminis.app.ui.chat.ChatViewModel.InLoopContextAction
import com.openminis.app.ui.chat.ChatViewModel.PreSendContextAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Append a system-info block to the conversation. Not persisted — matches the
 * iOS `appendSystemInfo` behavior which surfaces a local notice in the chat
 * stream. Future work: wire real conversation compaction through the LLM.
 */
internal fun ChatViewModel.appendSystemInfo(text: String, iconKind: String, payload: String? = null) {
    val block = AssistantBlock(
        id = "sysinfo_${System.currentTimeMillis()}",
        kind = "info",
        content = text,
        toolName = iconKind,
        // Reuse toolArgs as a freeform payload slot — for `iconKind="compact"`
        // this carries the full summary text so the UI can show an info-icon
        // affordance opening a detail sheet (mirrors iOS CompactSummarySheet).
        toolArgs = payload.orEmpty(),
    )
    _messages.value = _messages.value + ChatMessage(
        id = "sysinfo_${System.currentTimeMillis()}",
        role = "system",
        content = "",
        toolBlocks = listOf(block),
    )
}

/**
 * Fold the current session history into a single summary stored in
 * `compact_markers`. Mirrors iOS `compactAll()` + Phase-B semantics:
 *
 *   1. Build a compact conversation transcript (role + parts preview).
 *   2. Call the **current provider's non-streaming `sendMessage`** with a
 *      hardcoded summarization system prompt that emphasises preserving
 *      paths/commands/IDs/decisions/errors/open tasks.
 *   3. Persist a `CompactMarkerEntity` via the DAO; publish via
 *      [_compactSummary] so [effectiveAgentHistory] starts injecting it.
 *   4. agentHistory itself is NOT truncated — the audit trail stays.
 *
 * Concurrency: gated by [_isCompacting] so the slash command can't
 * overlap with an in-flight streaming turn (`_isStreaming`) or another
 * compact. Runs on [Dispatchers.IO].
 */
/**
 * Public entrypoint used by the debug RPC (`chat.session.compact`) to
 * trigger compaction without going through the ChatScreen slash-command
 * UI path. Mirrors what [executeSlashCommand]("compact") does — just
 * calls [compactAll]. RPC callers can then observe [isCompacting] flipping
 * back to false to know the run finished, and read [compactSummary] for
 * the resulting summary text.
 */
fun ChatViewModel.runCompactNow() {
    compactAll()
}

/**
 * Token usage of a finished reply, for its long-press menu. Read from the persisted rows the reply
 * was built from rather than carried on every UI message, so the chat list stays untouched; the
 * reply reports its last turn that has usage (see [lastReplyUsage]). Null when there is none.
 * Ported from OpenMinis 1.14 [T-android-usage-capsule-time].
 */
internal suspend fun ChatViewModel.replyUsage(messageId: String): ReplyUsage? {
    val message = _messages.value.firstOrNull { it.id == messageId } ?: return null
    val rows = message.sourceDbIds.ifEmpty { listOf(message.id) }.mapNotNull { dbId ->
        chatRepository.messageById(dbId)?.let { it.tokenUsage to it.createdAt }
    }
    return lastReplyUsage(rows)
}

/**
 * Public entrypoint for "compact up through this message" (mirrors iOS
 * AIChatViewModel.compactBefore). The chat list's long-press menu and
 * the debug RPC `chat.compact.before` route through here.
 *
 * @param dbMessageId the DB message id to use as the new marker's
 *   anchor. agentHistory range to compact = `[prevAnchor+1, anchorIdx]`
 *   where anchorIdx is the agentHistory position of this id.
 * @param includesBoundary accepted for ABI compatibility with iOS, but
 *   in v2 the anchor IS the caller-supplied message regardless — the
 *   flag is logged and ignored. (iOS made the same simplification.)
 *
 * If the id can't be resolved to an agentHistory entry, this falls
 * back to compactAll() behaviour so the user's gesture isn't lost.
 */
fun ChatViewModel.compactBefore(dbMessageId: String, includesBoundary: Boolean = false) {
    AppLogger.info(
        TAG,
        "[Compact] compactBefore() id=${dbMessageId.take(8)} includesBoundary=$includesBoundary " +
            "(v2: includesBoundary ignored — caller-supplied id becomes the anchor)",
    )
    val history = agentHistory.toList()
    val idx = history.indexOfLast { it.dbMessageId == dbMessageId }
    if (idx < 0) {
        AppLogger.warning(
            TAG,
            "[Compact] compactBefore: id=${dbMessageId.take(8)} not in agentHistory — falling back to compactAll()",
        )
        compactAll(anchorIdxOverride = null)
        return
    }
    compactAll(anchorIdxOverride = idx)
}

/**
 * [T-android-auto-compact-inloop] Compact the session.
 *
 * [allowDuringProcessing] lets the in-loop guard in [runAgentLoop] compact
 * BETWEEN agent iterations, where `_isStreaming` is legitimately true. All
 * user-initiated paths keep the default (false) so the "can't compact while
 * a turn is running" guard is unchanged for them. Re-entrancy is still
 * covered by [_isCompacting]. Mirrors iOS f70ac173.
 *
 * [onFinished] fires on the IO coroutine once the compaction attempt has
 * settled (success or failure), so the loop can await it before issuing the
 * next API call — the function itself is fire-and-forget.
 */
/**
 * Public entry: guarantees [onFinished] is invoked exactly once even when a
 * precondition rejects the request before any work is launched. The inner
 * implementation has many early returns; wrapping it here is safer than
 * threading a callback through each one, and it means an in-loop caller can
 * never hang waiting for a callback that was skipped.
 */
internal fun ChatViewModel.compactAll(
    anchorIdxOverride: Int? = null,
    allowDuringProcessing: Boolean = false,
    onFinished: ((Boolean) -> Unit)? = null,
) {
    var started = false
    compactAllImpl(anchorIdxOverride, allowDuringProcessing, onFinished) { started = true }
    if (!started) onFinished?.invoke(false)
}

internal inline fun ChatViewModel.compactAllImpl(
    anchorIdxOverride: Int?,
    allowDuringProcessing: Boolean,
    noinline onFinished: ((Boolean) -> Unit)?,
    markStarted: () -> Unit,
) {
    AppLogger.info(TAG, "[Compact] compactAll() invoked streaming=${_isStreaming.value} compacting=${_isCompacting.value} historySize=${agentHistory.size} anchorOverride=$anchorIdxOverride inLoop=$allowDuringProcessing")
    if (_isStreaming.value && !allowDuringProcessing) {
        AppLogger.info(TAG, "[Compact] aborted: stream in progress")
        appendSystemInfo(
            text = "Cannot compact while a turn is in progress. Stop the current response first.",
            iconKind = "compact",
        )
        return
    }
    if (_isCompacting.value) {
        AppLogger.info(TAG, "[Compact] aborted: another compact already in flight")
        appendSystemInfo(
            text = "A compact is already in progress. Please wait for it to finish.",
            iconKind = "compact",
        )
        return
    }
    val provider = currentProvider ?: run {
        appendSystemInfo("No provider configured. Cannot compact.", "compact")
        return
    }
    val history = agentHistory.toList()
    if (history.isEmpty()) {
        appendSystemInfo("Nothing to compact — the session is empty.", "compact")
        return
    }
    val contextWindow = effectiveContextWindowTokens()
        ?: currentModel?.contextWindow?.takeIf { it > 0 }
    val ready = when (
        val outcome = CompactionPlanner.plan(
            history = history,
            anchorIdxOverride = anchorIdxOverride,
            prev = _cachedLatestMarker,
            existingSummary = _compactSummary.value?.takeIf { it.isNotBlank() },
            contextWindow = contextWindow,
        )
    ) {
        is CompactionPlanner.Outcome.Reject -> {
            appendSystemInfo(text = outcome.message, iconKind = "compact")
            return
        }
        is CompactionPlanner.Outcome.Ready -> outcome
    }
    val toCompact = ready.toCompact
    // Past every precondition — from here the launch below owns the
    // onFinished callback.
    markStarted()
    _isCompacting.value = true
    compactionSummarizer.resetCalls()
    val transcriptChars = compactionSummarizer.buildConversationTextForSummary(toCompact).length
    val compactTimeoutMs = compactTimeoutMsFor(transcriptChars)
    _compactProgress.value = CompactProgress(
        startedAtMs = System.currentTimeMillis(),
        depth = 0,
        callsIssued = 0,
        callBudget = MAX_COMPACT_LLM_CALLS,
        timeoutSeconds = (compactTimeoutMs / 1000L).toInt(),
    )
    compactJob = viewModelScope.launch(Dispatchers.IO) {
        // [T-android-compact-queued-drain] Only a SUCCESSFUL compact kicks
        // the queued-prompt drain below; failure/cancel/empty-summary paths
        // keep today's behavior (queued bubbles stay pending + cancellable).
        var compactSucceeded = false
        try {
            val result = CompactionExecution.run(
                plan = ready,
                history = history,
                summarizer = compactionSummarizer,
                timeoutMs = compactTimeoutMs,
                sessionId = realSessionId.ifEmpty { sessionId },
                loadMessageIds = { chatRepository.dao.loadMessages(realSessionId.ifEmpty { sessionId }).map { it.id }.toSet() },
                insertMarker = { chatRepository.dao.insertCompactMarker(it) },
            )
            when (result) {
                is CompactionExecution.Result.Stopped -> {
                    withContext(Dispatchers.Main) { appendSystemInfo(result.message, "compact") }
                    return@launch
                }
                is CompactionExecution.Result.Done -> {
                    val summary = result.summary
                    _compactSummary.value = summary
                    // Keep the marker in memory so effectiveAgentHistory() can
                    // resolve the boundary on the very next outgoing turn.
                    // Mirrors iOS `cachedLatestMarker = marker`.
                    _cachedLatestMarker = result.marker
                    withContext(Dispatchers.Main) {
                        val divided = CompactionPlanner.markCompacted(_messages.value, result.cutoffId)
                        val compactedUICount = divided.compactedUiCount
                        _messages.value = divided.messages
                        AppLogger.info(TAG, "[Compact] divider: $compactedUICount UI bubbles compacted (history entries: ${ready.toCompact.size})")
                        appendSystemInfo(
                            text = "$compactedUICount messages compacted",
                            iconKind = "compact",
                            payload = summary,
                        )
                    }
                }
            }
            compactSucceeded = true
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Compact timed out after ${compactTimeoutMs}ms", e)
            withContext(Dispatchers.Main) {
                appendSystemInfo(
                    text = "Compaction timed out after ${compactTimeoutMs / 1_000}s. Try again later.",
                    iconKind = "compact",
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: CompactRejectedSummary) {
            // [C3-android-compaction-guards] A summary (a chunk, an
            // overflow half or the decorated final text) failed Eta's
            // gate. Nothing was written: history, marker and
            // _compactSummary all keep their previous values, and the
            // upstream message is surfaced verbatim.
            Log.w(TAG, "[Compact] rejected ${e.code}: ${e.message}")
            withContext(Dispatchers.Main) {
                appendSystemInfo(
                    text = e.message.orEmpty().ifEmpty {
                        "Compaction was rejected; the original context is kept."
                    },
                    iconKind = "compact",
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Compact failed", e)
            withContext(Dispatchers.Main) {
                appendSystemInfo(
                    text = "Compaction failed: ${e.message ?: e.javaClass.simpleName}",
                    iconKind = "compact",
                )
            }
        } catch (e: com.openminis.app.provider.ProviderTransportPolicy.Violation) {
            // A stale or revoked cleartext approval remains a provider
            // configuration error, but must not crash the Application.
            Log.w(TAG, "provider configuration rejected while loading session: " + e.message)
            currentProvider = null
            _activeEntryId.value = null
            _error.value = e.message ?: "Provider configuration rejected"
        } finally {
            _isCompacting.value = false
            _compactProgress.value = null
            // [T-android-auto-compact-inloop] Signal the awaiting in-loop
            // caller. In `finally` so a thrown/cancelled compaction can
            // never strand the agent loop waiting on a callback.
            onFinished?.invoke(compactSucceeded)
        }
        // [T-android-compact-queued-drain] A successful compact must let
        // any queued prompts proceed — previously nothing re-triggered the
        // drain after compact (loop-end / cancel / tool-boundary are the
        // only drain triggers), so a prompt sitting in the queue when a
        // compact ran stayed in the dashed "queued" state forever. Reuse
        // resumeQueueAfterCancel: it re-checks queue-non-empty + not-
        // streaming + not-compacting after its grace delay (so an ✕ tap at
        // the compact-finish instant is a clean no-op), refreshes OAuth,
        // and drains through the normal stream-slot machinery — no new
        // reentrancy path. Runs after `finally` so isCompacting is already
        // false. Mirrors the iOS fix for the same report.
        if (compactSucceeded && _promptQueue.value.isNotEmpty()) {
            AppLogger.info(TAG, "[Compact] success with ${_promptQueue.value.size} queued prompt(s) — kicking drain")
            resumeQueueAfterCancel()
        }
    }
}

/**
 * Revert the most recent compact on this session.
 *
 * Drops the latest CompactMarker (its summary is discarded), refreshes
 * [_cachedLatestMarker] / [_compactSummary] to whatever's left (or
 * null), and rebuilds the message list so the UI reflects the new (or
 * absent) divider. Effect by design:
 *   - If a previous (older) marker exists, divider snaps back to that
 *     marker's anchor; effectiveAgentHistory replays that summary.
 *   - If no previous marker exists, divider disappears, full history
 *     flows to the model again.
 *
 * Mirrors iOS `revertCompact()`. Refuses to run mid-stream.
 */
fun ChatViewModel.revertCompact() {
    if (_isStreaming.value) {
        appendSystemInfo("Cannot revert compact while a response is in progress.", "compact")
        return
    }
    if (_isCompacting.value) {
        appendSystemInfo("Cannot revert compact while compaction is in progress.", "compact")
        return
    }
    val current = _cachedLatestMarker ?: run {
        appendSystemInfo("Nothing to revert — no compact marker on this session.", "compact")
        return
    }
    val sid = realSessionId.ifEmpty { sessionId }
    if (sid.isEmpty()) return

    viewModelScope.launch(Dispatchers.IO) {
        AppLogger.info(TAG, "[Compact] ━━━ REVERT ━━━ session=${sid.take(8)} markerId=${current.id.take(8)} v=${current.version}")
        val removed = runCatching { chatRepository.dao.deleteCompactMarker(current.id) }.getOrNull() ?: 0
        if (removed <= 0) {
            Log.w(TAG, "[Compact] revert: deleteCompactMarker returned 0 rows for id=${current.id.take(8)}")
            withContext(Dispatchers.Main) {
                appendSystemInfo("Revert failed: marker not found in DB.", "compact")
            }
            return@launch
        }

        // Refresh cache to next-most-recent marker (or null).
        val next = chatRepository.dao.latestCompactMarker(sid)
        _cachedLatestMarker = next
        _compactSummary.value = next?.summary

        // Rebuild UI from DB so the previous marker's divider re-emerges
        // (or all dividers vanish if there are no remaining markers).
        // Drop any stale compact-divider system rows first; the reload
        // path will re-insert one only if the new latest marker calls
        // for it.
        withContext(Dispatchers.Main) {
            _messages.value = _messages.value.filterNot { msg ->
                msg.role == "system" &&
                    msg.toolBlocks.firstOrNull()?.toolName == "compact"
            }
        }

        // Reload session messages — the existing path runs Phase 2.5
        // graying via applyCompactMarkerGraying() with the new cached
        // marker, so divider position falls back to the previous one
        // (or disappears entirely). loadSession() launches its own
        // viewModelScope job, so call from the Main thread.
        withContext(Dispatchers.Main) {
            reloadSessionFromDb()
        }

        if (next != null) {
            AppLogger.info(TAG, "[Compact] revert DONE: now showing previous marker id=${next.id.take(8)} v=${next.version}")
        } else {
            AppLogger.info(TAG, "[Compact] revert DONE: no remaining markers, full history active")
        }
    }
}

/**
 * Re-load the current session's UI message list from disk so any
 * cached-marker change (revert) gets re-applied through Phase-2.5-
 * style restore. Defers to the existing [loadSession] entry; that
 * function reads `_cachedLatestMarker` we just refreshed and routes
 * through [applyCompactMarkerGraying] to (re)position the divider.
 */
internal fun ChatViewModel.reloadSessionFromDb() {
    if (realSessionId.isEmpty() && sessionId.isEmpty()) return
    loadSession()
}

/**
 * [T-android-compact-orphan-toolcall] The outgoing history, with tool
 * call/result pairing repaired. Every request goes through here — see
 * [dropOrphanedToolParts] for why the sweep exists and what it can and
 * cannot fix.
 */
internal fun ChatViewModel.effectiveAgentHistory(): List<LLMMessage> =
    injectUnknownOutcomes(
        OutgoingHistory.dropOrphanedToolParts(
            OutgoingHistory.effective(agentHistory.toList(), _compactSummary.value, _cachedLatestMarker),
        ),
    )

/**
 * Recover interrupted tool executions (DSH session-checkpoint-policy
 * TOOL_OUTCOME_UNKNOWN): if the process died while a tool was running,
 * the persisted intent has no result. Inject a model-visible message so
 * the agent does not blindly re-run a call that may already have had
 * side effects. Inspection never consumes a recovery warning.
 */
internal fun ChatViewModel.injectUnknownOutcomes(history: List<LLMMessage>): List<LLMMessage> {
    val sid = activeSessionId ?: return history
    // Reconcile a crash after Room committed but before checkpoint acknowledgement.
    ToolCheckpointStore.markDoneBatch(context, sid,
        agentHistory.filter { it.dbMessageId != null }.flatMap { message ->
            message.contentParts.filterIsInstance<AgentContentPart.ToolResult>()
        }.associate { it.id to !it.isError })
    val pending = ToolCheckpointStore.drainPending(context, sid)
    if (pending.isEmpty()) return history
    return history + pending.map { rec ->
        val text = "[tool outcome unknown] The tool '" + rec.toolName +
            "' was interrupted before its result could be persisted (the app process " +
            "died mid-execution). Whether it took effect is UNKNOWN. Do not blindly " +
            "re-run it — if the call could have side effects (file writes, external " +
            "API calls, payments), verify the actual state first (e.g. read the file / " +
            "check the workspace) or ask the user."
        LLMMessage(
            role = LLMMessage.Role.ASSISTANT,
            content = text,
            contentParts = listOf(AgentContentPart.Text(text)),
        )
    }
}

/**
 * Consult [ContextPolicy] before sending. Returns true to proceed. The
 * Android MVP doesn't surface a "Compact before send" dialog (iOS does),
 * so we only warn via [appendSystemInfo] at the `needsCompact` /
 * `exhausted` boundaries and still allow the send. That gives the user
 * a signal to invoke `/compact` explicitly without blocking their turn.
 * A [ContextPressure] advisory (dsh-token-meter port) is also surfaced
 * through the same channel whenever the fixed-heuristic estimate crosses
 * 80% of the model's context window — advisory only; the compaction flow
 * below is left untouched.
 */
internal fun ChatViewModel.checkContextBeforeSend(): PreSendContextAction {
    val tokens = _lastTurnContextTokens.value
    if (tokens <= 0) return PreSendContextAction.PROCEED
    // [T-context-window-live-read] Live window (entry re-resolved + group
    // contextLimitTokens folded in) — not the currentModel snapshot.
    val window = effectiveContextWindowTokens() ?: return PreSendContextAction.PROCEED
    // [Token Meter] Fixed-heuristic context pressure (dsh-token-meter
    // port) over the exact history this send would carry. Advisory only:
    // when the estimate crosses 80% of the window we surface the
    // percentage through the existing warning channel (appendSystemInfo);
    // the compaction flow below is unchanged.
    val pressure = ContextPressure.compute(window, effectiveAgentHistory())
    if (pressure.needsCompact) {
        appendSystemInfo(
            text = context.getString(R.string.chat_context_pressure_note, pressure.percent, window),
            iconKind = "compact",
        )
    }
    val policy = ContextPolicy.forContextWindow(window)
    return when (policy.check(tokens, window)) {
        ContextPolicy.CheckResult.OK -> PreSendContextAction.PROCEED

        // Mirrors iOS AIChatViewModel.swift:2224. Previously Android only
        // appended a notice here and sent anyway, which meant the very
        // request that tripped the threshold still went out over-length —
        // the warning arrived alongside the failure it was meant to avoid.
        ContextPolicy.CheckResult.NEEDS_COMPACT -> {
            if (com.openminis.app.data.AutoCompactPrefs.isEnabled()) {
                AppLogger.info(
                    TAG,
                    "[Context] pre-send near capacity ($tokens / $window) — auto-compacting (pref on)",
                )
                PreSendContextAction.COMPACT_THEN_SEND
            } else {
                AppLogger.info(
                    TAG,
                    "[Context] pre-send near capacity ($tokens / $window) — prompting user",
                )
                PreSendContextAction.ASK_USER
            }
        }

        // Exhausted tiers have compactThreshold = 0 by policy: the window is
        // too small for a summary to pay for itself, so compacting is not
        // on offer. Keep the existing advisory-and-proceed behaviour rather
        // than blocking the user out of their own chat.
        ContextPolicy.CheckResult.EXHAUSTED -> {
            appendSystemInfo(
                text = "Context is near the model's limit ($tokens / $window tokens). Start a new chat or /compact to continue reliably.",
                iconKind = "compact",
            )
            PreSendContextAction.PROCEED
        }
    }
}

/**
 * Dialog action: compact the history, then send what the user was holding.
 * [alsoEnableAutoCompact] backs iOS's one-tap opt-in button, which compacts
 * now AND remembers the choice for every future conversation.
 */
fun ChatViewModel.compactAndSendPending(alsoEnableAutoCompact: Boolean = false) {
    if (alsoEnableAutoCompact) setAutoCompactEnabled(true)
    _showCompactBeforeSendPrompt.value = false
    val text = pendingSendText ?: return
    pendingSendText = null
    viewModelScope.launch {
        val ok = awaitCompaction()
        if (!ok) {
            AppLogger.warning(TAG, "[Context] pre-send compaction failed — sending anyway")
        }
        sendMessage(text, skipContextCheck = true)
    }
}

/** Dialog action: send without compacting. */
fun ChatViewModel.sendPendingWithoutCompacting() {
    _showCompactBeforeSendPrompt.value = false
    val text = pendingSendText ?: return
    pendingSendText = null
    sendMessage(text, skipContextCheck = true)
}

/** Dialog dismissed — restore the text to the composer so it isn't lost. */
fun ChatViewModel.cancelCompactBeforeSend() {
    _showCompactBeforeSendPrompt.value = false
    pendingSendText?.let { _inputText.value = it }
    pendingSendText = null
}

/**
 * [T-android-auto-compact-inloop] Re-evaluate [ContextPolicy] between agent
 * iterations and act on it (iOS f70ac173).
 *
 * Why this exists: [checkContextBeforeSend] only runs at the SEND entry
 * point. A single turn that fans out into many tool iterations can cross the
 * compact/exhausted thresholds mid-loop, and offload alone cannot recover
 * when the bulk is the model's own text — the turn then slams into the
 * provider's context ceiling.
 *
 * Blocks until the compaction attempt settles, because the next API call
 * must read the freshly-compacted history.
 */
internal suspend fun ChatViewModel.inLoopContextCheck(compactionsSoFar: Int): InLoopContextAction {
    val tokens = _lastTurnContextTokens.value
    val window = effectiveContextWindowTokens()
    return when (InLoopContextPolicy.decide(tokens, window, compactionsSoFar)) {
        InLoopContextPolicy.Decision.PROCEED -> InLoopContextAction.PROCEED

        InLoopContextPolicy.Decision.STOP_COMPACTION_BUDGET -> {
            AppLogger.warning(
                TAG,
                "[AutoCompact] still over threshold after $compactionsSoFar compaction(s) — stopping",
            )
            InLoopContextAction.STOP
        }

        InLoopContextPolicy.Decision.COMPACT -> {
            // NOTE: deliberately NOT gated on AutoCompactPrefs. That flag
            // governs the SEND-time decision (compact silently vs. ask
            // first) — mid-loop there is nobody to ask, and the alternative
            // to compacting is aborting the user's turn outright. iOS makes
            // the same call: its in-loop branch
            // (AIChatViewModel.swift:4739) never consults
            // autoCompactEnabled either.
            AppLogger.info(
                TAG,
                "[AutoCompact] mid-loop compact #${compactionsSoFar + 1}: $tokens / $window tokens " +
                    "(autoCompactPref=${com.openminis.app.data.AutoCompactPrefs.isEnabled()}, not a gate here)",
            )
            appendSystemInfo(
                text = "Context is filling up ($tokens / $window tokens) — compacting to continue.",
                iconKind = "compact",
            )
            val ok = awaitCompaction()
            if (!ok) return InLoopContextAction.STOP
            // [T-android-auto-compact-inloop] Invalidate the stale reading.
            // `_lastTurnContextTokens` is only refreshed by a usage chunk,
            // which needs a COMPLETED API call — but this path compacts and
            // `continue`s without one. Leaving the pre-compaction value in
            // place made the very next iteration read the same number and
            // compact again immediately, burning the whole budget in
            // seconds (observed on device: two compactions 3s apart, both
            // logging an identical 66358). Zeroing it makes the guard
            // PROCEED once, so the next real response measures the
            // post-compaction size and the decision is made on fresh data.
            _lastTurnContextTokens.value = 0
            InLoopContextAction.COMPACTED
        }

        InLoopContextPolicy.Decision.STOP_EXHAUSTED -> {
            AppLogger.warning(
                TAG,
                "[AutoCompact] exhausted on a no-auto-compact tier ($tokens / $window) — stopping",
            )
            InLoopContextAction.STOP
        }
    }
}

/**
 * [T-android-auto-compact-inloop] Run [compactAll] with the in-loop flag and
 * suspend until it settles. Returns whether it actually compacted.
 *
 * `compactAll` is fire-and-forget (it launches its own IO coroutine), so the
 * loop cannot simply call it and continue — the next API call would read the
 * pre-compaction history and the guard would fire again immediately.
 */
internal suspend fun ChatViewModel.awaitCompaction(): Boolean =
    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        var resumed = false
        compactAll(allowDuringProcessing = true) { ok ->
            // compactAll guarantees exactly one callback, but guard anyway:
            // resuming a continuation twice throws.
            if (!resumed) {
                resumed = true
                if (cont.isActive) cont.resume(ok) { _, _, _ -> }
            }
        }
    }
