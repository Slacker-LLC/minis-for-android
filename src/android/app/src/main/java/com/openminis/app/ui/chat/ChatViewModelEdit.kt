package com.openminis.app.ui.chat

import android.net.Uri
import android.util.Log
import androidx.compose.material.icons.filled.Delete
import androidx.lifecycle.viewModelScope
import com.openminis.app.R
import com.openminis.app.agent.AgentTurnHandle
import com.openminis.app.agent.AgentTurnOutcome
import com.openminis.app.browser.BrowserTabPool
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.service.SessionConcurrencyManager
import com.openminis.app.ui.chat.ChatViewModel.Companion.TAG
import com.openminis.app.ui.chat.ChatViewModel.Companion.TAG_STREAM
import com.openminis.app.ui.chat.ChatViewModel.Companion.canRetryMessage
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * T137: Wipe in-memory and on-disk message state for the current session
 * without touching the session's chat files (workspace/, attachments/,
 * offloads/). Mirrors iOS [AIChatViewModel.clearChat] — same surface area,
 * same "files survive" guarantee.
 *
 * Cancels any in-flight stream first so the UI doesn't race the wipe.
 */
fun ChatViewModel.clearChat() {
    if (_isStreaming.value) cancelStream()
    val sid = activeSessionId
    // T-streaming-side-channel: ensure no stale stream delta survives a
    // session wipe; the messages list is about to be cleared, so any
    // pending key would be orphaned.
    // [T-android-stream-flush-review] also cancel pending trailing flushes
    // so none re-adds an orphan side-channel entry after the wipe.
    clearAllStreamFlushStates()
    _streamingById.value = emptyMap()
    // Memory state — match iOS clearChat() field list one-for-one.
    _messages.value = emptyList()
    // A clear creates a fresh conversation projection. Preserve the
    // session's monotonic next sequence but make old deltas unreplayable.
    SessionEventHub.clear(sid)
    agentHistory.clear()
    _error.value = null
    _cachedLatestMarker = null
    toolLoopDetector.reset()
    _canResume.value = false
    _attachments.value = emptyList()
    _promptQueue.value = emptyList()
    _hasInjectedShareContent.value = false
    // T261: tool-detail sheet is per-session UI state — clear it so a
    // newly cleared chat doesn't briefly flash a stale tool's sheet
    // before the existence-guard catches up.
    _selectedToolDetailId.value = null
    // Drop any browser tabs the agent spawned for this session, and
    // delete the persisted tab snapshot so a future open starts clean.
    // iOS calls BrowserTabPool.deletePersistedData(for:) +
    // BrowserUseOffloadBridge.releasePool(forSession:); on Android the
    // pool is per-VM (lazy), so releasing tabs here is sufficient.
    _browserTabPoolRef?.releaseAllTabs()
    runCatching {
        java.io.File(context.filesDir, "browser_tabs/$sid.json").delete()
    }
    // Persist: drop messages + compact markers. Files (workspace,
    // attachments, offloads) intentionally retained.
    viewModelScope.launch {
        chatRepository.dao.deleteMessages(sid)
        chatRepository.dao.deleteCompactMarkers(sid)
        Log.i(TAG, "clearChat: session=$sid wiped (files preserved)")
    }
}

fun ChatViewModel.markShareInjected() { _hasInjectedShareContent.value = true }

fun ChatViewModel.clearShareInjectedFlag() { _hasInjectedShareContent.value = false }

/**
 * Convert a staged share file (under filesDir/share_extension/) into
 * an [InputAttachment] and add it to the composer. Called by
 * ChatScreen when draining a [com.openminis.app.share.PendingShare].
 */
fun ChatViewModel.addAttachmentFromStagedShare(file: java.io.File): InputAttachment? {
    if (!file.exists()) return null
    val ext = file.extension.lowercase()
    val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        ?: "application/octet-stream"
    val kind = if (mime.startsWith("image/")) InputAttachment.Kind.IMAGE
               else InputAttachment.Kind.DOCUMENT
    // T185 fix: ChatScreen wipes the share-extension directory right
    // after this call returns (`SharedShareStore.cleanSharedFiles`),
    // so a `Uri.fromFile(<staged file>)` would dangle by the time the
    // user actually sends — the byte-read in prepareUserAttachments
    // then fails to open the stream and the image never makes it into
    // the LLM payload, leaving the model staring at "what is this?" with no
    // picture. Copy the staged bytes into our own private dir so the
    // attachment outlives the share-extension cleanup.
    val durableDir = java.io.File(context.cacheDir, "share_inbound").apply { mkdirs() }
    val durable = java.io.File(durableDir, "${java.util.UUID.randomUUID()}-${file.name}")
    try {
        file.inputStream().use { input ->
            durable.outputStream().use { output -> input.copyTo(output) }
        }
    } catch (e: Exception) {
        Log.w(TAG, "failed to copy staged share file ${file.name}: ${e.message}")
        return null
    }
    val attachment = InputAttachment(
        fileName = file.name,
        uri = android.net.Uri.fromFile(durable),
        mimeType = mime,
        kind = kind,
    )
    addAttachment(attachment)
    return attachment
}

/**
 * [T-android-rerun-from-tool-block-position] Resolve the live UI assistant
 * bubble id that currently owns the tool block with [blockId] (== its
 * tool_use id). Returns null when no live bubble holds it. Used by the
 * debug RPC ([com.openminis.app.agent.AgentRunner.rerunFromToolBlock])
 * because the in-memory bubble id is a volatile `assistant_<ts>` runtime id
 * (not the DB row id a caller would read from `chat.messages.list`), so the
 * harness can't supply it directly.
 */
fun ChatViewModel.assistantMessageIdForToolBlock(blockId: String): String? =
    _messages.value.firstOrNull { m ->
        m.role == "assistant" && m.toolBlocks.any { it.id == blockId }
    }?.id

