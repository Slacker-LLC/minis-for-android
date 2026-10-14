package com.openminis.app.agent

import com.openminis.app.ui.chat.*

import android.util.Log
import androidx.compose.material.icons.outlined.Build
import com.openminis.app.R
import com.openminis.app.agent.AgentTurnOutcome
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.runtime.ExecutionCoordinator
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.service.SessionConcurrencyManager
import com.openminis.app.agent.AgentSession.Companion.CANCELLED_MARKER
import com.openminis.app.agent.AgentSession.Companion.TAG
import com.openminis.app.agent.AgentSession.Companion.TAG_STREAM
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.openminis.app.data.repository.loadApiKey
import com.openminis.app.data.repository.saveApiKey

fun AgentSession.cancelStream() {
    AppLogger.info(TAG_STREAM, "cancelStream invoked _isStreaming=false (sid=$activeSessionId)")
    execution.cancelTurn()
    _isStreaming.value = false
    // T-streaming-side-channel: flush any in-flight delta back into the
    // canonical message so the rest of cancelStream's cleanup (publish
    // overlay excerpt, persist, retry-eligible state) sees the real
    // content rather than a stale pre-stream snapshot.
    flushAllStreamingDeltas()
    // T171: drop activity tracker immediately, don't wait for the
    // streamJob's finally block. When OkHttp is wedged in a blocking
    // execute() call.cancel() may unwind eventually but the finally
    // doesn't run until then — meanwhile RPC chat.session.status would
    // still report isRunning=true and the user thinks the stop button
    // did nothing.
    // [T-android-overlay-reply-status-34599] User-initiated cancel:
    // surface any reply we already streamed + tag outcome as
    // Cancelled so the overlay's glyph reflects the actual end
    // state (⊘) instead of carrying over the prior tool's outcome.
    publishOverlayReplyExcerpt(activeSessionId)
    SessionActivityTracker.clearToolRunning(com.openminis.app.service.ToolOutcome.Cancelled)
    SessionActivityTracker.setInactive(activeSessionId)
    com.openminis.app.tools.android.DeviceScreenLease.shared.releaseSession(activeSessionId)
    if (isDraft && realSessionId.isNotEmpty() && activeSessionId != sessionId) {
        SessionActivityTracker.setInactive(sessionId)
        com.openminis.app.tools.android.DeviceScreenLease.shared.releaseSession(sessionId)
    }
    // Stop whichever shell the agent loop is actually dispatching against.
    // Before `ensureSession()` that is the draft id; after, the real id.
    // Stopping the wrong one leaves a runaway yt-dlp/ffmpeg alive.
    ExecutionCoordinator.stopCurrentCommand(activeSessionId)
    if (isDraft && realSessionId.isNotEmpty() && activeSessionId != sessionId) {
        // Mid-turn rename: sweep any lingering draft shell too.
        ExecutionCoordinator.stopCurrentCommand(sessionId)
    }
    handleUserCancelledCleanup()

    // T189: iOS parity (AIAgentSession.swift L2592-2610). If the user
    // enqueued prompts during the cancelled stream, auto-resume the drain
    // instead of leaving them stuck as dashed bubbles waiting for a manual
    // long-press retry.
    val pending = _promptQueue.value
    if (pending.isNotEmpty()) {
        AppLogger.info(TAG_STREAM, "cancel — ${pending.size} queued prompt(s) remain, restarting drain")
        resumeQueueAfterCancel()
    }
}

/** Wait for the actual stream coroutine to leave after cancellation. */
internal suspend fun AgentSession.awaitStreamExit(timeoutMs: Long): Boolean {
    return execution.awaitExit(timeoutMs)
}

internal suspend fun AgentSession.awaitStreamExit(): Boolean {
    execution.awaitExit()
    return true
}

/**
 * T189: spawn a fresh agent loop to drain whatever the user queued during
 * the cancelled stream. Wait for actual execution cleanup to release the
 * concurrency slot and finish persistence before preparing another turn. Guards on
 * entry: empty queue (user withdrew) or already streaming (user manually
 * retried) → noop return.
 *
 * Provider / systemPrompt / fallback resolution mirrors [sendMessage]
 * verbatim (incl. OAuth token refresh + Claude Code prefix), so a queued
 * prompt drain after cancel uses the same plumbing as a fresh send.
 */
