package com.openminis.app.runtime.guest

import com.openminis.app.runtime.guest.CalendarOffloadHandler.Companion.CalendarRef
import com.openminis.app.runtime.guest.CalendarOffloadHandler.Companion.CalendarTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class CalendarWriteTargetTest {
    private val personal = CalendarRef(1, "Personal")
    private val work = CalendarRef(2, "Work")
    private val workout = CalendarRef(3, "Workout")
    private val writable = listOf(personal, work, workout)

    private fun resolve(id: Long? = null, name: String? = null) =
        CalendarOffloadHandler.resolveCalendarTarget(id, name, writable)

    @Test
    fun noFlagMeansTheCallerMayPickADefault() {
        assertEquals(CalendarTarget.NoneGiven, resolve())
    }

    @Test
    fun anExactNameWinsOverALongerNameThatContainsIt() {
        assertEquals(CalendarTarget.Found(2), resolve(name = "work"))
        assertEquals(CalendarTarget.Found(3), resolve(name = "Workout"))
    }

    @Test
    fun aSinglePartialMatchIsAccepted() {
        assertEquals(CalendarTarget.Found(1), resolve(name = "pers"))
    }

    @Test
    fun aMisspelledNameIsRejectedWithTheCandidatesNotSentToAnotherAccount() {
        val rejected = resolve(name = "Work-Typo") as CalendarTarget.Rejected
        assertEquals(writable, rejected.candidates)
        assertTrue(rejected.reason.contains("Work-Typo"))
    }

    @Test
    fun aPartialNameThatMatchesSeveralIsAmbiguousNotTheFirstOne() {
        val rejected = resolve(name = "wor") as CalendarTarget.Rejected
        assertEquals(listOf(work, workout), rejected.candidates)
    }

    @Test
    fun twoCalendarsWithTheSameNameNeedAnId() {
        val dup = listOf(CalendarRef(5, "Family"), CalendarRef(6, "family"))
        assertTrue(CalendarOffloadHandler.resolveCalendarTarget(null, "Family", dup) is CalendarTarget.Rejected)
    }

    @Test
    fun anIdMustBeAWritableCalendar() {
        assertEquals(CalendarTarget.Found(2), resolve(id = 2))
        assertTrue(resolve(id = 99) is CalendarTarget.Rejected)
    }

    @Test
    fun anEmptyNameMatchesNothing() {
        assertTrue(resolve(name = "  ") is CalendarTarget.Rejected)
    }

    // ---- dates ---------------------------------------------------------

    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun validDatesParse() {
        assertEquals(1_743_379_200_000L, CalendarOffloadHandler.parseStrictInstant("2025-03-31", utc))
        assertNotNull(CalendarOffloadHandler.parseStrictInstant("2025-03-31T09:30", utc))
        assertNotNull(CalendarOffloadHandler.parseStrictInstant("2025-03-31T09:30:00+08:00", utc))
    }

    @Test
    fun aWrongMonthIsNotRolledIntoNextYearAndTrailingTextIsNotIgnored() {
        assertNull(CalendarOffloadHandler.parseStrictInstant("2026-13-01", utc))
        assertNull(CalendarOffloadHandler.parseStrictInstant("2025-02-30", utc))
        assertNull(CalendarOffloadHandler.parseStrictInstant("2025-03-31T09:30 please", utc))
        assertNull(CalendarOffloadHandler.parseStrictInstant("not-a-date", utc))
    }
}