/**
 * [T-android-rerun-from-tool-block-position] Re-run the conversation from
 * the exact point a specific tool_use block was about to be issued —
 * BLOCK-boundary, not turn-boundary. Keeps the blocks BEFORE the target
 * tool_use in the same assistant turn; drops the target block + every
 * later block in that turn + its tool_result + all later turns, then
 * re-runs so the model re-decides from that point.
 *
 * Ported from iOS `retryFromToolBlock` (commit 0149457e). Anchor is the
 * block's tool_use id ([blockId], which for a tool_use [AssistantBlock]
 * equals its `id`) — stable + unique, NOT a positional count, so streaming
 * / merged-turn alignment can't drift the cut point.
 *
 * Degenerate case: when the target is the FIRST real block of its turn
 * (nothing precedes it), this is equivalent to truncating at the preceding
 * user message — delegate to [retryFromMessage] (the existing whole-turn
 * path) and skip the sub-message DB rewrite.
 *
 * Android does the cut DB-first (delete rows after the trimmed assistant
 * row, then rewrite that row's parts in place via
 * [ChatRepository.updateMessageParts]) and rebuilds agentHistory from the
 * trimmed DB state. The UI is trimmed in-memory (same as
 * [retryFromMessage]'s `retainedHead`, so compact-marker graying isn't
 * disturbed). Because the agent loop persists each turn as its own row and
 * `toChatMessages` merges consecutive assistant rows into one bubble, the
 * surviving trimmed turn and the new generation coalesce on the next
 * reload — no duplicate header (iOS needed an explicit resume-into-turn
 * fix for the same; Android gets it from the merge). The thinking
 * indicator shows immediately via [runAgentLoop]'s awaiting placeholder.
 *
 * No-op (returns false) when streaming, when the message/block isn't
 * found, or when the block isn't a tool_use. The caller gates the menu
 * item with the same `!isStreaming` rule, but the guard here is the source
 * of truth.
 */
fun ChatViewModel.rerunFromToolBlock(assistantMessageId: String, blockId: String): Boolean =
    rerunFromToolBlock(assistantMessageId, blockId, null)

