package com.openminis.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BotTaskDao {
    @Query("SELECT * FROM bot_tasks WHERE goal_session_id = :sessionId LIMIT 1")
    suspend fun sessionGoal(sessionId: String): BotTaskEntity?

    @Query("SELECT * FROM bot_tasks WHERE goal_session_id = :sessionId LIMIT 1")
    fun observeSessionGoal(sessionId: String): Flow<BotTaskEntity?>

    @Query("SELECT * FROM sessions WHERE id = :sessionId")
    suspend fun goalOwner(sessionId: String): ChatSessionEntity?

    @Update
    suspend fun replace(task: BotTaskEntity)

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
    ): BotTaskEntity? {
        val owner = checkNotNull(goalOwner(sessionId)) { "session_deleted" }
        val current = sessionGoal(sessionId)
        if (operation == "create") check(current == null) { "goal_already_exists" }
        if (operation == "update") check(current != null) { "goal_not_found" }
        if (goal.isBlank()) {
            return clearSessionGoal(sessionId)
        }
        check(current == null || current.status !in setOf(BotTaskEntity.STATUS_COMPLETED,
            BotTaskEntity.STATUS_CANCELLED, BotTaskEntity.STATUS_FAILED)) { "goal_is_terminal" }
        val now = System.currentTimeMillis()
        val task = if (current == null) {
            BotTaskEntity(
                id = java.util.UUID.randomUUID().toString(),
                originSessionId = sessionId,
                ownerBotId = owner.botId,
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
    suspend fun setSessionGoalActive(sessionId: String, active: Boolean): BotTaskEntity? {
        checkNotNull(goalOwner(sessionId)) { "session_deleted" }
        val current = sessionGoal(sessionId) ?: return null
        check(!active || current.completionEvidence?.let { org.json.JSONObject(it).optJSONObject("restoration")?.optBoolean("requires_review") } != true) {
            "请先核实从备份恢复的工作现场"
        }
        check(current.status !in BotTaskEntity.TERMINAL_STATUSES || (active &&
            current.status == BotTaskEntity.STATUS_BUDGET_EXHAUSTED && current.autoRunsUsed < current.maxAutoRuns &&
            current.deadlineAt?.let { it <= System.currentTimeMillis() } != true)) { "goal_is_terminal_or_budget_exhausted" }
        val changed = current.copy(
            status = if (active) BotTaskEntity.STATUS_ACTIVE else BotTaskEntity.STATUS_PAUSED,
            revision = current.revision + 1,
            // Invalidate callbacks started before either pause or explicit resume.
            stopGeneration = current.stopGeneration + 1,
            updatedAt = System.currentTimeMillis(),
        )
        replace(changed)
        return changed
    }

    @Transaction
    suspend fun clearSessionGoal(sessionId: String): BotTaskEntity? {
        val current = sessionGoal(sessionId) ?: return null
        val changed = current.copy(
            goalSessionId = null,
            status = if (current.status in BotTaskEntity.TERMINAL_STATUSES) current.status else BotTaskEntity.STATUS_CANCELLED,
            stopGeneration = current.stopGeneration + 1,
            updatedAt = System.currentTimeMillis(),
        )
        replace(changed)
        return changed
    }

    @Transaction
    suspend fun setSessionPlan(sessionId: String, mode: String, plan: String): BotTaskEntity? {
        checkNotNull(goalOwner(sessionId)) { "session_deleted" }
        val current = sessionGoal(sessionId) ?: return null
        val changed = current.copy(planMode = mode, taskPlan = plan, updatedAt = System.currentTimeMillis())
        replace(changed)
        return changed
    }

    @Transaction
    suspend fun updateOwnedGoal(expected: BotTaskEntity, goal: String, maxRuns: Int?, criteria: String?): BotTaskEntity {
        val current = checkNotNull(get(expected.id)) { "task_not_found" }
        check(current.revision == expected.revision && current.stopGeneration == expected.stopGeneration) { "task_changed" }
        check(current.status !in BotTaskEntity.TERMINAL_STATUSES) { "goal_is_terminal" }
        val changed = current.copy(goal = goal, maxAutoRuns = maxRuns ?: current.maxAutoRuns,
            acceptanceCriteria = criteria ?: current.acceptanceCriteria, revision = current.revision + 1,
            updatedAt = System.currentTimeMillis())
        replace(changed)
        return changed
    }

    @Query("SELECT * FROM bot_tasks WHERE origin_session_id = :sessionId OR goal_session_id = :sessionId OR current_owner_session_id = :sessionId ORDER BY updated_at DESC LIMIT 8")
    fun observeSessionTasks(sessionId: String): Flow<List<BotTaskEntity>>

    @Transaction
    suspend fun resumeReviewedTask(expected: BotTaskEntity): BotTaskEntity {
        val current = checkNotNull(get(expected.id)) { "task_not_found" }
        check(current.revision == expected.revision && current.stopGeneration == expected.stopGeneration && current.status == BotTaskEntity.STATUS_PAUSED) { "task_changed_or_not_paused" }
        check(current.completionEvidence?.let { org.json.JSONObject(it).optJSONObject("restoration")?.let { marker ->
            marker.optBoolean("requires_review") || !marker.optBoolean("checkpoint_restore_complete") } } != true) { "restoration_review_required" }
        check(current.autoRunsUsed < current.maxAutoRuns && current.deadlineAt?.let { it <= System.currentTimeMillis() } != true) { "task_budget_or_deadline_exhausted" }
        return current.copy(status = BotTaskEntity.STATUS_ACTIVE, revision = current.revision + 1,
            stopGeneration = current.stopGeneration + 1, updatedAt = System.currentTimeMillis()).also { replace(it) }
    }

    @Query("SELECT * FROM bot_tasks ORDER BY updated_at DESC LIMIT 100")
    fun observeRecent(): Flow<List<BotTaskEntity>>

    @Query("SELECT * FROM bot_tasks WHERE id = :id")
    fun observe(id: String): Flow<BotTaskEntity?>

    @Query("SELECT * FROM bot_tasks WHERE id = :id")
    suspend fun get(id: String): BotTaskEntity?

    @Query("SELECT * FROM bot_tasks WHERE status = 'COMPLETED' AND completion_evidence IS NOT NULL " +
        "AND (:sessionId IS NULL OR origin_session_id = :sessionId OR current_owner_session_id = :sessionId) " +
        "ORDER BY completed_at DESC, id DESC LIMIT :limit OFFSET :offset")
    suspend fun completedEvidence(sessionId: String?, limit: Int, offset: Int): List<BotTaskEntity>

    @Query("SELECT * FROM bot_tasks WHERE status = 'COMPLETED' AND completion_evidence IS NOT NULL AND INSTR(completion_evidence, 'global_shared') > 0 ORDER BY completed_at DESC LIMIT 20")
    suspend fun completedShared(): List<BotTaskEntity>

    @Query("SELECT * FROM bot_tasks WHERE owner_bot_id = :botId AND status = 'COMPLETED' AND completion_evidence IS NOT NULL ORDER BY completed_at DESC LIMIT 5")
    suspend fun completedForBot(botId: String): List<BotTaskEntity>

    @Query("SELECT * FROM bot_tasks WHERE created_at <= :snapshot ORDER BY created_at, id LIMIT :limit OFFSET :offset")
    suspend fun backupTasks(snapshot: Long, limit: Int, offset: Int): List<BotTaskEntity>

    @Query("SELECT COUNT(*) FROM bot_tasks WHERE (origin_session_id = :sessionId OR current_owner_session_id = :sessionId OR goal_session_id = :sessionId) AND completion_evidence LIKE '%\"checkpoint_restore_complete\":false%'")
    suspend fun incompleteCheckpointRestores(sessionId: String): Int

    @Query("SELECT completion_evidence FROM bot_tasks WHERE completion_evidence LIKE '%\"checkpoint_restore_complete\":false%'")
    suspend fun incompleteRestoreEvidence(): List<String>

    @Query("UPDATE bot_tasks SET completion_evidence = :evidence WHERE id = :id AND status = 'COMPLETED' AND completion_evidence = :expected")
    suspend fun appendDelivery(id: String, expected: String, evidence: String): Int


    @Query("SELECT * FROM bot_tasks WHERE origin_session_id = :sessionId AND origin_message_id = :messageId LIMIT 1")
    suspend fun findByOrigin(sessionId: String, messageId: String): BotTaskEntity?

    /** Root lookup and creation share Room's writer transaction across callers. */
    @Transaction
    suspend fun ensureRootTask(task: BotTaskEntity): BotTaskEntity {
        sessionGoal(task.originSessionId)?.takeIf { it.ownerBotId == task.ownerBotId }?.let { return it }
        task.originMessageId?.let { message ->
            findByOrigin(task.originSessionId, message)?.let { existing ->
                check(existing.ownerBotId == task.ownerBotId) { "task_owner_changed" }
                return existing
            }
        }
        insert(task)
        return task
    }

    @Query("SELECT id FROM bot_tasks WHERE origin_session_id = :sessionId OR goal_session_id = :sessionId OR current_owner_session_id = :sessionId")
    suspend fun taskIdsForSession(sessionId: String): List<String>

    @Transaction
    suspend fun bindRunOwner(id: String, revision: Int, generation: Int, sessionId: String): BotTaskEntity {
        val task = checkNotNull(get(id)) { "task_not_found" }
        check(task.revision == revision && task.stopGeneration == generation) { "task_changed" }
        check(task.status in setOf(BotTaskEntity.STATUS_ACTIVE, BotTaskEntity.STATUS_WAITING, BotTaskEntity.STATUS_NEEDS_USER)) { "task_not_continuable" }
        val owner = checkNotNull(goalOwner(sessionId)) { "session_deleted" }
        check(if (task.ownerBotId != null) task.ownerBotId == owner.botId else
            task.goalSessionId == sessionId || task.originSessionId == sessionId) { "task_owner_mismatch" }
        if (task.currentOwnerSessionId == sessionId) return task
        return task.copy(currentOwnerSessionId = sessionId, updatedAt = System.currentTimeMillis()).also { replace(it) }
    }

    @Query("SELECT COUNT(*) FROM bot_delegations WHERE root_task_id = :id AND status IN ('QUEUED', 'WAITING_TARGET', 'RUNNING')")
    suspend fun nonTerminalDelegations(id: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(task: BotTaskEntity)

    @Query(
        "UPDATE bot_tasks SET status = :status, phase = :phase, " +
            "current_owner_session_id = :ownerSessionId, updated_at = :now WHERE id = :id",
    )
    suspend fun updateState(id: String, status: String, phase: String, ownerSessionId: String?, now: Long): Int

    @Query(
        "UPDATE bot_tasks SET status = :status, phase = :phase, " +
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
        "UPDATE bot_tasks SET revision = revision + 1, revision_rounds_used = revision_rounds_used + 1, " +
            "status = :status, phase = 'REVISING', updated_at = :now " +
            "WHERE id = :id AND status IN ('ACTIVE', 'NEEDS_USER', 'WAITING') AND revision_rounds_used < :maxRounds " +
            "AND (:revision IS NULL OR revision = :revision) AND (:generation IS NULL OR stop_generation = :generation)",
    )
    suspend fun requestRevision(id: String, status: String, now: Long, maxRounds: Int, revision: Int?, generation: Int?): Int

    @Query(
        "UPDATE bot_tasks SET stop_generation = stop_generation + 1, status = :status, " +
            "updated_at = :now WHERE id = :id AND status NOT IN ('COMPLETED', 'CANCELLED', 'FAILED', 'BUDGET_EXHAUSTED')",
    )
    suspend fun stop(id: String, status: String, now: Long): Int

    /** Results are checked before this transaction; a concurrent stop/edit invalidates the commit. */
    @Transaction
    suspend fun settleVerified(
        id: String, revision: Int, generation: Int, status: String, progress: String,
        evidence: String?,
    ): BotTaskEntity {
        val current = checkNotNull(get(id)) { "task_not_found" }
        check(current.revision == revision && current.stopGeneration == generation) { "task_changed" }
        check(current.status in setOf(BotTaskEntity.STATUS_ACTIVE, BotTaskEntity.STATUS_NEEDS_USER, BotTaskEntity.STATUS_WAITING)) { "task_not_active" }
        check(status in setOf(BotTaskEntity.STATUS_COMPLETED, BotTaskEntity.STATUS_PAUSED,
            BotTaskEntity.STATUS_WAITING, BotTaskEntity.STATUS_NEEDS_USER)) { "invalid_task_status" }
        check(status != BotTaskEntity.STATUS_COMPLETED || !evidence.isNullOrBlank()) { "completion_evidence_required" }
        check(status != BotTaskEntity.STATUS_COMPLETED || nonTerminalDelegations(id) == 0) { "unsettled_delegations_require_review" }
        val now = System.currentTimeMillis()
        val changed = current.copy(status = status, progress = progress,
            phase = if (status == BotTaskEntity.STATUS_COMPLETED) BotTaskEntity.PHASE_DELIVERING else BotTaskEntity.PHASE_REVIEWING,
            completionEvidence = if (status == BotTaskEntity.STATUS_COMPLETED) evidence else current.completionEvidence,
            completedAt = if (status == BotTaskEntity.STATUS_COMPLETED) now else null,
            stopGeneration = if (status == BotTaskEntity.STATUS_PAUSED) generation + 1 else generation,
            updatedAt = now)
        replace(changed)
        return changed
    }

    @Transaction
    suspend fun continueGoal(id: String, revision: Int, generation: Int): BotTaskEntity? {
        val current = get(id) ?: return null
        if (current.revision != revision || current.stopGeneration != generation ||
            current.status != BotTaskEntity.STATUS_ACTIVE) return null
        val now = System.currentTimeMillis()
        val exhausted = current.autoRunsUsed >= current.maxAutoRuns || current.deadlineAt?.let { it <= now } == true
        val changed = current.copy(
            status = if (exhausted) BotTaskEntity.STATUS_BUDGET_EXHAUSTED else current.status,
            phase = if (exhausted) BotTaskEntity.PHASE_REVIEWING else BotTaskEntity.PHASE_EXECUTING,
            progress = if (exhausted) "自动接续预算已用完，需要用户决定下一步。" else current.progress,
            autoRunsUsed = if (exhausted) current.autoRunsUsed else current.autoRunsUsed + 1,
            updatedAt = now,
        )
        replace(changed)
        return changed
    }

    @Query(
        "UPDATE bot_tasks SET auto_runs_used = auto_runs_used + 1, updated_at = :now " +
            "WHERE id = :id AND status IN ('ACTIVE', 'NEEDS_USER', 'WAITING') AND auto_runs_used < :maxRuns AND auto_runs_used < max_auto_runs " +
            "AND (:revision IS NULL OR revision = :revision) AND (:generation IS NULL OR stop_generation = :generation)",
    )
    suspend fun recordAutoRun(id: String, now: Long, maxRuns: Int, revision: Int?, generation: Int?): Int

    @Query(
        "UPDATE bot_tasks SET delegations_used = delegations_used + 1, updated_at = :now " +
            "WHERE id = :id AND status IN ('ACTIVE', 'NEEDS_USER', 'WAITING') AND delegations_used < :maxDelegations " +
            "AND (:revision IS NULL OR revision = :revision) AND (:generation IS NULL OR stop_generation = :generation)",
    )
    suspend fun recordDelegation(id: String, now: Long, maxDelegations: Int, revision: Int?, generation: Int?): Int

    @Query(
        "UPDATE bot_tasks SET status = 'BUDGET_EXHAUSTED', phase = 'REVIEWING', updated_at = :now " +
            "WHERE id = :id AND status IN ('ACTIVE', 'NEEDS_USER', 'WAITING') " +
            "AND revision = :revision AND stop_generation = :generation",
    )
    suspend fun exhaustBudget(id: String, now: Long, revision: Int, generation: Int): Int
}
