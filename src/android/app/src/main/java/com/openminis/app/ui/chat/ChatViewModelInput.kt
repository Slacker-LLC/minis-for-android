package com.openminis.app.ui.chat

import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.chat.ChatViewModel.Companion.LONG_SESSION_THRESHOLD
import com.openminis.app.ui.chat.ChatViewModel.Companion.TAG
import com.openminis.app.ui.chat.ChatViewModel.Companion.VISIBLE_MESSAGE_CAP_STEP
import com.openminis.app.ui.chat.ChatViewModel.Companion.joinDraftWithSnippet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Bump the visible cap by [VISIBLE_MESSAGE_CAP_STEP], saturating at
 * the total message count. Safe to call when there are no older
 * messages — it's a no-op (cap clamps to size). Called by the
 * LazyColumn's "load older" header when the user reaches the top of
 * the windowed slice.
 */
fun ChatViewModel.loadOlderMessages() {
    val totalNow = _messages.value.size
    if (totalNow <= LONG_SESSION_THRESHOLD) return
    val next = (_visibleMessageCap.value + VISIBLE_MESSAGE_CAP_STEP).coerceAtMost(totalNow)
    if (next != _visibleMessageCap.value) {
        _visibleMessageCap.value = next
    }
}

/**
 * [T-android-stream-flush-review] Cancel a message's pending trailing flush
 * and drop its throttle accumulator. Call from EVERY stream-termination
 * path (natural end, cancel, turn-limit, retry-truncate, clearChat) so a
 * trailing coroutine — which runs on viewModelScope, NOT streamJob, and is
 * therefore NOT cancelled by streamJob.cancel() — can't fire after the
 * side channel was drained and re-revive a stale "thinking" overlay row.
 */
internal fun ChatViewModel.clearStreamFlushState(id: String) {
    streamFlushStates.remove(id)?.trailingJob?.cancel()
}

internal fun ChatViewModel.clearAllStreamFlushStates() {
    streamFlushStates.values.forEach { it.trailingJob?.cancel() }
    streamFlushStates.clear()
}

/** Cancel + drop flush states for any message id NOT in [keptIds] (retry/truncate). */
internal fun ChatViewModel.retainStreamFlushStates(keptIds: Set<String>) {
    val drop = streamFlushStates.keys.filter { it !in keptIds }
    for (id in drop) streamFlushStates.remove(id)?.trailingJob?.cancel()
}

// Dual-path flush thresholds — ported from iOS. Time tiers scale with total
// length; the newline fast-path flushes immediately on a line break once
// enough new chars have accumulated, gated to short docs so dense
// box-drawing streams don't pin the flush rate to the per-token cadence.
internal fun ChatViewModel.streamFlushThrottleMs(len: Int): Long = when {
    len < 500 -> 200L
    len < 2_000 -> 300L
    len < 32_000 -> 500L
    len < 64_000 -> 1_000L
    len < 128_000 -> 1_500L
    else -> 2_000L
}

/** Read-and-clear the pending caret so it applies exactly once. */
fun ChatViewModel.consumePendingCaret(): Int? {
    val c = _pendingCaret.value
    _pendingCaret.value = null
    return c
}

fun ChatViewModel.setInputText(value: String) {
    _inputText.value = value
}

/**
 * [T-selection-add-to-input] Append [snippet] to the chat composer
 * with a single trailing space:
 *   - composer empty → `"<snippet> "`
 *   - composer non-empty → `"<existing> <snippet> "`
 *
 * Whitespace between [existing] and [snippet] is normalized to a
 * single space so we never produce `"foo  bar "` when the user's
 * draft happens to end in a trailing space already.
 */
fun ChatViewModel.appendToInputText(snippet: String) {
    val joined = joinDraftWithSnippet(_inputText.value, snippet) ?: return
    _inputText.value = joined
}

fun ChatViewModel.stashPastedText(text: String): String {
    val entry = PastedText(id = nextPasteId++, text = text)
    _pastedTexts.value = _pastedTexts.value + entry
    AppLogger.info(TAG, "[Paste] stashed #${entry.id} (${text.length} chars)")
    return entry.placeholder
}