internal fun ChatViewModel.rerunFromToolBlock(assistantMessageId: String, blockId: String, request: AgentTurnHandle?): Boolean {
    if (_isStreaming.value) { request?.reject("session_busy"); return false }
    val messages = _messages.value
    val asstIdx = messages.indexOfFirst { it.id == assistantMessageId }
    if (asstIdx < 0) { request?.reject("message_not_loaded"); return false }
    val asstMsg = messages[asstIdx]
    val blockIdx = asstMsg.toolBlocks.indexOfFirst { it.id == blockId }
    if (blockIdx < 0) { request?.reject("tool_block_not_found"); return false }
    val targetBlock = asstMsg.toolBlocks[blockIdx]
    // Only a real tool_use block anchors a block cut — its id is the
    // tool_use id we match against in agentHistory / parts_json.
    if (targetBlock.kind != "tool_use" || targetBlock.id.isBlank()) { request?.reject("not_a_tool_block"); return false }
    // [T-android-tool-autoscroll] Start-of-turn snap — see resume().
    _forceScrollToBottom.tryEmit(Unit)
    val targetToolUseId = targetBlock.id

    // Degenerate: nothing of substance precedes the target in this turn —
    // a block cut here is identical to truncating at the preceding user
    // message, so reuse the existing whole-turn path. "Substance" = any
    // earlier block that isn't an empty text block (mirrors iOS
    // hasPrecedingContent).
    val hasPrecedingContent = asstMsg.toolBlocks.take(blockIdx).any { blk ->
        if (blk.isText) blk.content.isNotEmpty() else true
    }
    // [T-android-rerun-from-tool-deletes-earlier-turns] The degenerate
    // shortcut is ONLY equivalent to truncating at the preceding user
    // message when there is NOTHING between that user message and this
    // assistant turn. If an EARLIER assistant turn/bubble sits right before
    // this one (asstIdx-1 is also assistant), retryFromMessage(precedingUser)
    // would delete that earlier turn's tools too — exactly the "rerun from
    // the last tool wiped the tools above it / re-ran from the very start"
    // bug (logged: historySize 29 → 3 on the 2nd consecutive rerun). In
    // that case fall through to the DB-precise cut below, which keeps every
    // row before the target row (its cutPartIdx==0 branch deletes only the
    // target row onward) and preserves the earlier turns.
    val precededByUserOnly = asstIdx == 0 || messages[asstIdx - 1].role != "assistant"
    if (!hasPrecedingContent && precededByUserOnly) {
        val userMsg = (asstIdx - 1 downTo 0).asSequence()
            .map { messages[it] }
            .firstOrNull { it.role == "user" && it.content.isNotBlank() }
            ?: run { request?.reject("no_preceding_user_message"); return false }
        Log.i(TAG, "rerunFromToolBlock degenerate → retryFromMessage(precedingUser) tuId=${targetToolUseId.take(12)}")
        return retryFromMessage(userMsg.id, request)
    }

    val initialProvider = currentProvider
    if (initialProvider == null) {
        _error.value = "No provider configured"
        request?.reject("no_provider")
        return false
    }
    _canResume.value = false
    _error.value = null

    // T149 parity: revoke memory_writes in the parts we're about to drop
    // so the on-disk daily log doesn't keep entries the user rewound past.
    // The dropped range is: the target turn's blocks FROM the target
    // onward (the target tool_use itself + any later same-turn blocks) +
    // every later message. The surviving earlier blocks of the target turn
    // are kept, so they're excluded.
    val droppedTargetTail = asstMsg.copy(
        toolBlocks = asstMsg.toolBlocks.drop(blockIdx),
    )
    val deletedMessages = listOf(droppedTargetTail) +
        messages.subList(asstIdx + 1, messages.size).toList()

    // Claim the streaming flag synchronously so a rapid second tap is
    // rejected by the entry guard (same rationale as retryFromMessage T145).
    AppLogger.info(TAG_STREAM, "rerunFromToolBlock _isStreaming=true (sync, sid=$activeSessionId)")
    _isStreaming.value = true

    viewModelScope.launch {
        var streamLaunched = false
        try {
            val sid = realSessionId.takeIf { it.isNotEmpty() } ?: sessionId

            // Locate the DB assistant row holding the target tool_use, and
            // the parts-array index of that tool_use within it.
            val dbMessages = chatRepository.loadMessages(sid)
            var cutRow: MessageEntity? = null
            var cutPartIdx = -1
            outer@ for (entity in dbMessages) {
                if (entity.role != "assistant") continue
                val arr = try { org.json.JSONArray(entity.partsJson) } catch (_: Exception) { continue }
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optString("type") != "toolUse") continue
                    val tuId = o.optJSONObject("value")?.optString("toolUseId") ?: ""
                    if (tuId == targetToolUseId) {
                        cutRow = entity
                        cutPartIdx = i
                        break@outer
                    }
                }
            }
            val row = cutRow
            if (row == null || cutPartIdx < 0) {
                // Anchor not in DB (shouldn't happen for a rendered tool
                // block). Abort cleanly without a half-applied truncation.
                Log.w(TAG, "rerunFromToolBlock: toolUseId ${targetToolUseId.take(12)} not found in DB — aborting")
                return@launch
            }

            // Trim the row's parts to those strictly before the target
            // tool_use, preserving array order (parts_json mirrors block
            // order). An assistant turn may hold text + several tool_use
            // parts; we keep everything ahead of the matched index.
            val srcArr = org.json.JSONArray(row.partsJson)
            val keptArr = org.json.JSONArray()
            for (i in 0 until cutPartIdx) keptArr.put(srcArr.get(i))

            if (cutPartIdx == 0) {
                // Nothing precedes the target in its DB row — trimming would
                // leave an empty assistant row. Drop the whole row instead
                // (keepCount = its sort_order). The UI degenerate guard
                // above normally catches this, but a merged-bubble layout
                // could route a first-in-row tool_use here; handle it so we
                // never persist a phantom empty assistant message.
                chatRepository.deleteMessagesAfter(sid, row.sortOrder)
                Log.i(TAG, "rerunFromToolBlock cut at row start (empty trim) tuId=${targetToolUseId.take(12)} keepCount=${row.sortOrder} row=${row.id.take(8)}")
            } else {
                // Delete every row after the trimmed assistant row, then
                // rewrite the trimmed row in place. deleteMessagesAfter
                // keeps rows with sort_order < keepCount, so keepCount =
                // thisRow.sortOrder + 1 drops the following tool_result row
                // + all later turns while keeping (then overwriting) this one.
                chatRepository.deleteMessagesAfter(sid, row.sortOrder + 1)
                chatRepository.updateMessageParts(row.id, keptArr.toString())
                Log.i(TAG, "rerunFromToolBlock sub-message cut tuId=${targetToolUseId.take(12)} keepCount=${row.sortOrder + 1} partIdx=$cutPartIdx trimmedRow=${row.id.take(8)}")
            }

            // T149 parity: revoke memory writes in the dropped range.
            revokeMemoryWritesInDeletedMessages(deletedMessages)

            // Trim the UI in-memory (same approach as retryFromMessage's
            // `_messages.value = retainedHead`, which doesn't reload from
            // DB and so doesn't disturb compact-marker graying): keep the
            // target assistant message with only its blocks BEFORE the
            // target, and drop every later message. Block trim mirrors the
            // parts trim above so UI ↔ history stay in lockstep.
            withContext(Dispatchers.Main) {
                val cur = _messages.value
                val ai = cur.indexOfFirst { it.id == assistantMessageId }
                if (ai >= 0) {
                    val keptBlocks = cur[ai].toolBlocks.take(blockIdx)
                    if (keptBlocks.isEmpty()) {
                        // [T-android-rerun-from-tool-deletes-earlier-turns]
                        // Target was the first block of its bubble — the DB
                        // side dropped the whole row (cutPartIdx==0). Drop
                        // the bubble in the UI too instead of leaving an
                        // empty assistant message; earlier bubbles (the
                        // turns that precede this one) are preserved by
                        // subList(0, ai).
                        _messages.value = cur.subList(0, ai).toList()
                    } else {
                        // Recompute `content` from the surviving text blocks
                        // so it doesn't keep text the renderer just dropped.
                        // The chat list renders ordering from toolBlocks, but
                        // `content` feeds previews / copy, so keep it in sync.
                        val keptText = keptBlocks.filter { it.isText }
                            .joinToString("") { it.content }
                        val trimmed = cur[ai].copy(
                            content = keptText,
                            toolBlocks = keptBlocks,
                            isStreaming = false,
                        )
                        _messages.value = cur.subList(0, ai).toList() + trimmed
                    }
                }
            }
            val keptIds = _messages.value.mapTo(mutableSetOf()) { it.id }
            retainStreamFlushStates(keptIds)
            if (_streamingById.value.isNotEmpty()) {
                _streamingById.value = _streamingById.value.filterKeys { it in keptIds }
            }

            // Rebuild agentHistory from the trimmed DB state.
            agentHistory.clear()
            toolLoopDetector.reset()
            for (entity in chatRepository.loadMessages(sid)) {
                agentHistory.add(messageMapper.toLLMMessage(entity))
            }

            streamLaunched = runRerunStreamTail(initialProvider, "rerunFromToolBlock", request)
        } finally {
            if (!streamLaunched) {
                AppLogger.info(TAG_STREAM, "rerunFromToolBlock _isStreaming=false (setup aborted)")
                _isStreaming.value = false
                request?.reject("rerun_setup_failed")
            }
        }
    }
    return true
}

/**
 * [T-android-regenerate-assistant-message] Resolve [messageId] to its
 * preceding user turn and trigger retryFromMessage, reusing the entire
 * existing Agent retry / truncation / streaming execution pipeline.
 */
fun ChatViewModel.regenerateAssistantMessage(messageId: String): Boolean {
    if (_isStreaming.value || _generatingMessageId.value != null) return false
    val messages = _messages.value
    val asstIndex = messages.indexOfFirst { it.id == messageId }
    if (asstIndex < 0) return false
    val asstMsg = messages[asstIndex]
    if (asstMsg.role != "assistant") return false

    val userIndex = messages.subList(0, asstIndex).indexOfLast { it.role == "user" }
    if (userIndex < 0) return false
    val userMsg = messages[userIndex]

    _generatingMessageId.value = messageId
    _forceScrollToBottom.tryEmit(Unit)
    if (!retryFromMessage(userMsg.id)) {
        // retryFromMessage rejects blank/image-only turns and missing
        // providers before streaming starts. No streaming transition will
        // arrive to clear this marker, so clear it at the rejection site.
        _generatingMessageId.value = null
        return false
    }
    return true
}

