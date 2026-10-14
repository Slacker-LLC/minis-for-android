package com.openminis.app.agent

import com.openminis.app.ui.chat.*

import android.content.Context
import android.util.Log
import androidx.compose.material.icons.outlined.Build
import com.openminis.app.R
import com.openminis.app.agent.AgentTurnHandle
import com.openminis.app.agent.AgentTurnOutcome
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.service.SessionConcurrencyManager
import com.openminis.app.agent.AgentSession.Companion.IN_FLIGHT_TOOL_STATUSES
import com.openminis.app.agent.AgentSession.Companion.TAG
import com.openminis.app.agent.AgentSession.Companion.TAG_STREAM
import com.openminis.app.agent.AgentSession.FallbackCandidate
import com.openminis.app.agent.AgentSession.InjectedTurn
import com.openminis.app.agent.AgentSession.PreSendContextAction
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.openminis.app.data.repository.loadApiKey
import com.openminis.app.data.repository.saveApiKey

/**
 * Hold a prompt for the currently running agent loop. The message appears immediately in the chat with
 * isQueued=true. A [PendingDelivery.STEER] prompt is injected at the loop's next step boundary; a
 * [PendingDelivery.QUEUE] starts its own owner turn after the current owner settles.
 * Acceptance and delivery are persisted in the existing message row.
 */
fun AgentSession.enqueuePrompt(text: String, delivery: PendingDelivery = PendingDelivery.STEER) {
    enqueuePromptWithAttachments(text, delivery, _attachments.value, clearComposer = true)
}

/** Spoken follow-ups own only their text, never the user's unsent composer attachments. */
internal suspend fun AgentSession.enqueueVoicePrompt(text: String): String {
    val accepted = kotlinx.coroutines.CompletableDeferred<String>()
    enqueuePromptWithAttachments(text, PendingDelivery.STEER, emptyList(), clearComposer = false, accepted = accepted)
    return accepted.await()
}

/** A delegating agent owns only this text; process recovery cannot promote it to a human input. */
internal suspend fun AgentSession.enqueueAgentPrompt(text: String): String {
    val accepted = kotlinx.coroutines.CompletableDeferred<String>()
    enqueuePromptWithAttachments(text, PendingDelivery.STEER, emptyList(), clearComposer = false,
        accepted = accepted, userInput = false)
    return accepted.await()
}

