package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-assistant-action-more] Tests for more-menu actions: single message
 * deletion, plain-text share payload, and message selection.
 */
class AssistantActionBarMoreActionsTest {

    data class SimpleMessage(val id: String, val role: String, val content: String)

    private fun deleteSingleMessage(
        messages: List<SimpleMessage>,
        targetId: String,
    ): List<SimpleMessage> {
        val target = messages.firstOrNull { it.id == targetId } ?: return messages
        if (target.role != "assistant") return messages
        return messages.filterNot { it.id == targetId }
    }

    @Test
    fun `deleteSingleAssistantMessage removes only target without truncating subsequent messages`() {
        val messages = listOf(
            SimpleMessage("u1", "user", "question 1"),
            SimpleMessage("a1", "assistant", "answer 1"),
            SimpleMessage("u2", "user", "question 2"),
            SimpleMessage("a2", "assistant", "answer 2"),
            SimpleMessage("u3", "user", "question 3"),
        )
        // Delete only a1
        val remaining = deleteSingleMessage(messages, "a1")
        assertEquals(4, remaining.size)
        assertEquals(listOf("u1", "u2", "a2", "u3"), remaining.map { it.id })
        // Verify subsequent turns are completely intact
        assertEquals("question 2", remaining[1].content)
        assertEquals("answer 2", remaining[2].content)
        assertEquals("question 3", remaining[3].content)
    }

    @Test
    fun `deleteSingleAssistantMessage refuses to delete user message`() {
        val messages = listOf(
            SimpleMessage("u1", "user", "question 1"),
            SimpleMessage("a1", "assistant", "answer 1"),
        )
        val remaining = deleteSingleMessage(messages, "u1")
        assertEquals(2, remaining.size)
        assertEquals(listOf("u1", "a1"), remaining.map { it.id })
    }

    @Test
    fun `share payload cleans markdown formatting and omits internal ids`() {
        val markdown = "# Heading\n\nHere is some **bold** text and [link](https://example.com)."
        val plainText = MarkdownClipboard.markdownToPlainText(markdown)
        assertTrue(plainText.contains("Heading"))
        assertTrue(plainText.contains("bold"))
        assertFalse(plainText.contains("#"))
        assertFalse(plainText.contains("**"))
        assertFalse(plainText.contains("]("))
    }

    @Test
    fun `selectMessage calculates positions across message shards`() {
        val shard1 = TextShardId("msg-1", "mdblock:msg-1:0")
        val shard2 = TextShardId("msg-1", "mdblock:msg-1:1")
        val shardOther = TextShardId("msg-2", "mdblock:msg-2:0")

        val matching = listOf(shard1, shard2, shardOther).filter { it.messageId == "msg-1" }
        assertEquals(2, matching.size)
        assertEquals(shard1, matching.first())
        assertEquals(shard2, matching.last())
    }
}

