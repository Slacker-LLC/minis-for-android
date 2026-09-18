package com.openminis.app.agent

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-tool-batch-repair-android] The pairing rule the provider requires, and the wording
 * the model needs: a call whose result never arrived may already have taken effect, so its
 * synthetic result says the state is unknown and says not to replay it (Eta
 * `agent/model/AgentToolBatchRecovery.kt`, Mangi-11/Eta @ c15de97).
 */
class ToolBatchRepairTest {

    private fun call(id: String, name: String = "android.sms.send") = AgentContentPart.ToolUse(
        id = id,
        name = name,
        input = JSONObject().put("to", "10086"),
    )

    private fun result(id: String, text: String = "sent") = AgentContentPart.ToolResult(
        id = id,
        name = "android.sms.send",
        content = text,
    )

    private fun assistant(vararg parts: AgentContentPart) = LLMMessage(
        role = LLMMessage.Role.ASSISTANT,
        content = "",
        contentParts = parts.toList(),
    )

    private fun toolMessage(vararg parts: AgentContentPart) = LLMMessage(
        role = LLMMessage.Role.USER,
        content = "",
        contentParts = parts.toList(),
    )

    @Test
    fun `a complete batch is left alone`() {
        val messages = listOf(assistant(call("t1")), toolMessage(result("t1")))

        val repaired = ToolBatchRepair.repair(messages)

        assertFalse(repaired.changed)
        assertEquals(messages, repaired.messages)
    }

    @Test
    fun `a call with no result gets one, and it warns instead of inviting a retry`() {
        val repaired = ToolBatchRepair.repair(listOf(assistant(call("t1"))))

        assertEquals(1, repaired.injected)
        val injected = repaired.messages[1].contentParts.single()
        assertTrue(injected is AgentContentPart.ToolResult)
        injected as AgentContentPart.ToolResult
        assertEquals("t1", injected.id)
        assertTrue(injected.isError)
        assertTrue(injected.content.contains(ToolBatchRepair.INTERRUPTED_MARKER))
        assertTrue(injected.content.contains("may or may not have taken effect"))
        assertTrue(injected.content.contains("do not replay"))
    }

    @Test
    fun `missing results join a user message that already carries results`() {
        val messages = listOf(
            assistant(call("t1"), call("t2")),
            toolMessage(result("t1")),
        )

        val repaired = ToolBatchRepair.repair(messages)

        assertEquals(1, repaired.injected)
        assertEquals(2, repaired.messages.size)
        val ids = repaired.messages[1].contentParts
            .filterIsInstance<AgentContentPart.ToolResult>()
            .map { it.id }
        assertEquals(listOf("t1", "t2"), ids)
    }

    @Test
    fun `several open calls are closed together`() {
        val repaired = ToolBatchRepair.repair(listOf(assistant(call("t1"), call("t2"), call("t3"))))

        assertEquals(3, repaired.injected)
        assertEquals(3, repaired.messages[1].contentParts.size)
    }

    @Test
    fun `a result whose call is gone is dropped, and the empty message with it`() {
        val repaired = ToolBatchRepair.repair(listOf(toolMessage(result("ghost"))))

        assertEquals(1, repaired.droppedResults)
        assertEquals(1, repaired.droppedMessages)
        assertTrue(repaired.messages.isEmpty())
    }

    @Test
    fun `a user message keeps its text when only its orphan result is dropped`() {
        val repaired = ToolBatchRepair.repair(
            listOf(
                LLMMessage(
                    role = LLMMessage.Role.USER,
                    content = "still talking to you",
                    contentParts = listOf(result("ghost")),
                ),
            ),
        )

        assertEquals(1, repaired.droppedResults)
        assertEquals(0, repaired.droppedMessages)
        assertEquals("still talking to you", repaired.messages.single().content)
        assertTrue(repaired.messages.single().contentParts.isEmpty())
    }

    @Test
    fun `an unanswered assistant text turn is not treated as a batch`() {
        val repaired = ToolBatchRepair.repair(
            listOf(LLMMessage(role = LLMMessage.Role.ASSISTANT, content = "no tools here")),
        )

        assertFalse(repaired.changed)
    }
}
