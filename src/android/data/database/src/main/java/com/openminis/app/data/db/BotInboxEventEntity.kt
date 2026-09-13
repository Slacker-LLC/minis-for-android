package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A durable, idempotent notification waiting for a Bot owner to resume. */
@Entity(
    tableName = "bot_inbox_events",
    indices = [
        Index(value = ["dedupe_key"], unique = true, name = "index_bot_inbox_events_dedupe"),
        Index(value = ["recipient_bot_id", "status", "created_at"], name = "index_bot_inbox_events_recipient_status"),
        Index(value = ["root_task_id", "created_at"], name = "index_bot_inbox_events_root_created"),
        Index(value = ["status", "lease_expires_at"], name = "index_bot_inbox_events_lease"),
    ],
)
data class BotInboxEventEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "dedupe_key") val dedupeKey: String,
    @ColumnInfo(name = "root_task_id") val rootTaskId: String? = null,
    @ColumnInfo(name = "recipient_bot_id") val recipientBotId: String,
    @ColumnInfo(name = "recipient_session_id") val recipientSessionId: String? = null,
    val type: String,
    @ColumnInfo(name = "producer_delegation_id") val producerDelegationId: String? = null,
    @ColumnInfo(name = "producer_event_id") val producerEventId: String? = null,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    val status: String = STATUS_PENDING,
    @ColumnInfo(name = "lease_owner") val leaseOwner: String? = null,
    @ColumnInfo(name = "lease_expires_at") val leaseExpiresAt: Long? = null,
    @ColumnInfo(name = "wake_batch") val wakeBatch: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "consumed_at") val consumedAt: Long? = null,
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_CLAIMED = "CLAIMED"
        const val STATUS_CONSUMED = "CONSUMED"
        const val STATUS_DEAD = "DEAD"

        const val TYPE_DELEGATION_SUBMITTED = "DELEGATION_SUBMITTED"
        const val TYPE_REVISION_REQUESTED = "REVISION_REQUESTED"
        const val TYPE_TASK_NEEDS_USER = "TASK_NEEDS_USER"
        const val TYPE_TASK_FAILED = "TASK_FAILED"
    }
}
