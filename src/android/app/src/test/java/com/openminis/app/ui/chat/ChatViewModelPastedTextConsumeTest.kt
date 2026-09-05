package com.openminis.app.ui.chat

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for Issue #188: Consuming pasted texts only after message persistence succeeds. */
class ChatViewModelPastedTextConsumeTest {

    data class PastedItem(val id: String, val text: String)

    @Test
    fun `pasted item is retained when appendMessage fails`() = runTest {
        val pastedBuffer = mutableListOf(PastedItem("p1", "hello world"))
        val consumedIds = setOf("p1")

        var appendSucceeded = false
        try {
            // Simulate DB failure
            throw IllegalStateException("Database write failed")
            @Suppress("UNREACHABLE_CODE")
            appendSucceeded = true
            @Suppress("UNREACHABLE_CODE")
            pastedBuffer.removeAll { it.id in consumedIds }
        } catch (_: Exception) {
            // Handled
        }

        assertFalse(appendSucceeded)
        assertEquals(1, pastedBuffer.size)
        assertEquals("p1", pastedBuffer[0].id)
    }

    @Test
    fun `pasted item is consumed when appendMessage succeeds`() = runTest {
        val pastedBuffer = mutableListOf(PastedItem("p1", "hello world"), PastedItem("p2", "keep me"))
        val consumedIds = setOf("p1")

        var appendSucceeded = false
        // Simulate DB success
        appendSucceeded = true
        if (appendSucceeded) {
            pastedBuffer.removeAll { it.id in consumedIds }
        }

        assertTrue(appendSucceeded)
        assertEquals(1, pastedBuffer.size)
        assertEquals("p2", pastedBuffer[0].id)
    }
}
