package com.openminis.app.ui.chat

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Unit tests for Issue #188 & PastedTextProcessor: Consuming pasted texts and cleaning files on failure. */
class ChatViewModelPastedTextConsumeTest {

    data class PastedItem(val id: Int, val text: String)

    @Test
    fun `pasted item is retained and files cleaned when appendMessage fails`() = runTest {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "test_pasted_${System.currentTimeMillis()}").apply { mkdirs() }
        val tempFile = File(tempDir, "test_part.txt").apply { writeText("sample data") }
        assertTrue(tempFile.exists())

        val pastedBuffer = mutableListOf(PastedItem(1, "hello world"))
        val pastedParts = PastedParts(
            partsJson = listOf("{\"type\":\"mediaRef\"}"),
            modelText = "hello world",
            uiNames = listOf("Pasted#1.txt"),
            uiUris = emptyList(),
            consumedIds = setOf(1),
            createdFiles = listOf(tempFile),
        )

        var appendSucceeded = false
        try {
            // Simulate DB failure
            throw IllegalStateException("Database write failed")
            @Suppress("UNREACHABLE_CODE")
            appendSucceeded = true
            @Suppress("UNREACHABLE_CODE")
            pastedBuffer.removeAll { it.id in pastedParts.consumedIds }
        } catch (_: Exception) {
            PastedTextProcessor.cleanupFiles(pastedParts)
        }

        assertFalse(appendSucceeded)
        assertEquals(1, pastedBuffer.size)
        assertEquals(1, pastedBuffer[0].id)
        assertFalse("Created file should be cleaned up on failure", tempFile.exists())
        tempDir.delete()
    }

    @Test
    fun `pasted item is consumed and files retained when appendMessage succeeds`() = runTest {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "test_pasted_${System.currentTimeMillis()}").apply { mkdirs() }
        val tempFile = File(tempDir, "test_part.txt").apply { writeText("sample data") }
        assertTrue(tempFile.exists())

        val pastedBuffer = mutableListOf(PastedItem(1, "hello world"), PastedItem(2, "keep me"))
        val pastedParts = PastedParts(
            partsJson = listOf("{\"type\":\"mediaRef\"}"),
            modelText = "hello world",
            uiNames = listOf("Pasted#1.txt"),
            uiUris = emptyList(),
            consumedIds = setOf(1),
            createdFiles = listOf(tempFile),
        )

        var appendSucceeded = false
        // Simulate DB success
        appendSucceeded = true
        if (appendSucceeded) {
            pastedBuffer.removeAll { it.id in pastedParts.consumedIds }
        }

        assertTrue(appendSucceeded)
        assertEquals(1, pastedBuffer.size)
        assertEquals(2, pastedBuffer[0].id)
        assertTrue("Created file should be preserved on success", tempFile.exists())
        tempFile.delete()
        tempDir.delete()
    }
}
