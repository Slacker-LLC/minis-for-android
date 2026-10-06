package com.openminis.app.runtime.guest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

class PhotosStrictDateTest {
    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun validDatesAndDateTimesParse() {
        assertEquals(1_743_379_200_000L, PhotosOffloadHandler.parseStrictInstant("2025-03-31", utc))
        assertEquals(1_743_379_200_000L + 14 * 3600_000L + 5 * 60_000L,
            PhotosOffloadHandler.parseStrictInstant("2025-03-31T14:05", utc))
        assertNotNull(PhotosOffloadHandler.parseStrictInstant("2025-03-31T14:05:09Z", utc))
        assertNotNull(PhotosOffloadHandler.parseStrictInstant("2025-03-31T14:05:09+08:00", utc))
    }

    @Test
    fun anythingElseIsNullSoItCannotBecomeAnUnboundedFilter() {
        for (bad in listOf("not-a-date", "2025-02-30", "2025-13-01", "2025-03-31xyz", "2025-03-31T25:00", "", "yesterday")) {
            assertNull(bad, PhotosOffloadHandler.parseStrictInstant(bad, utc))
        }
    }
}
