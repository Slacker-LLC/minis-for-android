package com.openminis.app.tools

import android.content.Context
import androidx.room.withTransaction
import com.openminis.app.agent.AgentSessionStore
import com.openminis.app.agent.RunCheckpointStore
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.AgentTaskEntity
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.runtime.files.WorkspaceFileClient
import com.openminis.app.tools.runtime.ToolExecutor
import com.openminis.app.tools.runtime.ToolRegistry
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** One exact unknown action, settled through Room before sidecar ACK. */
internal object ToolOutcomeVerification {
    private data class Receipt(val value: JSONObject, val proof: JSONObject)

    private suspend fun receipts(context: Context, sid: String): List<Receipt> =
        ChatRepository(AppDatabase.getInstance(context).chatDao()).loadMessages(sid).flatMap { message ->
            val parts = runCatching { JSONArray(message.partsJson) }.getOrNull() ?: JSONArray()
            (0 until parts.length()).mapNotNull { index ->
                val part = parts.optJSONObject(index) ?: return@mapNotNull null
                val value = part.optJSONObject("value") ?: return@mapNotNull null
                if (part.optString("type") != "toolResult") return@mapNotNull null
                val proof = value.optJSONObject("outcomeVerification") ?: return@mapNotNull null
                if (!value.optBoolean("success") && !(proof.optString("method") in setOf("user_observation", "job_receipt") &&
                    proof.optString("resolution") in setOf("not_applied", "failed"))) return@mapNotNull null
                if (proof.optInt("version") != 1 || proof.optString("session_id") != sid ||
                    value.optString("executionIntentId") != proof.optString("execution_id") ||
                    value.optString("executionRunId") != proof.optString("run_id") ||
                    value.optString("executionTaskId") != proof.optString("task_id") ||
                    value.optString("toolUseId") != proof.optString("call_id")) return@mapNotNull null
                Receipt(value, proof)
            }
        }

    private fun matches(proof: JSONObject, intent: ToolCheckpointStore.IntentRecord) =
        intent.intentId != null && proof.optString("execution_id") == intent.intentId &&
            (if (intent.runId == null) proof.isNull("run_id") else proof.optString("run_id") == intent.runId) &&
            (if (intent.taskId == null) proof.isNull("task_id") else proof.optString("task_id") == intent.taskId) &&
            proof.optString("call_id") == intent.callId && proof.optString("tool_name") == intent.toolName

    /** Read durable receipts even when they are outside the loaded/compacted model history. */
    suspend fun pending(context: Context, sid: String): List<ToolCheckpointStore.IntentRecord> {
        val tasks = AppDatabase.getInstance(context).agentTaskDao()
        check(tasks.incompleteCheckpointRestores(sid) == 0 && tasks.incompleteRestoreEvidence().none { body ->
            val sources = JSONObject(body).getJSONObject("restoration").optJSONArray("checkpoint_sessions") ?: JSONArray()
            (0 until sources.length()).any { sources.optString(it) == sid }
        }) { "restored_checkpoint_import_incomplete" }
        val pending = ToolCheckpointStore.drainPending(context, sid)
        if (pending.isEmpty()) return pending
        val committed = receipts(context, sid)
        val legacy = committed.filter { it.proof.optString("method") == "user_observation" && it.proof.has("legacy_row_key") }
            .map { it.proof.getString("legacy_row_key") }.toSet()
        ToolCheckpointStore.acknowledgeLegacyObservation(context, sid, legacy)
        val acknowledged = pending.filter { intent -> committed.count { matches(it.proof, intent) ||
            (intent.intentId != null && it.proof.optString("verified_by_execution_id") == intent.intentId &&
                it.proof.optString("verified_by_run_id") == intent.runId && it.proof.optString("task_id") == intent.taskId &&
                ToolRegistry.canonicalName(intent.toolName) == "agent.goal") } == 1 }
            .mapNotNull { it.intentId }.toSet()
        val completedControls = pending.filter { intent ->
            if (intent.intentId == null || intent.taskId == null || ToolRegistry.canonicalName(intent.toolName) != "agent.goal") false
            else AppDatabase.getInstance(context).agentTaskDao().get(intent.taskId)?.takeIf { it.status == AgentTaskEntity.STATUS_COMPLETED }
                ?.completionEvidence?.let { JSONObject(it) }?.let { proof ->
                    proof.optString("verified_by_execution_id") == intent.intentId && proof.optString("run_id") == intent.runId &&
                        proof.optString("session_id") == sid && proof.optString("task_id") == intent.taskId
                } == true
        }.mapNotNull { it.intentId }.toSet()
        ToolCheckpointStore.acknowledge(context, sid, acknowledged + completedControls)
        return pending.filter { it.intentId !in acknowledged && it.intentId !in completedControls &&
            (it.intentId != null || ToolCheckpointStore.legacyKey(it) !in legacy) }
    }

