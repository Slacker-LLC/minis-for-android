package com.openminis.app.agent

import com.openminis.app.data.db.MessageEntity
import com.openminis.app.ui.chat.PendingDelivery
import com.openminis.app.ui.chat.QueuedPrompt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** Room is the inbox; these values are only its current presentation. */
internal suspend fun AgentSession.restorePendingRequests() {
    val rows = chatRepository.dao.pendingRequests(activeSessionId)
    val visible = messageMapper.toChatMessages(rows, rows.associate { it.id to runCatching { JSONArray(it.partsJson) } })
    _messages.value = _messages.value + visible.filter { candidate -> _messages.value.none { it.id == candidate.id } }
    _promptQueue.value = rows.map { row ->
        QueuedPrompt(row.id, visible.firstOrNull { it.id == row.id }?.content.orEmpty(),
            delivery = PendingDelivery.entries.firstOrNull { it.name == row.requestDelivery } ?: PendingDelivery.QUEUE,
            needsReview = row.requestState != "PENDING")
    }
}

internal suspend fun AgentSession.requestPayload(row: MessageEntity): com.openminis.app.data.model.LLMMessage = withContext(Dispatchers.IO) {
    val parts = JSONArray(row.partsJson)
    for (i in 0 until parts.length()) {
        val part = parts.getJSONObject(i)
        if (part.optString("type") != "mediaRef") continue
        val value = part.getJSONObject("value")
        val relative = value.getString("relativePath")
        val root = mediaStore.mediaBaseDir.canonicalFile
        val file = java.io.File(root, relative).canonicalFile
        check(file.toPath().startsWith(root.toPath()) && file != root && file.isFile) { "request_attachment_unavailable" }
    }
    messageMapper.toLLMMessage(row)
}

internal suspend fun AgentSession.claimPendingRequest(row: MessageEntity, runId: String, steering: Boolean): MessageEntity? {
    try {
        requestPayload(row)
        if (steering || row.requestTaskId != null) {
            val task = activeGoalTask?.id?.let { (context.applicationContext as com.openminis.app.MinisApp).agentTaskRepository.get(it) }
            check(row.requestTaskId == task?.id && row.requestTaskGeneration == task?.stopGeneration &&
                (!steering || task == null || task.status in setOf("ACTIVE", "WAITING", "NEEDS_USER"))) { "request_task_changed" }
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (error: Exception) {
        chatRepository.dao.setPendingRequestState(activeSessionId, row.id, "NEEDS_REVIEW")
        restorePendingRequests()
        setInlineError(error.message ?: "request_needs_review")
        return null
    }
    val task = activeGoalTask
    if (chatRepository.dao.claimRequest(activeSessionId, row.id, runId, task?.id, task?.revision, task?.stopGeneration) != 1) {
        chatRepository.dao.setPendingRequestState(activeSessionId, row.id, "NEEDS_REVIEW")
        restorePendingRequests()
        return null
    }
    val claimed = checkNotNull(chatRepository.dao.getMessageById(row.id))
    _promptQueue.value = _promptQueue.value.filterNot { it.id == row.id }
    withContext(Dispatchers.Main) {
        val bubble = messageMapper.toChatMessages(listOf(claimed), mapOf(claimed.id to Result.success(JSONArray(claimed.partsJson)))).single()
        sessionEventEmitter.messageCreated(bubble)
        _messages.value = _messages.value.filterNot { it.id == row.id } + bubble
    }
    return claimed
}

/** Each queued request receives its own execution owner after predecessor cleanup. */
internal fun AgentSession.startNextQueuedRequest() {
    sessionScope.launch {
        execution.awaitExit()
        if (execution.isClosed || isMutating.value || _isStreaming.value || _isCompacting.value || currentProvider == null) return@launch
        val row = chatRepository.dao.pendingRequests(activeSessionId).firstOrNull {
            it.requestState == "PENDING" && it.requestUserInput == true && it.requestDelivery == "QUEUE"
        } ?: return@launch
        try { requestPayload(row) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) {
            chatRepository.dao.setPendingRequestState(activeSessionId, row.id, "NEEDS_REVIEW")
            restorePendingRequests()
            setInlineError(error.message ?: "request_attachment_unavailable")
            return@launch
        }
        if (execution.isClosed || _isStreaming.value || isMutating.value) return@launch
        val saved = chatRepository.dao.getMessageById(row.id) ?: return@launch
        if (saved.requestState != "PENDING" || saved.requestRunId != null || saved.requestDelivery != "QUEUE") return@launch
        val bubbleText = _promptQueue.value.firstOrNull { it.id == row.id }?.text.orEmpty()
        sendMessage(bubbleText, skipContextCheck = true,
            request = AgentTurnHandle(taskId = row.requestTaskId, userInput = true), storedRequest = saved)
    }
}

internal suspend fun AgentSession.settleAcceptedRequests(runId: String, outcome: AgentTurnOutcome) {
    val state = when (outcome) {
        AgentTurnOutcome.Completed -> "COMPLETED"
        AgentTurnOutcome.Cancelled -> "INTERRUPTED"
        is AgentTurnOutcome.NeedsAttention -> "NEEDS_ATTENTION"
        else -> "FAILED"
    }
    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
        chatRepository.dao.settleRequests(activeSessionId, runId, state)
        chatRepository.dao.reviewSteeringRequests(activeSessionId)
    }
}
