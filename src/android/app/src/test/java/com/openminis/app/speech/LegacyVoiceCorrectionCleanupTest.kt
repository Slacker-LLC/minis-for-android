package com.openminis.app.speech

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LegacyVoiceCorrectionCleanupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun removesDatabaseAndSidecars() {
        val dir = tmp.newFolder("files")
        listOf("voice-correction.db", "voice-correction.db-wal", "voice-correction.db-shm")
            .forEach { File(dir, it).writeText("x") }

        assertEquals(3, LegacyVoiceCorrectionCleanup.deleteDatabaseFiles(dir))
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test
    fun leavesOtherFilesAndSimilarNamesAlone() {
        val dir = tmp.newFolder("files")
        val keep = listOf("voice-correction.db.bak", "voice-correction", "chat.db", "notes.txt")
        keep.forEach { File(dir, it).writeText("x") }

        assertEquals(0, LegacyVoiceCorrectionCleanup.deleteDatabaseFiles(dir))
        assertTrue(keep.all { File(dir, it).exists() })
    }

    @Test
    fun doesNotFollowDirectoryNamedLikeTheDatabase() {
        val dir = tmp.newFolder("files")
        val trap = File(dir, "voice-correction.db").apply { mkdir() }
        File(trap, "inner.txt").writeText("x")

        assertEquals(0, LegacyVoiceCorrectionCleanup.deleteDatabaseFiles(dir))
        assertTrue(File(trap, "inner.txt").exists())
    }

    @Test
    fun missingDirectoryIsNoOp() {
        assertEquals(
            0,
            LegacyVoiceCorrectionCleanup.deleteDatabaseFiles(File(tmp.root, "absent")),
        )
        assertFalse(File(tmp.root, "absent").exists())
    }
}
