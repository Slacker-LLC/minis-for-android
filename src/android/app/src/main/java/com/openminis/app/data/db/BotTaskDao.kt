package com.openminis.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BotTaskDao {
    @Query("SELECT * FROM bot_tasks ORDER BY updated_at DESC LIMIT 100")
    fun observeRecent(): Flow<List<BotTaskEntity>>

    @Query("SELECT * FROM bot_tasks WHERE id = :id")
    fun observe(id: String): Flow<BotTaskEntity?>

    @Query("SELECT * FROM bot_tasks WHERE id = :id")
    suspend fun get(id: String): BotTaskEntity?

    @Query("SELECT * FROM bot_tasks WHERE origin_session_id = :sessionId AND origin_message_id = :messageId LIMIT 1")
    suspend fun findByOrigin(sessionId: String, messageId: String): BotTaskEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(task: BotTaskEntity)

    @Query(
        "UPDATE bot_tasks SET status = :status, phase = :phase, " +
            "current_owner_session_id = :ownerSessionId, updated_at = :now WHERE id = :id",
    )
    suspend fun updateState(id: String, status: String, phase: String, ownerSessionId: String?, now: Long): Int

    @Query(
        "UPDATE bot_tasks SET revision = revision + 1, revision_rounds_used = revision_rounds_used + 1, " +
            "status = :status, phase = 'REVISING', updated_at = :now WHERE id = :id AND status NOT IN ('COMPLETED', 'CANCELLED')",
    )
    suspend fun requestRevision(id: String, status: String, now: Long): Int

    @Query(
        "UPDATE bot_tasks SET stop_generation = stop_generation + 1, status = :status, " +
            "updated_at = :now WHERE id = :id AND status NOT IN ('COMPLETED', 'CANCELLED')",
    )
    suspend fun stop(id: String, status: String, now: Long): Int

    @Query(
        "UPDATE bot_tasks SET status = 'COMPLETED', phase = 'DELIVERING', completed_at = :now, updated_at = :now " +
            "WHERE id = :id AND status NOT IN ('CANCELLED', 'FAILED', 'BUDGET_EXHAUSTED')",
    )
    suspend fun complete(id: String, now: Long): Int

    @Query(
        "UPDATE bot_tasks SET auto_runs_used = auto_runs_used + 1, updated_at = :now " +
            "WHERE id = :id AND status NOT IN ('COMPLETED', 'CANCELLED', 'FAILED', 'BUDGET_EXHAUSTED')",
    )
    suspend fun recordAutoRun(id: String, now: Long): Int

    @Query(
        "UPDATE bot_tasks SET delegations_used = delegations_used + 1, updated_at = :now " +
            "WHERE id = :id AND status NOT IN ('COMPLETED', 'CANCELLED', 'FAILED', 'BUDGET_EXHAUSTED')",
    )
    suspend fun recordDelegation(id: String, now: Long): Int
}
