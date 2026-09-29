package com.openminis.app.tools.runtime

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.scheduled.ScheduledTaskPermissionTier
import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolExecutorScheduledTierTest {
    private class FakeHandler(
        name: String,
        override val isMcpTool: Boolean = false,
    ) : ToolHandler {
        var executionCount = 0
        override val definition = AgentToolDefinition(
            name = name,
            description = "V4 policy test handler",
            parameters = emptyMap(),
        )

        override suspend fun execute(
            argsJson: String,
            sessionId: String,
            context: Context,
            toolId: String,
        ): ToolExecutionResult {
            executionCount++
            return ToolExecutionResult("unexpected handler dispatch", true)
        }
    }

    @Test
    fun `read-only runtime gate rejects shell file python and dynamic MCP before dispatch`() = runBlocking {
        val suffix = System.nanoTime().toString()
        val names = listOf("linux.shell", "linux.file.write", "linux.python.run", "mcp.v4.test.$suffix")
        val fakes = names.mapIndexed { index, name -> FakeHandler(name, isMcpTool = index == 3) }
        val previous = fakes.map { ToolRegistry.handler(it.definition.name) }
        fakes.forEachIndexed { index, handler -> if (previous[index] == null) ToolRegistry.register(handler) }
        val sessionId = "v4-read-only-runtime-$suffix"

        try {
            OffloadPermissionManager.withUnattendedSession(sessionId, ScheduledTaskPermissionTier.READ_ONLY) {
                val shell = ToolExecutor.execute(
                    "linux.shell",
                    """{"command":"ls && rm -rf /"}""",
                    sessionId,
                    TestContext.dummy(),
                )
                val fileWrite = ToolExecutor.execute(
                    "linux.file.write",
                    """{"path":"/workspace/outside.txt","content":"x"}""",
                    sessionId,
                    TestContext.dummy(),
                )
                val python = ToolExecutor.execute(
                    "linux.python.run",
                    """{"code":"print('unsafe')"}""",
                    sessionId,
                    TestContext.dummy(),
                )
                val mcp = ToolExecutor.execute(
                    names[3],
                    "{}",
                    sessionId,
                    TestContext.dummy(),
                )

                assertFalse(shell.success)
                assertTrue(shell.output.contains("shell_denied_readonly_tier"))
                assertFalse(fileWrite.success)
                assertTrue(fileWrite.output.contains("file_write_denied_readonly_tier"))
                assertFalse(python.success)
                assertTrue(python.output.contains("shell_denied_readonly_tier"))
                assertFalse(mcp.success)
                assertTrue(mcp.output.contains("mcp_denied_readonly_tier"))
                assertEquals(0, fakes[3].executionCount)
                assertEquals(4, OffloadPermissionManager.consumeScheduledTierDenials(sessionId).size)
            }
        } finally {
            fakes.forEachIndexed { index, handler ->
                if (previous[index] == null) ToolRegistry.unregister(handler.definition.name)
            }
        }
    }
}
