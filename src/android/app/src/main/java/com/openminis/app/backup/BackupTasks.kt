package com.openminis.app.backup

import com.openminis.app.data.db.BotTaskEntity
import com.openminis.app.runtime.ubuntu.UbuntuPaths
import kotlinx.serialization.Serializable

/** Task facts and unresolved receipts travel with chats; restoration never resumes a command. */
@Serializable
internal data class BackupTaskRecord(val task: BotTaskEntity, val checkpoints: Map<String, List<String>> = emptyMap())

internal object BackupTasks {
    const val FILE = "agent_tasks"
    const val TYPE = "AgentTaskV1"

    fun validate(record: BackupTaskRecord) {
        val task = record.task
        require(BackupRoleplayMapping.validId(task.id) && UbuntuPaths.isSafeSessionId(task.originSessionId))
        require(task.goalSessionId == null || UbuntuPaths.isSafeSessionId(task.goalSessionId))
        require(task.currentOwnerSessionId == null || UbuntuPaths.isSafeSessionId(task.currentOwnerSessionId))
        require(task.ownerBotId == null || BackupRoleplayMapping.validId(task.ownerBotId))
        require(task.goal.isNotBlank() && task.goal.length <= com.openminis.app.data.repository.BotTaskRepository.GOAL_MAX_CHARS && task.taskPlan.length <= 64_000 && task.progress.length <= 2000)
        require(task.revision in 1 until Int.MAX_VALUE && task.stopGeneration in 0 until Int.MAX_VALUE)
        require(task.maxAutoRuns in 1..100 && task.autoRunsUsed >= 0 && task.delegationsUsed >= 0 && task.revisionRoundsUsed >= 0)
        require(task.createdAt >= 0 && task.updatedAt >= 0)
        require(task.status in BotTaskEntity.TERMINAL_STATUSES + setOf(BotTaskEntity.STATUS_ACTIVE, BotTaskEntity.STATUS_WAITING,
            BotTaskEntity.STATUS_NEEDS_USER, BotTaskEntity.STATUS_PAUSED))
        require(task.phase in setOf(BotTaskEntity.PHASE_PLANNING, BotTaskEntity.PHASE_EXECUTING, BotTaskEntity.PHASE_REVIEWING,
            BotTaskEntity.PHASE_REVISING, BotTaskEntity.PHASE_DELIVERING))
        task.completionEvidence?.let { require(it.length <= 512 * 1024); org.json.JSONObject(it) }
        require(record.checkpoints.size <= 100 && record.checkpoints.keys.all(UbuntuPaths::isSafeSessionId))
        require(record.checkpoints.values.sumOf { rows -> rows.sumOf { it.length.toLong() } } <= 512 * 1024)
    }
}
