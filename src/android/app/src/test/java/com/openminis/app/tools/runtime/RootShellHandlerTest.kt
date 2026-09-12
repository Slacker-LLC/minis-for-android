package com.openminis.app.tools.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression guard: Root is locally available through structured argv only. */
class RootShellHandlerTest {

    @Test
    fun `root shell definition exposes structured argv surface`() {
        val def = RootShellHandler().definition
        assertEquals("root.shell", def.name)
        assertTrue(def.parameters.containsKey("tool"))
        assertTrue(def.parameters.containsKey("args"))
        assertTrue(def.parameters.containsKey("timeout_ms"))
        assertFalse(def.parameters.containsKey("command"))
        assertFalse(def.parameters.containsKey("access_mode"))
        assertEquals(listOf("tool"), def.required)
        assertTrue(def.description.contains("structured"))
        assertTrue(def.description.contains("trusted Android system"))
    }

    @Test
    fun `root shell is local only and hidden from mcp`() {
        assertEquals(
            ToolPermissionManager.Level.LOCAL_ONLY,
            ToolPermissionManager.levelFor("root.shell", ToolPermissionManager.CALLER_LOCAL),
        )
        assertEquals(
            ToolPermissionManager.Level.LOCAL_ONLY,
            ToolPermissionManager.levelFor("root.shell", "mcp:attacker"),
        )
        assertTrue(ToolPermissionManager.isAllowedFor("root.shell", ToolPermissionManager.CALLER_LOCAL))
        assertFalse(ToolPermissionManager.isAllowedFor("root.shell", "mcp:attacker"))
        assertFalse(ToolPermissionManager.mcpVisibleTools().contains("root.shell"))
    }

    @Test
    fun `root shell remains discoverable locally but hidden from mcp`() {
        ToolRegistry.register(RootShellHandler(), aliasNames = listOf("shell_root"))
        try {
            assertTrue(ToolRegistry.contains("root.shell"))
            assertEquals("root.shell", ToolRegistry.canonicalName("shell_root"))
            assertTrue(ToolRegistry.definition("root.shell") != null)
            assertTrue(ToolRegistry.definitions().any { it.name == "root.shell" })
            assertTrue(
                ToolRegistry.definitionsForCaller(ToolPermissionManager.CALLER_LOCAL)
                    .any { it.name == "root.shell" },
            )
            assertFalse(
                ToolRegistry.definitionsForCaller("mcp:attacker")
                    .any { it.name == "root.shell" },
            )
        } finally {
            ToolRegistry.unregister("root.shell")
        }
    }

    @Test
    fun `standard local agent schema includes structured root shell`() {
        ToolRegistry.register(RootShellHandler())
        try {
            assertTrue(
                com.openminis.app.tools.AgentTools.makeAgentTools()
                    .any { it.name == "root.shell" },
            )
        } finally {
            ToolRegistry.unregister("root.shell")
        }
    }

}