fun ChatViewModel.stashPastedTextAsFile(text: String): InputAttachment? {
    val dir = java.io.File(context.cacheDir, "pasted_text").apply { mkdirs() }
    val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
        .format(java.util.Date())
    val name = "Pasted_$stamp-${java.util.UUID.randomUUID().toString().take(8)}.txt"
    val file = java.io.File(dir, name)
    return try {
        file.writeText(text)
        val attachment = InputAttachment(
            fileName = name,
            uri = android.net.Uri.fromFile(file),
            mimeType = "text/plain",
            kind = InputAttachment.Kind.DOCUMENT,
        )
        addAttachment(attachment)
        AppLogger.info(
            TAG,
            "[Paste] oversize paste -> file attachment $name (${text.length} chars)",
        )
        attachment
    } catch (e: Exception) {
        AppLogger.warning(TAG, "[Paste] failed to write oversize paste: ${e.message}")
        null
    }
}

fun ChatViewModel.removePastedText(id: Int) {
    _pastedTexts.value = _pastedTexts.value.filterNot { it.id == id }
}

/** Cancel an in-flight compaction. No-op when nothing is running. */
fun ChatViewModel.cancelCompact() {
    val job = compactJob ?: return
    if (!job.isActive) return
    AppLogger.info(TAG, "[Compact] cancelled by user")
    job.cancel(CancellationException("compact cancelled by user"))
}

internal fun ChatViewModel.acceptExternalMessage(message: MessageEntity) {
    require(message.sessionId == activeSessionId)
    pendingExternalMessages.add(message)
    viewModelScope.launch {
        sessionLoaded.first { it }
        if (_messages.value.none { it.id == message.id }) {
            val parsed = mapOf(message.id to runCatching { org.json.JSONArray(message.partsJson) })
            val visible = messageMapper.toChatMessages(listOf(message), parsed)
            _messages.value = _messages.value + visible
            visible.forEach { sessionEventEmitter.messageCreated(it) }
        }
    }
}

// Called only at serialized history boundaries, never from the receipt worker.
internal fun ChatViewModel.mergeExternalHistory() {
    while (true) {
        val message = pendingExternalMessages.poll() ?: break
        if (agentHistory.none { it.dbMessageId == message.id }) {
            agentHistory.add(messageMapper.toLLMMessage(message))
        }
    }
}

/**
 * The sub agent roster this session may delegate to, or null when the roster tool is not offered:
 * switched off in Settings (the older single-shot schema applies), or this session is itself a sub
 * agent's child (delegation is one level deep, so naming the roster to a child would be pure cost).
 */
internal fun ChatViewModel.subAgentRoster(): List<com.openminis.app.data.model.SubAgentDefinition>? {
    if (!com.openminis.app.agent.subagents.SubAgents.isEnabled()) return null
    if (sessionSource == ChatSessionEntity.SOURCE_SUB_AGENT) return null
    return com.openminis.app.agent.subagents.SubAgentStore.currentRoster()
}

/** The tool bullet plus the "which sub agent for which job" roster, or null when not offered. */
internal fun ChatViewModel.subAgentPromptFragment(): String? {
    val roster = subAgentRoster() ?: return null
    val entries = providerRepository.allVisibleEntries()
    val callable = subAgentCallableModels()
    val autoNote = if (callable.isEmpty()) "Auto — you choose with model_choice"
        else "Auto — you choose with model_choice, or name a listed model with model"
    val section = com.openminis.app.agent.subagents.SubAgentTask.rosterSection(roster) { def ->
        val pinned = def.pinnedEntryId
        when {
            pinned == null -> autoNote
            else -> "fixed — " + (entries.firstOrNull { it.id == pinned }?.model?.displayName ?: "unavailable model")
        }
    }
    val models = com.openminis.app.agent.subagents.SubAgentTask.callableModelsSection(callable)
    return com.openminis.app.agent.subagents.SubAgentTask.SYSTEM_PROMPT_BULLET + "\n" + section + models
}

/**
 * The models this session's delegating model may name in `subagent.model`: the user's agent model
 * list, offered only while some sub agent is set to Auto (a pinned one ignores the parameter, so
 * describing models nobody can use would be pure prompt cost).
 */
internal fun ChatViewModel.subAgentCallableModels(): List<com.openminis.app.agent.subagents.CallableModel> {
    val roster = subAgentRoster() ?: return emptyList()
    if (roster.none { it.pinnedEntryId == null }) return emptyList()
    return com.openminis.app.agent.subagents.SubAgents.callableModels(providerRepository)
}
