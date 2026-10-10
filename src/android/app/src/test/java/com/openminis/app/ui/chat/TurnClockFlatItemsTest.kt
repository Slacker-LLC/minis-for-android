package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One clock per turn: the first line under the user's message, counting until the whole reply is done. */
class TurnClockFlatItemsTest {
    private val sentAt = 5_000_000L

    private fun user(at: Long = sentAt) = ChatMessage(id = "u", role = "user", content = "hi", createdAtMs = at)

    private fun reply(
        id: String = "a",
        streaming: Boolean = false,
        updatedAtMs: Long? = null,
        blocks: List<AssistantBlock> = emptyList(),
        content: String = "ok",
    ) = ChatMessage(
        id = id, role = "assistant", content = content, createdAtMs = sentAt + 400L, isStreaming = streaming,
        updatedAtMs = updatedAtMs, toolBlocks = blocks,
    )

    private fun tool(id: String) = AssistantBlock(
        id = id, kind = TOOL_USE_KIND, content = "", toolStatus = ToolBlockStatus.RUNNING, toolName = "shell_execute",
    )

    private fun clocks(items: List<FlatChatItem>) = items.filterIsInstance<FlatChatItem.TurnClock>()

    @Test
    fun `the clock is the first row after the user's message, even before the reply has any content`() {
        val items = buildFlatChatItems(listOf(user(), reply(streaming = true, content = "")))
        val userAt = items.indexOfFirst { it is FlatChatItem.UserBubble }
        assertTrue("clock right under the user's message", items[userAt + 1] is FlatChatItem.TurnClock)
        val clock = clocks(items).single()
        assertEquals(sentAt, clock.startedAtMs)
        assertTrue(clock.live)
        assertNull(clock.endedAtMs)
    }

    @Test
    fun `a reply that starts with text still has the clock above it`() {
        val items = buildFlatChatItems(
            listOf(user(), reply(streaming = true, blocks = listOf(AssistantBlock(id = "t", kind = "text", content = "I'll look"), tool("c1")))),
        )
        val firstReplyRow = items.indexOfFirst { it is FlatChatItem.TurnClock }
        assertTrue(items.subList(firstReplyRow + 1, items.size).any { it is FlatChatItem.WorkProcessRow })
        assertEquals(1, clocks(items).size)
    }

    @Test
    fun `a finished turn shows the time from send to the end of the reply`() {
        val clock = clocks(buildFlatChatItems(listOf(user(), reply(updatedAtMs = sentAt + 38_000L)))).single()
        assertFalse(clock.live)
        assertEquals(sentAt + 38_000L, clock.endedAtMs)
    }

    @Test
    fun `a resumed reply is the same turn with one clock that is live while either part is`() {
        val first = reply(id = "a1", updatedAtMs = sentAt + 10_000L)
        val resumed = reply(id = "a2", streaming = true)
        val items = buildFlatChatItems(listOf(user(), first, resumed))
        val clock = clocks(items).single()
        assertEquals("only the first reply carries it", "a1", clock.messageId)
        assertTrue(clock.live)
        val done = clocks(buildFlatChatItems(listOf(user(), first, reply(id = "a2", updatedAtMs = sentAt + 25_000L)))).single()
        assertEquals(sentAt + 25_000L, done.endedAtMs)
    }

    @Test
    fun `each user message starts its own turn`() {
        val items = buildFlatChatItems(
            listOf(
                user(), reply(id = "a1", updatedAtMs = sentAt + 5_000L),
                ChatMessage(id = "u2", role = "user", content = "again", createdAtMs = sentAt + 60_000L),
                reply(id = "a2", streaming = true),
            ),
        )
        val all = clocks(items)
        assertEquals(listOf("a1", "a2"), all.map { it.messageId })
        assertEquals(listOf(sentAt, sentAt + 60_000L), all.map { it.startedAtMs })
    }

    @Test
    fun `a partial rebuild from the live reply still measures from the user's send`() {
        val items = buildFlatChatItems(listOf(user(), reply(streaming = true)), fromIndex = 1)
        assertEquals(sentAt, clocks(items).single().startedAtMs)
    }

    @Test
    fun `a missing end time shows no total instead of a wrong one`() {
        val clock = clocks(buildFlatChatItems(listOf(user(), reply()))).single()
        assertNull(clock.endedAtMs)
    }
}
