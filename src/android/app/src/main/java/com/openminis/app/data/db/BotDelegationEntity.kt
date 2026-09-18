package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "bot_delegations",
    indices = [
        Index(value = ["source_session_id", "source_run_id"], name = "index_bot_delegations_source_run"),
        Index(
            value = ["source_session_id", "source_tool_id"],
            unique = true,
            name = "index_bot_delegations_source_tool",
        ),
        Index(value = ["target_bot_id", "status"], name = "index_bot_delegations_target_status"),
        Index(value = ["status", "created_at"], name = "index_bot_delegations_status_created"),
        Index(value = ["root_task_id", "created_at"], name = "index_bot_delegations_root_created"),
    ],
)
data class BotDelegationEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "source_bot_id") val sourceBotId: String,
    @ColumnInfo(name = "source_session_id") val sourceSessionId: String,
    @ColumnInfo(name = "source_run_id") val sourceRunId: String,
    @ColumnInfo(name = "source_tool_id") val sourceToolId: String? = null,
    @ColumnInfo(name = "source_turn_settled") val sourceTurnSettled: Int = 0,
    @ColumnInfo(name = "root_task_id") val rootTaskId: String? = null,
    @ColumnInfo(name = "target_bot_id") val targetBotId: String,
    @ColumnInfo(name = "target_session_id") val targetSessionId: String? = null,
    val prompt: String,
    val depth: Int = 1,
    val attempts: Int = 0,
    val status: String = STATUS_QUEUED,
    @ColumnInfo(name = "outcome_unknown") val outcomeUnknown: Int = 0,
    @ColumnInfo(name = "result_text") val resultText: String? = null,
    @ColumnInfo(name = "error_text") val errorText: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "started_at") val startedAt: Long? = null,
    @ColumnInfo(name = "finished_at") val finishedAt: Long? = null,
    @ColumnInfo(name = "delivered_at") val deliveredAt: Long? = null,
) {
    companion object {
        const val STATUS_QUEUED = "QUEUED"
        const val STATUS_WAITING_TARGET = "WAITING_TARGET"
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_DENIED = "DENIED"
        const val STATUS_CANCELLED = "CANCELLED"
        const val STATUS_BUSY_GAVE_UP = "BUSY_GAVE_UP"
        val TERMINAL_STATUSES = setOf(STATUS_COMPLETED, STATUS_FAILED, STATUS_DENIED, STATUS_CANCELLED, STATUS_BUSY_GAVE_UP)
    }
}
