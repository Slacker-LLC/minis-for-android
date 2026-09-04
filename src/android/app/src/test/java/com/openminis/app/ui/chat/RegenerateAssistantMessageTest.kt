package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-android-regenerate-assistant-message] Tests for mapping an assistant
 * message to its preceding user turn for retry / regeneration.
 */
class RegenerateAssistantMessageTest {

    data class SimpleMsg(val id: String, val role: String)

    private fun resolvePrecedingUserMessageId(
        messages: List<SimpleMsg>,
        targetAssistantMessageId: String,
    ): String? {
        val asstIndex = messages.indexOfFirst { it.id == targetAssistantMessageId }
        if (asstIndex < 0) return null
        val asstMsg = messages[asstIndex]
        if (asstMsg.role != "assistant") return null

        val userIndex = messages.subList(0, asstIndex).indexOfLast { it.role == "user" }
        if (userIndex < 0) return null
        return messages[userIndex].id
    }

    @Test
    fun `last assistant message resolves to its preceding user turn`() {
        val messages = listOf(
            SimpleMsg("u1", "user"),
            SimpleMsg("a1", "assistant"),
            SimpleMsg("u2", "user"),
            SimpleMsg("a2", "assistant"),
        )
        val targetUser = resolvePrecedingUserMessageId(messages, "a2")
        assertEquals("u2", targetUser)
    }

    @Test
    fun `intermediate assistant message resolves to its own preceding user turn`() {
        val messages = listOf(
            SimpleMsg("u1", "user"),
            SimpleMsg("a1", "assistant"),
            SimpleMsg("u2", "user"),
            SimpleMsg("a2", "assistant"),
            SimpleMsg("u3", "user"),
            SimpleMsg("a3", "assistant"),
        )
        val targetUser = resolvePrecedingUserMessageId(messages, "a1")
        assertEquals("u1", targetUser)
    }

    @Test
    fun `multiple consecutive assistant turns resolve to the single preceding user turn`() {
        val messages = listOf(
            SimpleMsg("u1", "user"),
            SimpleMsg("a1-1", "assistant"),
            SimpleMsg("a1-2", "assistant"),
        )
        assertEquals("u1", resolvePrecedingUserMessageId(messages, "a1-1"))
        assertEquals("u1", resolvePrecedingUserMessageId(messages, "a1-2"))
    }

    @Test
    fun `user message cannot be targeted as assistant message`() {
        val messages = listOf(
            SimpleMsg("u1", "user"),
            SimpleMsg("a1", "assistant"),
        )
        assertNull(resolvePrecedingUserMessageId(messages, "u1"))
    }

    @Test
    fun `unknown message id resolves to null`() {
        val messages = listOf(
            SimpleMsg("u1", "user"),
            SimpleMsg("a1", "assistant"),
        )
        assertNull(resolvePrecedingUserMessageId(messages, "not-found"))
    }
}

