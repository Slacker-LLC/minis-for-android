package com.openminis.app.tools.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * root.shell handler contract: local-only compatibility name for structured
 * minisd root.exec, never an arbitrary shell-string interface.
 */
class RootShellHandlerTest {

    @Test
    fun `root shell is structured minisd root exec`() {
        val def = RootShellHandler().definition
        assertEquals("root.shell", def.name)
        assertTrue(def.parameters.containsKey("tool"))
        assertTrue(def.parameters.containsKey("args"))
        assertFalse(def.parameters.containsKey("command"))
        assertFalse(def.parameters.containsKey("access_mode"))
        assertEquals(null, def.parameters.getValue("tool").enumValues)
        assertEquals(listOf("tool"), def.required)
        assertTrue(def.description.contains("minisd"))
    }

    @Test
    fun `root shell policy is local only and invisible to mcp`() {
        val localLevel = ToolPermissionManager.levelFor("root.shell", "local_agent")
        val mcpLevel = ToolPermissionManager.levelFor("root.shell", "mcp:attacker")
        assertEquals(ToolPermissionManager.Level.LOCAL_ONLY, localLevel)
        assertTrue(ToolPermissionManager.isAllowedFor("root.shell", "local_agent"))
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
        assertTrue(
            com.openminis.app.tools.AgentTools.makeAgentTools().any { it.name == "root.shell" },
        )
        // alias must never appear in MCP-visible list either
        assertFalse(ToolPermissionManager.mcpVisibleTools().contains("shell_root"))
    }
}
