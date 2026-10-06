package com.openminis.app.runtime.guest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class AlarmArgParsingTest {

    private fun iso(epochMs: Long) =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).format(java.util.Date(epochMs))

    @Test
    fun durationsParseSecondsAndShorthand() {
        assertEquals(30, AlarmOffloadHandler.parseDuration("30"))
        assertEquals(30, AlarmOffloadHandler.parseDuration("30s"))
        assertEquals(300, AlarmOffloadHandler.parseDuration("5m"))
        assertEquals(3600, AlarmOffloadHandler.parseDuration("1h"))
        assertEquals(86400, AlarmOffloadHandler.parseDuration("24h"))
    }

    @Test
    fun garbageDurationIsNull() {
        for (bad in listOf("", "x", "5x", "m", "1.5h")) assertNull(bad, AlarmOffloadHandler.parseDuration(bad))
    }

    @Test
    fun aDurationThatOverflowsIntDoesNotWrapIntoTheValidRange() {
        // 1193047h * 3600 wrapped to 1904 seconds in 32-bit arithmetic.
        val parsed = AlarmOffloadHandler.parseDuration("1193047h")
        assertTrue("must stay out of 1..86400, was $parsed", parsed == null || parsed !in 1..86400)
        val huge = AlarmOffloadHandler.parseDuration("99999999999999d")
        assertTrue("must stay out of 1..86400, was $huge", huge == null || huge !in 1..86400)
    }

    @Test
    fun twoDaysParsesToMoreThanTheSystemClockAccepts() {
        assertEquals(172800, AlarmOffloadHandler.parseDuration("2d"))
    }

    @Test
    fun hhmmIsAccepted() {
        assertEquals(14 to 5, AlarmOffloadHandler.parseTimeArg("14:05"))
        assertNull(AlarmOffloadHandler.parseTimeArg("25:00"))
        assertNull(AlarmOffloadHandler.parseTimeArg("nonsense"))
    }

    @Test
    fun anIsoTimeInTheNextDayIsAccepted() {
        val now = System.currentTimeMillis()
        val target = now + 3 * 3_600_000L
        val expected = java.util.Calendar.getInstance().apply { timeInMillis = target }
        val parsed = AlarmOffloadHandler.parseTimeArg(iso(target), now)!!
        assertEquals(expected.get(java.util.Calendar.HOUR_OF_DAY) to expected.get(java.util.Calendar.MINUTE), parsed)
    }

    @Test
    fun anIsoDateBeyondTheNextOccurrenceIsRefusedNotTurnedIntoHhmm() {
        val now = System.currentTimeMillis()
        for (target in listOf(now + 15 * 86_400_000L, now - 86_400_000L)) {
            try {
                AlarmOffloadHandler.parseTimeArg(iso(target), now)
                fail("expected refusal for ${iso(target)}")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("next 24h"))
            }
        }
    }
}
