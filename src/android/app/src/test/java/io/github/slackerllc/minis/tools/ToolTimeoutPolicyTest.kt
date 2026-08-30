package io.github.slackerllc.minis.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolTimeoutPolicyTest {
    @Test
    fun shellOverrideIsClampedToProcessMaximum() {
        val resolved = ToolTimeoutPolicy.resolve("shell_execute", callerOverrideMs = 9_999_999)
        assertEquals(ToolTimeoutPolicy.Category.PROCESS, resolved.category)
        assertEquals(900_000L, resolved.timeoutMs)
        assertTrue(resolved.callerOverrideApplied)
    }

    @Test
    fun pythonAndRootUseDifferentBudgets() {
        assertEquals(300_000L, ToolTimeoutPolicy.resolve("linux.python.run").timeoutMs)
        assertEquals(30_000L, ToolTimeoutPolicy.resolve("root.shell").timeoutMs)
        assertEquals(120_000L, ToolTimeoutPolicy.resolve("root.shell", callerOverrideMs = 500_000).timeoutMs)
    }

    @Test
    fun mcpHasNetworkBudgetInsteadOfGlobalThirtySeconds() {
        val resolved = ToolTimeoutPolicy.resolve("mcp.server.tool")
        assertEquals(ToolTimeoutPolicy.Category.NETWORK_MCP, resolved.category)
        assertEquals(60_000L, resolved.timeoutMs)
    }

    @Test
    fun declaredToolsCannotEscalateTheirOwnBudget() {
        val resolved = ToolTimeoutPolicy.resolve("file_read", declaredTimeoutMs = 10_000, callerOverrideMs = 90_000)
        assertEquals(10_000L, resolved.timeoutMs)
        assertFalse(resolved.callerOverrideApplied)
    }

    @Test
    fun interactiveToolWithoutDeclaredBudgetRemainsUnboundedByThisPolicy() {
        val resolved = ToolTimeoutPolicy.resolve("ask_user_question")
        assertEquals(ToolTimeoutPolicy.Category.UNBOUNDED_INTERACTIVE, resolved.category)
        assertNull(resolved.timeoutMs)
    }
}
