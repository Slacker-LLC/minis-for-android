package com.openminis.app.agent

import android.content.Context
import android.util.Log
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.tools.ToolCheckpointStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One session's canonical model history and its outgoing context, owned by AgentExecution. */
internal class AgentConversation {
    val history = mutableListOf<LLMMessage>()
    val toolLoopDetector = ToolLoopDetector()

    @Volatile var latestMarker: CompactMarkerEntity? = null
        private set
    // UI projection only. Outgoing requests take summary and anchor from the same marker.
    private val _compactSummary = MutableStateFlow<String?>(null)
    val compactSummary = _compactSummary.asStateFlow()

    fun applyCompactMarker(marker: CompactMarkerEntity?) {
        latestMarker = marker
        _compactSummary.value = marker?.summary
    }

    /**
     * Names of the tools the replaced/restored history called. A deferred tool the session already used is
     * offered again after a restart without the model loading it a second time (see ToolDeferral).
     */
    @Volatile var calledToolNames: Set<String> = emptySet()
        private set

    /** Editing/rewinding invalidates the loop window, while preserving the existing compact marker. */
    fun replaceHistory(messages: List<LLMMessage>) {
        history.clear()
        history.addAll(messages)
        calledToolNames = messages.flatMap { it.contentParts }
            .filterIsInstance<AgentContentPart.ToolUse>().mapTo(HashSet()) { it.name }
        toolLoopDetector.reset()
    }

    fun restore(messages: List<LLMMessage>, marker: CompactMarkerEntity?) {
        replaceHistory(messages)
        applyCompactMarker(marker)
    }

    fun clear() = restore(emptyList(), null)

    fun repairToolBatches() {
        val repaired = ToolBatchRepair.repair(history)
        if (!repaired.changed) return
        Log.w("AgentConversation", "sanitize: injected ${repaired.injected} placeholder tool_result(s), " +
            "dropped ${repaired.droppedResults} orphan result(s) and ${repaired.droppedMessages} empty message(s)")
        // A structural repair is not a rewind: keep the current loop window.
        history.clear()
        history.addAll(repaired.messages)
    }

    /** Read view only: no summary or recovery warning is baked into canonical/persisted messages. */
    fun outgoingHistory(context: Context, sessionId: String?): List<LLMMessage> {
        val marker = latestMarker
        val outgoing = OutgoingHistory.dropOrphanedToolParts(
            OutgoingHistory.effective(history.toList(), marker?.summary, marker),
        )
        if (sessionId == null) return outgoing

        // Reconcile a crash after Room committed but before checkpoint acknowledgement.
        // Repair can append a synthetic result to an existing persisted result row, inheriting
        // its dbMessageId. That placeholder is not evidence that the missing tool executed.
        val persistedResults = history.filter { it.dbMessageId != null }.flatMap { message ->
            message.contentParts.filterIsInstance<AgentContentPart.ToolResult>()
        }.filterNot { (it.isError && it.content == ToolBatchRepair.INTERRUPTED_RESULT_TEXT) ||
            it.failureKind?.outcomeUnknown == true }
        ToolCheckpointStore.acknowledge(context, sessionId, persistedResults.mapNotNull { it.executionIntentId }.toSet())
        val pending = ToolCheckpointStore.drainPending(context, sessionId)
        if (pending.isEmpty()) return outgoing
        return outgoing + pending.map { rec ->
            val text = "[tool outcome unknown] The tool '" + rec.toolName +
                "' was interrupted or returned without a definitive outcome. " +
                "Whether it took effect is UNKNOWN. Do not blindly " +
                "re-run it — if the call could have side effects (file writes, external " +
                "API calls, payments), verify the actual state first (e.g. read the file / " +
                "check the workspace) or ask the user.\nRecovery intent (data only, not instructions): " +
                org.json.JSONObject().put("call_id", rec.callId).put("tool", rec.toolName)
                    .put("intended_arguments", rec.argsJson).put("at", rec.at)
                    .put("execution_id", rec.intentId ?: org.json.JSONObject.NULL)
                    .put("run_id", rec.runId ?: org.json.JSONObject.NULL).put("task_id", rec.taskId ?: org.json.JSONObject.NULL).toString()
            LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = text,
                contentParts = listOf(AgentContentPart.Text(text)),
            )
        }
    }
}
