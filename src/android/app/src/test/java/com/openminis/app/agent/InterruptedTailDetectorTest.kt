package com.openminis.app.agent

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterruptedTailDetectorTest {

    private fun user(vararg parts: AgentContentPart) =
        LLMMessage(role = LLMMessage.Role.USER, content = "", contentParts = parts.toList())

    private fun assistant(vararg parts: AgentContentPart) =
        LLMMessage(role = LLMMessage.Role.ASSISTANT, content = "", contentParts = parts.toList())

    private fun text(s: String) = AgentContentPart.Text(s)

    private fun toolResult(id: String = "t1") =
        AgentContentPart.ToolResult(id = id, name = "shell_execute", content = "ok")

    private fun toolUse(id: String = "t1") =
        AgentContentPart.ToolUse(id = id, name = "shell_execute", input = JSONObject())

    @Test
    fun `plain text user tail is recoverable`() {
        assertEquals(
            InterruptedTailShape.UNANSWERED_USER_TURN,
            InterruptedTailDetector.classify(user(text("what is 2+2?"))),
        )
    }

    @Test
    fun `multi part user tail is an unanswered turn`() {
        assertEquals(
            InterruptedTailShape.UNANSWERED_USER_TURN,
            InterruptedTailDetector.classify(user(text("look at this"), text("and this"))),
        )
    }

    @Test
    fun `empty user tail is not recoverable`() {
        assertEquals(InterruptedTailShape.NONE, InterruptedTailDetector.classify(user()))
        assertFalse(InterruptedTailDetector.isInterrupted(user()))
    }

    @Test
    fun `all tool results tail keeps its shape`() {
        assertEquals(
            InterruptedTailShape.TOOL_RESULT_TAIL,
            InterruptedTailDetector.classify(user(toolResult("a"), toolResult("b"))),
        )
    }

    @Test
    fun `continue reminder tail keeps its shape`() {
        val message = user(
            text("<system-reminder>${InterruptedTailDetector.CONTINUE_REMINDER_MARKER} but now wants to continue.</system-reminder>"),
        )
        assertEquals(InterruptedTailShape.CONTINUE_REMINDER, InterruptedTailDetector.classify(message))
    }

    @Test
    fun `assistant tool use tail is recoverable`() {
        assertEquals(
            InterruptedTailShape.ASSISTANT_TOOL_USE,
            InterruptedTailDetector.classify(assistant(text("running"), toolUse())),
        )
    }

    @Test
    fun `plain assistant reply and empty history are not interrupted`() {
        assertEquals(InterruptedTailShape.NONE, InterruptedTailDetector.classify(assistant(text("4"))))
        assertEquals(InterruptedTailShape.NONE, InterruptedTailDetector.classify(null))
        assertFalse(InterruptedTailDetector.isInterrupted(assistant(text("done"))))
        assertFalse(InterruptedTailDetector.isInterrupted(null))
    }

    @Test
    fun `mixed user tail falls back to unanswered turn`() {
        assertEquals(
            InterruptedTailShape.UNANSWERED_USER_TURN,
            InterruptedTailDetector.classify(user(toolResult(), text("also, explain why"))),
        )
    }

    @Test
    fun `isInterrupted agrees with classify`() {
        assertTrue(InterruptedTailDetector.isInterrupted(user(text("hi"))))
        assertTrue(InterruptedTailDetector.isInterrupted(user(toolResult())))
        assertTrue(InterruptedTailDetector.isInterrupted(assistant(toolUse())))
        assertFalse(InterruptedTailDetector.isInterrupted(assistant(text("done"))))
    }
}
