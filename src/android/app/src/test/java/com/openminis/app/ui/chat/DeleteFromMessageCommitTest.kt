package com.openminis.app.ui.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for the DB-first delete ordering used by ChatViewModel. */
class DeleteFromMessageCommitTest {
    @Test
    fun `delete state is applied only after database delete succeeds`() = runTest {
        val events = mutableListOf<String>()

        runAfterDatabaseDelete(
            delete = { events += "database" },
            afterCommit = { events += "state" },
        )

        assertEquals(listOf("database", "state"), events)
    }

    @Test
    fun `database failure leaves delete state unapplied`() = runTest {
        var stateApplied = false

        try {
            runAfterDatabaseDelete(
                delete = { error("database unavailable") },
                afterCommit = { stateApplied = true },
            )
        } catch (_: IllegalStateException) {
            // Expected: the commit gate must not invoke afterCommit.
        }

        assertFalse(stateApplied)
    }

    @Test
    fun `cancellation leaves delete state unapplied`() = runTest {
        var stateApplied = false
        var cancelled = false

        try {
            runAfterDatabaseDelete(
                delete = { throw CancellationException("delete cancelled") },
                afterCommit = { stateApplied = true },
            )
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertFalse(stateApplied)
    }
}
