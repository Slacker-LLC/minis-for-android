package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable root task for a user goal that may span several Bot turns.
 *
 * A delegation is one work package inside this record. Keeping the root
 * lifecycle separate means a worker finishing a turn cannot accidentally mark
 * the user's goal as delivered before the owner has reviewed the evidence.
 */
@Entity(
    tableName = "bot_tasks",
    indices = [
        Index(value = ["origin_session_id", "origin_message_id"], name = "index_bot_tasks_origin"),
        Index(value = ["owner_bot_id", "status", "updated_at"], name = "index_bot_tasks_owner_status"),
        Index(value = ["status", "updated_at"], name = "index_bot_tasks_status_updated"),
    ],
)
data class BotTaskEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "origin_session_id") val originSessionId: String,
    @ColumnInfo(name = "origin_message_id") val originMessageId: String? = null,
    @ColumnInfo(name = "owner_bot_id") val ownerBotId: String,
    val goal: String,
    @ColumnInfo(name = "acceptance_criteria") val acceptanceCriteria: String? = null,
    val status: String = STATUS_ACTIVE,
    val phase: String = PHASE_PLANNING,
    val revision: Int = 1,
    @ColumnInfo(name = "current_owner_session_id") val currentOwnerSessionId: String? = null,
    @ColumnInfo(name = "auto_runs_used") val autoRunsUsed: Int = 0,
    @ColumnInfo(name = "delegations_used") val delegationsUsed: Int = 0,
    @ColumnInfo(name = "revision_rounds_used") val revisionRoundsUsed: Int = 0,
    @ColumnInfo(name = "stop_generation") val stopGeneration: Int = 0,
    @ColumnInfo(name = "deadline_at") val deadlineAt: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "completed_at") val completedAt: Long? = null,
) {
    companion object {
        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_WAITING = "WAITING"
        const val STATUS_NEEDS_USER = "NEEDS_USER"
        const val STATUS_PAUSED = "PAUSED"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_CANCELLED = "CANCELLED"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_BUDGET_EXHAUSTED = "BUDGET_EXHAUSTED"

        const val PHASE_PLANNING = "PLANNING"
        const val PHASE_EXECUTING = "EXECUTING"
        const val PHASE_REVIEWING = "REVIEWING"
        const val PHASE_REVISING = "REVISING"
        const val PHASE_DELIVERING = "DELIVERING"

        val TERMINAL_STATUSES = setOf(
            STATUS_COMPLETED,
            STATUS_CANCELLED,
            STATUS_FAILED,
            STATUS_BUDGET_EXHAUSTED,
        )
    }
}
