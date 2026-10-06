package com.openminis.app.data.repository

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.notifications.NotificationHistoryPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Rows that age out while nothing is being recorded must still leave the store. */
@RunWith(AndroidJUnit4::class)
class NotificationHistoryRetentionInstrumentedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "retention-test-${System.nanoTime()}.db"
    private lateinit var repo: NotificationHistoryRepository

    @Before fun open() { repo = NotificationHistoryRepository(context, name) }

    @After fun cleanUp() { context.deleteDatabase(name) }

    @Test
    fun purgeRemovesOnlyRowsPastTheRetentionWindow() {
        val day = 24L * 60 * 60 * 1000
        val retentionDays = NotificationHistoryPolicy.RETENTION_MS / day
        val now = System.currentTimeMillis()
        repo.record("k1", "pkg", "title", "text", null, now)
        repo.record("k2", "pkg", "title2", "text2", null, now - 2 * day)
        assertEquals(2, repo.count())

        assertEquals("nothing is old enough yet", 0, repo.purgeExpired(now + (retentionDays - 4) * day))
        assertEquals(2, repo.count())

        // Time passes with no new notification: the row posted two days earlier crosses the line first.
        assertEquals(1, repo.purgeExpired(now + (retentionDays - 1) * day))
        assertEquals(1, repo.count())

        assertEquals(1, repo.purgeExpired(now + (retentionDays + 1) * day))
        assertEquals(0, repo.count())
    }
}
