package com.openminis.app.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SharedFileCleanupTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun shareOf(vararg files: String) = PendingShare(
        files.map { PendingShare.Item(PendingShare.Item.Kind.ATTACHMENT, it) } +
            PendingShare.Item(PendingShare.Item.Kind.INLINE_TEXT, "hello"),
        timestampMs = 0L,
    )

    @Test
    fun onlyTheConsumedSharesFilesAreDeletedNotAFileAnotherShareIsStaging() {
        val dir = tmp.newFolder("share_extension")
        val a = File(dir, "a.png").apply { writeText("A") }
        val b = File(dir, "b.pdf").apply { writeText("B") } // another share, mid-copy or awaiting confirmation

        SharedShareStore.deleteFilesIn(dir, shareOf("a.png").attachmentFileNames())

        assertFalse(a.exists())
        assertTrue(b.exists())
    }

    @Test
    fun inlineTextIsNotAFileName() {
        assertEquals(setOf("a.png"), shareOf("a.png").attachmentFileNames())
    }

    @Test
    fun namesThatReachOutsideTheDirectoryAreIgnored() {
        val dir = tmp.newFolder("share_extension")
        val outside = File(tmp.root, "outside.txt").apply { writeText("keep") }
        val nested = File(dir, "sub").apply { mkdirs() }
        File(nested, "inner.txt").writeText("keep")

        SharedShareStore.deleteFilesIn(dir, listOf("../outside.txt", "sub/inner.txt", "..", ".", "", "sub"))

        assertTrue(outside.exists())
        assertTrue(File(nested, "inner.txt").exists())
        assertTrue(nested.exists())
    }

    @Test
    fun aMissingFileIsNotAnError() {
        SharedShareStore.deleteFilesIn(tmp.newFolder("share_extension"), listOf("gone.png"))
    }
}
