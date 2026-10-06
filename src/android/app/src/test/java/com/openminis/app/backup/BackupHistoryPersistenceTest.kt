package com.openminis.app.backup

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupHistoryPersistenceTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context: Context = ContextWrapper(null)

    private fun record(id: String, at: Long = System.currentTimeMillis()) = BackupHistory.Record(
        id = id, backupId = "b-$id", startedAt = at, status = BackupHistory.Status.SUCCEEDED,
    )

    @Test
    fun `a saved record survives a new instance reading the same file`() {
        val file = File(tmp.root, "records.json")
        assertTrue(BackupHistory.forFile(context, file).upsert(record("r1")))
        assertEquals(listOf("r1"), BackupHistory.forFile(context, file).records().map { it.id })
    }

    @Test
    fun `a write that fails is reported and the shown list does not change`() {
        // The parent directory does not exist, so nothing can be written.
        val history = BackupHistory.forFile(context, File(tmp.root, "no/such/dir/records.json"))
        assertFalse(history.upsert(record("r1")))
        assertTrue("memory must not claim what the disk does not hold", history.records().isEmpty())
    }

    @Test
    fun `an unreadable file is kept aside instead of being overwritten as if it were empty`() {
        val file = File(tmp.root, "records.json").apply { writeText("{ truncated") }
        val history = BackupHistory.forFile(context, file)
        assertTrue(history.records().isEmpty())
        assertEquals("{ truncated", File(tmp.root, "records.json.corrupt").readText())
        assertTrue(history.upsert(record("r1")))
        assertEquals("{ truncated", File(tmp.root, "records.json.corrupt").readText())
        assertEquals(listOf("r1"), BackupHistory.forFile(context, file).records().map { it.id })
    }

    @Test
    fun `remove and clear report whether they were saved`() {
        val file = File(tmp.root, "records.json")
        val history = BackupHistory.forFile(context, file)
        history.upsert(record("a")); history.upsert(record("b"))
        assertTrue(history.remove("a"))
        assertEquals(listOf("b"), history.records().map { it.id })
        assertTrue(history.clear())
        assertTrue(history.records().isEmpty())
    }
}