/** How many messages follow [messageId]; regenerating it would discard all of them. */
fun ChatViewModel.messagesAfter(messageId: String): Int {
    val messages = _messages.value
    val index = messages.indexOfFirst { it.id == messageId }
    return if (index < 0) 0 else messages.size - index - 1
}

internal fun ChatViewModel.getOrCreateReplyPlayer(): com.openminis.app.speech.ReadAloudPlayer {
    return com.openminis.app.speech.VoiceOutputState.activePlayer
        ?: replyPlayer
        ?: com.openminis.app.speech.ReadAloudPlayer(context).also { replyPlayer = it }
}

/**
 * The reply's read-aloud button: start reading it, or stop while it is the one being read.
 * Pausing belongs to the reading bar ([toggleReplySpeechPause]).
 */
fun ChatViewModel.toggleReplySpeech(messageId: String, displayIndex: Int, rawMarkdown: String) {
    val cur = com.openminis.app.speech.VoiceOutputState.replySpeechState.value
    val readingThis = cur.activeMessageId == messageId && (
        cur.status == com.openminis.app.speech.ReplySpeechState.Status.READING ||
            cur.status == com.openminis.app.speech.ReplySpeechState.Status.PAUSED
        )
    if (readingThis) stopReplySpeech() else startReplySpeech(messageId, displayIndex, rawMarkdown)
}

fun ChatViewModel.toggleReplySpeechPause() {
    val cur = com.openminis.app.speech.VoiceOutputState.replySpeechState.value
    if (!cur.isActive) return
    when (cur.status) {
        com.openminis.app.speech.ReplySpeechState.Status.READING -> {
            (com.openminis.app.speech.VoiceOutputState.activePlayer ?: replyPlayer)?.togglePause()
            com.openminis.app.speech.VoiceOutputState.replySpeechState.value =
                cur.copy(status = com.openminis.app.speech.ReplySpeechState.Status.PAUSED)
        }
        com.openminis.app.speech.ReplySpeechState.Status.PAUSED -> {
            (com.openminis.app.speech.VoiceOutputState.activePlayer ?: replyPlayer)?.togglePause()
            com.openminis.app.speech.VoiceOutputState.replySpeechState.value =
                cur.copy(status = com.openminis.app.speech.ReplySpeechState.Status.READING)
        }
        else -> Unit
    }
}

internal fun ChatViewModel.startReplySpeech(messageId: String, displayIndex: Int, rawMarkdown: String) {
    replySpeechJob?.cancel()
    (com.openminis.app.speech.VoiceOutputState.activePlayer ?: replyPlayer)?.stop()
    val plainText = MarkdownClipboard.markdownToPlainText(rawMarkdown).trim()
    if (plainText.isEmpty()) {
        com.openminis.app.speech.VoiceOutputState.resetReplySpeech()
        return
    }

    val sentences = com.openminis.app.speech.SpeechSentenceSplitter
        .extractCompleteSentences(StringBuilder(plainText), streaming = false)
        .ifEmpty { listOf(plainText) }

    com.openminis.app.speech.VoiceOutputState.replySpeechState.value =
        com.openminis.app.speech.ReplySpeechState(
            activeMessageId = messageId,
            displayIndex = displayIndex,
            status = com.openminis.app.speech.ReplySpeechState.Status.READING,
            currentSentence = 1,
            totalSentences = sentences.size,
        )

    replySpeechJob = viewModelScope.launch {
        val player = getOrCreateReplyPlayer()
        for ((idx, sentence) in sentences.withIndex()) {
            if (com.openminis.app.speech.VoiceOutputState.replySpeechState.value.activeMessageId != messageId) break
            com.openminis.app.speech.VoiceOutputState.replySpeechState.value =
                com.openminis.app.speech.VoiceOutputState.replySpeechState.value.copy(
                    currentSentence = idx + 1,
                    status = com.openminis.app.speech.ReplySpeechState.Status.READING,
                )
            player.speakSentence(sentence)
            while ((player.isSpeaking.value || com.openminis.app.speech.VoiceOutputState.replySpeechState.value.status == com.openminis.app.speech.ReplySpeechState.Status.PAUSED)
                && com.openminis.app.speech.VoiceOutputState.replySpeechState.value.activeMessageId == messageId) {
                kotlinx.coroutines.delay(100)
            }
        }
        if (com.openminis.app.speech.VoiceOutputState.replySpeechState.value.activeMessageId == messageId) {
            com.openminis.app.speech.VoiceOutputState.replySpeechState.value =
                com.openminis.app.speech.VoiceOutputState.replySpeechState.value.copy(
                    status = com.openminis.app.speech.ReplySpeechState.Status.COMPLETED,
                )
            kotlinx.coroutines.delay(3000)
            if (com.openminis.app.speech.VoiceOutputState.replySpeechState.value.status == com.openminis.app.speech.ReplySpeechState.Status.COMPLETED) {
                com.openminis.app.speech.VoiceOutputState.resetReplySpeech()
            }
        }
    }
}

fun ChatViewModel.stopReplySpeech() {
    replySpeechJob?.cancel()
    replySpeechJob = null
    (com.openminis.app.speech.VoiceOutputState.activePlayer ?: replyPlayer)?.stop()
    com.openminis.app.speech.VoiceOutputState.resetReplySpeech()
}

/**
 * Retry from a specific user message: truncate all messages after it
 * (including the assistant response), rebuild agent history, and resend.
 * Mirrors iOS's edit/retry behavior — no duplicate user messages.
 */
/**
 * @param request when given, the retry's own completion: rejected when the retry does not start,
 *   otherwise bound to the stream job it launches (see [runRerunStreamTail]).
 */
fun ChatViewModel.retryFromMessage(messageId: String): Boolean = retryFromMessage(messageId, null)

