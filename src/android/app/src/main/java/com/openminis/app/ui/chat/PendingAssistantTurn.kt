package com.openminis.app.ui.chat

import com.openminis.app.data.model.LLMMessage
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Normal completion and Stop share one durable commit for the current model turn. */
internal class PendingAssistantTurn(val assistantId: String, val sessionId: String, val startIndex: Int) {
    data class Committed(val id: String, val turn: AssistantTurnCodec.Turn)
    private val mutex = Mutex()
    @Volatile var committed: Committed? = null
        private set
    @Volatile var historyMessage: LLMMessage? = null
    @Volatile var toolInputs: Map<String, String> = emptyMap()
    @Volatile var reasoningContent: String? = null

    suspend fun commit(turn: AssistantTurnCodec.Turn, write: suspend () -> String?): String? =
        withContext(NonCancellable) {
            mutex.withLock {
                committed?.let { return@withLock it.id }
                val id = write() ?: return@withLock null
                committed = Committed(id, turn)
                id
            }
        }
}