    fun reference(intent: ToolCheckpointStore.IntentRecord) = JSONObject()
        .put("execution_id", intent.intentId ?: JSONObject.NULL).put("run_id", intent.runId ?: JSONObject.NULL)
        .put("task_id", intent.taskId ?: JSONObject.NULL).put("call_id", intent.callId).put("tool_name", intent.toolName)
        .put("revision", intent.revision ?: JSONObject.NULL).put("stop_generation", intent.stopGeneration ?: JSONObject.NULL)

    /** Native user control also covers old/stopped tasks; it is never registered as a model/RPC tool. */
    suspend fun observeFromUi(context: Context, sessionId: String, expected: ToolCheckpointStore.IntentRecord) {
        val sid = AgentSessionStore.resolvePersistedId(sessionId)
        val all = pending(context, sid)
        val key = expected.intentId ?: ToolCheckpointStore.legacyKey(expected)
        val matches = all.filter { (it.intentId ?: ToolCheckpointStore.legacyKey(it)) == key }
        check(!ToolExecutor.isExecutionInFlight(expected.intentId)) { "execution_still_running" }
        check(matches.isNotEmpty() && matches.all { it == expected }) { "execution_changed_or_settled" }
        val task = expected.taskId?.let { AppDatabase.getInstance(context).agentTaskDao().get(it) }
        val answer = QuestionCenter.ask(sid, context.getString(com.openminis.app.R.string.outcome_verify_prompt,
            expected.toolName, java.text.DateFormat.getDateTimeInstance().format(java.util.Date(expected.at)), matches.size),
            listOf(QuestionOption(context.getString(com.openminis.app.R.string.outcome_confirm_completed), "completed"),
                QuestionOption(context.getString(com.openminis.app.R.string.outcome_confirm_not_applied), "not_applied"),
                QuestionOption(context.getString(com.openminis.app.R.string.outcome_confirm_unknown), "unknown")),
            allowCustom = false, timeoutMs = 600_000L)
        val resolution = answer?.takeUnless { it.skipped }?.selected?.singleOrNull()
        check(resolution in setOf("completed", "not_applied")) { "outcome_still_unknown" }
        kotlin.coroutines.coroutineContext.ensureActive()
        withContext(NonCancellable) {
            val db = AppDatabase.getInstance(context)
            db.withTransaction {
                check(db.chatDao().getSession(sid) != null) { "session_deleted" }
                check(!ToolExecutor.isExecutionInFlight(expected.intentId)) { "execution_still_running" }
                if (task != null) {
                    val current = checkNotNull(db.agentTaskDao().get(task.id))
                    check(current.revision == task.revision && current.stopGeneration == task.stopGeneration) { "task_changed_or_stopped" }
                }
                check(ToolCheckpointStore.drainPending(context, sid).filter { (it.intentId ?: ToolCheckpointStore.legacyKey(it)) == key } == matches) { "execution_changed_or_settled" }
                val proof = reference(expected).put("version", 1).put("session_id", sid).put("method", "user_observation")
                    .put("resolution", resolution).put("decision_origin", "native_user").put("verified_at", System.currentTimeMillis())
                if (expected.intentId == null) proof.put("legacy_row_key", key)
                val value = JSONObject().put("toolUseId", expected.callId).put("name", expected.toolName)
                    .put("executionIntentId", expected.intentId ?: JSONObject.NULL).put("executionRunId", expected.runId ?: JSONObject.NULL)
                    .put("executionTaskId", expected.taskId ?: JSONObject.NULL).put("success", resolution == "completed")
                    .put("failureKind", if (resolution == "not_applied") ToolFailureKind.EXECUTION_FAILED.name else JSONObject.NULL)
                    .put("executionStarted", JSONObject.NULL).put("outcomeVerification", proof).put("output", "[user verified action outcome] $proof")
                ChatRepository(db.chatDao()).appendMessage(sid, "user", JSONArray().put(JSONObject().put("type", "toolResult").put("value", value)).toString())
            }
            if (expected.intentId == null) ToolCheckpointStore.acknowledgeLegacyObservation(context, sid, setOf(key))
            else ToolCheckpointStore.acknowledge(context, sid, setOf(expected.intentId))
        }
    }

