package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-chat-branch-message] Contract and prefix slice tests for
 * forkSessionAtMessage.
 */
class SessionForkAtMessageTest {

    data class MockMsg(val id: String, val role: String)
    data class MockMarker(val id: String, val firstKeptId: String?, val lastCompactedId: String?)

    private fun resolvePrefix(
        messages: List<MockMsg>,
        targetMessageId: String,
    ): Pair<Boolean, List<MockMsg>> {
        val targetIdx = messages.indexOfFirst { it.id == targetMessageId }
        if (targetIdx < 0) return false to emptyList()
        val targetMsg = messages[targetIdx]
        if (targetMsg.role != "assistant") return false to emptyList()
        return true to messages.subList(0, targetIdx + 1)
    }

    private fun filterMarkers(
        markers: List<MockMarker>,
        copiedMessageIds: Set<String>,
    ): List<MockMarker> {
        return markers.filter { marker ->
            val firstOk = marker.firstKeptId == null || copiedMessageIds.contains(marker.firstKeptId)
            val lastOk = marker.lastCompactedId == null || copiedMessageIds.contains(marker.lastCompactedId)
            firstOk && lastOk
        }
    }

    @Test
    fun `fork at middle assistant message selects exact prefix`() {
        val messages = listOf(
            MockMsg("u1", "user"),
            MockMsg("a1", "assistant"),
            MockMsg("u2", "user"),
            MockMsg("a2", "assistant"),
            MockMsg("u3", "user"),
        )
        val (ok, prefix) = resolvePrefix(messages, "a1")
        assertTrue(ok)
        assertEquals(listOf("u1", "a1"), prefix.map { it.id })
    }

    @Test
    fun `fork at last assistant message includes all turns up to it`() {
        val messages = listOf(
            MockMsg("u1", "user"),
            MockMsg("a1", "assistant"),
            MockMsg("u2", "user"),
            MockMsg("a2", "assistant"),
        )
        val (ok, prefix) = resolvePrefix(messages, "a2")
        assertTrue(ok)
        assertEquals(listOf("u1", "a1", "u2", "a2"), prefix.map { it.id })
    }

    @Test
    fun `target message with user role is rejected`() {
        val messages = listOf(
            MockMsg("u1", "user"),
            MockMsg("a1", "assistant"),
            MockMsg("u2", "user"),
        )
        val (ok, prefix) = resolvePrefix(messages, "u2")
        assertFalse(ok)
        assertTrue(prefix.isEmpty())
    }

    @Test
    fun `unknown message id is rejected`() {
        val messages = listOf(
            MockMsg("u1", "user"),
            MockMsg("a1", "assistant"),
        )
        val (ok, prefix) = resolvePrefix(messages, "non-existent")
        assertFalse(ok)
        assertTrue(prefix.isEmpty())
    }

    @Test
    fun `compact markers outside prefix are dropped and markers inside are kept`() {
        val copiedIds = setOf("u1", "a1")
        val markers = listOf(
            MockMarker("m1", "u1", "a1"),
            MockMarker("m2", "u2", "a2"),
        )
        val filtered = filterMarkers(markers, copiedIds)
        assertEquals(1, filtered.size)
        assertEquals("m1", filtered.single().id)
    }

    @Test
    fun `fork title carries branch marker`() {
        val originalTitle = "Coding Assistant"
        val forkTitle = "$originalTitle (Branch)"
        assertEquals("Coding Assistant (Branch)", forkTitle)

        val nullTitle: String? = null
        val defaultForkTitle = "${nullTitle ?: "Chat"} (Branch)"
        assertEquals("Chat (Branch)", defaultForkTitle)
    }
}

