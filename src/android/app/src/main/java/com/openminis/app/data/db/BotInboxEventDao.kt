package com.openminis.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BotInboxEventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: BotInboxEventEntity): Long

    @Query("SELECT * FROM bot_inbox_events WHERE dedupe_key = :dedupeKey LIMIT 1")
    suspend fun findByDedupeKey(dedupeKey: String): BotInboxEventEntity?

    @Query(
        "SELECT * FROM bot_inbox_events WHERE recipient_bot_id = :botId AND status = 'PENDING' " +
            "ORDER BY created_at ASC LIMIT :limit",
    )
    suspend fun listPending(botId: String, limit: Int): List<BotInboxEventEntity>

    @Query(
        "UPDATE bot_inbox_events SET status = 'CLAIMED', lease_owner = :leaseOwner, " +
            "lease_expires_at = :leaseExpiresAt, wake_batch = :wakeBatch, updated_at = :now " +
            "WHERE id = :id AND status = 'PENDING'",
    )
    suspend fun claim(id: String, leaseOwner: String, leaseExpiresAt: Long, wakeBatch: String, now: Long): Int

    @Query(
        "UPDATE bot_inbox_events SET status = 'PENDING', lease_owner = NULL, lease_expires_at = NULL, " +
            "wake_batch = NULL, updated_at = :now WHERE status = 'CLAIMED' AND lease_expires_at <= :now",
    )
    suspend fun releaseExpired(now: Long): Int

    @Query(
        "UPDATE bot_inbox_events SET status = 'CONSUMED', consumed_at = :now, updated_at = :now " +
            "WHERE id = :id AND status = 'CLAIMED' AND lease_owner = :leaseOwner",
    )
    suspend fun consume(id: String, leaseOwner: String, now: Long): Int

    @Query(
        "UPDATE bot_inbox_events SET status = 'DEAD', updated_at = :now " +
            "WHERE id = :id AND status IN ('PENDING', 'CLAIMED')",
    )
    suspend fun markDead(id: String, now: Long): Int
}
