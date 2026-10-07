package com.openminis.app.ui.chat

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.chat.ChatViewModel.Companion.TAG
import com.openminis.app.ui.chat.ChatViewModel.SessionTokenStats
import com.openminis.app.ui.chat.ChatViewModel.ThinkingInfo
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject

fun ChatViewModel.ackClearChatConfirmRequest() {
    _clearChatConfirmRequested.value = false
}

/**
 * Revoke a previously recorded memory_write by removing its entry from
 * today's or yesterday's daily log on disk, and dropping the row from
 * [memoryToolRecords] so the SessionMemorySheet reflects the removal.
 *
 * Returns the repository result so the UI can show a success / not-found
 * / I/O error dialog. The original ChatMessage tool block stays in the
 * conversation history untouched — only the on-disk entry and the
 * op-log row are mutated.
 */
fun ChatViewModel.revokeMemoryRecord(record: MemoryToolRecord): com.openminis.app.data.repository.MemoryRepository.EntryMutationResult {
    val repo = memoryRepository
        ?: return com.openminis.app.data.repository.MemoryRepository.EntryMutationResult.IOError("Memory not available")
    val written = record.writtenContent
        ?: return com.openminis.app.data.repository.MemoryRepository.EntryMutationResult.NotFound
    val result = repo.revokeEntry(written)
    if (result is com.openminis.app.data.repository.MemoryRepository.EntryMutationResult.Success) {
        _memoryToolRecords.value = _memoryToolRecords.value - record
    }
    return result
}

/**
 * T149: revoke every `memory_write` tool block embedded in the supplied
 * messages. Used when a retry path truncates the conversation — the
 * deleted assistant turns may have written entries to today's daily
 * memory log, and leaving them on disk after the conversation rewinds
 * means user-visible history is gone but the side effects remain.
 *
 * We match by the `content` field of the tool args against
 * [MemoryToolRecord.writtenContent] (which is what `revokeMemoryRecord`
 * keys on). If multiple records share the same content body — possible
 * if the agent wrote the same note twice — we revoke them in the
 * reverse insertion order so the most recent disk write is removed
 * first; the repository's revokeEntry only removes the first match
 * each call, so subsequent records may end up NotFound on disk but
 * still get pulled from the in-memory record list.
 */
internal fun ChatViewModel.revokeMemoryWritesInDeletedMessages(deletedMessages: List<ChatMessage>) {
    if (memoryRepository == null) return
    val deletedContents = mutableListOf<String>()
    for (msg in deletedMessages) {
        for (block in msg.toolBlocks) {
            if (block.kind != "tool_use") continue
            if (block.toolName != "memory_write") continue
            val content = try {
                JSONObject(block.toolArgs).optString("content", "")
            } catch (_: Exception) { "" }
            if (content.isNotBlank()) deletedContents.add(content)
        }
    }
    if (deletedContents.isEmpty()) return
    Log.i(TAG, "revokeMemoryWritesInDeletedMessages: ${deletedContents.size} write(s) to revoke")
    for (content in deletedContents.asReversed()) {
        // Find the latest matching record so revoke targets the most
        // recent disk entry first. Snapshot value because revoke mutates
        // the flow.
        val record = _memoryToolRecords.value.lastOrNull {
            it.isWrite && it.writtenContent == content
        } ?: continue
        val result = revokeMemoryRecord(record)
        Log.i(TAG, "  revoke result: ${result::class.simpleName}")
    }
}

/**
 * Replace the body of a previously recorded memory_write with
 * [newContent]. Mirrors iOS `MemoryWriteDetailView.replaceEntryInLog`.
 * On success, also updates the in-memory [MemoryToolRecord] so a
 * subsequent revoke or revisit sees the new body.
 */
fun ChatViewModel.replaceMemoryRecord(
    record: MemoryToolRecord,
    newContent: String,
): com.openminis.app.data.repository.MemoryRepository.EntryMutationResult {
    val repo = memoryRepository
        ?: return com.openminis.app.data.repository.MemoryRepository.EntryMutationResult.IOError("Memory not available")
    val old = record.writtenContent
        ?: return com.openminis.app.data.repository.MemoryRepository.EntryMutationResult.NotFound
    val result = repo.replaceEntryBody(old, newContent)
    if (result is com.openminis.app.data.repository.MemoryRepository.EntryMutationResult.Success) {
        _memoryToolRecords.value = _memoryToolRecords.value.map {
            if (it === record) it.copy(
                writtenContent = newContent,
                preview = newContent.lines().firstOrNull { line -> line.isNotBlank() }?.take(100) ?: "",
            ) else it
        }
    }
    return result
}

