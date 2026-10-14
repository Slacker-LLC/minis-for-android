package com.openminis.app.ui.chat

import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.chat.ChatViewModel.Companion.TAG
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.openminis.app.agent.*

fun ChatViewModel.ackClearChatConfirmRequest() = agentSession.ackClearChatConfirmRequest()

fun ChatViewModel.revokeMemoryRecord(record: MemoryToolRecord) = agentSession.revokeMemoryRecord(record)

fun ChatViewModel.replaceMemoryRecord(
    record: MemoryToolRecord,
    newContent: String,
) = agentSession.replaceMemoryRecord(record, newContent)

fun ChatViewModel.confirmAndEnableEnhancedCache() = agentSession.confirmAndEnableEnhancedCache()

fun ChatViewModel.setFastModeEnabled(enabled: Boolean) = agentSession.setFastModeEnabled(enabled)

fun ChatViewModel.setAutoCompactEnabled(enabled: Boolean) = agentSession.setAutoCompactEnabled(enabled)

fun ChatViewModel.thinkingInfo() = agentSession.thinkingInfo()

suspend fun ChatViewModel.loadSessionTokenStats() = agentSession.loadSessionTokenStats()

fun ChatViewModel.toggleMemoryForCommand() = agentSession.toggleMemoryForCommand()

fun ChatViewModel.toggleThinkingForCommand() = agentSession.toggleThinkingForCommand()

fun ChatViewModel.setThinkingLevel(level: ThinkingLevel) = agentSession.setThinkingLevel(level)

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
        "memory" -> agentSession.toggleMemoryEnabled()
        "thinking" -> agentSession.toggleThinking()
        "clear" -> _clearChatConfirmRequested.value = true
        // Unified Android-authoritative command set: identical id +
        // handler as the Web Remote's commands/list; async work is
        // dispatched on a fire-and-forget scope and reported through
        // the same session journal the Web projection reads.
        "model", "permission", "goal", "plan", "feedback", "export" -> {
            agentSession.sessionScope.launch {
                val outcome = com.openminis.app.remote.AgentCommandRegistry.execute(
                    context, sessionId, cmd.id, "",
                )
                if (outcome.text.isNotEmpty()) {
                    agentSession.appendSystemInfo(text = outcome.text, iconKind = "command")
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
