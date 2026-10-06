package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunSetupTest {
    private fun prompt(caller: String? = null, bot: String? = null, session: String? = null, source: String? = null, unattended: Boolean = false) =
        AgentRunSetup.systemPrompt(caller, bot, session, source, unattended)

    @Test
    fun `nothing in gives nothing out`() {
        assertNull(prompt())
        assertNull(prompt(caller = " ", bot = ""))
    }

    @Test
    fun `the caller prompt and the bot identity are joined, blanks dropped`() {
        assertEquals("base\n\nbot", prompt(caller = "base", bot = "bot"))
        assertEquals("base", prompt(caller = "base", bot = "  "))
        assertEquals("bot", prompt(caller = null, bot = "bot"))
    }

    @Test
    fun `the session's instructions come last in a labelled block`() {
        assertEquals(
            "base\n\n<session-specific-instructions>\nbe brief\n</session-specific-instructions>",
            prompt(caller = "base", session = "be brief"),
        )
        assertEquals(
            "<session-specific-instructions>\nbe brief\n</session-specific-instructions>",
            prompt(session = "be brief"),
        )
    }

    @Test
    fun `an unattended scheduled run gets the safety note and an ordinary one does not`() {
        val ordinary = prompt(caller = "base", source = null, unattended = false)
        assertEquals("base", ordinary)
        val unattended = prompt(caller = "base", source = "scheduled", unattended = true)
        assertTrue(unattended!!.startsWith("base"))
        assertTrue("a note was appended", unattended.length > "base".length)
    }

    @Test
    fun `context shape counts characters, results, images and the biggest message`() {
        val big = LLMMessage(LLMMessage.Role.USER, "x".repeat(500))
        val withResult = LLMMessage(
            LLMMessage.Role.ASSISTANT, "ok",
            contentParts = listOf(
                AgentContentPart.Text("abc"),
                AgentContentPart.ToolUse("t", "shell", JSONObject()),
                AgentContentPart.ToolResult("t", "shell", "r".repeat(40), imageData = ByteArray(10)),
            ),
        )
        val shape = AgentRunSetup.contextShape(listOf(big, withResult))
        assertEquals(2, shape.historySize)
        assertEquals(500L + 2 + 3 + 40, shape.totalChars)
        assertEquals(500, shape.maxMessageChars)
        assertEquals("USER", shape.maxMessageRole)
        assertEquals(1, shape.toolResultParts)
        assertEquals(1, shape.imageParts)
        assertEquals(10L, shape.imageBytes)
        assertEquals(shape.totalChars / 4, shape.approxTokens)
    }

    @Test
    fun `an empty history has a zero shape and the log line carries the memory text`() {
        val shape = AgentRunSetup.contextShape(emptyList())
        assertEquals(0, shape.historySize)
        assertEquals(0L, shape.totalChars)
        assertTrue(shape.toLogString("mem=ok").endsWith("approxTokens=0 mem=ok"))
    }
}