internal fun AgentSession.resumeQueueAfterCancel() = startNextQueuedRequest()

/**
 * After the user stops a streaming turn, reconcile UI + agentHistory so
 * the conversation is valid on the next API call and resumable via
 * [resume]. Mirrors iOS AIAgentSession.handleUserCancelledCleanup
 * (Case 1: tool cancel, Case 2: text cancel).
 *
 *  - Case 1: any in-flight tool block is flipped to [ToolBlockStatus.CANCELLED]
 *    and a synthetic tool_result with [CANCELLED_MARKER] is persisted so
 *    tool_use/tool_result stays paired.
 *  - Case 2: if there was partial assistant text streamed (and no tool
 *    cancel), commit the partial text + a truncation `<system-reminder>`
 *    to agentHistory so the model knows the prior turn was cut short.
 *
 * Always sets [_canResume] = true when there is something to resume from.
 */
internal fun AgentSession.handleUserCancelledCleanup() {
    val msgs = _messages.value.toMutableList()
    val lastIdx = msgs.indexOfLast { it.role == "assistant" }
    if (lastIdx < 0) return
    var last = msgs[lastIdx]

    // T73: clear "Minis is thinking…" the moment the user taps Stop.
    // isAwaitingModelResponse is set true at runAgentLoop entry (≈ line
    // 2785) so the typing indicator shows during the initial request
    // gap before the first stream chunk. The cancel paths below didn't
    // reset it, so after Stop the indicator stayed live forever even
    // though the streamJob was already torn down. Reset before either
    // case runs so both tool-cancel and text-cancel paths benefit.
    if (last.isAwaitingModelResponse) {
        last = last.copy(isAwaitingModelResponse = false)
        msgs[lastIdx] = last
        _messages.value = msgs
    }

    // Case 1: cancel during tool execution. Flip in-flight tool blocks to
    // CANCELLED and persist matching tool_result rows.
    val cancelledIds = mutableListOf<Pair<String, String>>() // (toolUseId, toolName)
    val updatedBlocks = last.toolBlocks.map { b ->
        val s = b.toolStatus
        if (s == ToolBlockStatus.STREAMING || s == ToolBlockStatus.PENDING || s == ToolBlockStatus.RUNNING) {
            if (b.kind == "tool_use") cancelledIds.add(b.id to b.toolName)
            b.copy(toolStatus = ToolBlockStatus.CANCELLED)
        } else b
    }
    val hadInflightTools = cancelledIds.isNotEmpty()
    val pending = pendingAssistantTurn?.takeIf { it.assistantId == last.id }
    if (pending != null && pending.committed == null) {
        msgs[lastIdx] = last.copy(toolBlocks = updatedBlocks, isStreaming = false)
        _messages.value = msgs
        persistInterruptedMediaTurn(pending, updatedBlocks, cancelledIds)
        _canResume.value = true
        return
    }
    if (hadInflightTools) {
        msgs[lastIdx] = last.copy(toolBlocks = updatedBlocks)
        _messages.value = msgs
        val parts = cancelledIds.map { (id, name) ->
            AgentContentPart.ToolResult(
                id = id,
                name = name,
                content = CANCELLED_MARKER,
                isError = true,
            )
        }
        sessionScope.launch(Dispatchers.IO) {
            persistToolResultMessage(parts)
        }
        _canResume.value = true
        return
    }

    // Case 2: cancel during text streaming. If partial assistant text
    // exists and agentHistory does not already end with the assistant
    // turn we're on, commit the partial text + truncation marker so the
    // model sees an interrupted prior turn on the next call.
    val partialText = buildString {
        if (last.content.isNotEmpty()) append(last.content)
        for (b in last.toolBlocks) {
            if (b.kind == "text" && b.content.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append(b.content)
            }
        }
    }
    val historyEndsWithAssistant =
        agentHistory.lastOrNull()?.role == LLMMessage.Role.ASSISTANT

    // Case 0 (T-ios-stop-clear-thinking-and-partial — Android port):
    // Stop fired while still in the pre-first-chunk thinking gap (no
    // partial text, no tool_use emitted, no committed history for this
    // turn). The placeholder ChatMessage runAgentLoop pushed at L5248 is
    // not in the DB and would otherwise render as an empty "Minis" header
    // bubble with no body. Drop it so the UI snaps back to idle the
    // instant the user taps Stop. Mirrors the iOS #566/#569 boundary:
    // a candidate WITH real text or any emitted tool_use is kept (handled
    // by Case 1 / Case 2 below); a thinking-only placeholder is not.
    val hasAnyToolUse = last.toolBlocks.any { it.kind == "tool_use" }
    if (partialText.isEmpty() && !hasAnyToolUse && !historyEndsWithAssistant) {
        msgs.removeAt(lastIdx)
        _messages.value = msgs
        return
    }

    if (partialText.isNotEmpty() && !historyEndsWithAssistant) {
        val parts = listOf<AgentContentPart>(
            AgentContentPart.Text(partialText),
            AgentContentPart.Text(
                "<system-reminder>The user stopped this response. Content may be incomplete.</system-reminder>"
            ),
        )
        agentHistory.add(
            LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = partialText,
                contentParts = parts,
            )
        )
        sessionScope.launch(Dispatchers.IO) {
            val partsJson = buildAssistantPartsJson(parts)
            chatRepository.appendMessage(
                activeSessionId,
                "assistant",
                partsJson,
                modelSnapshot = modelAttributionSnapshot(currentProvider),
                requestRunId = activeBotRunId,
            )
        }
        _canResume.value = true
    } else if (historyEndsWithAssistant) {
        // Already committed (tool cancel path above handled or prior turn
        // wrote an assistant row). Still allow resume.
        _canResume.value = true
    }
}

