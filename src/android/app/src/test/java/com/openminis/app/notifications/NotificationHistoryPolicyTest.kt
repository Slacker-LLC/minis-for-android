package com.openminis.app.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-eta-notification-history] Ported from Eta's notification history store and tools
 * (Mangi-11/Eta @ c15de97). The bounds are what keep a device-wide, privacy-sensitive feed
 * finite; the escape rule keeps a query literal.
 */
class NotificationHistoryPolicyTest {

    @Test
    fun `recent limits clamp to the shade tool bounds`() {
        assertEquals(NotificationHistoryPolicy.RECENT_DEFAULT_LIMIT, NotificationHistoryPolicy.clampRecentLimit(null))
        assertEquals(1, NotificationHistoryPolicy.clampRecentLimit(0))
        assertEquals(NotificationHistoryPolicy.RECENT_MAX_LIMIT, NotificationHistoryPolicy.clampRecentLimit(999))
        assertEquals(7, NotificationHistoryPolicy.clampRecentLimit(7))
    }

    @Test
    fun `search limits clamp to the history tool bounds`() {
        assertEquals(NotificationHistoryPolicy.SEARCH_DEFAULT_LIMIT, NotificationHistoryPolicy.clampSearchLimit(null))
        assertEquals(1, NotificationHistoryPolicy.clampSearchLimit(-5))
        assertEquals(NotificationHistoryPolicy.SEARCH_MAX_LIMIT, NotificationHistoryPolicy.clampSearchLimit(1_000))
    }

    @Test
    fun `the lookback window never leaves the retention period`() {
        assertEquals(NotificationHistoryPolicy.SEARCH_DEFAULT_MAX_AGE_HOURS, NotificationHistoryPolicy.clampMaxAgeHours(null))
        assertEquals(1, NotificationHistoryPolicy.clampMaxAgeHours(0))
        assertEquals(
            NotificationHistoryPolicy.SEARCH_MAX_MAX_AGE_HOURS,
            NotificationHistoryPolicy.clampMaxAgeHours(10_000),
        )
        assertEquals(
            "the maximum lookback is exactly the retention window",
            NotificationHistoryPolicy.RETENTION_DAYS * 24,
            NotificationHistoryPolicy.SEARCH_MAX_MAX_AGE_HOURS,
        )
    }

    @Test
    fun `an empty notification is not stored`() {
        assertFalse(NotificationHistoryPolicy.isRecordable(null, null, null))
        assertFalse(NotificationHistoryPolicy.isRecordable("", "  ", null))
        assertTrue(NotificationHistoryPolicy.isRecordable("title", null, null))
        assertTrue(NotificationHistoryPolicy.isRecordable(null, "body", null))
        assertTrue(NotificationHistoryPolicy.isRecordable(null, null, "sub"))
    }

    @Test
    fun `fields are bounded and null stays null`() {
        assertNull(NotificationHistoryPolicy.bounded(null))
        val long = "x".repeat(NotificationHistoryPolicy.MAX_FIELD_CHARS + 100)
        assertEquals(
            NotificationHistoryPolicy.MAX_FIELD_CHARS,
            NotificationHistoryPolicy.bounded(long)!!.length,
        )
        assertEquals("short", NotificationHistoryPolicy.bounded("short"))
    }

    @Test
    fun `like wildcards in a query are escaped`() {
        assertEquals("50\\%", NotificationHistoryPolicy.escapeLike("50%"))
        assertEquals("a\\_b", NotificationHistoryPolicy.escapeLike("a_b"))
        assertEquals("c:\\\\path", NotificationHistoryPolicy.escapeLike("c:\\path"))
        assertEquals("plain", NotificationHistoryPolicy.escapeLike("plain"))
    }
}
