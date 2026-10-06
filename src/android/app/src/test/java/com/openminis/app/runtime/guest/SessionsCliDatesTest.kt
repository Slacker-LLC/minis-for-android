package com.openminis.app.runtime.guest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SessionsCliDatesTest {
    private val shanghai = TimeZone.getTimeZone("Asia/Shanghai")
    private val la = TimeZone.getTimeZone("America/Los_Angeles")

    @Test
    fun aRealDateParsesAsLocalMidnight() {
        val ms = SessionsOffloadHandler.parseStrictDate("2025-03-31", shanghai)!!
        val cal = Calendar.getInstance(shanghai).apply { timeInMillis = ms }
        assertEquals(2025, cal.get(Calendar.YEAR))
        assertEquals(Calendar.MARCH, cal.get(Calendar.MONTH))
        assertEquals(31, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun theSameStringIsADifferentInstantInAnotherZone() {
        val a = SessionsOffloadHandler.parseStrictDate("2025-03-31", shanghai)!!
        val b = SessionsOffloadHandler.parseStrictDate("2025-03-31", la)!!
        assertEquals(15 * 3600_000L, b - a)
    }

    @Test
    fun notADateIsNullNotAnUnboundedFilter() {
        for (bad in listOf("nonsense", "2025-02-30", "2025-13-01", "2025-3-1x", "2025-03-31xyz", "2025-03-31 10:00", "", "31/03/2025")) {
            assertNull(bad, SessionsOffloadHandler.parseStrictDate(bad, shanghai))
        }
        assertNotNull(SessionsOffloadHandler.parseStrictDate(" 2025-03-31 ", shanghai))
    }

    @Test
    fun formattingFollowsTheZoneItIsGivenEachTime() {
        val instant = SessionsOffloadHandler.parseStrictDate("2025-03-31", shanghai)!!
        assertEquals("2025-03-31 00:00", SessionsOffloadHandler.formatTime(instant, shanghai))
        assertEquals("2025-03-30 09:00", SessionsOffloadHandler.formatTime(instant, la))
    }

    @Test
    fun concurrentFormattingNeverMixesValues() {
        val pool = Executors.newFixedThreadPool(8)
        val expected = (0 until 8).associateWith { i ->
            SessionsOffloadHandler.formatTime(1_700_000_000_000L + i * 86_400_000L * 40, shanghai)
        }
        val failures = java.util.concurrent.atomic.AtomicInteger()
        repeat(2_000) { n ->
            val i = n % 8
            pool.execute {
                val got = SessionsOffloadHandler.formatTime(1_700_000_000_000L + i * 86_400_000L * 40, shanghai)
                if (got != expected[i]) failures.incrementAndGet()
            }
        }
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.SECONDS)
        assertEquals(0, failures.get())
    }
}