internal fun ChatViewModel.retryFromMessage(messageId: String, request: AgentTurnHandle?): Boolean {
    if (deferUntilMediaCommitted { retryFromMessage(messageId, request) }) return true
    if (_isStreaming.value) { request?.reject("session_busy"); return false }
    _canResume.value = false
    val messages = _messages.value
    val index = messages.indexOfFirst { it.id == messageId }
    if (index < 0) { request?.reject("message_not_loaded"); return false }
    val message = messages[index]
    // [T-android-tool-autoscroll] Start-of-turn snap — see resume().
    _forceScrollToBottom.tryEmit(Unit)
    val initialProvider = currentProvider
    if (!canRetryMessage(message, initialProvider != null)) {
        if (message.role == "user" && message.content.isNotBlank() && initialProvider == null) {
            _error.value = "No provider configured"
        }
        request?.reject(if (initialProvider == null) "no_provider" else "message_not_retryable")
        return false
    }

    val provider: LLMProvider = initialProvider ?: run { request?.reject("no_provider"); return false }
    request?.userMessageId = messageId
    _error.value = null

    // T149: snapshot messages about to be truncated so we can revoke any
    // memory_write tool blocks they contain. Without this, a retry leaves
    // the on-disk daily log with entries the user has just rewound past.
    val deletedMessages = messages.subList(index + 1, messages.size).toList()

    // Truncate UI messages: keep up to and including this user message.
    // T189: if the retried bubble was still in the queued state (manual
    // retry of a queued message before resumeQueueAfterCancel's grace
    // window — or fallback when auto-resume is disabled), flip it out of
    // queued visuals and drop its queue entry so the upcoming send
    // doesn't double up against a later auto-drain.
    val retainedHead = messages.subList(0, index + 1).map { m ->
        if (m.id == messageId && m.isQueued) {
            m.queuedPromptId?.let { pid ->
                _promptQueue.value = _promptQueue.value.filterNot { it.id == pid }
            }
            m.copy(isQueued = false, queuedPromptId = null)
        } else m
    }
    _messages.value = retainedHead
    // T-streaming-side-channel: scrub stream deltas pointing at
    // messages we just truncated so they can't resurface later.
    val keptIds = retainedHead.mapTo(mutableSetOf()) { it.id }
    retainStreamFlushStates(keptIds)
    if (_streamingById.value.isNotEmpty()) {
        _streamingById.value = _streamingById.value.filterKeys { it in keptIds }
    }

    revokeMemoryWritesInDeletedMessages(deletedMessages)

    // T145: claim the streaming flag SYNCHRONOUSLY so a rapid second tap
    // (or any concurrent send/retry attempt) is rejected by the entry
    // guard. Previously this was set inside the suspended outer launch,
    // leaving a multi-second window during DB cleanup + OAuth refresh
    // where two retries could slip through and spawn duplicate streamJobs.
    // The orphaned first job's `_isStreaming = false` at completion would
    // then flip the UI to "stopped" while the second job was still running.
    AppLogger.info(TAG_STREAM, "retry _isStreaming=true (sync, sid=$activeSessionId)")
    _isStreaming.value = true

    viewModelScope.launch {
        // If setup throws before the inner streamJob is launched, the
        // streaming flag would be stuck true forever. Reset on the
        // unhappy paths; happy path resets in the streamJob's tail.
        var streamLaunched = false
        try {
        val sid = realSessionId.takeIf { it.isNotEmpty() } ?: sessionId

        // Find the DB sort_order cutoff for this user message.
        // UI visible user messages are the N-th user msg with actual text content.
        // Count which visible user message this is (0-based).
        val visibleUserIndex = messages.subList(0, index + 1).count { it.role == "user" } - 1
        val dbMessages = chatRepository.loadMessages(sid)
        // Walk DB rows, counting visible user messages (those with non-toolResult text)
        var visibleUserCount = 0
        var cutoffSortOrder = -1
        for (entity in dbMessages) {
            if (entity.role == "user") {
                // Check if this user message has visible text (not toolResult-only).
                // [T-ios-retry-anchor-synthetic-user] Synthetic user rows the
                // agent loop persists WITHOUT a UI bubble — resume()'s
                // stop-continue "<system-reminder>" message — must not count,
                // or the cutoff anchors one user message too early and the
                // retried bubble (plus the whole last turn) is silently
                // dropped from the rebuilt history (mirrors the iOS fix).
                val hasText = try {
                    val arr = org.json.JSONArray(entity.partsJson)
                    (0 until arr.length()).any { i ->
                        val o = arr.getJSONObject(i)
                        val v = o.optString("value", "")
                        o.optString("type") == "text" && v.isNotBlank() &&
                            !v.trimStart().startsWith("<system-reminder>")
                    }
                } catch (_: Exception) { true }
                if (hasText) {
                    if (visibleUserCount == visibleUserIndex) {
                        cutoffSortOrder = entity.sortOrder + 1
                        break
                    }
                    visibleUserCount++
                }
            }
        }
        if (cutoffSortOrder >= 0) {
            chatRepository.deleteMessagesAfter(sid, cutoffSortOrder)
        }

        // Rebuild agentHistory from remaining DB messages
        agentHistory.clear()
        toolLoopDetector.reset()
        val remaining = chatRepository.loadMessages(sid)
        for (entity in remaining) {
            agentHistory.add(messageMapper.toLLMMessage(entity))
        }

        streamLaunched = runRerunStreamTail(provider, "retryFromMessage", request)
        } finally {
            if (!streamLaunched) {
                AppLogger.info(TAG_STREAM, "retry _isStreaming=false (setup aborted)")
                _isStreaming.value = false
                request?.reject("retry_setup_failed")
            }
        }
    }
    return true
}

/**
 * [T-android-rerun-from-tool-block-position] Shared streaming tail used by
 * both [retryFromMessage] and [rerunFromToolBlock]: refresh the OAuth
 * token if needed, build the (OAuth-prefixed) system prompt, and launch
 * the agent-loop stream job. Callers must have already (a) claimed
 * `_isStreaming = true` synchronously, (b) truncated UI + DB to the desired
 * re-entry point, and (c) rebuilt [agentHistory]. Returns true once the
 * stream job is launched (the caller's outer `finally` resets
 * `_isStreaming` only when this returns false / throws first).
 */
