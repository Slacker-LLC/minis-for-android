package com.openminis.app.ui.chat

import android.content.Context
import android.util.Log
import androidx.compose.material.icons.outlined.Build
import androidx.lifecycle.viewModelScope
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
import com.openminis.app.ui.chat.ChatViewModel.Companion.IN_FLIGHT_TOOL_STATUSES
import com.openminis.app.ui.chat.ChatViewModel.Companion.TAG
import com.openminis.app.ui.chat.ChatViewModel.Companion.TAG_STREAM
import com.openminis.app.ui.chat.ChatViewModel.FallbackCandidate
import com.openminis.app.ui.chat.ChatViewModel.InjectedTurn
import com.openminis.app.ui.chat.ChatViewModel.PreSendContextAction
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.openminis.app.data.repository.loadApiKey
import com.openminis.app.data.repository.saveApiKey

/**
 * Hold a prompt for the currently running agent loop. The message appears immediately in the chat with
 * isQueued=true. A [PendingDelivery.STEER] prompt is injected at the loop's next step boundary; a
 * [PendingDelivery.QUEUE] prompt waits until the loop finishes and drainQueuedPrompts() starts its turn.
 * Mirrors iOS AIChatViewModel.enqueuePrompt().
 */
fun ChatViewModel.enqueuePrompt(text: String, delivery: PendingDelivery = PendingDelivery.STEER) {
    val trimmed = text.trim()
    val pendingAttachments = _attachments.value
    if ((trimmed.isBlank() && pendingAttachments.isEmpty()) || !_isStreaming.value) return

    val prompt = QueuedPrompt(
        id = "queued_${System.currentTimeMillis()}_${(Math.random() * 1_000_000).toInt()}",
        text = trimmed,
        attachments = pendingAttachments,
        delivery = delivery,
    )
    _promptQueue.value = _promptQueue.value + prompt

    val attachmentNames = pendingAttachments.map { it.fileName }
    val imageUris = pendingAttachments.filter { it.isImage }.map { it.uri }
    val attachmentUris = pendingAttachments.filterNot { it.isImage }.map { it.uri }
    val chatMsg = ChatMessage(
        id = "queued_msg_${prompt.id}",
        role = "user",
        content = trimmed,
        imageUris = imageUris,
        attachmentNames = attachmentNames,
        attachmentUris = attachmentUris,
        isQueued = true,
        queuedPromptId = prompt.id,
        queuedDelivery = delivery,
    )
    _messages.value = _messages.value + chatMsg
    sessionEventEmitter.messageCreated(chatMsg)
    clearAttachments()
    Log.i(TAG, "Enqueued prompt (${trimmed.length}ch, ${pendingAttachments.size} attachments), queue=${_promptQueue.value.size}")
}

/** Switch a waiting message between steering the running task and waiting for it to finish. */
fun ChatViewModel.setQueuedDelivery(messageId: String, delivery: PendingDelivery) {
    val msg = _messages.value.firstOrNull { it.id == messageId } ?: return
    if (!msg.isQueued) return
    val pid = msg.queuedPromptId ?: return
    _promptQueue.value = _promptQueue.value.map { if (it.id == pid) it.copy(delivery = delivery) else it }
    _messages.value = _messages.value.map { if (it.id == messageId) it.copy(queuedDelivery = delivery) else it }
}

/** Remove a queued prompt and its chat message by prompt id. */
fun ChatViewModel.removeQueuedPrompt(promptId: String) {
    _promptQueue.value = _promptQueue.value.filterNot { it.id == promptId }
    _messages.value = _messages.value.filterNot { it.queuedPromptId == promptId }
}

/** Withdraw a queued message before it gets injected into the agent loop. */
fun ChatViewModel.withdrawQueuedMessage(messageId: String) {
    val msg = _messages.value.firstOrNull { it.id == messageId } ?: return
    if (!msg.isQueued) return
    val pid = msg.queuedPromptId ?: return
    _promptQueue.value = _promptQueue.value.filterNot { it.id == pid }
    _messages.value = _messages.value.filterNot { it.id == messageId }
    Log.i(TAG, "Withdrew queued message, queue=${_promptQueue.value.size}")
}