    suspend fun reviewRestore(context: Context, sid: String, task: AgentTaskEntity, args: JSONObject, toolId: String): JSONObject {
        val ownId = requireOwner(context, sid, task, toolId)
        return reviewRestoreRecord(context, sid, task, args, toolId, ownId)
    }

    /** Native human review does not impersonate a tool execution or restart a restored task. */
    suspend fun reviewRestoreFromUi(context: Context, sid: String, task: AgentTaskEntity): JSONObject {
        check(task.currentOwnerSessionId == sid || task.originSessionId == sid || task.goalSessionId == sid) { "task_owner_mismatch" }
        check(AgentSessionStore.activeRun(sid) == null) { "task_still_running" }
        return reviewRestoreRecord(context, sid, task, JSONObject().put("task_id", task.id)
            .put("revision", task.revision).put("stop_generation", task.stopGeneration), null, null)
    }

    private suspend fun reviewRestoreRecord(context: Context, sid: String, task: AgentTaskEntity, args: JSONObject,
                                            toolId: String?, ownId: String?): JSONObject {
        require(args.optString("task_id") == task.id && args.optInt("revision", -1) == task.revision &&
            args.optInt("stop_generation", -1) == task.stopGeneration) { "task_changed_or_missing_identity" }
        val saved = checkNotNull(task.completionEvidence) { "no_restoration_review" }
        val proof = JSONObject(saved)
        val restore = proof.optJSONObject("restoration") ?: error("no_restoration_review")
        check(restore.optBoolean("checkpoint_restore_complete")) { "restored_checkpoint_import_incomplete" }
        if (!restore.optBoolean("requires_review")) return JSONObject().put("reviewed", true).put("status", task.status)
        val answer = QuestionCenter.ask(sid, "任务“${task.goal.take(200)}”来自备份。请检查原工作现场、协作结果和未知动作；" +
            "确认已核实后才允许手动恢复。此确认不会重放命令、清除未知执行或自动继续。",
            listOf(QuestionOption("已检查恢复现场，可以由我手动继续", "reviewed"), QuestionOption("尚未核实，保持暂停", "unknown")),
            allowCustom = false, timeoutMs = 600_000L)
        check(answer?.takeUnless { it.skipped }?.selected == listOf("reviewed")) { "restoration_review_pending" }
        val db = AppDatabase.getInstance(context)
        db.withTransaction {
            val current = checkNotNull(db.agentTaskDao().get(task.id))
            check(current.revision == task.revision && current.stopGeneration == task.stopGeneration &&
                (current.currentOwnerSessionId == sid || (toolId == null && (current.originSessionId == sid || current.goalSessionId == sid))) && current.completionEvidence == saved &&
                current.status !in AgentTaskEntity.TERMINAL_STATUSES) { "task_changed_or_stopped" }
            if (toolId != null) requireOwner(context, sid, current, toolId)
            else check(AgentSessionStore.activeRun(sid) == null) { "task_still_running" }
            restore.put("requires_review", false).put("reviewed_at", System.currentTimeMillis())
            if (ownId != null) restore.put("reviewed_by_execution_id", ownId) else restore.put("reviewed_by", "native_user")
            db.agentTaskDao().replace(current.copy(completionEvidence = proof.toString()))
        }
        return JSONObject().put("reviewed", true).put("status", task.status).put("task_id", task.id)
    }