/** [T-android-enhanced-cache] True once the user accepted the one-time warning. */
fun ChatViewModel.isEnhancedCacheConfirmed(): Boolean =
    com.openminis.app.data.EnhancedCachePrefs.isConfirmed(context)

/**
 * [T-android-enhanced-cache] Enable Enhanced Cache after the confirmation
 * dialog was accepted (records the durable acknowledgement) and flips the
 * in-memory toggle on.
 */
fun ChatViewModel.confirmAndEnableEnhancedCache() {
    com.openminis.app.data.EnhancedCachePrefs.setConfirmed(context)
    _enhancedCacheEnabled.value = true
}

/**
 * [T-android-enhanced-cache] Toggle the switch when confirmation is not
 * required (turning it OFF, or turning it ON after the user already
 * acknowledged). The confirmation-gated first enable is handled in the UI.
 */
fun ChatViewModel.setEnhancedCacheEnabled(enabled: Boolean) {
    _enhancedCacheEnabled.value = enabled
}

fun ChatViewModel.setFastModeEnabled(enabled: Boolean) {
    com.openminis.app.data.FastModePrefs.setEnabled(context, enabled)
    _fastModeEnabled.value = enabled
}

fun ChatViewModel.setAutoCompactEnabled(enabled: Boolean) {
    com.openminis.app.data.AutoCompactPrefs.setEnabled(context, enabled)
    _autoCompactEnabled.value = enabled
}

/**
 * [T-context-window-live-read] Effective context window for capacity
 * judgment (compaction warnings, tool-output offload, empty-response
 * heuristic, Token Usage sheet). Reads LIVE state on every call instead of
 * the `currentModel` snapshot, so editing the model's context window or
 * the bound group's `contextLimitTokens` takes effect on the very next
 * judgment without re-picking the model/group (mirrors iOS fcc22b66):
 *   1. the active entry's model is re-resolved from the current repository
 *      config (folds ModelOverrides live), falling back to the snapshot
 *      only when the entry can't be found (e.g. synced sessions before
 *      config finished loading);
 *   2. the result is clamped by the bound group's `contextLimitTokens`
 *      (null / <=0 = unlimited). Pre-fix that group field was write-only
 *      on Android — persisted by the group editor but never consulted at
 *      runtime.
 */
internal fun ChatViewModel.effectiveContextWindowTokens(): Int? {
    val config = providerRepository.config.value
    val liveModel = _activeEntryId.value
        ?.let { id -> config.modelEntries.find { it.id == id }?.model }
        ?: currentModel
    val window = liveModel?.contextWindowTokens ?: return null
    val entryLimit = _activeEntryId.value
        ?.let { id -> config.modelEntries.find { it.id == id }?.overrides?.contextLimitTokens }
        ?.takeIf { it > 0 }
    return if (entryLimit != null) minOf(window, entryLimit) else window
}

/** Read-only view of the current thinking configuration for the model. */
fun ChatViewModel.thinkingInfo(): ThinkingInfo? {
    val model = currentModel ?: return null
    val supported = model.supportsReasoning == true
    val level = _thinkingLevel.value
    val enabled = supported && level.isEnabled
    val levelText = if (enabled) level.displayName else "—"
    return ThinkingInfo(supported, enabled, levelText)
}

/**
 * Load session-level token aggregates from the database. Suspend so the
 * Token Usage sheet can fetch on demand without keeping a live subscription
 * — token data rarely changes mid-view, and we want to avoid reactive
 * overhead per token chunk.
 */
suspend fun ChatViewModel.loadSessionTokenStats(): SessionTokenStats {
    val sid = realSessionId.ifEmpty { sessionId }
    if (sid.isEmpty()) return SessionTokenStats(0, 0, 0, 0, 0, 0)
    return SessionTokenStatsCalc.aggregate(chatRepository.sessionTokenUsages(sid), _messages.value)
}