internal suspend fun ChatViewModel.injectQueuedPromptsAsNewTurn(
    finishedAssistantId: String,
    finishedAccumulatedText: String,
    finishedAllToolBlocks: List<AssistantBlock>,
): InjectedTurn? {
    // Only prompts meant to steer the running task are delivered here; QUEUE ones stay for drainQueuedPrompts.
    val queued = _promptQueue.value.dueAtStepBoundary()
    if (queued.isEmpty()) return null
    _promptQueue.value = _promptQueue.value - queued.toSet()

    // [T-android-queued-message-duplicated-on-inject] REMOVE the queued
    // placeholder bubbles (the ones enqueuePrompt added with
    // id="queued_msg_…") for the prompts we're injecting. Step (c) below
    // appends a single combined user bubble (id=userEntity.id) for the same
    // text — so flipping isQueued=false and KEEPING the placeholders (the
    // old behaviour) rendered the message TWICE: once as the un-queued
    // placeholder, once as the injected bubble. drainQueuedPrompts reuses
    // its placeholders and never re-appends, so it didn't dupe; this mid-
    // loop inject path appends a fresh bubble, so the placeholders must go.
    val queuedIds = queued.map { it.id }.toSet()
    val msgsAfterUnqueue = _messages.value.filterNot { m ->
        m.queuedPromptId != null && queuedIds.contains(m.queuedPromptId)
    }

    // Build the combined user message from all queued prompts.
    val sid = ensureSession()
    val combinedAttachments = queued.flatMap { it.attachments }
    val prepared = attachmentPreparer.prepare(combinedAttachments, sid)

    val combinedParts = mutableListOf<AgentContentPart>()
    val combinedText = StringBuilder()
    for (prompt in queued) {
        if (prompt.text.isNotEmpty()) {
            if (combinedText.isNotEmpty()) combinedText.append("\n\n")
            combinedText.append(prompt.text)
            combinedParts.add(AgentContentPart.Text(expandPastePlaceholders(prompt.text, _pastedTexts.value).first))
        }
    }
    prepared.imageParts.forEachIndexed { idx, part ->
        val path = prepared.imageUploadPaths.getOrNull(idx)
        if (path != null) combinedParts.add(AgentContentPart.Text("[attached image: $path]"))
        combinedParts.add(AgentContentPart.ImageData(part.data, part.mimeType, linuxPath = path, noVisionPlaceholder = visionPlaceholderFor(path)))
    }
    prepared.attachedFilesXml?.let { combinedParts.add(AgentContentPart.Text(it)) }

    // Guard: every queued prompt produced no content (no text, no
    // image). An empty user msg is a 400 from every provider. Skip —
    // the caller falls through to a normal next-turn dispatch so the
    // loop doesn't spin.
    if (combinedParts.isEmpty()) {
        AppLogger.warning(
            TAG_STREAM,
            "injectQueuedPromptsAsNewTurn: ${queued.size} queued prompt(s) produced no content, skipping",
        )
        return null
    }

    // Bridge entry into agentHistory ONLY (not persisted). The tail
    // before this call is user(tool_result); without the bridge the
    // queued user message becomes two consecutive user roles and the
    // provider merges them — exactly the regression iOS hit at #579.
    // Empty/whitespace-only bridge text would itself be merged out by
    // some sanitizers; keep a small visible string for parity with iOS.
    agentHistory.add(
        LLMMessage(
            role = LLMMessage.Role.ASSISTANT,
            content = "(Interrupted mid-task by a new user message. Decide based on the new message and overall context whether the prior task should continue — do not forget or abandon it unless the user explicitly says to stop, or the new message makes clear it is no longer needed.)",
            contentParts = listOf(
                AgentContentPart.Text("(Interrupted mid-task by a new user message. Decide based on the new message and overall context whether the prior task should continue — do not forget or abandon it unless the user explicitly says to stop, or the new message makes clear it is no longer needed.)"),
            ),
        ),
    )

    // Persist the queued user message as its own DB row + append to
    // agentHistory so the next API call carries it.
    val userText = combinedText.toString()
    val queuedPaste = buildPastedParts(userText, sid)
    val userPartsJson = buildUserPartsJson(
        userText,
        prepared.mediaRefPartsJson,
        prepared.attachedFilesXml,
        bodyPartsJson = queuedPaste?.partsJson,
    )
    val userEntity = PastedTextProcessor.commitMessage(
        pastedParts = queuedPaste,
        persist = { chatRepository.appendMessage(sid, "user", userPartsJson) },
        consume = { ids -> _pastedTexts.value = _pastedTexts.value.filterNot { it.id in ids } },
    )
    agentHistory.add(
        LLMMessage(
            role = LLMMessage.Role.USER,
            content = queuedPaste?.modelText ?: userText,
            imageParts = prepared.imageParts,
            contentParts = combinedParts,
            dbMessageId = userEntity.id,
        ),
    )

    // Finalize the just-finished assistant bubble in the UI on Main:
    // (a) un-queue the queued chat bubbles, (b) flush the side-channel
    // delta into the canonical row and clear isStreaming /
    // isAwaitingModelResponse, then (c) append the freshly-created
    // queued user ChatMessage + a NEW empty assistant placeholder so
    // the next iteration's streaming writes target the new bubble.
    val newAssistantId = "assistant_${System.currentTimeMillis()}"
    withContext(Dispatchers.Main) {
        // (a) + (b) one emit: build the post-finalize list.
        _messages.value = msgsAfterUnqueue
        updateAssistantMessage(
            finishedAssistantId,
            finishedAccumulatedText,
            false,
            finishedAllToolBlocks,
            isAwaitingModelResponse = false,
        )
        // (c) — append the queued user bubble + the new assistant
        // placeholder. Mirrors sendMessage's user-bubble append shape so
        // attachments / images / file chips render the same.
        val queuedUserMsg = ChatMessage(
            id = userEntity.id,
            role = "user",
            content = userText,
            imageUris = prepared.imageUris,
            attachmentNames = prepared.attachmentNames,
            attachmentUris = prepared.nonImageUris,
        )
        val nextAssistantMsg = ChatMessage(
            id = newAssistantId,
            role = "assistant",
            content = "",
            isStreaming = true,
            isAwaitingModelResponse = true,
            thinkingLevel = _thinkingLevel.value,
        )
        _messages.value = _messages.value + queuedUserMsg + nextAssistantMsg
        sessionEventEmitter.messageCreated(queuedUserMsg)
        sessionEventEmitter.messageCreated(nextAssistantMsg)
        // Note: ChatScreen's `lastUserAppendMs` (the trailing-row
        // ScrollPin send-grace window) is updated reactively by
        // ChatScreen's `LaunchedEffect(messages.size)` user-send hook
        // when messages.size grows — appending the queuedUserMsg above
        // bumps the size, so the pin window opens just like a normal
        // send. No direct write needed from here (and we couldn't —
        // `lastUserAppendMs` lives in ChatScreen's composition scope).
    }

    AppLogger.info(
        TAG_STREAM,
        "injectQueuedPromptsAsNewTurn: injected ${queued.size} queued prompt(s) as new turn, " +
            "finishedId=$finishedAssistantId newId=$newAssistantId",
    )
    return InjectedTurn(newAssistantId)
}

