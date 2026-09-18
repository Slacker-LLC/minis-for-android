package com.openminis.app.data.repository

import com.openminis.app.data.db.BotDelegationDao
import com.openminis.app.data.db.BotDelegationEntity
import java.util.UUID

class BotDelegationRepository(private val dao: BotDelegationDao) {
    fun observeRecent(): kotlinx.coroutines.flow.Flow<List<BotDelegationEntity>> = dao.observeRecent()
    fun observe(id: String): kotlinx.coroutines.flow.Flow<BotDelegationEntity?> = dao.observe(id)

    suspend fun enqueue(
        sourceBotId: String,
        sourceSessionId: String,
        sourceRunId: String,
        sourceToolId: String? = null,
        rootTaskId: String? = null,
        targetBotId: String,
        prompt: String,
        depth: Int = 1,
    ): BotDelegationEntity {
        val normalizedPrompt = prompt.trim().take(PROMPT_MAX_CHARS)
        require(normalizedPrompt.isNotEmpty()) { "Delegation prompt must not be blank" }
        require(depth == 1) { "Only one delegation level is supported" }
        val normalizedToolId = sourceToolId?.trim()?.takeIf { it.isNotEmpty() }
        normalizedToolId?.let { existing ->
            dao.findBySourceTool(sourceSessionId, existing)?.let { return it }
        }
        val now = System.currentTimeMillis()
        val delegation = BotDelegationEntity(
            id = UUID.randomUUID().toString(),
            sourceBotId = sourceBotId,
            sourceSessionId = sourceSessionId,
            sourceRunId = sourceRunId,
            sourceToolId = normalizedToolId,
            rootTaskId = rootTaskId?.trim()?.takeIf { it.isNotEmpty() },
            targetBotId = targetBotId,
            prompt = normalizedPrompt,
            depth = depth,
            createdAt = now,
            updatedAt = now,
        )
        val inserted = dao.insert(delegation)
        if (inserted == -1L && normalizedToolId != null) {
            return dao.findBySourceTool(sourceSessionId, normalizedToolId)
                ?: error("delegation insert conflict without existing source tool row")
        }
        return delegation
    }

    suspend fun get(id: String): BotDelegationEntity? = dao.get(id)

    suspend fun listForSourceSession(sessionId: String): List<BotDelegationEntity> =
        dao.listForSourceSession(sessionId)

    suspend fun countForSourceRun(sessionId: String, runId: String): Int =
        dao.countForSourceRun(sessionId, runId)

    suspend fun findBySourceTool(sessionId: String, sourceToolId: String): BotDelegationEntity? =
        dao.findBySourceTool(sessionId, sourceToolId)

    suspend fun listRunning(): List<BotDelegationEntity> = dao.listRunning()

    suspend fun listDispatchable(limit: Int = 32): List<BotDelegationEntity> =
        dao.listDispatchable(limit.coerceIn(1, 100))

    suspend fun markSourceRunSettled(sessionId: String, runId: String): Int =
        dao.markSourceRunSettled(sessionId, runId, System.currentTimeMillis())

    suspend fun listUndeliveredTerminal(limit: Int = 32): List<BotDelegationEntity> =
        dao.listUndeliveredTerminal(limit.coerceIn(1, 100))

    suspend fun markWaitingTarget(id: String): Boolean =
        dao.markWaitingTarget(id, System.currentTimeMillis()) == 1

    suspend fun giveUpBusy(id: String, reason: String): Boolean =
        dao.giveUpBusy(id, reason.take(ERROR_MAX_CHARS), System.currentTimeMillis()) == 1

    suspend fun claim(id: String, targetSessionId: String): Boolean =
        dao.claim(id, targetSessionId, System.currentTimeMillis()) == 1

    suspend fun cancelUnsettledAfterProcessStart(): Int =
        dao.cancelUnsettledAfterProcessStart(System.currentTimeMillis())

    suspend fun complete(id: String, targetSessionId: String, resultText: String): Boolean =
        dao.finish(
            id = id,
            status = BotDelegationEntity.STATUS_COMPLETED,
            targetSessionId = targetSessionId,
            resultText = resultText.take(RESULT_MAX_CHARS),
            errorText = null,
            outcomeUnknown = 0,
            now = System.currentTimeMillis(),
        ) == 1

    suspend fun fail(
        id: String,
        errorText: String,
        targetSessionId: String? = null,
        outcomeUnknown: Boolean = false,
    ): Boolean = dao.finish(
        id = id,
        status = BotDelegationEntity.STATUS_FAILED,
        targetSessionId = targetSessionId,
        resultText = null,
        errorText = errorText.take(ERROR_MAX_CHARS),
        outcomeUnknown = if (outcomeUnknown) 1 else 0,
        now = System.currentTimeMillis(),
    ) == 1

    suspend fun deny(id: String, reason: String): Boolean = dao.finish(
        id = id,
        status = BotDelegationEntity.STATUS_DENIED,
        targetSessionId = null,
        resultText = null,
        errorText = reason.take(ERROR_MAX_CHARS),
        outcomeUnknown = 0,
        now = System.currentTimeMillis(),
    ) == 1

    suspend fun cancelQueuedForSourceSession(sessionId: String, reason: String): Int =
        dao.cancelQueuedForSourceSession(sessionId, reason.take(ERROR_MAX_CHARS), System.currentTimeMillis())

    suspend fun cancelQueuedForSourceRun(sessionId: String, runId: String, reason: String): Int =
        dao.cancelQueuedForSourceRun(sessionId, runId, reason.take(ERROR_MAX_CHARS), System.currentTimeMillis())

    suspend fun cancel(id: String, reason: String? = null): Boolean =
        dao.cancel(id, reason?.take(ERROR_MAX_CHARS), System.currentTimeMillis()) == 1

    suspend fun markDelivered(id: String): Boolean =
        dao.markDelivered(id, System.currentTimeMillis()) == 1

    companion object {
        const val PROMPT_MAX_CHARS = 12_000
        const val RESULT_MAX_CHARS = 24_000
        const val ERROR_MAX_CHARS = 2_000
    }
}