/**
 * Build a JSON parts array matching the ChatRepository schema so a
 * committed interrupted-assistant turn round-trips across app restarts.
 * Only emits text parts — tool_use / tool_result paths are handled by
 * the existing persistence code in the agent loop.
 */
internal fun AgentSession.buildAssistantPartsJson(parts: List<AgentContentPart>): String =
    AssistantTurnCodec.encodeParts(parts)

internal fun AgentSession.persistInterruptedMediaTurn(
    pending: PendingAssistantTurn,
    blocks: List<AssistantBlock>,
    cancelledTools: List<Pair<String, String>>,
    userStopped: Boolean = true,
) {
    if (pendingMediaCommitJob?.isActive == true) return
    val history = pending.historyMessage ?: LLMMessage(LLMMessage.Role.ASSISTANT, "").also {
        pending.historyMessage = it
        agentHistory.add(it)
    }
    val snapshot = modelAttributionSnapshot(currentProvider)
    // Start the non-cancellable commit before returning from Stop. A new
    // send waits at ensureSession, so its row cannot overtake this reply.
    pendingMediaCommitJob = sessionScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
            try {
                val turn = AssistantTurnCodec.interrupted(
                    AssistantTurnCodec.build(blocks, pending.startIndex, pending.toolInputs, mediaStore.mediaBaseDir),
                    userStopped = userStopped,
                )
                val id = persistAssistantTurn(turn, null, modelSnapshot = snapshot, pendingTurn = pending)
                val committed = pending.committed?.turn ?: turn
                val results = cancelledTools.map { (toolId, name) ->
                    AgentContentPart.ToolResult(toolId, name,
                        if (userStopped) CANCELLED_MARKER else "Response interrupted before tool execution.", isError = true)
                }
                val resultId = if (results.isNotEmpty()) persistToolResultMessage(results) else null
                withContext(Dispatchers.Main) {
                    val index = agentHistory.indexOfFirst { it === history }
                    if (index >= 0) {
                        agentHistory[index] = history.copy(contentParts = committed.parts, dbMessageId = id)
                        if (results.isNotEmpty()) agentHistory.add(index + 1,
                            LLMMessage(LLMMessage.Role.USER, "", contentParts = results, dbMessageId = resultId))
                    }
                }
            } catch (error: Exception) {
                AppLogger.error(TAG_STREAM, "Failed to persist stopped media turn: ${error.message}")
                withContext(Dispatchers.Main) {
                    _messages.value = _messages.value.map {
                        if (it.id == pending.assistantId) it.copy(error = error.message ?: "Failed to save generated media") else it
                    }
                }
            }
        }
    }
}