/**
 * Drain queued prompts after an agent loop finishes. Each queued prompt is
 * appended to agentHistory, persisted, and re-runs the agent loop.
 * Mirrors iOS AIChatViewModel.drainQueuedPrompts().
 */
internal suspend fun ChatViewModel.drainQueuedPrompts(
    provider: LLMProvider,
    systemPrompt: String?,
    fallbackProviders: List<FallbackCandidate>,
    fallbackStrategy: com.openminis.app.data.model.FallbackStrategy,
): AgentTurnOutcome {
    while (_promptQueue.value.isNotEmpty()) {
        val queued = _promptQueue.value
        _promptQueue.value = emptyList()
        Log.i(TAG, "📨[DRAIN] Draining ${queued.size} queued prompt(s): " +
            queued.joinToString(", ") { "${it.id}=\"${it.text.take(20)}...\"" })

        // Flip isQueued=false on corresponding chat messages so they render as sent.
        // T189: also clear queuedPromptId so a later retry of this bubble
        // doesn't try to drop a phantom queue entry (and so the field state
        // matches what retryFromMessage's truncate path now produces).
        val queuedIds = queued.map { it.id }.toSet()
        _messages.value = _messages.value.map { m ->
            if (m.queuedPromptId != null && queuedIds.contains(m.queuedPromptId)) {
                m.copy(isQueued = false, queuedPromptId = null)
            } else m
        }

        // Build a combined user message (text + images from all queued prompts).
        // Persist as a single row.
        val sid = ensureSession()
        val combinedAttachments = queued.flatMap { it.attachments }
        val prepared = attachmentPreparer.prepare(combinedAttachments, sid)

        // T132: same shape as sendMessage — caption(s) first, then for each
        // image emit "[attached image: <path>]" + ImageData, finally the
        // <user-attached-files> XML. Keeps caption adjacent to image and
        // lets the agent re-read the file via read_image.
        val combinedParts = mutableListOf<AgentContentPart>()
        val combinedText = StringBuilder()
        for (prompt in queued) {
            if (prompt.text.isNotEmpty()) {
                if (combinedText.isNotEmpty()) combinedText.append("\n\n")
                combinedText.append(prompt.text)
                combinedParts.add(AgentContentPart.Text(expandPastePlaceholders(prompt.text, _pastedTexts.value).first))
            }
        }
        prepared.imageParts.forEachIndexed { idx, part ->
            val path = prepared.imageUploadPaths.getOrNull(idx)
            if (path != null) combinedParts.add(AgentContentPart.Text("[attached image: $path]"))
            combinedParts.add(AgentContentPart.ImageData(part.data, part.mimeType, linuxPath = path, noVisionPlaceholder = visionPlaceholderFor(path)))
        }
       prepared.attachedFilesXml?.let { combinedParts.add(AgentContentPart.Text(it)) }

       val userText = combinedText.toString()
        val drainPaste = buildPastedParts(userText, sid)
        val userPartsJson = buildUserPartsJson(
            userText,
            prepared.mediaRefPartsJson,
            prepared.attachedFilesXml,
            bodyPartsJson = drainPaste?.partsJson,
        )
        PastedTextProcessor.commitMessage(
            pastedParts = drainPaste,
            persist = { chatRepository.appendMessage(sid, "user", userPartsJson) },
            consume = { ids -> _pastedTexts.value = _pastedTexts.value.filterNot { it.id in ids } },
        )

        agentHistory.add(LLMMessage(
            role = LLMMessage.Role.USER,
            content = drainPaste?.modelText ?: userText,
            imageParts = prepared.imageParts,
            contentParts = combinedParts,
        ))

        try {
            val outcome = runAgentLoop(
                provider = provider,
                systemPrompt = systemPrompt,
                fallbackProviders = fallbackProviders,
                fallbackStrategy = fallbackStrategy,
            )
            if (outcome != AgentTurnOutcome.Completed) return outcome
        } catch (e: CancellationException) {
            Log.d(TAG, "Agent loop (queued-drain) cancelled")
            // Cancel mid-drain: cancelStream() will check _promptQueue
            // and call resumeQueueAfterCancel() if anything's still pending,
            // so just propagate.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Agent loop (queued-drain) error", e)
            setInlineError(e)
            return AgentTurnOutcome.Failed(e.message ?: "queued_turn_failed")
        }
    }
    return AgentTurnOutcome.Completed
}

