package com.openminis.app.ui.chat

import com.openminis.app.agent.ToolLoopDetector
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The per-call gates of a tool round, tested with a fake conversation: what is refused, and what is allowed to run. */
class ToolRoundTest {
    private class FakeHost(override var chatOnly: Boolean = false, private val preflightError: String? = null) : ToolRoundHost {
        override val sessionId = "s"
        val executed = mutableListOf<String>()
        val events = mutableListOf<String>()
        var result = ToolExecutionResult(output = "done", success = true)

        override suspend fun refreshUi(assistantId: String, text: String, blocks: List<AssistantBlock>) {}
        override fun preflight(name: String, args: JSONObject, tools: List<AgentToolDefinition>) = preflightError
        override suspend fun executeTool(
            name: String, argsJson: String, toolId: String, blocks: MutableList<AssistantBlock>,
            assistantId: String, currentText: String,
        ): ToolExecutionResult {
            events += "execute:$name"
            executed += name
            return result
        }
    }

    private val echo = AgentToolDefinition(
        name = "echo",
        description = "echoes",
        parameters = mapOf("text" to AgentToolParam("string", "what to echo")),
        required = listOf("text"),
    )

    private fun block(id: String, name: String) =
        AssistantBlock(id = id, kind = "tool_use", toolStatus = ToolBlockStatus.PENDING, toolName = name, startTimeMs = System.currentTimeMillis())

    private fun round(host: FakeHost, blocks: MutableList<AssistantBlock>, offered: Set<String> = setOf("echo"), onExecuting: () -> Unit = {}) =
        ToolRound(
            host = host, turn = 0, turnToolNames = offered, turnTools = listOf(echo), assistantId = "a",
            allToolBlocks = blocks, toolInputChunkRings = mutableMapOf(), toolLoopDetector = ToolLoopDetector(),
            sessionEventEmitter = ChatSessionEventEmitter { "s" }, onExecuting = onExecuting,
        )

    private fun call(id: String, name: String, args: String = """{"text":"hi"}""") = Triple(id, name, JSONObject(args))

    @Test
    fun `a call to a tool that was not offered is refused and never executed`() = runBlocking {
        val host = FakeHost()
        val blocks = mutableListOf(block("c1", "rm_everything"))
        var executing = false
        val r = round(host, blocks, onExecuting = { executing = true })
        r.run(listOf(call("c1", "rm_everything")), "")

        assertTrue(host.executed.isEmpty())
        assertFalse("not even marked as having a side effect", executing)
        val part = r.resultParts.single() as AgentContentPart.ToolResult
        assertTrue(part.isError)
        assertEquals("Error: Tool 'rm_everything' is not available.", part.content)
        assertEquals(ToolBlockStatus.FAILED, blocks.single().toolStatus)
    }

    @Test
    fun `in chat-only mode a call gets the chat-only refusal and nothing runs`() = runBlocking {
        val host = FakeHost(chatOnly = true)
        val blocks = mutableListOf(block("c1", "other"))
        val r = round(host, blocks, offered = emptySet())
        // In chat-only mode no tool is offered, so any call is refused with the chat-only reason.
        r.run(listOf(call("c1", "other")), "")
        assertTrue(host.executed.isEmpty())
        assertEquals("Error: Tools are disabled in chat-only mode.", (r.resultParts.single() as AgentContentPart.ToolResult).content)
    }

    @Test
    fun `a call rejected by preflight is not executed and tells the model why`() = runBlocking {
        val host = FakeHost(preflightError = "Missing required field: text.")
        val blocks = mutableListOf(block("c1", "echo"))
        var executing = false
        val r = round(host, blocks, onExecuting = { executing = true })
        r.run(listOf(call("c1", "echo", "{}")), "")

        assertTrue(host.executed.isEmpty())
        assertFalse(executing)
        val part = r.resultParts.single() as AgentContentPart.ToolResult
        assertTrue(part.isError)
        assertTrue(part.content, part.content.contains("Missing required field: text."))
        assertEquals(ToolBlockStatus.FAILED, blocks.single().toolStatus)
    }

    @Test
    fun `an offered, valid call runs once and its output comes back`() = runBlocking {
        val host = FakeHost()
        val blocks = mutableListOf(block("c1", "echo"))
        val r = round(host, blocks, onExecuting = { host.events += "executing" })
        r.run(listOf(call("c1", "echo")), "")

        assertEquals("the side-effect flag is raised before the tool body runs", listOf("executing", "execute:echo"), host.events)
        val part = r.resultParts.single() as AgentContentPart.ToolResult
        assertFalse(part.isError)
        assertEquals("c1", part.id)
        assertEquals("done", part.content)
        assertEquals(ToolBlockStatus.SUCCESS, blocks.single().toolStatus)
    }

    @Test
    fun `a failed tool is reported as an error and marks the block failed`() = runBlocking {
        val host = FakeHost().apply { result = ToolExecutionResult(output = "boom", success = false) }
        val blocks = mutableListOf(block("c1", "echo"))
        val r = round(host, blocks)
        r.run(listOf(call("c1", "echo")), "")
        assertTrue((r.resultParts.single() as AgentContentPart.ToolResult).isError)
        assertEquals(ToolBlockStatus.FAILED, blocks.single().toolStatus)
    }

    @Test
    fun `a tool that ran out of time is shown as timed out`() = runBlocking {
        val host = FakeHost().apply { result = ToolExecutionResult(output = "slow", success = false, timedOut = true) }
        val blocks = mutableListOf(block("c1", "echo"))
        round(host, blocks).run(listOf(call("c1", "echo")), "")
        assertEquals(ToolBlockStatus.TIMEOUT, blocks.single().toolStatus)
    }

    @Test
    fun `every call gets a result part, in order, even when some are refused`() = runBlocking {
        val host = FakeHost()
        val blocks = mutableListOf(block("c1", "echo"), block("c2", "nope"), block("c3", "echo"))
        val r = round(host, blocks)
        r.run(listOf(call("c1", "echo"), call("c2", "nope"), call("c3", "echo")), "")
        assertEquals(listOf("c1", "c2", "c3"), r.resultParts.map { (it as AgentContentPart.ToolResult).id })
        assertEquals(listOf("echo", "echo"), host.executed)
    }

    @Test
    fun `parameters of malformed json degrade to an empty map`() {
        assertEquals(emptyMap<String, Any?>(), parseToolParams("not json"))
        assertEquals(emptyMap<String, Any?>(), parseToolParams(""))
        assertEquals(mapOf<String, Any?>("a" to 1, "b" to null), parseToolParams("""{"a":1,"b":null}"""))
    }
}
