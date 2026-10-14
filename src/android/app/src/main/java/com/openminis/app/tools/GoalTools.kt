package com.openminis.app.tools

import android.content.Context
import com.openminis.app.agent.AgentSessionStore
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.BotTaskEntity
import com.openminis.app.data.repository.BotTaskRepository
import com.openminis.app.runtime.files.WorkspaceFileClient
import androidx.room.withTransaction
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Model, commands, Debug and the page share the same durable session Goal. */
object GoalTools {
    internal suspend fun currentTask(context: Context, sessionId: String): BotTaskEntity? {
        val repository = BotTaskRepository(AppDatabase.getInstance(context).botTaskDao())
        return AgentSessionStore.activeRunTask(sessionId)?.id?.let { repository.get(it) }
            ?: com.openminis.app.data.model.SessionOverrides.fromJson(AppDatabase.getInstance(context).chatDao().getSession(sessionId)?.sessionOverrides)
                .taskId?.let { id -> repository.get(id)?.takeIf { task -> task.originSessionId == sessionId || task.currentOwnerSessionId == sessionId || task.goalSessionId == sessionId } }
            ?: repository.sessionGoal(sessionId)
    }

    suspend fun execute(name: String, argsJson: String, sessionId: String?, context: Context, toolId: String = ""): ToolExecutionResult {
        val sid = sessionId?.let(AgentSessionStore::resolvePersistedId) ?: return ToolExecutionResult("goal: no active session", false)
        val args = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return ToolExecutionResult("goal: invalid arguments JSON", false)
        if (name !in setOf("get_goal", "create_goal", "update_goal", "verify_outcome", "review_restored_task")) {
            return ToolExecutionResult("goal: unknown action $name", false)
        }
        return try {
            val repository = BotTaskRepository(AppDatabase.getInstance(context).botTaskDao())
            val current = currentTask(context, sid)
            if (name == "get_goal" && current != null) {
                val ownId = kotlin.coroutines.coroutineContext[com.openminis.app.tools.runtime.ToolExecutor.ExecutionIdentity]?.intentId
                val pending = ToolOutcomeVerification.pending(context, sid).filter { ownId == null || it.intentId != ownId }
                return ToolExecutionResult(describe(current).put("unknown_outcomes", JSONArray(pending.map(ToolOutcomeVerification::reference))).toString(), true)
            }
            if (name == "verify_outcome") return ToolExecutionResult(ToolOutcomeVerification.verify(
                context, sid, checkNotNull(current) { "goal_not_found" }, args, toolId).toString(), true)
            if (name == "review_restored_task") return ToolExecutionResult(ToolOutcomeVerification.reviewRestore(
                context, sid, checkNotNull(current) { "goal_not_found" }, args, toolId).toString(), true)
            if (name == "update_goal" && args.has("status")) {
                val task = checkNotNull(current) { "goal_not_found" }
                require(!args.has("goal")) { "update goal text and settle status in separate calls" }
                require(args.optString("task_id") == task.id && args.optInt("revision", -1) == task.revision &&
                    args.optInt("stop_generation", -1) == task.stopGeneration) { "task_changed_or_missing_identity" }
                val status = when (args.getString("status")) {
                    "completed" -> BotTaskEntity.STATUS_COMPLETED
                    "paused" -> BotTaskEntity.STATUS_PAUSED
                    "waiting" -> BotTaskEntity.STATUS_WAITING
                    "needs_user" -> BotTaskEntity.STATUS_NEEDS_USER
                    else -> throw IllegalArgumentException("invalid_task_status")
                }
                val progress = args.optString("progress").trim()
                require(progress.isNotEmpty() && progress.length <= 2_000) { "progress explanation required, at most 2000 characters" }
                if (status == BotTaskEntity.STATUS_COMPLETED) {
                    check(!com.openminis.app.agent.subagents.SubAgents.activeForTask(task.id, sid)) { "unsettled_subagents_require_review" }
                    check(JobRegistry.list().none { it.taskId == task.id && it.status == JobRegistry.JobStatus.RUNNING }) { "unsettled_jobs_require_review" }
                }
                val proof = if (status == BotTaskEntity.STATUS_COMPLETED) verifyEvidence(context, sid, task, args, toolId) else null
                val settled = withContext(NonCancellable) {
                    AppDatabase.getInstance(context).withTransaction {
                        repository.settleVerified(task.id, task.revision, task.stopGeneration, status, progress, proof)
                    }
                }
                return ToolExecutionResult(describe(settled).toString(), true)
            }
            if (name == "create_goal") check(current == null) { "goal_already_exists" }
            if (name == "update_goal" && current != null && current.goalSessionId != sid) {
                require(args.opt("goal") is String) { "goal text is required" }
                val owned = checkNotNull(AgentSessionStore.activeRunTask(sid)) { "owner_run_required" }
                check(owned.id == current.id && owned.revision == current.revision && owned.stopGeneration == current.stopGeneration) { "owner_run_task_changed" }
                val rounds = args.optInt("max_goal_rounds").takeIf { args.has("max_goal_rounds") }
                require(rounds == null || rounds in 1..100) { "max_goal_rounds must be between 1 and 100" }
                if (args.getString("goal").isBlank()) {
                    BotDelegationCoordinator.current()?.cancelRootTask(current.id, cancelOwner = false)
                } else {
                    repository.updateOwnedGoal(current, args.getString("goal"), rounds,
                        args.optString("acceptance_criteria").takeIf { args.has("acceptance_criteria") })
                }
                val changed = checkNotNull(repository.get(current.id))
                AgentSessionStore.updateRunGoal(sid, changed)
                return ToolExecutionResult(describe(changed).toString(), true)
            }
            val g = if (name == "get_goal") AgentStateStore.goalGet(sid, context) else {
                val rounds = args.optInt("max_goal_rounds").takeIf { args.has("max_goal_rounds") }
                require(rounds == null || rounds in 1..100) { "max_goal_rounds must be between 1 and 100" }
                require(args.has("goal") && args.opt("goal") is String) { "goal text is required" }
                require(name != "create_goal" || args.getString("goal").isNotBlank()) { "new goal must not be blank" }
                AgentStateStore.goalSet(
                    sid, args.getString("goal"), context, rounds,
                    args.optString("acceptance_criteria").takeIf { args.has("acceptance_criteria") },
                    operation = if (name == "create_goal") "create" else "update",
                )
            }
            if (name != "get_goal") AgentSessionStore.updateRunGoal(sid, repository.sessionGoal(sid))
            ToolExecutionResult(
                if (g.text.isBlank()) "No goal is set." else JSONObject()
                    .put("task_id", g.id).put("goal", g.text).put("status", g.status)
                    .put("revision", g.revision).put("max_goal_rounds", g.maxGoalRounds)
                    .put("stop_generation", g.stopGeneration)
                    .put("rounds_used", g.roundsUsed)
                    .put("acceptance_criteria", g.acceptanceCriteria ?: JSONObject.NULL).toString(),
                true,
            )
        } catch (error: IllegalStateException) {
            ToolExecutionResult("goal: ${error.message}", false)
        } catch (error: IllegalArgumentException) {
            ToolExecutionResult("goal: ${error.message}", false)
        } catch (error: org.json.JSONException) {
            ToolExecutionResult("goal: invalid argument type", false)
        }
    }

