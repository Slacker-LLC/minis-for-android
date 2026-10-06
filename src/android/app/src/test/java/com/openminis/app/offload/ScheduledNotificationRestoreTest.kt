package com.openminis.app.offload

import com.openminis.app.offload.ScheduledNotificationStore.Companion.RestoreAction
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledNotificationRestoreTest {
    private val now = 1_000_000_000_000L
    private fun entry(at: Long) = JSONObject().put("id", "n").put("trigger_at_ms", at)

    @Test
    fun aFutureEntryIsReArmedAfterReboot() {
        assertEquals(RestoreAction.RESCHEDULE, ScheduledNotificationStore.restoreAction(entry(now + 60_000), now))
    }

    @Test
    fun anEntryThatCameDueWhileTheDeviceWasOffIsDeliveredNow() {
        assertEquals(RestoreAction.DELIVER_NOW, ScheduledNotificationStore.restoreAction(entry(now - 10 * 60_000), now))
    }

    @Test
    fun anEntryLongOverdueIsDropped() {
        val tooOld = now - ScheduledNotificationStore.EXPIRY_GRACE_MS - 1
        assertEquals(RestoreAction.DROP, ScheduledNotificationStore.restoreAction(entry(tooOld), now))
    }

    @Test
    fun aSlightlyLateAlarmIsNotYetLost() {
        // Doze can delay delivery by many minutes; the entry is still pending and cancellable.
        assertFalse(ScheduledNotificationStore.isLost(entry(now - 20 * 60_000), now))
        assertTrue(ScheduledNotificationStore.isLost(entry(now - 2 * 3600_000), now))
    }
}