    suspend fun requireOwner(context: Context, sid: String, task: AgentTaskEntity, toolId: String): String {
        val actual = checkNotNull(kotlin.coroutines.coroutineContext[ToolExecutor.ExecutionIdentity]) { "verification_requires_actual_execution" }
        val run = checkNotNull(AgentSessionStore.activeRun(sid)) { "verification_requires_active_owner_run" }
        val owned = checkNotNull(AgentSessionStore.activeRunTask(sid)) { "verification_requires_owned_task" }
        check(actual.model.callId == toolId && actual.model.runId == run && actual.model.taskId == task.id &&
            actual.model.revision == task.revision && actual.model.stopGeneration == task.stopGeneration &&
            owned.id == task.id && owned.revision == task.revision && owned.stopGeneration == task.stopGeneration &&
            task.currentOwnerSessionId == sid) { "owner_run_task_changed" }
        val own = ToolCheckpointStore.drainPending(context, sid).singleOrNull { it.intentId == actual.intentId }
        check(own != null && own.callId == toolId && own.runId == run && own.taskId == task.id &&
            own.revision == task.revision && own.stopGeneration == task.stopGeneration &&
            ToolRegistry.canonicalName(own.toolName) == "agent.goal") { "verification_requires_owner_tool_intent" }
        return actual.intentId
    }

    private fun originalVersion(context: Context, sid: String, intent: ToolCheckpointStore.IntentRecord): Pair<Int, Int>? {
        if (intent.revision != null && intent.stopGeneration != null) return intent.revision to intent.stopGeneration
        // Full legacy bodies may be verified only if their original runtime task binding survives.
        val run = intent.runId?.let { RunCheckpointStore.load(context, sid, it) } ?: return null
        if (run.damaged) return null
        val versions = run.events.mapNotNull { event -> runCatching { JSONObject(event) }.getOrNull() }
            .filter { it.optString("type") == "task_bound" && it.optString("task_id") == intent.taskId &&
                it.has("revision") && it.has("stop_generation") }
            .map { it.getInt("revision") to it.getInt("stop_generation") }.distinct()
        return versions.singleOrNull()
    }

