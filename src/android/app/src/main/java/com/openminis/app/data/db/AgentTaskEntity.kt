package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable lifecycle and evidence for a user goal in an ordinary Agent session.
 */
@Entity(
    tableName = "agent_tasks",
    indices = [
        Index(value = ["origin_session_id", "origin_message_id"], name = "index_agent_tasks_origin"),
        Index(value = ["status", "updated_at"], name = "index_agent_tasks_status_updated"),
        Index(value = ["goal_session_id"], unique = true, name = "index_agent_tasks_goal_session"),
    ],
)
@kotlinx.serialization.Serializable
data class AgentTaskEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "origin_session_id") val originSessionId: String,
    @ColumnInfo(name = "origin_message_id") val originMessageId: String? = null,
    /** Null for a task created from a user request; set for the session's explicit Goal. */
    @ColumnInfo(name = "goal_session_id") val goalSessionId: String? = null,
    val goal: String,
    @ColumnInfo(name = "acceptance_criteria") val acceptanceCriteria: String? = null,
    val status: String = STATUS_ACTIVE,
    val phase: String = PHASE_PLANNING,
    val revision: Int = 1,
    @ColumnInfo(name = "current_owner_session_id") val currentOwnerSessionId: String? = null,
    @ColumnInfo(name = "auto_runs_used") val autoRunsUsed: Int = 0,
    @ColumnInfo(name = "stop_generation") val stopGeneration: Int = 0,
    @ColumnInfo(name = "deadline_at") val deadlineAt: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "completed_at") val completedAt: Long? = null,
    @ColumnInfo(name = "max_auto_runs", defaultValue = "3") val maxAutoRuns: Int = 3,
    @ColumnInfo(name = "plan_mode", defaultValue = "'off'") val planMode: String = "off",
    @ColumnInfo(name = "task_plan", defaultValue = "''") val taskPlan: String = "",
    @ColumnInfo(name = "progress", defaultValue = "''") val progress: String = "",
    @ColumnInfo(name = "completion_evidence") val completionEvidence: String? = null,
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
