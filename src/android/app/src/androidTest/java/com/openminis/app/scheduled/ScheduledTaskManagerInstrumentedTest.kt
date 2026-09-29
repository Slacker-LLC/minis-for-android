package com.openminis.app.scheduled

import android.app.PendingIntent
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ScheduledTaskManagerInstrumentedTest {
    @Test
    fun botCapacityAndDeletion_areBoundedAndCancelOnlyOwnedAlarms() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = ScheduledTaskManager(context)
        val ownerBotId = "b2-owner-${UUID.randomUUID()}"
        val otherBotId = "b2-other-${UUID.randomUUID()}"
        val owned = (1..ScheduledTask.MAX_ROUTINES_PER_BOT).map { index ->
            ScheduledTask(
                id = "b2-routine-$index-${UUID.randomUUID()}",
                label = "Routine $index",
                timeOfDayHour = 9,
                timeOfDayMinute = 0,
                repeatMode = ScheduledRepeatMode.DAILY,
                prompt = "Run $index",
                botId = ownerBotId,
            )
        }
        val other = ScheduledTask(
            id = "b2-other-${UUID.randomUUID()}",
            label = "Other Bot routine",
            timeOfDayHour = 10,
            timeOfDayMinute = 0,
            repeatMode = ScheduledRepeatMode.DAILY,
            prompt = "Keep this routine",
            botId = otherBotId,
        )
        try {
            owned.forEach(manager::create)
            assertTrue(owned.all { pendingIntentExists(context, it.id) })

            val overflow = ScheduledTask(
                id = "b2-overflow-${UUID.randomUUID()}",
                label = "Overflow",
                timeOfDayHour = 11,
                timeOfDayMinute = 0,
                repeatMode = ScheduledRepeatMode.DAILY,
                prompt = "Must not be saved",
                botId = ownerBotId,
            )
            val failure = runCatching { manager.create(overflow) }.exceptionOrNull()
            assertTrue("expected a clear limit failure", failure is IllegalArgumentException)
            assertNull(manager.get(overflow.id))

            manager.create(other)
            assertEquals(ScheduledTask.MAX_ROUTINES_PER_BOT, manager.deleteAllForBot(ownerBotId))
            assertTrue(owned.all { manager.get(it.id) == null && !pendingIntentExists(context, it.id) })
            assertNotNull(manager.get(other.id))
            assertTrue(pendingIntentExists(context, other.id))
        } finally {
            owned.forEach { manager.delete(it.id) }
            manager.delete(other.id)
        }
    }

    private fun pendingIntentExists(context: android.content.Context, taskId: String): Boolean {
        val intent = Intent(context, ScheduledTaskAlarmReceiver::class.java).apply {
            action = ScheduledTaskManager.ACTION_FIRE
            putExtra(ScheduledTaskManager.EXTRA_TASK_ID, taskId)
        }
        val existing = PendingIntent.getBroadcast(
            context,
            taskId.hashCode() and 0x7FFFFFFF,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        return existing != null
    }
}
