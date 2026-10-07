package com.openminis.app.ui.chat

import com.openminis.app.agent.ToolLoopDetector
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.tools.runtime.ToolConcurrency
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** Read-only calls of one turn overlap; everything else waits for what came before and runs alone. */
class ToolRoundParallelTest {
    private class SlowHost(private val delays: Map<String, Long> = emptyMap(), private val failOn: String? = null) : ToolRoundHost {
        override val chatOnly = false
        override val sessionId = "s"
        private val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val log: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override suspend fun refreshUi(assistantId: String, text: String, blocks: List<AssistantBlock>) {}
        override fun preflight(name: String, args: JSONObject, tools: List<AgentToolDefinition>): String? = null
        override suspend fun executeTool(
            name: String, argsJson: String, toolId: String, blocks: MutableList<AssistantBlock>,
            assistantId: String, currentText: String,
        ): ToolExecutionResult {
            log += "start:$toolId"
            val now = active.incrementAndGet()
            maxActive.updateAndGet { maxOf(it, now) }
            try {
                delay(delays[toolId] ?: 60L)
                if (toolId == failOn) throw IllegalStateException("boom")
            } finally {
                active.decrementAndGet()
            }
            log += "end:$toolId"
            return ToolExecutionResult(output = "out-$toolId", success = true)
        }
    }

    private val READ = "linux.file.read"
    private val WRITE = "linux.file.write"
    private val SHELL = "shell_execute"

    private fun block(id: String, name: String) =
        AssistantBlock(id = id, kind = "tool_use", toolStatus = ToolBlockStatus.PENDING, toolName = name, startTimeMs = System.currentTimeMillis())

    private fun round(host: SlowHost, blocks: MutableList<AssistantBlock>, executing: AtomicInteger = AtomicInteger()) = ToolRound(
        host = host, turn = 0, turnToolNames = setOf(READ, WRITE, SHELL, "web"), turnTools = emptyList(), assistantId = "a",
        allToolBlocks = blocks, toolInputChunkRings = mutableMapOf(), toolLoopDetector = ToolLoopDetector(),
        sessionEventEmitter = ChatSessionEventEmitter { "s" }, onExecuting = { executing.incrementAndGet() },
    )

    private fun call(id: String, name: String) = Triple(id, name, JSONObject("""{"path":"/workspace/$id"}"""))

    private fun ids(r: ToolRound) = r.resultParts.map { (it as AgentContentPart.ToolResult).id }

    @Test
    fun `read-only calls overlap and their results still come back in call order`() = runBlocking {
        val host = SlowHost(delays = mapOf("c1" to 250L, "c2" to 20L, "c3" to 20L))
        val blocks = mutableListOf(block("c1", READ), block("c2", READ), block("c3", READ))
        val r = round(host, blocks)
        r.run(listOf(call("c1", READ), call("c2", READ), call("c3", READ)), "")

        assertEquals("all three were in flight together", 3, host.maxActive.get())
        assertEquals(listOf("c1", "c2", "c3"), ids(r))
        assertTrue(blocks.all { it.toolStatus == ToolBlockStatus.SUCCESS })
    }

    @Test
    fun `a write waits for the reads before it and the reads after it wait for the write`() = runBlocking {
        val host = SlowHost(delays = mapOf("r1" to 120L, "r2" to 120L, "w" to 60L, "r3" to 30L))
        val blocks = mutableListOf(block("r1", READ), block("r2", READ), block("w", WRITE), block("r3", READ))
        val r = round(host, blocks)
        r.run(listOf(call("r1", READ), call("r2", READ), call("w", WRITE), call("r3", READ)), "")

        val log = host.log
        assertTrue("the write starts only after both earlier reads ended",
            log.indexOf("start:w") > log.indexOf("end:r1") && log.indexOf("start:w") > log.indexOf("end:r2"))
        assertTrue("the later read starts only after the write ended", log.indexOf("start:r3") > log.indexOf("end:w"))
        assertEquals("only the first two reads overlapped", 2, host.maxActive.get())
        assertEquals(listOf("r1", "r2", "w", "r3"), ids(r))
    }

    @Test
    fun `the shell and unclassified tools never overlap with anything`() = runBlocking {
        val host = SlowHost()
        val blocks = mutableListOf(block("s1", SHELL), block("s2", SHELL), block("x1", "web"), block("r1", READ))
        val r = round(host, blocks)
        r.run(listOf(call("s1", SHELL), call("s2", SHELL), call("x1", "web"), call("r1", READ)), "")
        assertEquals(1, host.maxActive.get())
        assertEquals(listOf("s1", "s2", "x1", "r1"), ids(r))
        assertFalse(ToolConcurrency.isParallelSafe(SHELL))
        assertFalse(ToolConcurrency.isParallelSafe("web"))
        assertFalse(ToolConcurrency.isParallelSafe(WRITE))
        assertTrue(ToolConcurrency.isParallelSafe(READ))
    }

    @Test
    fun `a refused call keeps its place among overlapping ones`() = runBlocking {
        val host = SlowHost(delays = mapOf("c1" to 100L))
        val blocks = mutableListOf(block("c1", READ), block("c2", "ghost"), block("c3", READ))
        val r = round(host, blocks)
        r.run(listOf(call("c1", READ), call("c2", "ghost"), call("c3", READ)), "")

        assertEquals(listOf("c1", "c2", "c3"), ids(r))
        val refused = r.resultParts[1] as AgentContentPart.ToolResult
        assertTrue(refused.isError)
        assertEquals(2, host.maxActive.get())
    }

    @Test
    fun `the side-effect flag is raised once per executed call`() = runBlocking {
        val host = SlowHost()
        val executing = AtomicInteger()
        val blocks = mutableListOf(block("c1", READ), block("c2", "ghost"), block("c3", READ))
        val r = round(host, blocks, executing)
        r.run(listOf(call("c1", READ), call("c2", "ghost"), call("c3", READ)), "")
        assertEquals("the refused call executed nothing", 2, executing.get())
    }

    @Test
    fun `a failure in one overlapping call ends the round instead of being swallowed`() {
        val host = SlowHost(delays = mapOf("c2" to 20L, "c1" to 500L), failOn = "c2")
        val blocks = mutableListOf(block("c1", READ), block("c2", READ))
        val r = round(host, blocks)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { r.run(listOf(call("c1", READ), call("c2", READ)), "") }
        }
        assertFalse("the slower call was cancelled, not left running", host.log.contains("end:c1"))
    }
}
