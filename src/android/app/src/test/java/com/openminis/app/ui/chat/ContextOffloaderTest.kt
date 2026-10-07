package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextOffloaderTest {
    @Test
    fun `the character estimate counts text, tool input and tool output`() {
        val history = listOf(
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = "",
                contentParts = listOf(AgentContentPart.Text("a".repeat(700))),
            ),
        )
        assertEquals(200, ContextOffloader.estimateContextTokens(history))
        assertEquals(0, ContextOffloader.estimateContextTokens(emptyList()))
    }

    @Test
    fun `an empty history estimates to nothing and a longer one to more`() {
        val one = listOf(LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(AgentContentPart.Text("x".repeat(70)))))
        val two = one + one
        assertTrue(ContextOffloader.estimateContextTokens(two) > ContextOffloader.estimateContextTokens(one))
    }
}