fun ChatViewModel.sendMessage(text: String) = sendMessage(takeQuotedText(text), skipContextCheck = false)

/** Send [text]; while the agent is working it is held and delivered as [delivery] says. */
fun ChatViewModel.sendMessage(text: String, delivery: PendingDelivery) =
    sendMessage(takeQuotedText(text), skipContextCheck = false, delivery = delivery)

/**
 * A programmatic prompt (background callback, routine, RPC). It owns exactly [text] and
 * [attachments]: the user's composer — attachments being prepared, a message being edited, pasted
 * snippets — is not read, sent or cleared by it.
 */
internal fun ChatViewModel.submitPrompt(text: String, attachments: List<InputAttachment> = emptyList()): AgentTurnHandle {
    val handle = AgentTurnHandle()
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
internal fun ChatViewModel.sendMessage(
    text: String,
    skipContextCheck: Boolean,
    request: AgentTurnHandle? = null,
    ownAttachments: List<InputAttachment> = emptyList(),
    delivery: PendingDelivery = PendingDelivery.STEER,
) {
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
    if (trimmed.isBlank() && (if (programmatic) ownAttachments else _attachments.value).isEmpty()) {
        request?.reject("empty_prompt")
        return
    }
    if (_isCompacting.value) {
        request?.reject("session_compacting")
        appendSystemInfo(
            text = "Wait for the current compact to finish before sending.",
            iconKind = "compact",
        )
        return
    }
    // Context pressure check. Unlike before, needsCompact now HOLDS the
    // send: either compact silently (auto-compact on) or ask first. The
    // whole point is that the request which tripped the threshold must not
    // be the one that goes out over-length.
    if (!skipContextCheck) {
        when (checkContextBeforeSend()) {
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
        _error.value = "No provider configured"
        request?.reject("no_provider_configured")
        return
    }
    var provider: LLMProvider = initialProvider

    _error.value = null

    val currentAttachments = if (programmatic) {
        ownAttachments
    } else {
        _attachments.value.also { clearAttachments() }
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

    val acceptedTurn = request ?: AgentTurnHandle()
    val sendJob = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
        var streamLaunched = false
        try {
        // Ensure session exists in DB (creates on first message for draft sessions)
        val activeSessionId = ensureSession()

        if (editingId != null) {
            truncateBeforeEdit(editingId)
        }

        val prepared = attachmentPreparer.prepare(currentAttachments, activeSessionId)
        mergeExternalHistory()

       // Save user message — text + persisted mediaRef parts so images survive
       // a session reload (T128). Non-image attachments still only contribute
       // their name (rendered as a file tile) and are not persisted.
        val pasted = if (programmatic) null else buildPastedParts(trimmed, activeSessionId)
        val userPartsJson = buildUserPartsJson(
            trimmed,
            prepared.mediaRefPartsJson,
            prepared.attachedFilesXml,
            bodyPartsJson = pasted?.partsJson,
        )
        val persistedUser = PastedTextProcessor.commitMessage(
            pastedParts = pasted,
            persist = { chatRepository.appendMessage(activeSessionId, "user", userPartsJson) },
            consume = { ids -> _pastedTexts.value = _pastedTexts.value.filterNot { it.id in ids } },
        )
        acceptedTurn.userMessageId = persistedUser.id

        val userMsg = ChatMessage(
            id = persistedUser.id,
            role = "user",
            content = trimmed,
            imageUris = prepared.imageUris,
            imageRefs = prepared.imageRefs,
            attachmentNames = prepared.attachmentNames + (pasted?.uiNames ?: emptyList()),
            attachmentUris = prepared.nonImageUris + (pasted?.uiUris ?: emptyList()),
        )
        _messages.value = _messages.value + userMsg
        sessionEventEmitter.messageCreated(userMsg)
        val imageParts = prepared.imageParts

        // T132: build the user contentParts in iOS order — caption first
        // (only if non-empty), then per image emit
        //   text("[attached image: /var/minis/attachments/uploads/<f>]")
        //   ImageData(<bytes>, <mime>)
        // so the caption sits adjacent to the image in the wire payload,
        // and the agent's read_image tool can resolve the same path back
        // to bytes. Trailing <user-attached-files> XML block lets the
        // model see filenames/sizes without needing tool calls.
        val userContentParts = mutableListOf<AgentContentPart>()
        // The model gets the pasted bodies expanded; the composer text keeps its [Pasted#N] markers.
        val bodyText = pasted?.modelText ?: trimmed
        if (bodyText.isNotEmpty()) userContentParts.add(AgentContentPart.Text(bodyText))
        imageParts.forEachIndexed { idx, part ->
            val path = prepared.imageUploadPaths.getOrNull(idx)
            if (path != null) userContentParts.add(AgentContentPart.Text("[attached image: $path]"))
            userContentParts.add(AgentContentPart.ImageData(part.data, part.mimeType, linuxPath = path, noVisionPlaceholder = visionPlaceholderFor(path)))
        }
        prepared.attachedFilesXml?.let { userContentParts.add(AgentContentPart.Text(it)) }

       agentHistory.add(LLMMessage(
           role = LLMMessage.Role.USER,
            content = pasted?.modelText ?: trimmed,
           imageParts = imageParts,
           contentParts = userContentParts,
           dbMessageId = persistedUser.id,
       ))

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
        streamLaunched = true
        val sourceRunId = beginBotSourceRun()
        streamJob = launch(Dispatchers.IO) {
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

                var botTurnSucceeded = false
                try {
                    withBotTurnLock {
                        AppLogger.info(TAG_STREAM, "send runAgentLoop CALL")
                        val outcome = runAgentLoop(
                            provider = provider,
                            systemPrompt = systemPrompt,
                            fallbackProviders = fallbackProviders,
                            fallbackStrategy = activeFallbackStrategy,
                        )
                        AppLogger.info(TAG_STREAM, "send runAgentLoop RETURN normal")
                        // Drain any prompts the user queued while this loop was running.
                        // Skipped on cancel: cancelled job won't reach here.
                        acceptedTurn.record(outcome)
                        if (outcome == AgentTurnOutcome.Completed) {
                            val drained = drainQueuedPrompts(provider, systemPrompt, fallbackProviders, activeFallbackStrategy)
                            acceptedTurn.record(drained)
                            botTurnSucceeded = drained == AgentTurnOutcome.Completed
                        }
                        AppLogger.info(TAG_STREAM, "send drainQueuedPrompts RETURN")
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
                    com.openminis.app.tools.BotDelegationCoordinator.current()?.onSourceTurnSettled(activeSessionId, botTurnSucceeded, sourceRunId)
                    AppLogger.info(TAG_STREAM, "send streamJob FINALLY exit")
                }
            } catch (e: CancellationException) {
                acceptedTurn.record(AgentTurnOutcome.Cancelled)
                AppLogger.info(TAG_STREAM, "send streamJob CANCELLED waiting for slot")
                Log.d(TAG, "Cancelled while waiting for concurrency slot")
                com.openminis.app.tools.BotDelegationCoordinator.current()?.onSourceTurnSettled(activeSessionId, false, sourceRunId)
            }
            // [T-android-stale-streamjob-clears-isstreaming] guard — see
            // `var streamJob` KDoc; identical pattern as runRerunStreamTail.
            if (streamJob === coroutineContext[Job]) {
                AppLogger.info(TAG_STREAM, "send _isStreaming=false (about to set)")
                _isStreaming.value = false
            } else {
                AppLogger.info(TAG_STREAM, "send _isStreaming SKIPPED (stale job)")
            }
            AppLogger.info(TAG_STREAM, "send streamJob EXIT")
        }
        } catch (e: CancellationException) {
            acceptedTurn.record(AgentTurnOutcome.Cancelled)
            throw e
        } catch (e: Exception) {
            acceptedTurn.record(AgentTurnOutcome.Failed(e.message ?: "message_setup_failed"))
            setInlineError(e.message ?: "message_setup_failed")
        } finally {
            if (!streamLaunched && streamJob === coroutineContext[Job]) {
                AppLogger.info(TAG_STREAM, "send _isStreaming=false (setup aborted)")
                _isStreaming.value = false
            }
        }
    }
    streamJob = sendJob
    acceptedTurn.bind(sendJob)
    sendJob.start()
}

/** Set error inline on the last assistant message (iOS: message.error).
 *
 *  Also clears [ChatMessage.isAwaitingModelResponse] — without this, an
 *  exception thrown after a tool turn (which sets isAwaitingModelResponse=
 *  true at runAgentLoop ~4015) leaves the "Minis is thinking" indicator
 *  on screen even though streaming is over. The flag is per-message and
 *  is not implicitly cleared by isStreaming=false. */
internal fun ChatViewModel.setInlineError(errorText: String) {
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
            viewModelScope.launch(Dispatchers.IO) {
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
internal fun ChatViewModel.setTransientInlineError(errorText: String) {
    val msgs = _messages.value.toMutableList()
    val lastAssistantIdx = msgs.indexOfLast { it.role == "assistant" }
    if (lastAssistantIdx < 0) return
    val msg = msgs[lastAssistantIdx]
    msgs[lastAssistantIdx] = msg.copy(error = errorText)
    _messages.value = msgs
}

/** Clear any inline error on the last assistant message (used after successful retry). */
internal fun ChatViewModel.clearInlineError() {
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
        viewModelScope.launch(Dispatchers.IO) {
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
 * [T-error-persist-android] Fire-and-forget: clear the persisted error
 * sticker on the session's last assistant row. Called from the resume / retry
 * entrypoints that drop the in-memory error but don't go through
 * [clearInlineError], so a recovered turn can't merge-resurrect the old
 * banner on the next reload. No-op when there's no session/row yet.
 */
internal fun ChatViewModel.clearPersistedLastAssistantError() {
    val sid = realSessionId.ifEmpty { sessionId }
    if (sid.isEmpty()) return
    viewModelScope.launch(Dispatchers.IO) {
        try { chatRepository.updateLastAssistantError(sid, null) }
        catch (e: Exception) { Log.w(TAG, "clear error_info (persisted) failed: ${e.message}") }
    }
}

fun ChatViewModel.retryLast() {
    if (deferUntilMediaCommitted { retryLast() }) return
    val now = android.os.SystemClock.uptimeMillis()
    if (_isStreaming.value || now - lastRetryTimestamp < 800L) return
    lastRetryTimestamp = now
    // T-streaming-side-channel: belt-and-suspenders flush in case any
    // delta survived an earlier abnormal exit; retryLast is gated on
    // !isStreaming so this is normally a no-op.
    flushAllStreamingDeltas()
    val msgs = _messages.value.toMutableList()
    val lastAssistantIdx = msgs.indexOfLast { it.role == "assistant" }
    if (lastAssistantIdx < 0) return
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

    val initialProvider = currentProvider ?: return
    var provider: LLMProvider = initialProvider
    _error.value = null

    // T145: claim _isStreaming synchronously — see retryFromMessage for rationale.
    AppLogger.info(TAG_STREAM, "retryLast _isStreaming=true (sync, sid=$activeSessionId)")
    _isStreaming.value = true

    viewModelScope.launch {
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
        val sourceRunId = beginBotSourceRun()
        streamJob = launch(Dispatchers.IO) {
            AppLogger.info(TAG_STREAM, "retryLast streamJob ENTER sid=$activeSessionId")
            try {
                SessionConcurrencyManager.acquireSlot(activeSessionId)
                AppLogger.debug(TAG_STREAM, "retryLast streamJob slot acquired")
                SessionActivityTracker.setActive(activeSessionId, onStop = { cancelStream() })
                val activeFallbackStrategy = providerRepository.config.value.fallbackTrigger
                val fallbackProviders = buildFallbackProviders(provider)
                var botTurnSucceeded = false
                try {
                    withBotTurnLock {
                        AppLogger.info(TAG_STREAM, "retryLast runAgentLoop CALL")
                        val outcome = runAgentLoop(
                            provider = provider,
                            systemPrompt = systemPrompt,
                            fallbackProviders = fallbackProviders,
                            fallbackStrategy = activeFallbackStrategy,
                            reusingAssistantId = targetAssistantId,
                        )
                        AppLogger.info(TAG_STREAM, "retryLast runAgentLoop RETURN normal")
                        botTurnSucceeded = outcome == AgentTurnOutcome.Completed &&
                            drainQueuedPrompts(provider, systemPrompt, fallbackProviders, activeFallbackStrategy) == AgentTurnOutcome.Completed
                        AppLogger.info(TAG_STREAM, "retryLast drainQueuedPrompts RETURN")
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
                    com.openminis.app.tools.BotDelegationCoordinator.current()?.onSourceTurnSettled(activeSessionId, botTurnSucceeded, sourceRunId)
                    AppLogger.info(TAG_STREAM, "retryLast streamJob FINALLY exit")
                }
            } catch (e: CancellationException) {
                AppLogger.info(TAG_STREAM, "retryLast streamJob CANCELLED waiting for slot")
                Log.d(TAG, "Cancelled while waiting for concurrency slot")
                com.openminis.app.tools.BotDelegationCoordinator.current()?.onSourceTurnSettled(activeSessionId, false, sourceRunId)
            }
            // [T-android-stale-streamjob-clears-isstreaming] guard.
            if (streamJob === coroutineContext[Job]) {
                AppLogger.info(TAG_STREAM, "retryLast _isStreaming=false (about to set)")
                _isStreaming.value = false
            } else {
                AppLogger.info(TAG_STREAM, "retryLast _isStreaming SKIPPED (stale job)")
            }
            AppLogger.info(TAG_STREAM, "retryLast streamJob EXIT")
        }
        } finally {
            if (!streamLaunched) {
                AppLogger.info(TAG_STREAM, "retryLast _isStreaming=false (setup aborted)")
                _isStreaming.value = false
            }
        }
    }
}
