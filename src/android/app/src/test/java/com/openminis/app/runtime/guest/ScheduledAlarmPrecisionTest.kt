package com.openminis.app.runtime.guest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-scheduled-exact-alarm] The precision note the scheduled-task CLI
 * publishes beside every task.
 *
 * A device can only demonstrate the state it happens to be in (the test phone
 * has SCHEDULE_EXACT_ALARM denied), so the branch that says nothing needs a hint
 * is pinned here rather than on hardware.
 */
class ScheduledAlarmPrecisionTest {

    @Test
    fun `exact scheduling carries no hint`() {
        val (precision, hint) = ScheduledTaskOffloadHandler.precisionNote(canScheduleExact = true)
        assertEquals("exact", precision)
        assertNull(hint)
    }

    @Test
    fun `an inexact fallback explains itself`() {
        val (precision, hint) = ScheduledTaskOffloadHandler.precisionNote(canScheduleExact = false)
        assertEquals("inexact", precision)
        assertNotNull(hint)
        assertTrue(hint!!, hint.contains("Alarms & reminders"))
        assertTrue(hint, hint.contains("few minutes late"))
    }
}