private fun AgentSession.enqueuePromptWithAttachments(
    text: String,
    delivery: PendingDelivery,
    pendingAttachments: List<InputAttachment>,
    clearComposer: Boolean,
    accepted: kotlinx.coroutines.CompletableDeferred<String>? = null,
    userInput: Boolean = true,
) {
    val trimmed = text.trim()
    if (execution.isClosed) { accepted?.completeExceptionally(IllegalStateException("session_closed")); return }
    if (trimmed.isBlank() && pendingAttachments.isEmpty()) { accepted?.completeExceptionally(IllegalArgumentException("empty_prompt")); return }
    val pastedSnapshot = if (clearComposer) _pastedTexts.value else emptyList()
    val task = activeGoalTask.takeIf { delivery == PendingDelivery.STEER }
    val preparation = sessionScope.launch {
        requestCommitMutex.lock()
        try {
            val sid = ensureSession()
            val prepared = attachmentPreparer.prepare(pendingAttachments, sid)
            check(prepared.mediaRefPartsJson.size == pendingAttachments.size) { "request_attachment_unavailable" }
            val pasted = PastedTextProcessor.processPastedParts(trimmed, pastedSnapshot, sid, mediaStore)
            val parts = buildUserPartsJson(trimmed, prepared.mediaRefPartsJson, prepared.attachedFilesXml, pasted?.partsJson)
            val row = PastedTextProcessor.commitMessage(pasted,
                persist = { chatRepository.appendMessage(sid, "user", parts,
                    idempotencyKey = java.util.UUID.randomUUID().toString(), requestState = "PENDING",
                    requestDelivery = delivery.name, requestTaskId = task?.id,
                    requestTaskGeneration = task?.stopGeneration, requestUserInput = userInput) },
                consume = { ids -> if (clearComposer) _pastedTexts.value = _pastedTexts.value.filterNot { it.id in ids } })
            if (!_isStreaming.value && delivery == PendingDelivery.STEER)
                chatRepository.dao.setPendingRequestState(sid, row.id, "NEEDS_REVIEW")
            accepted?.complete(row.id)
            val bubble = messageMapper.toChatMessages(listOf(row), mapOf(row.id to Result.success(org.json.JSONArray(parts)))).single()
            _messages.value = _messages.value + bubble
            sessionEventEmitter.messageCreated(bubble)
            restorePendingRequests()
            if (clearComposer) _attachments.value = _attachments.value.filterNot { it in pendingAttachments }
            if (!_isStreaming.value) startNextQueuedRequest()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { accepted?.completeExceptionally(error); setInlineError(error.message ?: "request_commit_failed") }
        finally { requestCommitMutex.unlock() }
    }
    preparation.invokeOnCompletion { cause ->
        if (cause != null) accepted?.completeExceptionally(cause)
    }
}

/** Changing delivery is explicit authorization of this saved input, including after restart. */
fun AgentSession.setQueuedDelivery(messageId: String, delivery: PendingDelivery) {
    sessionScope.launch {
        val task = if (delivery == PendingDelivery.STEER) com.openminis.app.tools.GoalTools.currentTask(context, activeSessionId) else null
        val actualDelivery = if (!_isStreaming.value) PendingDelivery.QUEUE else delivery
        if (chatRepository.dao.authorizePendingRequest(activeSessionId, messageId, actualDelivery.name, task?.id, task?.stopGeneration) != 1) return@launch
        restorePendingRequests()
        _messages.value = _messages.value.map { if (it.id == messageId) it.copy(queuedDelivery = actualDelivery) else it }
        if (!_isStreaming.value) startNextQueuedRequest()
    }
}

fun AgentSession.removeQueuedPrompt(promptId: String) = withdrawQueuedMessage(promptId)

/** Withdraw commits first; it cannot remove an already claimed request. */
fun AgentSession.withdrawQueuedMessage(messageId: String) {
    sessionScope.launch {
        if (chatRepository.dao.setPendingRequestState(activeSessionId, messageId, "WITHDRAWN") != 1) return@launch
        restorePendingRequests()
        _messages.value = _messages.value.filterNot { it.id == messageId }
    }
}

internal suspend fun AgentSession.injectQueuedPromptsAsNewTurn(
    finishedAssistantId: String,
    finishedAccumulatedText: String,
    finishedAllToolBlocks: List<AssistantBlock>,
): InjectedTurn? {
    val runId = activeRunId ?: return null
    val rows = chatRepository.dao.pendingRequests(activeSessionId).filter {
        it.requestState == "PENDING" && it.requestDelivery == "STEER"
    }
    if (rows.isEmpty()) return null
    val claimed = rows.mapNotNull { claimPendingRequest(it, runId, steering = true) }
    if (claimed.isEmpty()) return null
    val bridge = "(The user added instructions to the current task. Preserve the prior objective unless explicitly changed.)"
    agentHistory.add(LLMMessage(LLMMessage.Role.ASSISTANT, bridge,
        contentParts = listOf(AgentContentPart.Text(bridge))))
    claimed.forEach { agentHistory.add(requestPayload(it)) }
    val newAssistantId = "assistant_${java.util.UUID.randomUUID()}"
    withContext(Dispatchers.Main) {
        updateAssistantMessage(finishedAssistantId, finishedAccumulatedText, false, finishedAllToolBlocks, isAwaitingModelResponse = false)
        val next = ChatMessage(id = newAssistantId, role = "assistant", content = "", isStreaming = true,
            isAwaitingModelResponse = true, thinkingLevel = _thinkingLevel.value)
        _messages.value = _messages.value + next
        sessionEventEmitter.messageCreated(next)
    }
    return InjectedTurn(newAssistantId)
}

fun AgentSession.sendMessage(text: String) = sendMessage(takeQuotedText(text), skipContextCheck = false)

/** Send [text]; while the agent is working it is held and delivered as [delivery] says. */
fun AgentSession.sendMessage(text: String, delivery: PendingDelivery) =
    sendMessage(takeQuotedText(text), skipContextCheck = false, delivery = delivery)

/**
 * A programmatic prompt (background callback, routine, RPC). It owns exactly [text] and
 * [attachments]: the user's composer — attachments being prepared, a message being edited, pasted
 * snippets — is not read, sent or cleared by it.
 */
internal fun AgentSession.submitPrompt(text: String, attachments: List<InputAttachment> = emptyList(), taskId: String? = null, requestId: String = java.util.UUID.randomUUID().toString(), userInput: Boolean = false): AgentTurnHandle {
    val handle = AgentTurnHandle(taskId, requestId, userInput)
    sendMessage(text, skipContextCheck = false, request = handle, ownAttachments = attachments)
    return handle
}

/**
 * @param skipContextCheck set by the pre-send context dialog's own actions,
 *   which have already made the compact decision. Without it the re-entrant
 *   send would re-evaluate the same (still stale until the next usage
 *   chunk) token count and pop the dialog again — iOS guards the identical
 *   re-entry with `skipCompactCheck`.
 */
internal fun AgentSession.sendMessage(
    text: String,
    skipContextCheck: Boolean,
    request: AgentTurnHandle? = null,
    ownAttachments: List<InputAttachment> = emptyList(),
    delivery: PendingDelivery = PendingDelivery.STEER,
    storedRequest: com.openminis.app.data.db.MessageEntity? = null,
) {
    if (execution.isClosed) { request?.reject("session_closed"); return }
    if (isMutating.value) { request?.reject("session_mutating"); return }
    val trimmed = text.trim()
    // Only submitPrompt passes a request; such a send carries its own input (see submitPrompt).
    val programmatic = request != null
    // While streaming, enqueue instead of silently dropping (iOS: send vs enqueuePrompt).
    if (_isStreaming.value) {
        if (request != null) request.reject("session_busy") else enqueuePrompt(text, delivery)
        return
    }
    // T180: allow attachments-only sends (no caption). Mirrors iOS, where
    // an empty text + non-empty attachments still produces a valid user
    // message. Without this an image-only "look at this" send dropped.
    if (storedRequest == null && trimmed.isBlank() && (if (programmatic) ownAttachments else _attachments.value).isEmpty()) {
        request?.reject("empty_prompt")
        return
    }
    if (_isCompacting.value) {
        request?.reject("session_compacting")
        appendSystemInfo(
            text = context.getString(R.string.compact_wait_send),
            iconKind = "compact",
        )
        return
    }
    // Context pressure check. Unlike before, needsCompact now HOLDS the
    // send: either compact silently (auto-compact on) or ask first. The
    // whole point is that the request which tripped the threshold must not
    // be the one that goes out over-length.
    if (!skipContextCheck) {
        val contextAction = try { checkContextBeforeSend() }
        catch (error: com.openminis.app.tools.ToolCheckpointStore.Unavailable) {
            request?.reject(error.message ?: "tool_checkpoint_unavailable")
            setInlineError(error)
            return
        }
        when (contextAction) {
            PreSendContextAction.PROCEED -> {}
            PreSendContextAction.COMPACT_THEN_SEND -> {
                if (request != null) {
                    request.reject("context_compaction_required")
                    return
                }
                pendingSendText = text
                _inputText.value = ""
                compactAndSendPending()
                return
            }
            PreSendContextAction.ASK_USER -> {
                if (request != null) {
                    request.reject("context_compaction_required")
                    return
                }
                // Park the text on the VM (not the composer) so the dialog
                // owns it; cancelCompactBeforeSend puts it back.
                pendingSendText = text
                _inputText.value = ""
                _showCompactBeforeSendPrompt.value = true
                return
            }
        }
    }
    // A fresh send supersedes any pending resume — mirror iOS which clears
    // canResume at the top of send().
    _canResume.value = false
    _resumeAfterCrash.value = false
    // T185: clear the share-injected flag the moment the user actually
    // sends. Without this, the "Move to…" capsule (gated on
    // hasInjectedShareContent) keeps floating over the user-message row
    // after the share content has been committed — it then visually
    // collides with the user-attachment chips, which renders as the
    // "image attachment shows up as Move to" symptom in T185. Mirrors
    // iOS AIChatView.swift:2255 (`hasInjectedShareContent = false`
    // inside the send button's tap closure).
    if (!programmatic && _hasInjectedShareContent.value) _hasInjectedShareContent.value = false

    val initialProvider = currentProvider
    if (initialProvider == null) {
        _error.value = context.getString(R.string.chat_no_provider)
        request?.reject("no_provider_configured")
        return
    }
    var provider: LLMProvider = initialProvider

    _error.value = null

    val currentAttachments = if (programmatic) {
        ownAttachments
    } else {
        _attachments.value
    }

    // T145: claim _isStreaming synchronously so a rapid second tap can't
    // slip past the entry guard during DB/OAuth setup. See retryFromMessage.
    AppLogger.info(TAG_STREAM, "send _isStreaming=true (sync, sid=$activeSessionId)")
    _isStreaming.value = true

    // [T-android-thinking-indicator-linger] Invariant sweep: a fresh send
    // only reaches here when no turn is streaming (the _isStreaming guard
    // at the top routes mid-stream sends to enqueuePrompt). So any residual
    // _streamingById entry is an orphan stranded by a prior turn that
    // exited without draining it (e.g. a late delta re-added the entry
    // after finalizeAtTurnLimit / cancel cleared it). mergeStreamingOverlay
    // forces isStreaming=true on any message holding such an entry, so an
    // orphan would render a second "thinking" row alongside the new turn's.
    // Flush them into the canonical messages (isStreaming=false) before the
    // new streaming message is created — no two messages ever stream at once.
    if (_streamingById.value.isNotEmpty()) {
        AppLogger.warning(TAG_STREAM, "send: sweeping ${_streamingById.value.size} orphan streaming delta(s) before new turn")
        flushAllStreamingDeltas()
    }

    // T187: when the user is editing a previous message, truncate the
    // conversation from that message (inclusive) before persisting the
    // edited text as a fresh user turn. Snapshot + clear the id here so
    // any error in the truncate path doesn't leave the composer stuck
    // in edit mode.
    // A programmatic send never takes over the user's edit: it would truncate history at the
    // message being edited and replace it with text the user never wrote.
    val editingId = if (programmatic) null else _editingMessageId.value
    if (editingId != null) _editingMessageId.value = null

    val acceptedTurn = request ?: AgentTurnHandle(userInput = true)
    launchAgentTurn(acceptedTurn) {
        try {
        // Ensure session exists in DB (creates on first message for draft sessions)
        val activeSessionId = ensureSession()

        if (editingId != null) {
            truncateBeforeEdit(editingId)
        }

        mergeExternalHistory()
        val persistedUser = if (storedRequest != null) storedRequest else {
            val prepared = attachmentPreparer.prepare(currentAttachments, activeSessionId)
            check(prepared.mediaRefPartsJson.size == currentAttachments.size) { "request_attachment_unavailable" }
            val pasted = if (programmatic) null else buildPastedParts(trimmed, activeSessionId)
            val parts = buildUserPartsJson(trimmed, prepared.mediaRefPartsJson, prepared.attachedFilesXml, pasted?.partsJson)
            PastedTextProcessor.commitMessage(pasted,
                persist = { chatRepository.appendMessage(activeSessionId, "user", parts,
                    idempotencyKey = acceptedTurn.requestId, requestState = "PENDING", requestDelivery = "START",
                    requestTaskId = acceptedTurn.taskId,
                    requestTaskGeneration = acceptedTurn.taskId?.let { (context.applicationContext as com.openminis.app.MinisApp).agentTaskRepository.get(it)?.stopGeneration },
                    requestUserInput = acceptedTurn.userInput) },
                consume = { ids -> _pastedTexts.value = _pastedTexts.value.filterNot { it.id in ids } })
        }
        acceptedTurn.markAccepted(persistedUser.id)
        if (_messages.value.none { it.id == persistedUser.id }) {
            val bubble = messageMapper.toChatMessages(listOf(persistedUser), mapOf(persistedUser.id to Result.success(org.json.JSONArray(persistedUser.partsJson)))).single()
            _messages.value = _messages.value + bubble
            sessionEventEmitter.messageCreated(bubble)
        }
        if (!programmatic) _attachments.value = _attachments.value.filterNot { it in currentAttachments }

        // The provider this chat talks to, for the agent's provider_quota tool.
        com.openminis.app.provider.quota.ActiveChatProvider.set(
            activeSessionId,
            _activeEntryId.value?.let { id -> providerRepository.config.value.modelEntries.find { it.id == id }?.providerInstanceId },
        )

        check(persistedUser.requestState == "PENDING" && persistedUser.requestRunId == null) { "request_already_claimed" }
        requestPayload(persistedUser)

        // Refresh OAuth token if needed before sending (mirrors iOS validAccessToken)
        if ((provider as? com.openminis.app.provider.anthropic.AnthropicProvider)?.isOAuth == true) {
            try {
                val activeEntryId = _activeEntryId.value
                val entry = activeEntryId?.let { id -> providerRepository.config.value.modelEntries.find { it.id == id } }
                val instance = entry?.let { e -> providerRepository.config.value.instances.find { it.id == e.providerInstanceId } }
                if (instance != null) {
                    val manager = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
                    val freshToken = manager?.validAccessToken()
                    if (freshToken != null) {
                        val storedKey = providerRepository.loadApiKey(instance.id)
                        if (freshToken != storedKey) {
                            providerRepository.saveApiKey(instance.id, freshToken)
                            // Recreate provider with fresh token
                            provider = com.openminis.app.provider.ProviderFactory.create(
                                instance, freshToken, currentModel ?: provider.model, context
                            )
                            currentProvider = provider
                            android.util.Log.i(TAG, "OAuth token refreshed before send")
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "OAuth token refresh failed: ${e.message}")
            }
        }

        // Build system prompt
        // Anthropic OAuth requires the Claude Code prefix in the system prompt
        val baseSystemPrompt = buildSystemPrompt()
        val systemPrompt = if ((provider as? com.openminis.app.provider.anthropic.AnthropicProvider)?.isOAuth == true) {
            val prefix = com.openminis.app.auth.ClaudeOAuthManager.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT
            if (baseSystemPrompt?.startsWith(prefix) == true) baseSystemPrompt
            else "$prefix\n\n${baseSystemPrompt ?: ""}"
        } else baseSystemPrompt

        // Start agent loop with fallback. _isStreaming was set synchronously at top.
        launch(Dispatchers.IO) {
            val sourceRunId = beginSourceRun(acceptedTurn)
            val claimed = claimPendingRequest(persistedUser, sourceRunId, steering = false)
            check(claimed != null) { "request_not_claimed" }
            agentHistory.add(requestPayload(claimed))
            AppLogger.info(TAG_STREAM, "send streamJob ENTER sid=$activeSessionId")
            try {
                // Acquire concurrency slot (suspends if at max)
                SessionConcurrencyManager.acquireSlot(activeSessionId)
                AppLogger.debug(TAG_STREAM, "send streamJob slot acquired")
                SessionActivityTracker.setActive(activeSessionId, onStop = { cancelStream() })

                // Resolve the active group's fallback strategy
                val activeFallbackStrategy = providerRepository.config.value.fallbackTrigger

                // Build full fallback provider list upfront (mirrors iOS triedEntries approach)
                val fallbackProviders = buildFallbackProviders(provider)

                try {
                    withSessionTurnLock {
                        AppLogger.info(TAG_STREAM, "send runAgentLoop CALL")
                        val outcome = runAgentLoop(
                            provider = provider,
                            systemPrompt = systemPrompt,
                            fallbackProviders = fallbackProviders,
                            fallbackStrategy = activeFallbackStrategy,
                        )
                        AppLogger.info(TAG_STREAM, "send runAgentLoop RETURN normal")
                        acceptedTurn.record(outcome)
                        AppLogger.info(TAG_STREAM, "send request settled")
                    }
                } catch (e: CancellationException) {
                    acceptedTurn.record(AgentTurnOutcome.Cancelled)
                    AppLogger.info(TAG_STREAM, "send runAgentLoop CANCELLED")
                    Log.d(TAG, "Agent loop cancelled")
                } catch (e: Exception) {
                    acceptedTurn.record(AgentTurnOutcome.Failed(e.message ?: "Unknown error"))
                    AppLogger.error(TAG_STREAM, "send runAgentLoop EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
                    Log.e(TAG, "Agent loop error (all fallbacks exhausted)", e)
                    setInlineError(e)
                    // T298: completion notifier should show the ❌ variant.
                    SessionActivityTracker.markStreamError(activeSessionId)
                } finally {
                    chatOnlyForNextTurn = false
                    AppLogger.info(TAG_STREAM, "send streamJob FINALLY enter")
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
                    AppLogger.info(TAG_STREAM, "send streamJob FINALLY exit")
                }
            } catch (e: CancellationException) {
                acceptedTurn.record(AgentTurnOutcome.Cancelled)
                AppLogger.info(TAG_STREAM, "send streamJob CANCELLED waiting for slot")
                Log.d(TAG, "Cancelled while waiting for concurrency slot")
            }
            AppLogger.info(TAG_STREAM, "send streamJob EXIT")
        }
        } catch (e: CancellationException) {
            acceptedTurn.record(AgentTurnOutcome.Cancelled)
            throw e
        } catch (e: Exception) {
            acceptedTurn.record(AgentTurnOutcome.Failed(e.message ?: "message_setup_failed"))
            setInlineError(e.message ?: "message_setup_failed")
        }
    }
}

/** Set error inline on the last assistant message (iOS: message.error).
 *
 *  Also clears [ChatMessage.isAwaitingModelResponse] — without this, an
 *  exception thrown after a tool turn (which sets isAwaitingModelResponse=
 *  true at runAgentLoop ~4015) leaves the "Minis is thinking" indicator
 *  on screen even though streaming is over. The flag is per-message and
 *  is not implicitly cleared by isStreaming=false. */
internal fun AgentSession.setInlineError(errorText: String) {
    // [T-error-persist-android] Never let an empty/blank error string reach
    // the banner. The UI gate is `message.error?.let { … }` — a non-null ""
    // would render an EMPTY error banner, and (now that errors persist) it
    // would stick across reloads. An exception with a blank `message`
    // (`e.message ?: "Unknown error"` only guards null, not "") is the
    // realistic source. Coalesce to a generic non-empty message.
    val safeError = errorText.ifBlank { context.getString(R.string.error_empty_response_generic) }
    // T-streaming-side-channel: before mutating the canonical message,
    // drain any in-flight streaming delta so the error frame carries
    // the actual accumulated content (otherwise the user sees content
    // snap back to a pre-stream prefix when the error banner appears).
    flushAllStreamingDeltas()
    val msgs = _messages.value.toMutableList()
    val lastAssistantIdx = msgs.indexOfLast { it.role == "assistant" }
    if (lastAssistantIdx >= 0) {
        val msg = msgs[lastAssistantIdx]
        msgs[lastAssistantIdx] = msg.copy(
            error = safeError,
            isStreaming = false,
            isAwaitingModelResponse = false,
            updatedAtMs = System.currentTimeMillis(),
        )
        _messages.value = msgs
        // [T-error-persist-android] Persist the terminal error onto the
        // session's last assistant DB row so the inline error + Retry button
        // survive a session reload. This is a targeted UPDATE (not a fresh
        // insert): the in-memory bubble id differs from the persisted row id,
        // so we address the row by "last assistant" — matching the load-side
        // merge that keeps the last assistant row's identity. No-op when the
        // failing turn never persisted a row (first-turn failure).
        val sid = realSessionId.ifEmpty { sessionId }
        if (sid.isNotEmpty()) {
            sessionScope.launch(Dispatchers.IO) {
                try { chatRepository.updateLastAssistantError(sid, safeError) }
                catch (e: Exception) { Log.w(TAG, "persist error_info failed: ${e.message}") }
            }
        }
    } else {
        // No assistant message yet — fall back to top-level error
        _error.value = safeError
    }
}

/**
 * Show a transient error on the last assistant message while keeping isStreaming=true
 * so the "thinking" indicator and streaming UI stay intact during auto-retry countdowns.
 * Mirrors iOS streamWithAutoRetry: `chatMessage?.error = desc` without dropping the loop.
 */
internal fun AgentSession.setTransientInlineError(errorText: String) {
    val msgs = _messages.value.toMutableList()
    val lastAssistantIdx = msgs.indexOfLast { it.role == "assistant" }
    if (lastAssistantIdx < 0) return
    val msg = msgs[lastAssistantIdx]
    msgs[lastAssistantIdx] = msg.copy(error = errorText)
    _messages.value = msgs
}

/** Clear any inline error on the last assistant message (used after successful retry). */
internal fun AgentSession.clearInlineError() {
    val msgs = _messages.value.toMutableList()
    val lastAssistantIdx = msgs.indexOfLast { it.role == "assistant" }
    if (lastAssistantIdx < 0) return
    val msg = msgs[lastAssistantIdx]
    if (msg.error == null) return
    msgs[lastAssistantIdx] = msg.copy(error = null)
    _messages.value = msgs
    // [T-error-persist-android] Clear the persisted sticker too, so a
    // recovered turn doesn't resurrect the error banner on the next reload.
    // Clear by the message's source DB rows when known (the in-memory bubble
    // maps to one or more persisted rows via sourceDbIds); fall back to the
    // last-assistant-row update otherwise.
    val sid = realSessionId.ifEmpty { sessionId }
    if (sid.isNotEmpty()) {
        val dbIds = msg.sourceDbIds
        sessionScope.launch(Dispatchers.IO) {
            try {
                if (dbIds.isNotEmpty()) {
                    dbIds.forEach { chatRepository.updateMessageErrorInfo(it, null) }
                } else {
                    chatRepository.updateLastAssistantError(sid, null)
                }
            } catch (e: Exception) { Log.w(TAG, "clear error_info failed: ${e.message}") }
        }
    }
}

/**
 * [T-error-persist-android] Clear and await the persisted error
 * sticker on the session's last assistant row. Called from the resume / retry
 * entrypoints that drop the in-memory error but don't go through
 * [clearInlineError], so a recovered turn can't merge-resurrect the old
 * banner on the next reload. No-op when there's no session/row yet.
 */
internal suspend fun AgentSession.clearPersistedLastAssistantError() {
    val sid = realSessionId.ifEmpty { sessionId }
    if (sid.isEmpty()) return
    withContext(Dispatchers.IO) {
        try { chatRepository.updateLastAssistantError(sid, null) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { Log.w(TAG, "clear error_info (persisted) failed: ${e.message}") }
    }
}

fun AgentSession.retryLast() {
    val now = android.os.SystemClock.uptimeMillis()
    if (execution.isClosed || isMutating.value || _isCompacting.value || _isStreaming.value || now - lastRetryTimestamp < 800L) return
    lastRetryTimestamp = now
    val initialProvider = currentProvider ?: return
    var provider: LLMProvider = initialProvider
    _error.value = null

    // T145: claim _isStreaming synchronously — see retryFromMessage for rationale.
    AppLogger.info(TAG_STREAM, "retryLast _isStreaming=true (sync, sid=$activeSessionId)")
    _isStreaming.value = true

    launchAgentTurn launch@ {
        // T-streaming-side-channel: belt-and-suspenders flush in case any
        // delta survived an earlier abnormal exit; retryLast is gated on
        // !isStreaming so this is normally a no-op.
        flushAllStreamingDeltas()
        val msgs = _messages.value.toMutableList()
        val lastAssistantIdx = msgs.indexOfLast { it.role == "assistant" }
        if (lastAssistantIdx < 0) return@launch
        // [T-android-tool-autoscroll] Start-of-turn snap — see resume().
        _forceScrollToBottom.tryEmit(Unit)

        // 1. Keep the assistant message; clear error + streaming flags + drop
        //    in-flight tool blocks. Clean any preceding empty ghost assistant messages.
        val lastMsg = msgs[lastAssistantIdx]
        val targetAssistantId = lastMsg.id
        val keptToolBlocks = lastMsg.toolBlocks.filter { block ->
            block.toolStatus !in IN_FLIGHT_TOOL_STATUSES
        }
        val cleanedMsgs = msgs.filterIndexed { index, m ->
            index == lastAssistantIdx || !(m.role == "assistant" && m.content.isBlank() && m.toolBlocks.isEmpty() && m.error == null && !m.isStreaming)
        }.toMutableList()
        val updatedIdx = cleanedMsgs.indexOfFirst { it.id == targetAssistantId }
        if (updatedIdx >= 0) {
            cleanedMsgs[updatedIdx] = lastMsg.copy(
                content = "",
                error = null,
                isStreaming = true,
                isAwaitingModelResponse = true,
                toolBlocks = keptToolBlocks,
            )
        }
        _messages.value = cleanedMsgs
        // [T-error-persist-android] Clear the persisted error sticker on the last
        // assistant row up-front. The DB-sync below only DELETES the trailing
        // assistant row when a trailing assistant was popped (Case A); in the
        // Case B path (tail = user(tool_result), next LLM call errored) the
        // stamped row is an EARLIER completed turn that is NOT deleted, so
        // without this clear the new successful turn would merge-resurrect the
        // old error banner on reload (msg.error ?: prev.error). Harmless in
        // Case A too — the row is deleted moments later regardless.
        clearPersistedLastAssistantError()

        // 2. Pop ONLY a trailing assistant entry from agentHistory (mirrors
        //    iOS retry() :2107-2109). If the tail is already user(tool_result),
        //    the next-turn LLM call errored — leave history alone.
        val poppedAssistant = if (agentHistory.lastOrNull()?.role == LLMMessage.Role.ASSISTANT) {
            val last = agentHistory.removeAt(agentHistory.size - 1)
            last
        } else null

        // 3. GC orphaned tool_result parts whose tool_use is gone (mirrors
        //    iOS retry() :2114-2128). Walks backward so removeAt is safe.
        val liveToolUseIds = agentHistory.flatMap { m ->
            m.contentParts.filterIsInstance<AgentContentPart.ToolUse>().map { it.id }
        }.toSet()
        for (i in agentHistory.indices.reversed()) {
            val m = agentHistory[i]
            if (m.role != LLMMessage.Role.USER) continue
            val cleanedParts = m.contentParts.filter { p ->
                p !is AgentContentPart.ToolResult || p.id in liveToolUseIds
            }
            when {
                cleanedParts.isEmpty() && m.contentParts.isNotEmpty() ->
                    agentHistory.removeAt(i)
                cleanedParts.size < m.contentParts.size ->
                    agentHistory[i] = m.copy(contentParts = cleanedParts)
            }
        }

        var streamLaunched = false
        try {
        val sid = realSessionId.takeIf { it.isNotEmpty() } ?: sessionId

        // T258: only sync the DB when step 2 popped a trailing assistant
        // entry from agentHistory. In that case the persisted partial-
        // assistant row would resurrect the failed turn on next session
        // load — drop it (and only it) by deleting from its sort_order.
        // Completed assistant + tool_result rows for earlier turns are
        // unchanged and stay persisted, so retry preserves their cards.
        // toolLoopDetector keeps its accumulated state — completed tools
        // shouldn't be unlearned just because the next turn errored.
        if (poppedAssistant != null) {
            val dbMessages = chatRepository.loadMessages(sid)
            val trailingAssistantSortOrder = dbMessages
                .lastOrNull { it.role == "assistant" }?.sortOrder
            if (trailingAssistantSortOrder != null) {
                chatRepository.deleteMessagesAfter(sid, trailingAssistantSortOrder)
                AppLogger.info(
                    TAG_STREAM,
                    "retryLast: deleted trailing assistant row sortOrder=$trailingAssistantSortOrder, kept ${trailingAssistantSortOrder} prior rows",
                )
            }
        } else {
            AppLogger.info(
                TAG_STREAM,
                "retryLast: retaining completed turns; checking persisted tool pairing",
            )
        }
        // The in-memory orphan sweep above does not repair persisted rows.
        // Keep unrelated parts and remove only results without a preceding call.
        val persistedCalls = mutableSetOf<String>()
        for (row in chatRepository.loadMessages(sid)) {
            val parts = org.json.JSONArray(row.partsJson)
            val kept = org.json.JSONArray()
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                val value = part.optJSONObject("value")
                val type = part.optString("type")
                if (row.role == "assistant" && type == "toolUse") {
                    value?.optString("toolUseId")?.takeIf { it.isNotBlank() }?.let(persistedCalls::add)
                }
                if (row.role == "user" && type == "toolResult" &&
                    value?.optString("toolUseId") !in persistedCalls) continue
                kept.put(part)
            }
            if (kept.length() != parts.length()) {
                if (kept.length() == 0) chatRepository.deleteSingleMessage(row.id)
                else chatRepository.updateMessageParts(row.id, kept.toString())
            }
        }

        // Refresh OAuth token if needed
        if ((provider as? com.openminis.app.provider.anthropic.AnthropicProvider)?.isOAuth == true) {
            try {
                val activeEntryId = _activeEntryId.value
                val entry = activeEntryId?.let { id -> providerRepository.config.value.modelEntries.find { it.id == id } }
                val instance = entry?.let { e -> providerRepository.config.value.instances.find { it.id == e.providerInstanceId } }
                if (instance != null) {
                    val manager = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
                    val freshToken = manager?.validAccessToken()
                    if (freshToken != null) {
                        val storedKey = providerRepository.loadApiKey(instance.id)
                        if (freshToken != storedKey) {
                            providerRepository.saveApiKey(instance.id, freshToken)
                            provider = ProviderFactory.create(instance, freshToken, currentModel ?: provider.model, context)
                            currentProvider = provider
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "OAuth token refresh failed: ${e.message}")
            }
        }

        val baseSystemPrompt = buildSystemPrompt()
        val systemPrompt = if ((provider as? com.openminis.app.provider.anthropic.AnthropicProvider)?.isOAuth == true) {
            val prefix = com.openminis.app.auth.ClaudeOAuthManager.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT
            if (baseSystemPrompt?.startsWith(prefix) == true) baseSystemPrompt
            else "$prefix\n\n${baseSystemPrompt ?: ""}"
        } else baseSystemPrompt

        // _isStreaming was already set synchronously at the top.
        streamLaunched = true
        launch(Dispatchers.IO) {
            val sourceRunId = beginSourceRun()
            AppLogger.info(TAG_STREAM, "retryLast streamJob ENTER sid=$activeSessionId")
            try {
                SessionConcurrencyManager.acquireSlot(activeSessionId)
                AppLogger.debug(TAG_STREAM, "retryLast streamJob slot acquired")
                SessionActivityTracker.setActive(activeSessionId, onStop = { cancelStream() })
                val activeFallbackStrategy = providerRepository.config.value.fallbackTrigger
                val fallbackProviders = buildFallbackProviders(provider)
                try {
                    withSessionTurnLock {
                        AppLogger.info(TAG_STREAM, "retryLast runAgentLoop CALL")
                        val outcome = runAgentLoop(
                            provider = provider,
                            systemPrompt = systemPrompt,
                            fallbackProviders = fallbackProviders,
                            fallbackStrategy = activeFallbackStrategy,
                            reusingAssistantId = targetAssistantId,
                        )
                        AppLogger.info(TAG_STREAM, "retryLast runAgentLoop RETURN normal")
                        AppLogger.info(TAG_STREAM, "retryLast request settled")
                    }
                } catch (e: CancellationException) {
                    AppLogger.info(TAG_STREAM, "retryLast runAgentLoop CANCELLED")
                    Log.d(TAG, "Agent loop cancelled")
                } catch (e: Exception) {
                    AppLogger.error(TAG_STREAM, "retryLast runAgentLoop EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
                    Log.e(TAG, "Agent loop error (retryLast)", e)
                    setInlineError(e)
                    // T298: completion notifier should show the ❌ variant.
                    SessionActivityTracker.markStreamError(activeSessionId)
                } finally {
                    AppLogger.info(TAG_STREAM, "retryLast streamJob FINALLY enter")
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
                    AppLogger.info(TAG_STREAM, "retryLast streamJob FINALLY exit")
                }
            } catch (e: CancellationException) {
                AppLogger.info(TAG_STREAM, "retryLast streamJob CANCELLED waiting for slot")
                Log.d(TAG, "Cancelled while waiting for concurrency slot")
            }
            AppLogger.info(TAG_STREAM, "retryLast streamJob EXIT")
        }
        } finally {
            if (!streamLaunched && coroutineContext.isActive) {
                AppLogger.info(TAG_STREAM, "retryLast setup aborted")
            }
        }
    }
}