    private fun describe(task: BotTaskEntity) = JSONObject().put("task_id", task.id).put("goal", task.goal)
        .put("status", task.status).put("revision", task.revision).put("stop_generation", task.stopGeneration)
        .put("max_goal_rounds", task.maxAutoRuns).put("rounds_used", task.autoRunsUsed)
        .put("acceptance_criteria", task.acceptanceCriteria ?: JSONObject.NULL).put("progress", task.progress)
        .put("completion_evidence", task.completionEvidence?.let(::JSONObject) ?: JSONObject.NULL)

    /** References are verified against the workspace and durable tool receipts, never model assertions. */
    private suspend fun verifyEvidence(context: Context, sid: String, task: BotTaskEntity, args: JSONObject, toolId: String): String {
        val ownId = ToolOutcomeVerification.requireOwner(context, sid, task, toolId)
        check(task.completionEvidence?.let { JSONObject(it).optJSONObject("restoration")?.optBoolean("requires_review") } != true) { "restored_task_requires_user_review" }
        val run = checkNotNull(AgentSessionStore.activeRun(sid))
        val pending = ToolOutcomeVerification.pending(context, sid).filter { it.intentId != ownId }
        check(pending.isEmpty()) { "unsettled_tool_outcome_requires_verification" }
        require(!args.has("resolved_call_ids")) { "use_verify_outcome_with_exact_execution_identity" }
        val refs = args.optJSONArray("evidence") ?: throw IllegalArgumentException("completion_evidence_required")
        require(refs.length() in 1..16) { "provide 1..16 evidence references" }
        val verified = JSONArray()
        for (index in 0 until refs.length()) {
            val ref = refs.getJSONObject(index)
            when (ref.getString("type")) {
                "file" -> {
                    val actual = WorkspaceFileClient.fingerprint(sid, ref.getString("path"))
                    if (ref.has("sha256")) require(ref.getString("sha256").equals(actual.getString("sha256"), true)) { "artifact_hash_mismatch" }
                    verified.put(actual.put("type", "file"))
                }
                "document" -> {
                    val actual = com.openminis.app.runtime.files.DocumentEvidence.fingerprint(context, ref.getString("uri"))
                    if (ref.has("sha256")) require(ref.getString("sha256").equals(actual.getString("sha256"), true)) { "artifact_hash_mismatch" }
                    verified.put(actual.put("type", "document"))
                }
                "tool_result" -> {
                    val callId = ref.getString("call_id")
                    val receipts = AppDatabase.getInstance(context).chatDao().loadMessages(sid)
                        .filter { it.createdAt >= task.createdAt }.flatMap { message ->
                            val parts = runCatching { JSONArray(message.partsJson) }.getOrNull() ?: JSONArray()
                            (0 until parts.length()).mapNotNull { partIndex ->
                                val part = parts.optJSONObject(partIndex) ?: return@mapNotNull null
                                if (part.optString("type") != "toolResult") return@mapNotNull null
                                val value = part.optJSONObject("value") ?: return@mapNotNull null
                                if (value.optString("toolUseId") != callId || value.optString("executionTaskId") != task.id ||
                                    value.isNull("executionIntentId") || value.optString("executionIntentId").isBlank()) return@mapNotNull null
                                if (ref.has("execution_id") && ref.getString("execution_id") != value.optString("executionIntentId")) return@mapNotNull null
                                val observed = value.optJSONObject("outcomeVerification")?.let { proof ->
                                    proof.optInt("version") == 1 && proof.optString("method") == "user_observation" &&
                                        proof.optString("resolution") == "completed" && proof.optString("session_id") == sid &&
                                        proof.optString("task_id") == task.id && proof.optString("execution_id") == value.optString("executionIntentId")
                                } == true
                                if (!value.optBoolean("success") || value.optString("failureKind").let { it.isNotBlank() && it != "null" } ||
                                    (value.opt("executionStarted") != true && !observed)) return@mapNotNull null
                                message.id to value
                            }
                        }
                    require(receipts.size == 1) { "tool_receipt_missing_or_ambiguous" }
                    val (messageId, result) = receipts.single()
                    val name = result.optString("name")
                    val output = result.optString("output")
                    val observation = result.optJSONObject("outcomeVerification")?.takeIf {
                        it.optString("method") == "user_observation" && it.optString("resolution") == "completed" &&
                            it.optString("execution_id") == result.optString("executionIntentId") && it.optString("task_id") == task.id
                    }
                    require(result.optBoolean("success") && name !in setOf("get_goal", "create_goal", "update_goal", "verify_outcome", "review_restored_task", "agent.goal") &&
                        result.optString("failureKind").let { it.isBlank() || it == "null" ||
                            runCatching { !ToolFailureKind.valueOf(it).outcomeUnknown }.getOrDefault(false) } &&
                        !output.contains("TOOL_OUTCOME_UNKNOWN") && !output.contains("TOOL_INTERRUPTED")) { "tool_receipt_not_verifiable_success" }
                    val sha = java.security.MessageDigest.getInstance("SHA-256").digest(output.toByteArray())
                        .joinToString("") { "%02x".format(it) }
                    val receipt = JSONObject().put("type", "tool_result").put("call_id", callId).put("message_id", messageId)
                        .put("name", name)
                        .put("verification_method", observation?.optString("method") ?: "tool_receipt")
                        .put("execution_id", result.getString("executionIntentId")).put("run_id", result.getString("executionRunId"))
                    if (ToolSensitivePolicy.isSensitive(name)) receipt.put("output_redacted", true)
                    else receipt.put("output_sha256", sha)
                    verified.put(receipt)
                }
                else -> throw IllegalArgumentException("unknown_evidence_type")
            }
        }
        return JSONObject().put("task_id", task.id).put("session_id", sid).put("revision", task.revision)
            .put("verified_by_execution_id", ownId)
            .put("stop_generation", task.stopGeneration).put("run_id", run).put("verified_at", System.currentTimeMillis())
            .put("references", verified).toString()
    }
}
