package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlatItemsTimeDividerTest {
    private fun user(id: String, at: Long) = ChatMessage(id = id, role = "user", content = "hi $id", createdAtMs = at)
    private fun reply(id: String, at: Long) = ChatMessage(id = id, role = "assistant", content = "ok $id", createdAtMs = at)

    private fun dividers(messages: List<ChatMessage>) =
        buildFlatChatItems(messages).filterIsInstance<FlatChatItem.TimeDivider>()

    @Test fun theConversationOpensWithATimeLine() {
        val items = buildFlatChatItems(listOf(user("a", 1_000_000L), reply("b", 1_001_000L)))
        assertTrue(items.first() is FlatChatItem.TimeDivider)
        assertEquals(1, items.count { it is FlatChatItem.TimeDivider })
    }

    @Test fun messagesCloseTogetherShareOneTimeLine() {
        val hour = TIME_DIVIDER_GAP_MS
        assertEquals(1, dividers(listOf(user("a", 5 * hour), reply("b", 5 * hour + hour - 1))).size)
    }

    @Test fun aPauseOfAnHourOrMoreGetsANewTimeLine() {
        val hour = TIME_DIVIDER_GAP_MS
        assertEquals(2, dividers(listOf(user("a", 5 * hour), reply("b", 5 * hour + hour))).size)
    }

    @Test fun messagesWithoutATimestampGetNoTimeLine() {
        assertEquals(0, dividers(listOf(user("a", 0L), reply("b", 0L))).size)
    }
}
