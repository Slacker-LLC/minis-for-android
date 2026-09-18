package com.openminis.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BotDelegationDao {
    @Query("SELECT * FROM bot_delegations ORDER BY updated_at DESC LIMIT 100")
    fun observeRecent(): Flow<List<BotDelegationEntity>>

    @Query("SELECT * FROM bot_delegations WHERE id = :id")
    fun observe(id: String): Flow<BotDelegationEntity?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(delegation: BotDelegationEntity): Long

    @Query("SELECT * FROM bot_delegations WHERE source_session_id = :sessionId AND source_tool_id = :sourceToolId LIMIT 1")
    suspend fun findBySourceTool(sessionId: String, sourceToolId: String): BotDelegationEntity?

    @Query("SELECT * FROM bot_delegations WHERE id = :id")
    suspend fun get(id: String): BotDelegationEntity?

    @Query("SELECT * FROM bot_delegations WHERE source_session_id = :sessionId ORDER BY created_at DESC")
    suspend fun listForSourceSession(sessionId: String): List<BotDelegationEntity>

    @Query("SELECT COUNT(*) FROM bot_delegations WHERE source_session_id = :sessionId AND source_run_id = :runId")
    suspend fun countForSourceRun(sessionId: String, runId: String): Int

    @Query("SELECT * FROM bot_delegations WHERE status = 'RUNNING' ORDER BY created_at ASC")
    suspend fun listRunning(): List<BotDelegationEntity>

    @Query("SELECT * FROM bot_delegations WHERE status IN ('QUEUED', 'WAITING_TARGET') AND source_turn_settled = 1 ORDER BY created_at ASC LIMIT :limit")
    suspend fun listDispatchable(limit: Int): List<BotDelegationEntity>

    @Query("UPDATE bot_delegations SET source_turn_settled = 1, updated_at = :now WHERE source_session_id = :sessionId AND source_run_id = :runId AND status IN ('QUEUED', 'WAITING_TARGET')")
    suspend fun markSourceRunSettled(sessionId: String, runId: String, now: Long): Int

    @Query("SELECT * FROM bot_delegations WHERE status IN ('COMPLETED', 'FAILED', 'DENIED', 'CANCELLED', 'BUSY_GAVE_UP') AND delivered_at IS NULL ORDER BY finished_at ASC, created_at ASC LIMIT :limit")
    suspend fun listUndeliveredTerminal(limit: Int): List<BotDelegationEntity>

    @Query("UPDATE bot_delegations SET status = 'WAITING_TARGET', attempts = attempts + 1, updated_at = :now WHERE id = :id AND source_turn_settled = 1 AND status IN ('QUEUED', 'WAITING_TARGET')")
    suspend fun markWaitingTarget(id: String, now: Long): Int

    @Query("UPDATE bot_delegations SET status = 'BUSY_GAVE_UP', error_text = :reason, finished_at = :now, updated_at = :now WHERE id = :id AND status = 'WAITING_TARGET'")
    suspend fun giveUpBusy(id: String, reason: String, now: Long): Int

    @Query("UPDATE bot_delegations SET status = 'RUNNING', target_session_id = :targetSessionId, started_at = :now, updated_at = :now WHERE id = :id AND source_turn_settled = 1 AND status IN ('QUEUED', 'WAITING_TARGET')")
    suspend fun claim(id: String, targetSessionId: String, now: Long): Int

    @Query("UPDATE bot_delegations SET status = 'CANCELLED', error_text = 'source process stopped before turn settled', finished_at = :now, updated_at = :now WHERE source_turn_settled = 0 AND status IN ('QUEUED', 'WAITING_TARGET')")
    suspend fun cancelUnsettledAfterProcessStart(now: Long): Int

    @Query("UPDATE bot_delegations SET status = :status, target_session_id = COALESCE(:targetSessionId, target_session_id), result_text = :resultText, error_text = :errorText, outcome_unknown = :outcomeUnknown, finished_at = :now, updated_at = :now WHERE id = :id AND status IN ('QUEUED', 'WAITING_TARGET', 'RUNNING')")
    suspend fun finish(
        id: String,
        status: String,
        targetSessionId: String?,
        resultText: String?,
        errorText: String?,
        outcomeUnknown: Int,
        now: Long,
    ): Int

    @Query("UPDATE bot_delegations SET status = 'CANCELLED', error_text = :reason, finished_at = :now, updated_at = :now WHERE source_session_id = :sessionId AND status IN ('QUEUED', 'WAITING_TARGET')")
    suspend fun cancelQueuedForSourceSession(sessionId: String, reason: String?, now: Long): Int

    @Query("UPDATE bot_delegations SET status = 'CANCELLED', error_text = :reason, finished_at = :now, updated_at = :now WHERE source_session_id = :sessionId AND source_run_id = :runId AND status IN ('QUEUED', 'WAITING_TARGET')")
    suspend fun cancelQueuedForSourceRun(sessionId: String, runId: String, reason: String?, now: Long): Int

    @Query("UPDATE bot_delegations SET status = 'CANCELLED', error_text = :reason, finished_at = :now, updated_at = :now WHERE id = :id AND status IN ('QUEUED', 'WAITING_TARGET', 'RUNNING')")
    suspend fun cancel(id: String, reason: String?, now: Long): Int

    @Query("UPDATE bot_delegations SET delivered_at = :now, updated_at = :now WHERE id = :id AND delivered_at IS NULL")
    suspend fun markDelivered(id: String, now: Long): Int
}
