package com.openminis.app.tools

import com.openminis.app.data.db.BotInboxEventDao
import com.openminis.app.data.db.BotInboxEventEntity
import com.openminis.app.data.repository.BotInboxRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BotInboxLeaseTest {
    @Test
    fun `expired lease is reclaimed while consumed event is not claimed twice`() = runBlocking {
        val dao = InMemoryInboxDao()
        val repo = BotInboxRepository(dao)
        dao.rows["expired"] = event("expired", leaseOwner = "dead-process", leaseExpiresAt = 1L)
        dao.rows["consumed"] = event("consumed", status = BotInboxEventEntity.STATUS_CONSUMED)

        val claimed = repo.claimPending("owner", "wake:test", leaseMs = 60_000L, rootTaskId = "root")
        assertEquals(listOf("expired"), claimed.map { it.id })
        assertTrue(repo.consume("expired", "wake:test"))
        assertTrue(repo.claimPending("owner", "wake:test", rootTaskId = "root").isEmpty())
        assertEquals(BotInboxEventEntity.STATUS_CONSUMED, dao.rows.getValue("expired").status)
    }

    @Test
    fun `consume rejects a mismatched lease owner`() = runBlocking {
        val dao = InMemoryInboxDao()
        val repo = BotInboxRepository(dao)
        dao.rows["event"] = event("event")

        assertEquals(1, repo.claimPending("owner", "wake:correct", rootTaskId = "root").size)
        assertFalse(repo.consume("event", "wake:wrong"))
        assertTrue(repo.consume("event", "wake:correct"))
    }

    private fun event(
        id: String,
        status: String = BotInboxEventEntity.STATUS_PENDING,
        leaseOwner: String? = null,
        leaseExpiresAt: Long? = null,
    ) = BotInboxEventEntity(
        id = id,
        dedupeKey = id,
        rootTaskId = "root",
        recipientBotId = "owner",
        recipientSessionId = "owner-session",
        type = BotInboxEventEntity.TYPE_DELEGATION_SUBMITTED,
        payloadJson = "{}",
        status = status,
        leaseOwner = leaseOwner,
        leaseExpiresAt = leaseExpiresAt,
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
    )

    private class InMemoryInboxDao : BotInboxEventDao {
        val rows = linkedMapOf<String, BotInboxEventEntity>()

        override suspend fun insert(event: BotInboxEventEntity): Long {
            if (rows.values.any { it.dedupeKey == event.dedupeKey }) return -1L
            rows[event.id] = event
            return 1L
        }

        override suspend fun findByDedupeKey(dedupeKey: String) = rows.values.firstOrNull { it.dedupeKey == dedupeKey }

        override suspend fun listPending(botId: String, rootTaskId: String?, limit: Int) = rows.values
            .filter { it.recipientBotId == botId && it.status == BotInboxEventEntity.STATUS_PENDING }
            .filter { rootTaskId == null || it.rootTaskId == rootTaskId }
            .take(limit)

        override suspend fun listPendingForRootTask(botId: String, rootTaskId: String) = rows.values
            .filter {
                it.recipientBotId == botId && it.rootTaskId == rootTaskId &&
                    it.status == BotInboxEventEntity.STATUS_PENDING
            }

        override suspend fun listPendingAll(limit: Int) = rows.values
            .filter { it.status == BotInboxEventEntity.STATUS_PENDING }
            .take(limit)

        override suspend fun earliestLeaseExpiration(): Long? = rows.values
            .filter { it.status == BotInboxEventEntity.STATUS_CLAIMED }
            .mapNotNull { it.leaseExpiresAt }
            .minOrNull()

        override suspend fun claim(id: String, leaseOwner: String, leaseExpiresAt: Long, wakeBatch: String, now: Long): Int {
            val row = rows[id] ?: return 0
            if (row.status != BotInboxEventEntity.STATUS_PENDING) return 0
            rows[id] = row.copy(
                status = BotInboxEventEntity.STATUS_CLAIMED,
                leaseOwner = leaseOwner,
                leaseExpiresAt = leaseExpiresAt,
                wakeBatch = wakeBatch,
                updatedAt = now,
            )
            return 1
        }

        override suspend fun releaseExpired(now: Long): Int {
            var count = 0
            rows.entries.toList().forEach { (id, row) ->
                if (row.status == BotInboxEventEntity.STATUS_CLAIMED && row.leaseExpiresAt?.let { it <= now } == true) {
                    rows[id] = row.copy(
                        status = BotInboxEventEntity.STATUS_PENDING,
                        leaseOwner = null,
                        leaseExpiresAt = null,
                        wakeBatch = null,
                        updatedAt = now,
                    )
                    count++
                }
            }
            return count
        }

        override suspend fun consume(id: String, leaseOwner: String, now: Long): Int {
            val row = rows[id] ?: return 0
            if (row.status != BotInboxEventEntity.STATUS_CLAIMED || row.leaseOwner != leaseOwner) return 0
            rows[id] = row.copy(status = BotInboxEventEntity.STATUS_CONSUMED, consumedAt = now, updatedAt = now)
            return 1
        }

        override suspend fun markDead(id: String, now: Long): Int {
            val row = rows[id] ?: return 0
            if (row.status !in setOf(BotInboxEventEntity.STATUS_PENDING, BotInboxEventEntity.STATUS_CLAIMED)) return 0
            rows[id] = row.copy(status = BotInboxEventEntity.STATUS_DEAD, updatedAt = now)
            return 1
        }
    }
}
