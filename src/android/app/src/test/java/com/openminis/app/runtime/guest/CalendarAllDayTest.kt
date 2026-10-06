package com.openminis.app.runtime.guest

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class CalendarAllDayTest {
    private val utc = TimeZone.getTimeZone("UTC")

    private fun local(zone: TimeZone, y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0) =
        Calendar.getInstance(zone).apply { clear(); set(y, m, d, h, min, 0) }.timeInMillis

    private fun utcMidnight(y: Int, m: Int, d: Int) =
        Calendar.getInstance(utc).apply { clear(); set(y, m, d, 0, 0, 0) }.timeInMillis

    @Test
    fun aSingleLocalDayIsOneUtcDayInEveryZone() {
        for (id in listOf("UTC", "Asia/Shanghai", "America/Los_Angeles", "Pacific/Kiritimati", "Pacific/Pago_Pago")) {
            val zone = TimeZone.getTimeZone(id)
            val (start, end) = CalendarOffloadHandler.allDayStoredRange(local(zone, 2026, Calendar.OCTOBER, 6), null, zone)
            assertEquals(id, utcMidnight(2026, Calendar.OCTOBER, 6), start)
            assertEquals(id, utcMidnight(2026, Calendar.OCTOBER, 7), end)
        }
    }

    @Test
    fun anEndDayIsIncludedByTheExclusiveStoredEnd() {
        val zone = TimeZone.getTimeZone("Asia/Shanghai")
        val (start, end) = CalendarOffloadHandler.allDayStoredRange(
            local(zone, 2026, Calendar.OCTOBER, 6), local(zone, 2026, Calendar.OCTOBER, 8), zone,
        )
        assertEquals(utcMidnight(2026, Calendar.OCTOBER, 6), start)
        assertEquals(utcMidnight(2026, Calendar.OCTOBER, 9), end)
    }

    @Test
    fun aTimeOfDayInTheGivenInstantDoesNotMoveTheDay() {
        val zone = TimeZone.getTimeZone("America/Los_Angeles")
        val (start, _) = CalendarOffloadHandler.allDayStoredRange(local(zone, 2026, Calendar.OCTOBER, 6, 23, 30), null, zone)
        assertEquals(utcMidnight(2026, Calendar.OCTOBER, 6), start)
    }

    @Test
    fun anEndBeforeTheStartStillStoresAtLeastTheStartDay() {
        val zone = utc
        val (start, end) = CalendarOffloadHandler.allDayStoredRange(
            local(zone, 2026, Calendar.OCTOBER, 6), local(zone, 2026, Calendar.OCTOBER, 1), zone,
        )
        assertEquals(utcMidnight(2026, Calendar.OCTOBER, 7), end)
        assertEquals(utcMidnight(2026, Calendar.OCTOBER, 6), start)
    }
}