internal suspend fun ChatViewModel.runRerunStreamTail(
    initialProvider: LLMProvider,
    label: String,
    request: AgentTurnHandle? = null,
): Boolean {
    var provider = initialProvider
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
                        provider = com.openminis.app.provider.ProviderFactory.create(
                            instance, freshToken, currentModel ?: provider.model, context
                        )
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

    // _isStreaming was already set synchronously by the caller.
    val launchedProvider = provider
    val sourceRunId = beginBotSourceRun()
    streamJob = viewModelScope.launch(Dispatchers.IO) {
        AppLogger.info(TAG_STREAM, "$label streamJob ENTER sid=$activeSessionId")
        try {
            SessionConcurrencyManager.acquireSlot(activeSessionId)
            AppLogger.debug(TAG_STREAM, "$label streamJob slot acquired")
            SessionActivityTracker.setActive(activeSessionId, onStop = { cancelStream() })
            val activeFallbackStrategy = providerRepository.config.value.fallbackTrigger
            val fallbackProviders = buildFallbackProviders(launchedProvider)
            var botTurnSucceeded = false
            try {
                AppLogger.info(TAG_STREAM, "$label runAgentLoop CALL")
                val outcome = withBotTurnLock {
                    runAgentLoop(
                        provider = launchedProvider,
                        systemPrompt = systemPrompt,
                        fallbackProviders = fallbackProviders,
                        fallbackStrategy = activeFallbackStrategy,
                    )
                }
                botTurnSucceeded = outcome == AgentTurnOutcome.Completed
                request?.record(outcome)
                AppLogger.info(TAG_STREAM, "$label runAgentLoop RETURN normal")
            } catch (e: CancellationException) {
                request?.record(AgentTurnOutcome.Cancelled)
                AppLogger.info(TAG_STREAM, "$label runAgentLoop CANCELLED")
                Log.d(TAG, "Agent loop cancelled")
            } catch (e: Exception) {
                request?.record(AgentTurnOutcome.Failed(e.message ?: "Unknown error"))
                AppLogger.error(TAG_STREAM, "$label runAgentLoop EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
                Log.e(TAG, "Agent loop error ($label)", e)
                setInlineError(e.message ?: "Unknown error")
                // T298: flag the upcoming setInactive() so the
                // background completion notifier renders the ❌
                // variant instead of a clean success.
                SessionActivityTracker.markStreamError(activeSessionId)
            } finally {
                AppLogger.info(TAG_STREAM, "$label streamJob FINALLY enter")
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
                AppLogger.info(TAG_STREAM, "$label streamJob FINALLY exit")
            }
        } catch (e: CancellationException) {
            request?.record(AgentTurnOutcome.Cancelled)
            AppLogger.info(TAG_STREAM, "$label streamJob CANCELLED waiting for slot")
            Log.d(TAG, "Cancelled while waiting for concurrency slot")
            // A source Bot turn can be cancelled while waiting for the
            // global capacity slot. Settle its queued delegations here;
            // the inner finally is never reached in this path.
            com.openminis.app.tools.BotDelegationCoordinator.current()?.onSourceTurnSettled(activeSessionId, false, sourceRunId)
        }
        // [T-android-stale-streamjob-clears-isstreaming] Only the current
        // streamJob is allowed to flip _isStreaming false. An orphaned
        // earlier job (cancelled but its finally still draining downstream
        // I/O) reaching this tail AFTER a fresh send/resume/retry has
        // already taken over would otherwise hide the Stop button while
        // the new turn is still streaming. See `var streamJob` KDoc and
        // XIN 2026-06-12 log (20:22:26 / 20:23:25).
        if (streamJob === coroutineContext[Job]) {
            AppLogger.info(TAG_STREAM, "$label _isStreaming=false (about to set)")
            _isStreaming.value = false
        } else {
            AppLogger.info(TAG_STREAM, "$label _isStreaming SKIPPED (stale job; current=${streamJob?.hashCode()} this=${coroutineContext[Job]?.hashCode()})")
        }
        AppLogger.info(TAG_STREAM, "$label streamJob EXIT")
    }
    streamJob?.let { request?.bind(it) }
    return true
}

/**
 * T187: enter edit mode for [messageId]. Returns the cleaned text the
 * caller should drop into the composer (with any
 * `<user-attached-files>` XML stripped), or null when the message
 * cannot be edited (streaming in progress, message missing, or not
 * a user turn). Setting `_editingMessageId` is what flips the
 * composer into edit-mode UI; the next sendMessage call sees the
 * non-null id and truncates the conversation from that point.
 * Mirrors iOS AIChatViewModel.editMessage(_:) (L2468).
 */
fun ChatViewModel.editMessage(messageId: String): String? {
    if (_isStreaming.value) return null
    val msg = _messages.value.firstOrNull { it.id == messageId } ?: return null
    if (msg.role != "user") return null
    var text = msg.content
    val startIdx = text.indexOf("<user-attached-files>")
    if (startIdx >= 0) {
        val endTag = "</user-attached-files>"
        val endIdx = text.indexOf(endTag, startIdx)
        text = if (endIdx >= 0) {
            (text.substring(0, startIdx) + text.substring(endIdx + endTag.length)).trim()
        } else {
            text.substring(0, startIdx).trim()
        }
    }
    val restored = mutableListOf<InputAttachment>()
    msg.imageUris.forEachIndexed { i, uri ->
        val name = msg.attachmentNames.getOrNull(i) ?: uri.lastPathSegment ?: "image"
        restored.add(
            InputAttachment(
                fileName = name,
                uri = uri,
                mimeType = guessMimeType(name, fallback = "image/*"),
                kind = InputAttachment.Kind.IMAGE,
            ),
        )
    }
    msg.attachmentUris.forEachIndexed { i, uri ->
        val name = msg.attachmentNames.getOrNull(msg.imageUris.size + i)
            ?: uri.lastPathSegment ?: "file"
        restored.add(
            InputAttachment(
                fileName = name,
                uri = uri,
                mimeType = guessMimeType(name, fallback = "application/octet-stream"),
                kind = InputAttachment.Kind.DOCUMENT,
            ),
        )
    }
    _attachments.value = restored
    _editingMessageId.value = messageId
    AppLogger.info(TAG_STREAM, "✏️ editMessage id=${messageId.take(8)} text=${text.length}ch attachments=${restored.size}")
    return text
}

internal fun ChatViewModel.guessMimeType(fileName: String, fallback: String): String {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    if (ext.isEmpty()) return fallback
    return android.webkit.MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(ext) ?: fallback
}

/**
 * T187: leave edit mode without sending. Just clears the id flag —
 * caller (ChatScreen) is responsible for clearing inputText. iOS
 * parity: AIChatViewModel.cancelEdit (L2522).
 */
fun ChatViewModel.cancelEdit() {
    if (_editingMessageId.value != null) {
        _attachments.value = emptyList()
        AppLogger.info(TAG_STREAM, "✏️ cancelEdit")
    }
    _editingMessageId.value = null
}

/**
 * [T-android-delete-single-assistant-message] Delete exactly one assistant
 * message without truncating the subsequent messages, updating DB, agent
 * history, speech and memory writes.
 */
fun ChatViewModel.deleteSingleAssistantMessage(messageId: String) {
    if (_isStreaming.value) return
    val messages = _messages.value
    val index = messages.indexOfFirst { it.id == messageId }
    if (index < 0) return
    val target = messages[index]
    if (target.role != "assistant") return

    val sid = activeSessionId ?: return
    viewModelScope.launch {
        try {
            runAfterDatabaseDelete(
                delete = { chatRepository.deleteSingleMessage(messageId) },
                afterCommit = {
                    val remaining = chatRepository.loadMessages(sid)
                    _messages.value = _messages.value.filterNot { it.id == messageId }
                    agentHistory.clear()
                    toolLoopDetector.reset()
                    for (entity in remaining) {
                        agentHistory.add(messageMapper.toLLMMessage(entity))
                    }
                    runCatching {
                        revokeMemoryWritesInDeletedMessages(listOf(target))
                    }.onFailure { error ->
                        Log.e(TAG, "deleteSingleAssistantMessage: memory revoke failed after DB commit", error)
                    }
                    if (com.openminis.app.speech.VoiceOutputState.replySpeechState.value.activeMessageId == messageId) {
                        stopReplySpeech()
                    }
                    runCatching {
                        chatRepository.updateSessionPreview(sid, remaining.lastOrNull()?.partsJson ?: "[]")
                    }
                    AppLogger.info(TAG, "deleteSingleAssistantMessage: removed message $messageId, ${remaining.size} remain")
                },
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.e(TAG, "deleteSingleAssistantMessage: database delete failed; state unchanged", error)
        }
    }
}

fun ChatViewModel.deleteFromMessage(messageId: String) {
    if (_isStreaming.value) return
    val messages = _messages.value
    val index = messages.indexOfFirst { it.id == messageId }
    if (index < 0) return

    val deletedMessages = messages.subList(index, messages.size).toList()
    val retainedHead = messages.subList(0, index)
    val sid = activeSessionId ?: return
    viewModelScope.launch {
        try {
            val target = messages[index]
            val cutoffSortOrder = resolveDeleteCutoffSortOrder(sid, messages, index, target)
            if (cutoffSortOrder < 0) {
                AppLogger.warning(TAG, "deleteFromMessage: could not resolve cutoff for $messageId")
                return@launch
            }

            runAfterDatabaseDelete(
                delete = { chatRepository.deleteMessagesAfter(sid, cutoffSortOrder) },
                afterCommit = {
                    // Read the committed database state before publishing
                    // any in-memory changes. If this read fails, the old
                    // UI remains intact and the next session load can
                    // reconcile from Room.
                    val remaining = chatRepository.loadMessages(sid)
                    _canResume.value = false
                    for (m in deletedMessages) {
                        m.queuedPromptId?.let { pid ->
                            _promptQueue.value = _promptQueue.value.filterNot { it.id == pid }
                        }
                    }
                    _messages.value = retainedHead

                    val keptIds = retainedHead.mapTo(mutableSetOf()) { it.id }
                    retainStreamFlushStates(keptIds)
                    if (_streamingById.value.isNotEmpty()) {
                        _streamingById.value = _streamingById.value.filterKeys { it in keptIds }
                    }

                    // Memory is an external side effect, so it follows the
                    // committed Room delete instead of preceding it.
                    runCatching {
                        revokeMemoryWritesInDeletedMessages(deletedMessages)
                    }.onFailure { error ->
                        Log.e(TAG, "deleteFromMessage: memory revoke failed after DB commit", error)
                    }
                    if (deletedMessages.any {
                            it.id == com.openminis.app.speech.VoiceOutputState.replySpeechState.value.activeMessageId
                        }) {
                        stopReplySpeech()
                    }

                    agentHistory.clear()
                    toolLoopDetector.reset()
                    for (entity in remaining) {
                        agentHistory.add(messageMapper.toLLMMessage(entity))
                    }
                    runCatching {
                        chatRepository.updateSessionPreview(
                            sid,
                            remaining.lastOrNull()?.partsJson ?: "[]",
                        )
                    }
                    AppLogger.info(
                        TAG,
                        "deleteFromMessage: cut at sortOrder=$cutoffSortOrder, " +
                            "${deletedMessages.size} message(s) removed, ${remaining.size} remain",
                    )
                },
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.e(TAG, "deleteFromMessage: database delete failed; state unchanged", error)
        }
    }
}

internal suspend fun ChatViewModel.resolveDeleteCutoffSortOrder(
    sid: String,
    messages: List<ChatMessage>,
    index: Int,
    target: ChatMessage,
): Int {
    val dbMessages = chatRepository.loadMessages(sid)
    val visibleUserIndex = messages.subList(0, index + 1).count { it.role == "user" } - 1
    if (visibleUserIndex < 0) return 0
    var visibleUserCount = 0
    for (entity in dbMessages) {
        if (entity.role != "user") continue
        val hasText = try {
            val arr = org.json.JSONArray(entity.partsJson)
            (0 until arr.length()).any { i ->
                val o = arr.getJSONObject(i)
                val v = o.optString("value", "")
                o.optString("type") == "text" && v.isNotBlank() &&
                    !v.trimStart().startsWith("<system-reminder>")
            }
        } catch (_: Exception) { true }
        if (!hasText) continue
        if (visibleUserCount == visibleUserIndex) {
            return if (target.role == "user") {
                entity.sortOrder
            } else {
                entity.sortOrder + 1
            }
        }
        visibleUserCount++
    }
    return -1
}

/**
 * [T-android-chat-branch-message] Fork the session at [messageId], creating
 * a new independent conversation from the prefix up to that assistant message.
 */
fun ChatViewModel.forkSessionAtMessage(messageId: String, onForkSuccess: (String) -> Unit) {
    if (_isStreaming.value) return
    val sid = activeSessionId ?: return
    val sessionForkManager = com.openminis.app.data.SessionForkManager(
        context = context,
        chatRepository = chatRepository,
        skillRepository = skillRepository,
        filesDir = context.filesDir,
    )
    viewModelScope.launch {
        try {
            val newSessionId = sessionForkManager.forkSessionAtMessage(sid, messageId)
            if (newSessionId != null) {
                withContext(Dispatchers.Main) {
                    onForkSuccess(newSessionId)
                }
            } else {
                withContext(Dispatchers.Main) {
                    com.openminis.app.ui.components.MinisToast.show(context, context.getString(com.openminis.app.R.string.chat_branch_failed))
                }
            }
        } catch (e: Exception) {
            com.openminis.app.logging.AppLogger.warning("ChatViewModel", "forkSessionAtMessage failed: ${e.message}")
            withContext(Dispatchers.Main) {
                com.openminis.app.ui.components.MinisToast.show(context, context.getString(com.openminis.app.R.string.chat_branch_failed))
            }
        }
    }
}

/**
 * T187: drop the message at [messageId] *and* every later message
 * (in UI, in agentHistory, and on disk) so the new sendMessage()
 * call below this can persist the edited text as a fresh user
 * turn at the same position. Reuses the cutoff-search machinery
 * from retryFromMessage but offsets by `entity.sortOrder` (not
 * +1) — retry preserves the original turn, edit replaces it.
 */
internal suspend fun ChatViewModel.truncateBeforeEdit(messageId: String) {
    val messages = _messages.value
    val index = messages.indexOfFirst { it.id == messageId }
    if (index < 0) return

    val deletedMessages = messages.subList(index, messages.size).toList()
    // [T-android-uimessages-sublist-cme] Defensive: without `.toList()` this
    // stores a live subList VIEW as `_messages.value`.
    //
    // Reproduced on device with this `.toList()` reverted (Pixel 4a): the
    // truncation ran (8 messages → 4) and a further message was sent, and it
    // did NOT crash — the next `+` copies the view into a plain ArrayList
    // before anything can invalidate it. So this line is hardening, not the
    // proven cause of the reported CME. See the long note on `uiMessages`.
    // (`deletedMessages` above already copies; this line did not.)
    val kept = messages.subList(0, index).toList()
    _messages.value = kept
    if (_streamingById.value.isNotEmpty()) {
        val keptIds = kept.mapTo(mutableSetOf()) { it.id }
        retainStreamFlushStates(keptIds)
        _streamingById.value = _streamingById.value.filterKeys { it in keptIds }
    }
    revokeMemoryWritesInDeletedMessages(deletedMessages)

    val sid = realSessionId.takeIf { it.isNotEmpty() } ?: sessionId
    // Visible-user index of the *edited* message — count user turns
    // strictly before `index`, which is the 0-based ordinal of the
    // edited turn itself.
    val visibleUserIndex = messages.subList(0, index).count { it.role == "user" }
    val dbMessages = chatRepository.loadMessages(sid)
    var visibleUserCount = 0
    var cutoffSortOrder = -1
    for (entity in dbMessages) {
        if (entity.role == "user") {
            val hasText = try {
                val arr = org.json.JSONArray(entity.partsJson)
                (0 until arr.length()).any { i ->
                    val o = arr.getJSONObject(i)
                    // [T-android-retry-attachment-loss] Exclude the now-
                    // persisted <user-attached-files> XML text part so this
                    // "is this a visible user bubble?" count stays identical
                    // to pre-XML-persistence behaviour. An attachments-only
                    // turn must NOT flip to hasText just because the XML
                    // inventory is now a text part — that would shift the
                    // retry/edit cutoff onto the wrong message.
                    // [T-ios-retry-anchor-synthetic-user] Likewise exclude
                    // resume()'s synthetic stop-continue <system-reminder>
                    // user row — it has no UI bubble, so counting it shifts
                    // the cutoff one user message too early.
                    o.optString("type") == "text" &&
                        messageMapper.stripAttachedFilesXml(o.optString("value", "")).isNotBlank() &&
                        !o.optString("value", "").trimStart().startsWith("<system-reminder>")
                }
            } catch (_: Exception) { true }
            if (hasText) {
                if (visibleUserCount == visibleUserIndex) {
                    // ChatDao.deleteMessagesAfter is `sort_order >= keepCount`
                    // → passing this row's sortOrder deletes IT and everything
                    // after, which is exactly what edit semantics want.
                    cutoffSortOrder = entity.sortOrder
                    break
                }
                visibleUserCount++
            }
        }
    }
    if (cutoffSortOrder >= 0) {
        chatRepository.deleteMessagesAfter(sid, cutoffSortOrder)
    }
    agentHistory.clear()
    toolLoopDetector.reset()
    val remaining = chatRepository.loadMessages(sid)
    for (entity in remaining) {
        agentHistory.add(messageMapper.toLLMMessage(entity))
    }
    AppLogger.info(
        TAG_STREAM,
        "✏️ truncateBeforeEdit cutoffSortOrder=$cutoffSortOrder remaining=${remaining.size}"
    )
}
