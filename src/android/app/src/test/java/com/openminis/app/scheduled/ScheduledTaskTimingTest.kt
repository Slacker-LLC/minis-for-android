package com.openminis.app.scheduled

import com.openminis.app.ui.scheduled.localDayToPickerUtcMidnight
import com.openminis.app.ui.scheduled.startOfLocalDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ScheduledTaskTimingTest {

    private fun task(
        mode: ScheduledRepeatMode = ScheduledRepeatMode.DAILY,
        days: Set<Int> = emptySet(),
        start: Long? = null,
        end: Long? = null,
        enabled: Boolean = true,
    ) = ScheduledTask(
        id = "t",
        label = "t",
        timeOfDayHour = 9,
        timeOfDayMinute = 0,
        repeatMode = mode,
        customDays = days,
        prompt = "run",
        enabled = enabled,
        startDateMs = start,
        endDateMs = end,
    )

    private val now = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 6, 12, 0, 0) }.timeInMillis
    private fun day(offset: Int) = Calendar.getInstance().apply {
        timeInMillis = now
        add(Calendar.DAY_OF_YEAR, offset)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    // ---- SC08 ----------------------------------------------------------

    @Test
    fun anOrdinaryEnabledRoutineIsFine() {
        assertNull(ScheduledTaskPolicy.schedulingProblem(task(), now))
        assertNull(ScheduledTaskPolicy.schedulingProblem(task(start = day(1), end = day(30)), now))
    }

    @Test
    fun aCustomRepeatWithNoWeekdayCouldNeverFire() {
        assertNotNull(ScheduledTaskPolicy.schedulingProblem(task(ScheduledRepeatMode.CUSTOM, emptySet()), now))
        assertNull(ScheduledTaskPolicy.schedulingProblem(task(ScheduledRepeatMode.CUSTOM, setOf(Calendar.MONDAY)), now))
    }

    @Test
    fun anEndDateBeforeTheStartDateIsRefused() {
        assertNotNull(ScheduledTaskPolicy.schedulingProblem(task(start = day(5), end = day(2)), now))
    }

    @Test
    fun anEndDateAlreadyPastLeavesNothingToRun() {
        assertNotNull(ScheduledTaskPolicy.schedulingProblem(task(end = day(-3)), now))
    }

    @Test
    fun aDisabledDraftMayBeIncomplete() {
        assertNull(ScheduledTaskPolicy.schedulingProblem(task(ScheduledRepeatMode.CUSTOM, emptySet(), enabled = false), now))
        assertNull(ScheduledTaskPolicy.schedulingProblem(task(start = day(5), end = day(2), enabled = false), now))
    }

    // ---- SC07 ----------------------------------------------------------

    private val zones = listOf("UTC", "Asia/Shanghai", "America/Los_Angeles", "Pacific/Kiritimati", "Pacific/Pago_Pago")
        .map { TimeZone.getTimeZone(it) }

    private fun localMidnight(zone: TimeZone, y: Int, m: Int, d: Int) =
        Calendar.getInstance(zone).apply { clear(); set(y, m, d, 0, 0, 0) }.timeInMillis

    @Test
    fun theStoredLocalDayShownInThePickerIsTheSameCalendarDay() {
        for (zone in zones) {
            val stored = localMidnight(zone, 2026, Calendar.OCTOBER, 5)
            val shown = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                .apply { timeInMillis = localDayToPickerUtcMidnight(stored, zone) }
            assertEquals(zone.id, 5, shown.get(Calendar.DAY_OF_MONTH))
            assertEquals(zone.id, Calendar.OCTOBER, shown.get(Calendar.MONTH))
        }
    }

    @Test
    fun confirmingTheDialogWithoutChangingAnythingKeepsTheSameStoredDay() {
        for (zone in zones) {
            val stored = localMidnight(zone, 2026, Calendar.OCTOBER, 5)
            val roundTrip = startOfLocalDay(localDayToPickerUtcMidnight(stored, zone), zone)
            assertEquals(zone.id, stored, roundTrip)
        }
    }
}
