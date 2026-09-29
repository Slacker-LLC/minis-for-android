package com.openminis.app.scheduled

import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduledTaskStoreMigrationTest {
    @Test
    fun `group binding rewrite preserves task fields and alarm trigger`() {
        val task = ScheduledTask(
            id = "task-1",
            label = "Morning review",
            timeOfDayHour = 9,
            timeOfDayMinute = 30,
            repeatMode = ScheduledRepeatMode.DAILY,
            prompt = "Review the inbox",
            modelId = "old-model",
            modelBinding = """{"type":"group","groupId":"legacy"}""",
            enabled = true,
            createdAt = 1234L,
            startDateMs = 10_000L,
            endDateMs = 90_000L,
            lastFiredAt = 20_000L,
            lastResultPreview = "Done",
            lastResultSessionId = "session-1",
            runHistory = listOf(ScheduledRun(20_000L, "session-1", "Done", true)),
        )
        val update = ScheduledTaskBindingUpdate(
            modelBinding = """{"type":"entry","entryId":"provider/model"}""",
            modelId = "new-model",
        )
        val (migrated, changed) = rewriteScheduledTaskBindings(listOf(task), mapOf(task.id to update))
        val expected = task.copy(modelBinding = update.modelBinding, modelId = update.modelId)

        assertEquals(1, changed)
        assertEquals(listOf(expected), migrated)
        assertEquals(task.nextTriggerMs(now = 30_000L), migrated.single().nextTriggerMs(now = 30_000L))

        val (secondPass, secondChanged) = rewriteScheduledTaskBindings(migrated, mapOf(task.id to update))
        assertEquals(0, secondChanged)
        assertEquals(migrated, secondPass)
    }

    @Test
    fun `null binding migration clears legacy group without dropping pinned model id`() {
        val task = ScheduledTask(
            id = "task-2",
            label = "No members",
            timeOfDayHour = 8,
            timeOfDayMinute = 0,
            repeatMode = ScheduledRepeatMode.ONCE,
            prompt = "Run",
            modelId = "legacy-pinned-model",
            modelBinding = """{"type":"group","groupId":"removed"}""",
        )
        val (migrated, changed) = rewriteScheduledTaskBindings(
            listOf(task),
            mapOf(task.id to ScheduledTaskBindingUpdate(modelBinding = null)),
        )
        assertEquals(1, changed)
        assertEquals(null, migrated.single().modelBinding)
        assertEquals(task.modelId, migrated.single().modelId)
    }
}
