package com.openminis.app.data.repository

import com.openminis.app.data.db.BotTaskDao
import com.openminis.app.data.db.BotTaskEntity
import java.util.UUID

/** Repository for the root lifecycle shared by owner, workers and the UI. */
class BotTaskRepository(private val dao: BotTaskDao) {
    fun observeSessionGoal(sessionId: String) = dao.observeSessionGoal(sessionId)

    suspend fun sessionGoal(sessionId: String) = dao.sessionGoal(sessionId)

    suspend fun setSessionGoal(
        sessionId: String,
        goal: String,
        maxRuns: Int? = null,
        criteria: String? = null,
        operation: String = "set",
        initialPlanMode: String = "off",
        initialPlan: String = "",
    ) = dao.setSessionGoal(
        sessionId, normalize(goal, GOAL_MAX_CHARS), maxRuns?.coerceIn(1, 100),
        criteria?.let { normalize(it, CRITERIA_MAX_CHARS) }, operation,
        initialPlanMode, normalize(initialPlan, PLAN_MAX_CHARS),
    )

    suspend fun setSessionGoalActive(sessionId: String, active: Boolean) = dao.setSessionGoalActive(sessionId, active)

    suspend fun clearSessionGoal(sessionId: String) = dao.clearSessionGoal(sessionId)

    suspend fun setSessionPlan(sessionId: String, mode: String, plan: String) =
        dao.setSessionPlan(sessionId, mode, normalize(plan, PLAN_MAX_CHARS))

    suspend fun updateOwnedGoal(expected: BotTaskEntity, goal: String, maxRuns: Int?, criteria: String?): BotTaskEntity {
        val text = normalize(goal, GOAL_MAX_CHARS)
        require(text.isNotBlank()) { "goal text is required" }
        return dao.updateOwnedGoal(expected, text, maxRuns, criteria?.let { normalize(it, CRITERIA_MAX_CHARS) })
    }

    fun observeRecent() = dao.observeRecent()

    fun observe(id: String) = dao.observe(id)

    suspend fun get(id: String): BotTaskEntity? = dao.get(id)

    suspend fun taskIdsForSession(sessionId: String) = dao.taskIdsForSession(sessionId)

    suspend fun bindRunOwner(task: BotTaskEntity, sessionId: String) =
        dao.bindRunOwner(task.id, task.revision, task.stopGeneration, sessionId)

    suspend fun ensureRootTask(
        originSessionId: String,
        originMessageId: String?,
        ownerBotId: String,
        goal: String,
        acceptanceCriteria: String? = null,
        deadlineAt: Long? = null,
    ): BotTaskEntity {
        val normalizedGoal = normalize(goal, GOAL_MAX_CHARS)
        require(normalizedGoal.isNotEmpty()) { "Task goal must not be blank" }
        val normalizedMessageId = originMessageId?.trim()?.takeIf { it.isNotEmpty() }
        val now = System.currentTimeMillis()
        val task = BotTaskEntity(
            id = UUID.randomUUID().toString(),
            originSessionId = originSessionId,
            originMessageId = normalizedMessageId,
            ownerBotId = ownerBotId,
            goal = normalizedGoal,
            acceptanceCriteria = acceptanceCriteria?.let { normalize(it, CRITERIA_MAX_CHARS) },
            deadlineAt = deadlineAt,
            createdAt = now,
            updatedAt = now,
        )
        return dao.ensureRootTask(task)
    }

    suspend fun updateState(
        id: String,
        status: String,
        phase: String,
        ownerSessionId: String? = null,
    ): Boolean = dao.updateState(id, status, phase, ownerSessionId, System.currentTimeMillis()) == 1

    suspend fun updateStateIfWakeable(
        id: String,
        status: String,
        phase: String,
        ownerSessionId: String? = null,
        revision: Int? = null,
        generation: Int? = null,
    ): Boolean = dao.updateStateIfWakeable(id, status, phase, ownerSessionId, System.currentTimeMillis(), revision, generation) == 1

    suspend fun requestRevision(id: String, maxRounds: Int, revision: Int? = null, generation: Int? = null): Boolean =
        dao.requestRevision(id, BotTaskEntity.STATUS_ACTIVE, System.currentTimeMillis(), maxRounds, revision, generation) == 1

    suspend fun pause(id: String): Boolean {
        if (dao.stop(id, BotTaskEntity.STATUS_PAUSED, System.currentTimeMillis()) != 1) return false
        dao.get(id)?.let { task -> com.openminis.app.agent.AgentSessionStore.stopObsoleteGoalRun(
            task.currentOwnerSessionId ?: task.goalSessionId ?: task.originSessionId, task) }
        return true
    }

    suspend fun cancel(id: String, stopOwner: Boolean = true): Boolean {
        if (dao.stop(id, BotTaskEntity.STATUS_CANCELLED, System.currentTimeMillis()) != 1) return false
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            dao.get(id)?.let { com.openminis.app.agent.AgentSessionStore.stopTaskWork(it, stopOwner) }
        }
        return true
    }

    suspend fun settleVerified(id: String, revision: Int, generation: Int, status: String, progress: String, evidence: String?) =
        dao.settleVerified(id, revision, generation, status, normalize(progress, 2_000), evidence)

    suspend fun continueGoal(id: String, revision: Int, generation: Int) = dao.continueGoal(id, revision, generation)

    suspend fun recordAutoRun(id: String, maxRuns: Int, revision: Int? = null, generation: Int? = null): Boolean =
        dao.recordAutoRun(id, System.currentTimeMillis(), maxRuns, revision, generation) == 1

    suspend fun recordDelegation(id: String, maxDelegations: Int, revision: Int? = null, generation: Int? = null): Boolean =
        dao.recordDelegation(id, System.currentTimeMillis(), maxDelegations, revision, generation) == 1

    suspend fun exhaustBudget(id: String, revision: Int, generation: Int): Boolean =
        dao.exhaustBudget(id, System.currentTimeMillis(), revision, generation) == 1

    private fun normalize(value: String, limit: Int): String =
        value.replace("\r\n", "\n").replace('\r', '\n')
            .replace(Regex("[\\p{Cntrl}&&[^\\n\\t]]"), "")
            .trim().take(limit)

    companion object {
        const val GOAL_MAX_CHARS = 12_000
        const val CRITERIA_MAX_CHARS = 8_000
        const val PLAN_MAX_CHARS = 12_000
    }
}
