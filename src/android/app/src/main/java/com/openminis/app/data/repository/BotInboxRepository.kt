package com.openminis.app.data.repository

import com.openminis.app.data.db.BotInboxEventDao
import com.openminis.app.data.db.BotInboxEventEntity
import java.util.UUID

/** Durable owner inbox with dedupe and expiring claims for process recovery. */
class BotInboxRepository(private val dao: BotInboxEventDao) {
    suspend fun enqueue(
        dedupeKey: String,
        recipientBotId: String,
        type: String,
        payloadJson: String,
        rootTaskId: String? = null,
        recipientSessionId: String? = null,
        producerDelegationId: String? = null,
        producerEventId: String? = null,
    ): BotInboxEventEntity {
        val normalizedKey = dedupeKey.trim().take(KEY_MAX_CHARS)
        require(normalizedKey.isNotEmpty()) { "Inbox dedupe key must not be blank" }
        require(payloadJson.length <= PAYLOAD_MAX_CHARS) { "Inbox payload is too large" }
        dao.findByDedupeKey(normalizedKey)?.let { return it }
        val now = System.currentTimeMillis()
        val event = BotInboxEventEntity(
            id = UUID.randomUUID().toString(),
            dedupeKey = normalizedKey,
            rootTaskId = rootTaskId?.trim()?.takeIf { it.isNotEmpty() },
            recipientBotId = recipientBotId,
            recipientSessionId = recipientSessionId?.trim()?.takeIf { it.isNotEmpty() },
            type = type,
            producerDelegationId = producerDelegationId,
            producerEventId = producerEventId,
            payloadJson = payloadJson,
            createdAt = now,
            updatedAt = now,
        )
        val inserted = dao.insert(event)
        if (inserted == -1L) {
            return dao.findByDedupeKey(normalizedKey)
                ?: error("inbox insert conflict without an existing dedupe row")
        }
        return event
    }

    suspend fun releaseExpired(): Int = dao.releaseExpired(System.currentTimeMillis())

    suspend fun claimPending(
        recipientBotId: String,
        leaseOwner: String,
        wakeBatch: String = UUID.randomUUID().toString(),
        leaseMs: Long = DEFAULT_LEASE_MS,
        limit: Int = DEFAULT_BATCH_SIZE,
    ): List<BotInboxEventEntity> {
        val now = System.currentTimeMillis()
        dao.releaseExpired(now)
        return dao.listPending(recipientBotId, limit.coerceIn(1, MAX_BATCH_SIZE)).mapNotNull { event ->
            if (dao.claim(event.id, leaseOwner, now + leaseMs.coerceAtLeast(1_000L), wakeBatch, now) == 1) {
                event.copy(
                    status = BotInboxEventEntity.STATUS_CLAIMED,
                    leaseOwner = leaseOwner,
                    leaseExpiresAt = now + leaseMs.coerceAtLeast(1_000L),
                    wakeBatch = wakeBatch,
                    updatedAt = now,
                )
            } else null
        }
    }

    suspend fun consume(id: String, leaseOwner: String): Boolean =
        dao.consume(id, leaseOwner, System.currentTimeMillis()) == 1

    suspend fun markDead(id: String): Boolean = dao.markDead(id, System.currentTimeMillis()) == 1

    companion object {
        const val DEFAULT_LEASE_MS = 60_000L
        const val DEFAULT_BATCH_SIZE = 16
        const val MAX_BATCH_SIZE = 100
        const val KEY_MAX_CHARS = 512
        const val PAYLOAD_MAX_CHARS = 64_000
    }
}
