package com.openminis.app.scheduled

import com.openminis.app.data.db.BotEntity

/** Pure policy helpers shared by scheduled-task surfaces and JVM tests. */
object ScheduledTaskPolicy {
    fun botOwnerState(botId: String?, owner: BotEntity?): BotOwnerState = when {
        botId.isNullOrBlank() -> BotOwnerState.UNOWNED
        owner == null -> BotOwnerState.MISSING
        owner.enabled -> BotOwnerState.ENABLED
        else -> BotOwnerState.DISABLED
    }

    /** A routine-level binding is explicit; a legacy pinned model also beats Bot defaults. */
    fun effectiveModelBinding(taskBinding: String?, legacyModelId: String?, botBinding: String?): String? =
        taskBinding ?: if (legacyModelId == null) botBinding else null

    fun withinBotRoutineLimit(
        existing: List<ScheduledTask>,
        candidate: ScheduledTask,
        excludingTaskId: String? = null,
        limit: Int = ScheduledTask.MAX_ROUTINES_PER_BOT,
    ): Boolean {
        val botId = candidate.botId ?: return true
        return existing.count { it.botId == botId && it.id != excludingTaskId } < limit
    }

    fun tasksForBot(tasks: List<ScheduledTask>, botId: String): List<ScheduledTask> =
        tasks.filter { it.botId == botId }

    /** Exact Bot ids win; names are case-insensitive and must identify one Bot. */
    fun resolveBotSelector(
        selector: String?,
        bots: List<BotEntity>,
        sessionBotId: String? = null,
    ): BotSelectorResult {
        val value = selector?.trim()?.takeIf { it.isNotEmpty() }
            ?: return bots.firstOrNull { it.id == sessionBotId }
                ?.let(BotSelectorResult::Found)
                ?: BotSelectorResult.None
        bots.firstOrNull { it.id == value }?.let { return BotSelectorResult.Found(it) }
        val matches = bots.filter { it.name.trim().equals(value, ignoreCase = true) }
        return when (matches.size) {
            0 -> BotSelectorResult.NotFound(value)
            1 -> BotSelectorResult.Found(matches.single())
            else -> BotSelectorResult.Ambiguous(value, matches)
        }
    }
}

sealed interface BotSelectorResult {
    data object None : BotSelectorResult
    data class Found(val bot: BotEntity) : BotSelectorResult
    data class NotFound(val selector: String) : BotSelectorResult
    data class Ambiguous(val selector: String, val candidates: List<BotEntity>) : BotSelectorResult
}

enum class BotOwnerState { UNOWNED, MISSING, DISABLED, ENABLED }
