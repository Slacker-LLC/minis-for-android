package com.openminis.app.data.repository

import com.openminis.app.data.db.BotTaskDao
import com.openminis.app.data.db.BotTaskEntity
import java.util.UUID

/** Repository for the root lifecycle shared by owner, workers and the UI. */
class BotTaskRepository(private val dao: BotTaskDao) {
    fun observeRecent() = dao.observeRecent()

    fun observe(id: String) = dao.observe(id)

    suspend fun get(id: String): BotTaskEntity? = dao.get(id)

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
        if (normalizedMessageId != null) {
            dao.findByOrigin(originSessionId, normalizedMessageId)?.let { return it }
        }
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
        dao.insert(task)
        return task
    }

    suspend fun updateState(
        id: String,
        status: String,
        phase: String,
        ownerSessionId: String? = null,
    ): Boolean = dao.updateState(id, status, phase, ownerSessionId, System.currentTimeMillis()) == 1

    suspend fun requestRevision(id: String): Boolean =
        dao.requestRevision(id, BotTaskEntity.STATUS_ACTIVE, System.currentTimeMillis()) == 1

    suspend fun pause(id: String): Boolean =
        dao.stop(id, BotTaskEntity.STATUS_PAUSED, System.currentTimeMillis()) == 1

    suspend fun cancel(id: String): Boolean =
        dao.stop(id, BotTaskEntity.STATUS_CANCELLED, System.currentTimeMillis()) == 1

    suspend fun complete(id: String): Boolean = dao.complete(id, System.currentTimeMillis()) == 1

    suspend fun recordAutoRun(id: String): Boolean = dao.recordAutoRun(id, System.currentTimeMillis()) == 1

    suspend fun recordDelegation(id: String): Boolean = dao.recordDelegation(id, System.currentTimeMillis()) == 1

    private fun normalize(value: String, limit: Int): String =
        value.replace("\r\n", "\n").replace('\r', '\n')
            .replace(Regex("[\\p{Cntrl}&&[^\\n\\t]]"), "")
            .trim().take(limit)

    companion object {
        const val GOAL_MAX_CHARS = 12_000
        const val CRITERIA_MAX_CHARS = 8_000
    }
}