    suspend fun verify(context: Context, sid: String, task: AgentTaskEntity, args: JSONObject, toolId: String): JSONObject {
        check(task.status in setOf(AgentTaskEntity.STATUS_ACTIVE, AgentTaskEntity.STATUS_WAITING,
            AgentTaskEntity.STATUS_NEEDS_USER)) { "task_changed_or_stopped" }
        val ownId = requireOwner(context, sid, task, toolId)
        require(args.optString("task_id") == task.id && args.optInt("revision", -1) == task.revision &&
            args.optInt("stop_generation", -1) == task.stopGeneration) { "task_changed_or_missing_identity" }
        require(!args.has("status") && !args.has("goal") && !args.has("evidence")) { "verify_outcome_is_independent_of_goal_settlement" }
        val ref = args.getJSONObject("outcome")
        val execution = ref.getString("execution_id")
        val originalRun = ref.getString("run_id")
        require(execution.isNotBlank() && originalRun.isNotBlank()) { "exact_execution_identity_required" }
        val db = AppDatabase.getInstance(context)
        // A retry after commit must consume the saved proof, even if the file changed afterwards.
        suspend fun existing(): JSONObject? {
            val found = receipts(context, sid).filter { it.proof.optString("execution_id") == execution }
            check(found.size <= 1) { "ambiguous_verification_receipt" }
            return found.singleOrNull()?.proof?.also {
                check(it.getString("run_id") == originalRun && it.getString("task_id") == task.id &&
                    it.getInt("revision") == task.revision && it.getInt("stop_generation") == task.stopGeneration) { "verification_receipt_identity_mismatch" }
            }
        }
        var proof = existing()
        if (proof == null) {
            val intent = ToolCheckpointStore.drainPending(context, sid).singleOrNull { it.intentId == execution }
                ?: throw IllegalStateException("unknown_execution_not_found")
            check(!ToolExecutor.isExecutionInFlight(intent.intentId)) { "execution_still_running" }
            require(intent.intentId != ownId && intent.runId == originalRun && intent.taskId == task.id &&
                originalVersion(context, sid, intent) != null) { "unknown_execution_identity_mismatch" }
            val method = args.optString("verification", "automatic")
            require(method in setOf("automatic", "user")) { "invalid_verification_method" }
            if (method == "user") {
                val answer = QuestionCenter.ask(sid,
                    "请先检查实际现场，再核实这次结果未知的动作：${intent.toolName}（执行 ${execution.take(8)}，" +
                        "时间 ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(intent.at))}）。任务：${task.goal.take(200)}。" +
                        "这只记录你的现场核验，不会重执行动作、完成任务或自动恢复任务。无法确定请保留未知。",
                    listOf(QuestionOption("已检查，要求的动作完整生效", "completed"),
                        QuestionOption("已检查，动作没有生效", "not_applied"),
                        QuestionOption("仍不确定或只部分生效，保留未知", "unknown")),
                    allowCustom = false, timeoutMs = 600_000L)
                val resolution = answer?.takeUnless { it.skipped }?.selected?.singleOrNull()
                check(resolution in setOf("completed", "not_applied")) { "outcome_still_unknown" }
                proof = reference(intent).put("version", 1).put("session_id", sid).put("revision", task.revision)
                    .put("stop_generation", task.stopGeneration).put("verified_at", System.currentTimeMillis())
                    .put("verified_by_execution_id", ownId).put("verified_by_run_id", AgentSessionStore.activeRun(sid))
                    .put("original_revision", intent.revision ?: JSONObject.NULL).put("original_stop_generation", intent.stopGeneration ?: JSONObject.NULL)
                    .put("method", "user_observation").put("resolution", resolution)
            } else if (ToolRegistry.canonicalName(intent.toolName) == com.openminis.app.agent.subagents.SubAgentToolSchema.HANDLER_NAME) {
                require(originalVersion(context, sid, intent) == (task.revision to task.stopGeneration)) { "unknown_execution_identity_mismatch" }
                val job = com.openminis.app.agent.subagents.SubAgents.runtime(context).registry.jobs.value.values.singleOrNull {
                    it.parentExecutionId == execution && it.parentRunId == originalRun && it.parentTaskId == task.id && it.parentSessionId == sid
                } ?: error("unknown_subagent_needs_user_observation")
                check(!job.outcomeUnknown && job.state in setOf(com.openminis.app.agent.subagents.SubAgentJobState.DONE,
                    com.openminis.app.agent.subagents.SubAgentJobState.FAILED)) { "subagent_outcome_still_unknown" }
                proof = reference(intent).put("version", 1).put("session_id", sid).put("revision", task.revision)
                    .put("stop_generation", task.stopGeneration).put("verified_at", System.currentTimeMillis())
                    .put("verified_by_execution_id", ownId).put("verified_by_run_id", AgentSessionStore.activeRun(sid))
                    .put("method", "job_receipt").put("job_kind", "subagent").put("job_id", job.id)
                    .put("resolution", if (job.state == com.openminis.app.agent.subagents.SubAgentJobState.DONE) "completed" else "failed")
            } else if (ToolRegistry.canonicalName(intent.toolName) == "linux.shell") {
                require(originalVersion(context, sid, intent) == (task.revision to task.stopGeneration)) { "unknown_execution_identity_mismatch" }
                val job = JobRegistry.list().singleOrNull { it.executionIntentId == execution && it.runId == originalRun &&
                    it.taskId == task.id && it.sessionId == sid } ?: error("unknown_job_needs_user_observation")
                check(!job.nativeOutcomeUnknown && job.failureKind?.outcomeUnknown != true &&
                    job.status in setOf(JobRegistry.JobStatus.COMPLETED, JobRegistry.JobStatus.FAILED)) { "job_outcome_still_unknown" }
                proof = reference(intent).put("version", 1).put("session_id", sid).put("revision", task.revision)
                    .put("stop_generation", task.stopGeneration).put("verified_at", System.currentTimeMillis())
                    .put("verified_by_execution_id", ownId).put("verified_by_run_id", AgentSessionStore.activeRun(sid))
                    .put("method", "job_receipt").put("resolution", if (job.status == JobRegistry.JobStatus.COMPLETED) "completed" else "failed")
                    .put("job_id", job.id).put("output_sha256", com.openminis.app.util.Sha256.hex(JobRegistry.output(job.id).orEmpty().toByteArray()))
            } else {
                require(originalVersion(context, sid, intent) == (task.revision to task.stopGeneration)) { "unknown_execution_identity_mismatch" }
                require(ToolRegistry.canonicalName(intent.toolName) == "linux.file.write") { "unknown_outcome_needs_domain_verification_or_user_observation" }
                val input = JSONObject(intent.argsJson)
                val path: String
                val size: Long
                val hash: String
                if (input.has("file_write_summary_version")) {
                    require(input.getInt("file_write_summary_version") == 1 && input.opt("path") is String &&
                        input.opt("utf8_size") is Number && input.opt("sha256") is String) { "file_write_intent_is_incomplete" }
                    path = input.getString("path"); size = input.getLong("utf8_size"); hash = input.getString("sha256")
                    require(size >= 0 && hash.matches(Regex("[a-f0-9]{64}"))) { "invalid_file_write_summary" }
                } else {
                    require(input.opt("path") is String && input.opt("content") is String && !input.optBoolean("append", false)) { "file_write_intent_is_incomplete" }
                    path = input.getString("path")
                    val bytes = input.getString("content").toByteArray(Charsets.UTF_8)
                    size = bytes.size.toLong(); hash = com.openminis.app.util.Sha256.hex(bytes)
                }
                val actual = WorkspaceFileClient.fingerprint(sid, path)
                check(actual.getLong("size") == size && actual.getString("sha256") == hash) { "unknown_write_does_not_match_verified_file" }
                proof = reference(intent).put("version", 1).put("session_id", sid).put("revision", task.revision)
                    .put("stop_generation", task.stopGeneration).put("verified_at", System.currentTimeMillis())
                    .put("verified_by_execution_id", ownId).put("verified_by_run_id", AgentSessionStore.activeRun(sid))
                    .put("file", actual)
            }
        }
        kotlin.coroutines.coroutineContext.ensureActive()
        val verified = checkNotNull(proof)
        val settled = withContext(NonCancellable) {
            db.withTransaction {
                val current = checkNotNull(db.agentTaskDao().get(task.id)) { "task_not_found" }
                check(current.revision == task.revision && current.stopGeneration == task.stopGeneration &&
                    current.currentOwnerSessionId == sid && current.status in setOf(AgentTaskEntity.STATUS_ACTIVE,
                        AgentTaskEntity.STATUS_WAITING, AgentTaskEntity.STATUS_NEEDS_USER)) { "task_changed_or_stopped" }
                requireOwner(context, sid, current, toolId)
                if (existing() == null) {
                    check(ToolCheckpointStore.drainPending(context, sid).any { matches(verified, it) }) { "unknown_execution_not_found" }
                    val value = JSONObject().put("toolUseId", verified.getString("call_id")).put("name", verified.getString("tool_name"))
                        .put("executionIntentId", execution).put("executionRunId", originalRun).put("executionTaskId", task.id)
                        .put("success", verified.optString("resolution") !in setOf("not_applied", "failed"))
                        .put("failureKind", if (verified.optString("resolution") in setOf("not_applied", "failed")) ToolFailureKind.EXECUTION_FAILED.name else JSONObject.NULL)
                        .put("executionStarted", if (verified.optString("method") == "user_observation") JSONObject.NULL else true)
                        .put("output", "[verified action outcome] $verified").put("outcomeVerification", verified)
                    ChatRepository(db.chatDao()).appendMessage(sid, "user", JSONArray().put(JSONObject()
                        .put("type", "toolResult").put("value", value)).toString())
                }
                // Resolving an action is not permission to resume a waiting or user-controlled task.
                checkNotNull(db.agentTaskDao().get(task.id))
            }.also { ToolCheckpointStore.acknowledge(context, sid, setOf(execution)) }
        }
        AgentSessionStore.updateRunGoal(sid, settled)
        return JSONObject().put("verified_outcome", verified).put("status", settled.status)
            .put("task_id", settled.id).put("revision", settled.revision).put("stop_generation", settled.stopGeneration)
    }
}
