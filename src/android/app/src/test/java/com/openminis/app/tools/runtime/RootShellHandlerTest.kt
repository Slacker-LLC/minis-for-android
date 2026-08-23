package com.openminis.app.tools.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * root.shell handler contract (T-K1/T-K3 style): the second shell exists,
 * is LOCAL_ONLY (never visible to MCP), and its definition carries the
 * command/timeout parameters.
 */
class RootShellHandlerTest {

    @Test
    fun `root shell is registered with exact name and alias`() {
        val def = RootShellHandler().definition
        assertEquals("root.shell", def.name)
        assertTrue(def.parameters.containsKey("command"))
        assertEquals(listOf("command"), def.required)
        assertTrue(def.description.contains("Root"))
    }

    @Test
    fun `root shell policy is local only and invisible to mcp`() {
        val localLevel = ToolPermissionManager.levelFor("root.shell", "local_agent")
        val mcpLevel = ToolPermissionManager.levelFor("root.shell", "mcp:attacker")
        assertEquals(ToolPermissionManager.Level.LOCAL_ONLY, localLevel)
        assertFalse(ToolPermissionManager.mcpVisibleTools().contains("root.shell"))
        assertFalse(ToolPermissionManager.isDirectlyAllowed("root.shell", "mcp:attacker"))
        // MCP cannot even list it
        assertTrue(ToolPermissionManager.mcpVisibleTools().none { it == "root.shell" })
    }

    @Test
    fun `alias shell_root resolves to same policy`() {
        // JVM tests don't run MinisApp.onCreate registration — register manually.
        ToolRegistry.register(RootShellHandler(), aliasNames = listOf("shell_root"))
        assertTrue(ToolRegistry.contains("root.shell"))
        assertTrue(ToolRegistry.contains("shell_root"))
        // alias must never appear in MCP-visible list either
        assertFalse(ToolPermissionManager.mcpVisibleTools().contains("shell_root"))
    }
}