/**
 * Resume an interrupted agent loop. Injects a `<system-reminder>` into
 * agentHistory so the model picks up where it left off, then re-enters
 * the agent loop in a fresh [execution] turn. Mirrors iOS
 * AIAgentSession.resume().
 *
 * Safe to call only when [canResume] is true and [isStreaming] is false.
 * Clears [_canResume] on entry so repeated taps don't stack.
 */
fun AgentSession.resume() {
    if (execution.isClosed || isMutating.value || _isCompacting.value || _isStreaming.value || !_canResume.value) return
    val provider = currentProvider ?: run {
        _error.value = context.getString(R.string.chat_no_provider)
        return
    }
    _isStreaming.value = true
    _canResume.value = false
    _resumeAfterCrash.value = false
    _error.value = null
    // [T-error-persist-android] resume() follows finalizeAtTurnLimit's
    // setInlineError (which persisted an error sticker on the last assistant
    // row). Clear it now so a successful resume doesn't merge-resurrect the
    // turn-limit banner on the next reload.
    AppLogger.info(TAG, "▶️ resume: continuing partial assistant message (no new header emitted)")
    // [T-android-tool-autoscroll] Start-of-turn snap. The thinking
    // placeholder is the only visible delta until the model's first
    // token, and the auto-follow tuple won't advance until content
    // streams — ChatScreen would otherwise leave the placeholder
    // behind the input bar.
    _forceScrollToBottom.tryEmit(Unit)

    launchAgentTurn {
        _canResume.value = false
        clearPersistedLastAssistantError()
        // If history ends with assistant (Case 2: text-cancel committed a
        // partial assistant turn), append a continue reminder as a user
        // message. If it ends with user tool_result (Case 1), it's already
        // a valid starting point for the next API call — no reminder needed.
        val historyEndsWithAssistant =
            agentHistory.lastOrNull()?.role == LLMMessage.Role.ASSISTANT
        if (historyEndsWithAssistant) {
            val reminder =
                "<system-reminder>The user stopped the previous response but now wants to continue. Pick up exactly where you left off.</system-reminder>"
            val parts = listOf<AgentContentPart>(AgentContentPart.Text(reminder))
            agentHistory.add(
                LLMMessage(
                    role = LLMMessage.Role.USER,
                    content = reminder,
                    contentParts = parts,
                )
            )
            withContext(Dispatchers.IO) {
                val partsJson = """[{"type":"text","value":${escapeJson(reminder)}}]"""
                chatRepository.appendMessage(activeSessionId, "user", partsJson)
            }
        }

        val baseSystemPrompt = buildSystemPrompt()
        val systemPrompt =
            if ((provider as? com.openminis.app.provider.anthropic.AnthropicProvider)?.isOAuth == true) {
                val prefix = com.openminis.app.auth.ClaudeOAuthManager.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT
                if (baseSystemPrompt?.startsWith(prefix) == true) baseSystemPrompt
                else "$prefix\n\n${baseSystemPrompt ?: ""}"
            } else baseSystemPrompt
        launch(Dispatchers.IO) {
            val sourceRunId = beginBotSourceRun()
            AppLogger.info(TAG_STREAM, "resume streamJob ENTER sid=$activeSessionId")
            try {
                SessionConcurrencyManager.acquireSlot(activeSessionId)
                AppLogger.debug(TAG_STREAM, "resume streamJob slot acquired")
                SessionActivityTracker.setActive(activeSessionId, onStop = { cancelStream() })
                val activeFallbackStrategy = providerRepository.config.value.fallbackTrigger
                val fallbackProviders = buildFallbackProviders(provider)
                var botTurnSucceeded = false
                try {
                    withBotTurnLock {
                        AppLogger.info(TAG_STREAM, "resume runAgentLoop CALL")
                        val outcome = runAgentLoop(
                            provider = provider,
                            systemPrompt = systemPrompt,
                            fallbackProviders = fallbackProviders,
                            fallbackStrategy = activeFallbackStrategy,
                        )
                        AppLogger.info(TAG_STREAM, "resume runAgentLoop RETURN normal")
                        botTurnSucceeded = outcome == AgentTurnOutcome.Completed
                        AppLogger.info(TAG_STREAM, "resume request settled")
                    }
                } catch (e: CancellationException) {
                    AppLogger.info(TAG_STREAM, "resume runAgentLoop CANCELLED")
                    Log.d(TAG, "Agent loop cancelled (resume)")
                } catch (e: Exception) {
                    AppLogger.error(TAG_STREAM, "resume runAgentLoop EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
                    Log.e(TAG, "Agent loop error (resume)", e)
                    setInlineError(e)
                } finally {
                    AppLogger.info(TAG_STREAM, "resume streamJob FINALLY enter")
                    // [T-android-overlay-reply-status-34599] Surface
                    // the assistant's most recent reply text to the
                    // overlay BEFORE setInactive so the post-completion
                    // overlay state (no-running, has-outcome) carries a
                    // non-null excerpt. Reading _messages here is safe:
                    // we're in the finally block of the agent loop and
                    // the stream has already flushed its last delta.
                    publishOverlayReplyExcerpt(activeSessionId)
                    SessionActivityTracker.setInactive(activeSessionId)
                    com.openminis.app.tools.android.DeviceScreenLease.shared.releaseSession(activeSessionId)
                    SessionConcurrencyManager.releaseSlot(activeSessionId)
                    com.openminis.app.tools.BotDelegationCoordinator.current()?.onSourceTurnSettled(activeSessionId, botTurnSucceeded, sourceRunId)
                    AppLogger.info(TAG_STREAM, "resume streamJob FINALLY exit")
                }
            } catch (e: CancellationException) {
                AppLogger.info(TAG_STREAM, "resume streamJob CANCELLED waiting for slot")
                Log.d(TAG, "Cancelled while waiting for concurrency slot (resume)")
                com.openminis.app.tools.BotDelegationCoordinator.current()?.onSourceTurnSettled(activeSessionId, false, sourceRunId)
            }
            AppLogger.info(TAG_STREAM, "resume streamJob EXIT")
        }
    }
}

/**
 * T-android-new-chat-empty-residue: when the user leaves the chat screen,
 * drop sessions that were materialised in the DB (e.g. via a thinking /
 * memory toggle in `ensureSession()`) but never received a real message.
 * Without this hook, tapping "New chat" → toggling a session-scoped
 * setting → exiting leaves an empty row at the top of the session list.
 *
 * Called from ChatScreen's onDispose. Gates:
 *   - realSessionId must be non-empty (a row was actually inserted)
 *   - not currently streaming (background agent work would be lost)
 *   - persisted message count == 0 (authoritative DB check — `_messages`
 *     also contains ephemeral system-info bubbles that aren't persisted,
 *     so a state-only check would over-count).
 *
 * Safe to call multiple times; the row-existence + count gates make it
 * idempotent. After deletion we release the cached VM so a stale entry
 * doesn't linger in `AgentSessionStore`.
 */
fun AgentSession.clearError() {
    _error.value = null
}

internal fun AgentSession.escapeJson(text: String): String {
    val sb = StringBuilder("\"")
    for (c in text) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> {
                if (c.code < 0x20) sb.append("\\u%04x".format(c.code))
                else sb.append(c)
            }
        }
    }
    sb.append("\"")
    return sb.toString()
}

/**
 * [T-eta-hosted-web-search] The localized name of a tool the provider ran itself; the kind is
 * the token the chunk carries, and anything this version does not know still gets a row.
 */
internal fun AgentSession.hostedToolLabelRes(kind: String): Int = when (kind) {
    "web_search" -> R.string.hosted_tool_web_search
    "file_search" -> R.string.hosted_tool_file_search
    "code_interpreter" -> R.string.hosted_tool_code_interpreter
    "computer" -> R.string.hosted_tool_computer
    "image_generation" -> R.string.hosted_tool_image_generation
    "mcp" -> R.string.hosted_tool_mcp
    else -> R.string.hosted_tool_unknown
}