/**
 * Execute a slash command. Returns the text the composer should hold
 * afterward (caret via [pendingCaret] when relevant).
 *
 * [T-android-slash-menu-align-ios-prepend] Over-content (the menu was
 * opened via the "/" button, so [savedInputBeforeSlash] holds the user's
 * original text): a skill row prepends "/<skill> " to the original; an
 * action command (clear/compact/…) runs as a side effect and restores the
 * original (stripping the injected "/ "). Typed-"/" (no saved original):
 * a skill fills "/<skill> ", an action clears the input. The original body
 * is always preserved — never discarded (no regression of e48fe7a0).
 *
 * [currentInput] is retained for call-site compatibility; the body text is
 * sourced from [savedInputBeforeSlash], not the live string.
 */
fun ChatViewModel.executeSlashCommand(cmd: SlashCommand, currentInput: String = ""): String {
    val saved = savedInputBeforeSlash
    // [T-skill-slash a88ea8f9] Skill rows aren't directly executable —
    // they're a typing aid. Fill the composer with the literal slash
    // command; the user then taps Send and the model handles the skill via
    // the existing SKILL.md fragment injection in runAgentLoop.
    if (cmd.isSkill) {
        AppLogger.info(TAG, "[Slash] tap skill id=${cmd.id} title=${cmd.title} → composer fill only")
        savedInputBeforeSlash = null
        _showSlashMenu.value = false
        _slashMenuSelectedIndex.value = -1
        val prefix = "/${cmd.title} "
        // [T-android-slash-menu-align-ios-prepend] iOS parity: over-content
        // (saved != null) → PREPEND "/<skill> " to the original, so the
        // composer reads "/<skill> <original>" with the original as args,
        // caret right after the prefix (before the original). Typed-"/"
        // (saved == null) → just "/<skill> " (the input WAS the partial
        // command). Trailing space lets the user type "/<skill> <args>".
        return if (saved != null) {
            _pendingCaret.value = prefix.length
            prefix + saved
        } else {
            prefix
        }
    }
    AppLogger.info(TAG, "[Slash] tap id=${cmd.id} title=${cmd.title} streaming=${_isStreaming.value} compacting=${_isCompacting.value}")
    savedInputBeforeSlash = null
    _showSlashMenu.value = false
    _slashMenuSelectedIndex.value = -1

    when (cmd.id) {
        "compact" -> compactAll()
        "memory" -> toggleMemoryEnabled()
        "thinking" -> toggleThinking()
        "clear" -> _clearChatConfirmRequested.value = true
        // Unified Android-authoritative command set: identical id +
        // handler as the Web Remote's commands/list; async work is
        // dispatched on a fire-and-forget scope and reported through
        // the same session journal the Web projection reads.
        "model", "permission", "goal", "plan", "feedback", "export" -> {
            viewModelScope.launch {
                val outcome = com.openminis.app.remote.AgentCommandRegistry.execute(
                    context, sessionId, cmd.id, "",
                )
                if (outcome.text.isNotEmpty()) {
                    appendSystemInfo(text = outcome.text, iconKind = "command")
                }
            }
        }
        else -> AppLogger.info(TAG, "[Slash] unrecognized id=${cmd.id} — no dispatch")
    }
    // [T-android-slash-menu-align-ios-prepend] Action command: restore the
    // saved ORIGINAL (stripping the injected "/ " prefix) so the body text
    // survives — never the live "/ <original>". Typed-"/" path → clear.
    if (saved != null) {
        _pendingCaret.value = saved.length
        return saved
    }
    return ""
}

/**
 * Set memory for the active session from a settings surface. Unlike a raw
 * DAO write this updates the live StateFlow first, so the next agent turn
 * immediately rebuilds its memory-gated tool list and system prompt.
 */
fun ChatViewModel.setMemoryEnabledForSession(enabled: Boolean) {
    if (_memoryEnabled.value == enabled) return
    _memoryEnabled.value = enabled
    // A row-less draft snapshots this flag into the row it creates on the first send.
    if (hasNoRow) return
    viewModelScope.launch {
        val sid = ensureSession()
        chatRepository.dao.updateMemoryEnabled(sid, if (enabled) 1 else 0)
    }
}

/** Toggle memory writes on/off, persist to DB, and append a system-info message. */
internal fun ChatViewModel.toggleMemoryEnabled() {
    val newValue = !_memoryEnabled.value
    _memoryEnabled.value = newValue
    // Before the first message there is no row; ensureSession() copies this flag into the row
    // it creates, so a row-less draft needs no write here.
    if (!hasNoRow) viewModelScope.launch {
        val sid = ensureSession()
        chatRepository.dao.updateMemoryEnabled(sid, if (newValue) 1 else 0)
    }
    appendSystemInfo(
        text = "Memory writes ${if (newValue) "enabled" else "disabled"}. Reads are unaffected.",
        iconKind = "memory",
    )
}

