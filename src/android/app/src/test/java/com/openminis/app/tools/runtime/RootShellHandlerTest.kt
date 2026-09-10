package com.openminis.app.tools.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression guard: generic Root execution must never be exposed to Agents or MCP. */
class RootShellHandlerTest {

    @Test
    fun `root shell definition exposes no command surface`() {
        val def = RootShellHandler().definition
        assertEquals("root.shell", def.name)
        assertTrue(def.parameters.isEmpty())
        assertTrue(def.required.isEmpty())
        assertTrue(def.description.contains("Retired"))
        assertFalse(def.description.contains("through minisd"))
    }

    @Test
    fun `root shell is denied to local agent and mcp`() {
        assertEquals(
            ToolPermissionManager.Level.MCP_DENIED,
            ToolPermissionManager.levelFor("root.shell", ToolPermissionManager.CALLER_LOCAL),
        )
        assertEquals(
            ToolPermissionManager.Level.MCP_DENIED,
            ToolPermissionManager.levelFor("root.shell", "mcp:attacker"),
        )
        assertFalse(ToolPermissionManager.isAllowedFor("root.shell", ToolPermissionManager.CALLER_LOCAL))
        assertFalse(ToolPermissionManager.isAllowedFor("root.shell", "mcp:attacker"))
        assertFalse(ToolPermissionManager.mcpVisibleTools().contains("root.shell"))
    }

    @Test
    fun `retired root shell remains resolvable but hidden from discovery`() {
        ToolRegistry.register(RootShellHandler(), aliasNames = listOf("shell_root"))
        try {
            assertTrue(ToolRegistry.contains("root.shell"))
            assertEquals("root.shell", ToolRegistry.canonicalName("shell_root"))
            assertTrue(ToolRegistry.definition("root.shell") != null)
            assertFalse(ToolRegistry.definitions().any { it.name == "root.shell" })
            assertFalse(
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
    fun `handler fail closed result remains permission denied`() {
        val result = RootShellHandler().failClosedResult()
        assertFalse(result.success)
        assertTrue(result.output.contains("permission_denied"))
    }
}
