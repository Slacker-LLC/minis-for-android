package com.openminis.app.ui.chat

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.openminis.app.agent.*

fun ChatViewModel.cancelStream() = agentSession.cancelStream()

internal suspend fun ChatViewModel.awaitStreamExit(timeoutMs: Long) = agentSession.awaitStreamExit(timeoutMs)

internal suspend fun ChatViewModel.awaitStreamExit() = agentSession.awaitStreamExit()

fun ChatViewModel.resume() = agentSession.resume()

fun ChatViewModel.cleanupIfEmptyOnExit() {
    val sid = realSessionId
    if (sid.isEmpty() || isStreaming.value || attachments.value.isNotEmpty()) return
    viewModelScope.launch(Dispatchers.IO) {
        val session = chatRepository.getSession(sid) ?: return@launch
        if (session.botId != null || session.source != null || chatRepository.messageCount(sid) > 0) return@launch
        // Selection and execution start share Main with the page lifecycle.
        // An outgoing projection cannot delete its already-mounted replacement.
        withContext(Dispatchers.Main) {
            if (ChatViewModelStore.activeSessionId == sid || agentSession.execution.currentJob?.isActive == true) return@withContext
            chatRepository.deleteUnusedDraft(sid)
        }
    }
}

fun ChatViewModel.clearError() = agentSession.clearError()

internal fun ChatViewModel.escapeJson(text: String) = agentSession.escapeJson(text)