/** Toggle thinking between OFF and MEDIUM (matches iOS default toggle semantics). */
internal fun ChatViewModel.toggleThinking() {
    if (!currentModelSupportsReasoning) {
        appendSystemInfo(
            text = "The current model does not support deep thinking.",
            iconKind = "thinking",
        )
        return
    }
    val newLevel = if (_thinkingLevel.value.isEnabled) ThinkingLevel.OFF else ThinkingLevel.MEDIUM
    _thinkingLevel.value = newLevel
    persistThinkingOverride(newLevel)
    appendSystemInfo(
        text = "Thinking set to ${newLevel.displayName.lowercase()}.",
        iconKind = "thinking",
    )
}

/**
 * Command-facing toggle for the unified `AgentCommandRegistry` (Web
 * `/thinking` executes through the same VM as the App's own slash menu;
 * there is deliberately no second memory/thinking toggle path). Returns
 * the new effective state so the host can report it honestly.
 */
fun ChatViewModel.toggleMemoryForCommand(): Boolean {
    toggleMemoryEnabled()
    return _memoryEnabled.value
}

/** Command-facing `/thinking` toggle; returns the new effective level. */
fun ChatViewModel.toggleThinkingForCommand(): ThinkingLevel {
    toggleThinking()
    return _thinkingLevel.value
}

/**
 * Set thinking level explicitly. Used by the inline level picker in the
 * `/thinking` slash row. Mirrors iOS `setThinkingLevel(_:)` — silently
 * ignored when the current model doesn't support reasoning.
 */
fun ChatViewModel.setThinkingLevel(level: ThinkingLevel) {
    if (!currentModelSupportsReasoning) return
    // [T-android-thinking-level-arch] Double-safety clamp: the composer UI
    // already filters to availableThinkingLevels, but never fully trust the
    // caller — cap to the current model's ceiling so a stale/over-range
    // request can't persist a level the model can't reach.
    val ceiling = currentModelMaxThinkingLevel
    val declared = availableThinkingLevels
    val clamped = when {
        level == ThinkingLevel.OFF -> ThinkingLevel.OFF
        declared.isEmpty() -> if (level.rank > ceiling.rank) ceiling else level
        else -> declared.lastOrNull { it.rank <= level.rank } ?: declared.first()
    }
    if (_thinkingLevel.value == clamped) return
    _thinkingLevel.value = clamped
    persistThinkingOverride(clamped)
}

/**
 * T239: write the user's explicit thinking-level choice back to the
 * sessions row so it survives cold-start. Stored as enum name; null
 * means "no override" (legacy behaviour). We always store a non-null
 * value here — including OFF — because the user's explicit "turn it
 * off for this session" must persist as distinct from "never set".
 *
 * Uses [ensureSession] so toggling on a draft (no DB row yet) first
 * materialises the row, mirroring how toggleMemoryEnabled lands its
 * preference on the persisted id rather than the `__new__…` draft key.
 */
internal fun ChatViewModel.persistThinkingOverride(level: ThinkingLevel) {
    if (hasNoRow) {
        pendingThinkingOverride = level
        return
    }
    viewModelScope.launch {
        val sid = ensureSession()
        chatRepository.dao.updateThinkingOverride(sid, level.name)
    }
}

/**
 * If `text` is a slash command literal (e.g. "/compact"), run it and
 * return true so the caller can skip the normal send path. Mirrors iOS
 * `tryExecuteInputAsSlashCommand()`. Recognized titles are matched
 * case-insensitively against [availableSlashCommands].
 *
 * Accepts both ASCII `/` and the full-width `／` (U+FF0F): some Chinese/
 * Japanese IMEs auto-substitute the full-width form when the user types
 * `/` while a CJK keyboard layout is active. We treat them identically.
 */
fun ChatViewModel.tryExecuteInputAsSlashCommand(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return false
    val first = trimmed[0]
    if (first != '/' && first != '／') return false
    val name = trimmed.drop(1).lowercase()
    val cmd = availableSlashCommands.firstOrNull { it.title.lowercase() == name }
        ?: return false
    executeSlashCommand(cmd)
    return true
}
