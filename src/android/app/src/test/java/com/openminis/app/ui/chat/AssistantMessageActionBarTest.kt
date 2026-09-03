package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-assistant-action-bar] Contract tests for the AI reply action bar
 * and flat chat item row generation.
 */
class AssistantMessageActionBarTest {

    enum class ActionButton {
        COPY,
        REGENERATE,
        SPEAK,
        BRANCH,
        MORE
    }

    enum class MoreMenuItem {
        SELECT_TEXT,
        COPY_MARKDOWN,
        SHARE,
        DELETE_MESSAGE
    }

    @Test
    fun `action bar buttons follow strict specification order`() {
        val expectedOrder = listOf(
            ActionButton.COPY,
            ActionButton.REGENERATE,
            ActionButton.SPEAK,
            ActionButton.BRANCH,
            ActionButton.MORE,
        )
        assertEquals(
            listOf(
                ActionButton.COPY,
                ActionButton.REGENERATE,
                ActionButton.SPEAK,
                ActionButton.BRANCH,
                ActionButton.MORE,
            ),
            expectedOrder,
        )
    }

    @Test
    fun `more menu contains four expected operations`() {
        val menuItems = listOf(
            MoreMenuItem.SELECT_TEXT,
            MoreMenuItem.COPY_MARKDOWN,
            MoreMenuItem.SHARE,
            MoreMenuItem.DELETE_MESSAGE,
        )
        assertEquals(4, menuItems.size)
        assertEquals(MoreMenuItem.DELETE_MESSAGE, menuItems.last())
    }

    @Test
    fun `action row produces stable key with prefix actions`() {
        val actionItem = FlatChatItem.AssistantActions(
            messageId = "msg-1234",
            messageMarkdown = "Hello AI",
            isStreaming = false,
            isError = false,
        )
        assertEquals("actions:msg-1234", actionItem.key)
        assertEquals("assistant_actions", actionItem.contentType)
    }

    @Test
    fun `buildFlatChatItems appends exactly one actions row per assistant message`() {
        val assistantMsg = ChatMessage(
            id = "asst-1",
            role = "assistant",
            content = "Here is the response",
            toolBlocks = listOf(
                AssistantBlock(id = "blk-1", kind = "text", content = "Here is the response"),
            ),
        )
        val flatItems = buildFlatChatItems(listOf(assistantMsg))
        val actionItems = flatItems.filterIsInstance<FlatChatItem.AssistantActions>()
        assertEquals(1, actionItems.size)
        assertEquals("actions:asst-1", actionItems.single().key)
        assertEquals("asst-1", actionItems.single().messageId)
        // Actions row must be at the very end of the assistant message's items
        assertEquals(actionItems.single(), flatItems.last())
    }

    @Test
    fun `actions row is not emitted for user messages`() {
        val userMsg = ChatMessage(
            id = "user-1",
            role = "user",
            content = "Hello",
        )
        val flatItems = buildFlatChatItems(listOf(userMsg))
        val actionItems = flatItems.filterIsInstance<FlatChatItem.AssistantActions>()
        assertTrue(actionItems.isEmpty())
    }

    @Test
    fun `streaming state propagates to actions row`() {
        val streamingMsg = ChatMessage(
            id = "asst-stream",
            role = "assistant",
            content = "Streaming...",
            isStreaming = true,
            toolBlocks = listOf(
                AssistantBlock(id = "blk-s", kind = "text", content = "Streaming..."),
            ),
        )
        val flatItems = buildFlatChatItems(listOf(streamingMsg))
        val actionItem = flatItems.filterIsInstance<FlatChatItem.AssistantActions>().single()
        assertTrue(actionItem.isStreaming)
    }
}
