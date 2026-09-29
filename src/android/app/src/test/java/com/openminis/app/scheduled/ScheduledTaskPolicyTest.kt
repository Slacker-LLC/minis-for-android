package com.openminis.app.scheduled

import com.openminis.app.data.db.BotEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledTaskPolicyTest {
    private fun bot(id: String, name: String) = BotEntity(
        id = id,
        name = name,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun task(id: String, botId: String?) = ScheduledTask(
        id = id,
        label = id,
        timeOfDayHour = 9,
        timeOfDayMinute = 0,
        repeatMode = ScheduledRepeatMode.DAILY,
        prompt = "run",
        botId = botId,
    )

    @Test
    fun `routine limit counts disabled rows and permits editing an existing row`() {
        val existing = (1..ScheduledTask.MAX_ROUTINES_PER_BOT).map { task("t$it", "bot-a") }
        assertFalse(ScheduledTaskPolicy.withinBotRoutineLimit(existing, task("new", "bot-a")))
        assertTrue(ScheduledTaskPolicy.withinBotRoutineLimit(existing, task("t1", "bot-a"), excludingTaskId = "t1"))
        assertTrue(ScheduledTaskPolicy.withinBotRoutineLimit(existing, task("new", "bot-b")))
        assertTrue(ScheduledTaskPolicy.withinBotRoutineLimit(existing, task("new", null)))
    }

    @Test
    fun `bot task filter does not include other owners`() {
        assertEquals(listOf("a"), ScheduledTaskPolicy.tasksForBot(listOf(task("a", "bot-a"), task("b", "bot-b")), "bot-a").map { it.id })
    }

    @Test
    fun `selector supports exact id and case-insensitive unique name`() {
        val bots = listOf(bot("one", "Research"), bot("two", "Writer"))
        assertEquals("one", (ScheduledTaskPolicy.resolveBotSelector("one", bots) as BotSelectorResult.Found).bot.id)
        assertEquals("one", (ScheduledTaskPolicy.resolveBotSelector(" research ", bots) as BotSelectorResult.Found).bot.id)
        assertTrue(ScheduledTaskPolicy.resolveBotSelector("unknown", bots) is BotSelectorResult.NotFound)
    }

    @Test
    fun `duplicate names are ambiguous and include candidate Bots`() {
        val result = ScheduledTaskPolicy.resolveBotSelector("Research", listOf(bot("one", "Research"), bot("two", "research")))
        assertTrue(result is BotSelectorResult.Ambiguous)
        assertEquals(listOf("one", "two"), (result as BotSelectorResult.Ambiguous).candidates.map { it.id })
    }

    @Test
    fun `missing selector inherits the Bot attached to the caller session`() {
        val bots = listOf(bot("owner", "Owner"), bot("other", "Other"))
        assertEquals("owner", (ScheduledTaskPolicy.resolveBotSelector(null, bots, "owner") as BotSelectorResult.Found).bot.id)
        assertTrue(ScheduledTaskPolicy.resolveBotSelector(null, bots, null) is BotSelectorResult.None)
    }

    @Test
    fun `owner state differentiates ordinary missing and disabled Bot routines`() {
        val active = bot("active", "Active")
        val disabled = active.copy(enabled = false)
        assertEquals(BotOwnerState.UNOWNED, ScheduledTaskPolicy.botOwnerState(null, null))
        assertEquals(BotOwnerState.MISSING, ScheduledTaskPolicy.botOwnerState("gone", null))
        assertEquals(BotOwnerState.DISABLED, ScheduledTaskPolicy.botOwnerState(disabled.id, disabled))
        assertEquals(BotOwnerState.ENABLED, ScheduledTaskPolicy.botOwnerState(active.id, active))
    }

    @Test
    fun `task model override and legacy pin beat Bot default`() {
        val botBinding = "{\"type\":\"entry\",\"entryId\":\"bot-model\"}"
        val taskBinding = "{\"type\":\"entry\",\"entryId\":\"task-model\"}"
        assertEquals(taskBinding, ScheduledTaskPolicy.effectiveModelBinding(taskBinding, null, botBinding))
        assertEquals(null, ScheduledTaskPolicy.effectiveModelBinding(null, "legacy-model", botBinding))
        assertEquals(botBinding, ScheduledTaskPolicy.effectiveModelBinding(null, null, botBinding))
    }
}
