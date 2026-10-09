package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** One clock per turn, started at the user's send: the first line under the user's message carries it. */
class TurnClockFlatItemsTest {
    private val sentAt = 5_000_000L

    private fun user() = ChatMessage(id = "u", role = "user", content = "hi", createdAtMs = sentAt)

    private fun live(blocks: List<AssistantBlock>) = ChatMessage(
        id = "a", role = "assistant", content = "", createdAtMs = sentAt + 400L, isStreaming = true, toolBlocks = blocks,
    )

    private fun tool(id: String) = AssistantBlock(
        id = id, kind = TOOL_USE_KIND, content = "", toolStatus = ToolBlockStatus.RUNNING, toolName = "shell_execute",
    )

    @Test
    fun `before the first step the typing line already carries the clock from the send`() {
        val items = buildFlatChatItems(listOf(user(), live(emptyList())))
        val typing = items.filterIsInstance<FlatChatItem.AssistantTyping>().single()
        assertEquals(sentAt, typing.startedAtMs)
    }

    @Test
    fun `once a work row exists it is the only working line`() {
        val items = buildFlatChatItems(listOf(user(), live(listOf(tool("c1")))))
        assertFalse("the live work row says working itself", items.any { it is FlatChatItem.AssistantTyping })
        val row = items.filterIsInstance<FlatChatItem.WorkProcessRow>().single()
        assertEquals("the row carries the clock from the user's send", sentAt, row.process.messageCreatedAtMs)
        assertTrue(row.process.clockLive)
    }

    @Test
    fun `a finished turn keeps one time on its first row`() {
        val done = live(listOf(tool("c1").copy(toolStatus = ToolBlockStatus.SUCCESS))).copy(isStreaming = false, updatedAtMs = sentAt + 9_000L)
        val row = buildFlatChatItems(listOf(user(), done)).filterIsInstance<FlatChatItem.WorkProcessRow>().single()
        assertFalse(row.process.clockLive)
        assertEquals(9_000L, row.process.durationMs)
    }

    @Test
    fun `a partial rebuild from the live reply still measures from the user's send`() {
        val messages = listOf(user(), live(listOf(tool("c1"))))
        val tail = buildFlatChatItems(messages, fromIndex = 1).filterIsInstance<FlatChatItem.WorkProcessRow>().single()
        assertEquals(sentAt, tail.process.messageCreatedAtMs)
    }
}
