package com.openminis.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentTaskDao {
    @Query("SELECT * FROM agent_tasks WHERE goal_session_id = :sessionId LIMIT 1")
    suspend fun sessionGoal(sessionId: String): AgentTaskEntity?

    @Query("SELECT * FROM agent_tasks WHERE goal_session_id = :sessionId LIMIT 1")
    fun observeSessionGoal(sessionId: String): Flow<AgentTaskEntity?>

    @Query("SELECT * FROM sessions WHERE id = :sessionId")
    suspend fun goalOwner(sessionId: String): ChatSessionEntity?

    @Update
    suspend fun replace(task: AgentTaskEntity)

    /** Check and write under Room's writer transaction; all entry points share this operation. */
    @Transaction
    suspend fun setSessionGoal(
        sessionId: String,
        goal: String,
        maxRuns: Int?,
        criteria: String?,
        operation: String,
        initialPlanMode: String,
        initialPlan: String,
    ): AgentTaskEntity? {
        checkNotNull(goalOwner(sessionId)) { "session_deleted" }
        val current = sessionGoal(sessionId)
        if (operation == "create") check(current == null) { "goal_already_exists" }
        if (operation == "update") check(current != null) { "goal_not_found" }
        if (goal.isBlank()) {
            return clearSessionGoal(sessionId)
        }
        check(current == null || current.status !in setOf(AgentTaskEntity.STATUS_COMPLETED,
            AgentTaskEntity.STATUS_CANCELLED, AgentTaskEntity.STATUS_FAILED)) { "goal_is_terminal" }
        val now = System.currentTimeMillis()
        val task = if (current == null) {
            AgentTaskEntity(
                id = java.util.UUID.randomUUID().toString(),
                originSessionId = sessionId,
                goalSessionId = sessionId,
                currentOwnerSessionId = sessionId,
                goal = goal,
                acceptanceCriteria = criteria,
                maxAutoRuns = maxRuns ?: 8,
                planMode = initialPlanMode,
                taskPlan = initialPlan,
                createdAt = now,
                updatedAt = now,
            )
        } else {
            current.copy(
                goal = goal,
                acceptanceCriteria = criteria ?: current.acceptanceCriteria,
                maxAutoRuns = maxRuns ?: current.maxAutoRuns,
                revision = current.revision + 1,
                updatedAt = now,
            )
        }
        if (current == null) insert(task) else replace(task)
        return task
    }

    @Transaction
    suspend fun setSessionGoalActive(sessionId: String, active: Boolean): AgentTaskEntity? {
        checkNotNull(goalOwner(sessionId)) { "session_deleted" }
        val current = sessionGoal(sessionId) ?: return null
        check(!active || current.completionEvidence?.let { org.json.JSONObject(it).optJSONObject("restoration")?.optBoolean("requires_review") } != true) {
            "请先核实从备份恢复的工作现场"
        }
        check(current.status !in AgentTaskEntity.TERMINAL_STATUSES || (active &&
            current.status == AgentTaskEntity.STATUS_BUDGET_EXHAUSTED && current.autoRunsUsed < current.maxAutoRuns &&
            current.deadlineAt?.let { it <= System.currentTimeMillis() } != true)) { "goal_is_terminal_or_budget_exhausted" }
        val changed = current.copy(
            status = if (active) AgentTaskEntity.STATUS_ACTIVE else AgentTaskEntity.STATUS_PAUSED,
            revision = current.revision + 1,
            // Invalidate callbacks started before either pause or explicit resume.
            stopGeneration = current.stopGeneration + 1,
            updatedAt = System.currentTimeMillis(),
        )
        replace(changed)
        return changed
    }

    @Transaction
    suspend fun clearSessionGoal(sessionId: String): AgentTaskEntity? {
        val current = sessionGoal(sessionId) ?: return null
        val changed = current.copy(
            goalSessionId = null,
            status = if (current.status in AgentTaskEntity.TERMINAL_STATUSES) current.status else AgentTaskEntity.STATUS_CANCELLED,
            stopGeneration = current.stopGeneration + 1,
            updatedAt = System.currentTimeMillis(),
        )
        replace(changed)
        return changed
    }

    @Transaction
    suspend fun setSessionPlan(sessionId: String, mode: String, plan: String): AgentTaskEntity? {
        checkNotNull(goalOwner(sessionId)) { "session_deleted" }
        val current = sessionGoal(sessionId) ?: return null
        val changed = current.copy(planMode = mode, taskPlan = plan, updatedAt = System.currentTimeMillis())
        replace(changed)
        return changed
    }

    @Transaction
    suspend fun updateOwnedGoal(expected: AgentTaskEntity, goal: String, maxRuns: Int?, criteria: String?): AgentTaskEntity {
        val current = checkNotNull(get(expected.id)) { "task_not_found" }
        check(current.revision == expected.revision && current.stopGeneration == expected.stopGeneration) { "task_changed" }
        check(current.status !in AgentTaskEntity.TERMINAL_STATUSES) { "goal_is_terminal" }
        val changed = current.copy(goal = goal, maxAutoRuns = maxRuns ?: current.maxAutoRuns,
            acceptanceCriteria = criteria ?: current.acceptanceCriteria, revision = current.revision + 1,
            updatedAt = System.currentTimeMillis())
        replace(changed)
        return changed
    }

    @Query("SELECT * FROM agent_tasks WHERE origin_session_id = :sessionId OR goal_session_id = :sessionId OR current_owner_session_id = :sessionId ORDER BY updated_at DESC LIMIT 8")
    fun observeSessionTasks(sessionId: String): Flow<List<AgentTaskEntity>>

    @Transaction
    suspend fun resumeReviewedTask(expected: AgentTaskEntity): AgentTaskEntity {
        val current = checkNotNull(get(expected.id)) { "task_not_found" }
        check(current.revision == expected.revision && current.stopGeneration == expected.stopGeneration && current.status == AgentTaskEntity.STATUS_PAUSED) { "task_changed_or_not_paused" }
        check(current.completionEvidence?.let { org.json.JSONObject(it).optJSONObject("restoration")?.let { marker ->
            marker.optBoolean("requires_review") || !marker.optBoolean("checkpoint_restore_complete") } } != true) { "restoration_review_required" }
        check(current.autoRunsUsed < current.maxAutoRuns && current.deadlineAt?.let { it <= System.currentTimeMillis() } != true) { "task_budget_or_deadline_exhausted" }
        return current.copy(status = AgentTaskEntity.STATUS_ACTIVE, revision = current.revision + 1,
            stopGeneration = current.stopGeneration + 1, updatedAt = System.currentTimeMillis()).also { replace(it) }
    }

    @Query("SELECT * FROM agent_tasks ORDER BY updated_at DESC LIMIT 100")
    fun observeRecent(): Flow<List<AgentTaskEntity>>

    @Query("SELECT * FROM agent_tasks WHERE id = :id")
    fun observe(id: String): Flow<AgentTaskEntity?>

    @Query("SELECT * FROM agent_tasks WHERE id = :id")
    suspend fun get(id: String): AgentTaskEntity?

    @Query("SELECT * FROM agent_tasks WHERE status = 'COMPLETED' AND completion_evidence IS NOT NULL " +
        "AND (:sessionId IS NULL OR origin_session_id = :sessionId OR current_owner_session_id = :sessionId) " +
        "ORDER BY completed_at DESC, id DESC LIMIT :limit OFFSET :offset")
    suspend fun completedEvidence(sessionId: String?, limit: Int, offset: Int): List<AgentTaskEntity>

    @Query("SELECT * FROM agent_tasks WHERE created_at <= :snapshot ORDER BY created_at, id LIMIT :limit OFFSET :offset")
    suspend fun backupTasks(snapshot: Long, limit: Int, offset: Int): List<AgentTaskEntity>

    @Query("SELECT COUNT(*) FROM agent_tasks WHERE (origin_session_id = :sessionId OR current_owner_session_id = :sessionId OR goal_session_id = :sessionId) AND completion_evidence LIKE '%\"checkpoint_restore_complete\":false%'")
    suspend fun incompleteCheckpointRestores(sessionId: String): Int

    @Query("SELECT completion_evidence FROM agent_tasks WHERE completion_evidence LIKE '%\"checkpoint_restore_complete\":false%'")
    suspend fun incompleteRestoreEvidence(): List<String>

    @Query("UPDATE agent_tasks SET completion_evidence = :evidence WHERE id = :id AND status = 'COMPLETED' AND completion_evidence = :expected")
    suspend fun appendDelivery(id: String, expected: String, evidence: String): Int


    @Query("SELECT id FROM agent_tasks WHERE origin_session_id = :sessionId OR goal_session_id = :sessionId OR current_owner_session_id = :sessionId")
    suspend fun taskIdsForSession(sessionId: String): List<String>

    @Transaction
    suspend fun bindRunOwner(id: String, revision: Int, generation: Int, sessionId: String): AgentTaskEntity {
        val task = checkNotNull(get(id)) { "task_not_found" }
        check(task.revision == revision && task.stopGeneration == generation) { "task_changed" }
        check(task.status in setOf(AgentTaskEntity.STATUS_ACTIVE, AgentTaskEntity.STATUS_WAITING, AgentTaskEntity.STATUS_NEEDS_USER)) { "task_not_continuable" }
        checkNotNull(goalOwner(sessionId)) { "session_deleted" }
        check(task.goalSessionId == sessionId || task.originSessionId == sessionId) { "task_owner_mismatch" }
        if (task.currentOwnerSessionId == sessionId) return task
        return task.copy(currentOwnerSessionId = sessionId, updatedAt = System.currentTimeMillis()).also { replace(it) }
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(task: AgentTaskEntity)

    @Query(
        "UPDATE agent_tasks SET status = :status, phase = :phase, " +
            "current_owner_session_id = :ownerSessionId, updated_at = :now WHERE id = :id",
    )
    suspend fun updateState(id: String, status: String, phase: String, ownerSessionId: String?, now: Long): Int

    @Query(
        "UPDATE agent_tasks SET status = :status, phase = :phase, " +
            "current_owner_session_id = :ownerSessionId, updated_at = :now " +
            "WHERE id = :id AND status IN ('ACTIVE', 'NEEDS_USER', 'WAITING') " +
            "AND (:revision IS NULL OR revision = :revision) AND (:generation IS NULL OR stop_generation = :generation)",
    )
    suspend fun updateStateIfWakeable(
        id: String,
        status: String,
        phase: String,
        ownerSessionId: String?,
        now: Long,
        revision: Int?,
        generation: Int?,
    ): Int

    @Query(
        "UPDATE agent_tasks SET stop_generation = stop_generation + 1, status = :status, " +
            "updated_at = :now WHERE id = :id AND status NOT IN ('COMPLETED', 'CANCELLED', 'FAILED', 'BUDGET_EXHAUSTED')",
    )
    suspend fun stop(id: String, status: String, now: Long): Int

    /** Results are checked before this transaction; a concurrent stop/edit invalidates the commit. */
    @Transaction
    suspend fun settleVerified(
        id: String, revision: Int, generation: Int, status: String, progress: String,
        evidence: String?,
    ): AgentTaskEntity {
        val current = checkNotNull(get(id)) { "task_not_found" }
        check(current.revision == revision && current.stopGeneration == generation) { "task_changed" }
        check(current.status in setOf(AgentTaskEntity.STATUS_ACTIVE, AgentTaskEntity.STATUS_NEEDS_USER, AgentTaskEntity.STATUS_WAITING)) { "task_not_active" }
        check(status in setOf(AgentTaskEntity.STATUS_COMPLETED, AgentTaskEntity.STATUS_PAUSED,
            AgentTaskEntity.STATUS_WAITING, AgentTaskEntity.STATUS_NEEDS_USER)) { "invalid_task_status" }
        check(status != AgentTaskEntity.STATUS_COMPLETED || !evidence.isNullOrBlank()) { "completion_evidence_required" }
        val now = System.currentTimeMillis()
        val changed = current.copy(status = status, progress = progress,
            phase = if (status == AgentTaskEntity.STATUS_COMPLETED) AgentTaskEntity.PHASE_DELIVERING else AgentTaskEntity.PHASE_REVIEWING,
            completionEvidence = if (status == AgentTaskEntity.STATUS_COMPLETED) evidence else current.completionEvidence,
            completedAt = if (status == AgentTaskEntity.STATUS_COMPLETED) now else null,
            stopGeneration = if (status == AgentTaskEntity.STATUS_PAUSED) generation + 1 else generation,
            updatedAt = now)
        replace(changed)
        return changed
    }

    @Transaction
    suspend fun continueGoal(id: String, revision: Int, generation: Int): AgentTaskEntity? {
        val current = get(id) ?: return null
        if (current.revision != revision || current.stopGeneration != generation ||
            current.status != AgentTaskEntity.STATUS_ACTIVE) return null
        val now = System.currentTimeMillis()
        val exhausted = current.autoRunsUsed >= current.maxAutoRuns || current.deadlineAt?.let { it <= now } == true
        val changed = current.copy(
            status = if (exhausted) AgentTaskEntity.STATUS_BUDGET_EXHAUSTED else current.status,
            phase = if (exhausted) AgentTaskEntity.PHASE_REVIEWING else AgentTaskEntity.PHASE_EXECUTING,
            progress = if (exhausted) "自动接续预算已用完，需要用户决定下一步。" else current.progress,
            autoRunsUsed = if (exhausted) current.autoRunsUsed else current.autoRunsUsed + 1,
            updatedAt = now,
        )
        replace(changed)
        return changed
    }

    @Query(
        "UPDATE agent_tasks SET auto_runs_used = auto_runs_used + 1, updated_at = :now " +
            "WHERE id = :id AND status IN ('ACTIVE', 'NEEDS_USER', 'WAITING') AND auto_runs_used < :maxRuns AND auto_runs_used < max_auto_runs " +
            "AND (:revision IS NULL OR revision = :revision) AND (:generation IS NULL OR stop_generation = :generation)",
    )
    suspend fun recordAutoRun(id: String, now: Long, maxRuns: Int, revision: Int?, generation: Int?): Int

    @Query(
        "UPDATE agent_tasks SET status = 'BUDGET_EXHAUSTED', phase = 'REVIEWING', updated_at = :now " +
            "WHERE id = :id AND status IN ('ACTIVE', 'NEEDS_USER', 'WAITING') " +
            "AND revision = :revision AND stop_generation = :generation",
    )
    suspend fun exhaustBudget(id: String, now: Long, revision: Int, generation: Int): Int
}
