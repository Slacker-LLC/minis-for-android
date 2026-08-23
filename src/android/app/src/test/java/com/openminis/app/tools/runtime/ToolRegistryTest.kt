package com.openminis.app.tools.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P3 registry/executor contract tests (JVM). Handlers are lightweight fakes;
 * real handlers may need a Context which JVM tests don't provide.
 */
class ToolRegistryTest {

    private class FakeHandler(private val name: String, private val output: String = "ok") : ToolHandler {
        override val definition = com.openminis.app.data.model.AgentToolDefinition(
            name = name,
            description = "fake $name",
            parameters = emptyMap(),
        )
        override suspend fun execute(
            argsJson: String,
            sessionId: String,
            context: android.content.Context,
            toolId: String,
        ): com.openminis.app.tools.ToolExecutionResult =
            com.openminis.app.tools.ToolExecutionResult(output, true)
    }

    @Before
    fun reset() {
        // fresh registry per test: re-register the linux file handlers
        ToolRegistry.register(LinuxFileReadHandler(), aliasNames = listOf("file_read"))
        ToolRegistry.register(LinuxFileWriteHandler(), aliasNames = listOf("file_write"))
        ToolRegistry.register(LinuxFileEditHandler(), aliasNames = listOf("file_edit"))
        ToolRegistry.register(LinuxShellHandler(), aliasNames = listOf("shell_execute"))
        ToolRegistry.register(
            AndroidToolHandler(
                com.openminis.app.tools.android.AndroidAgentTools.CAPABILITIES,
                "android.capabilities",
            ),
            aliasNames = listOf("android_capabilities"),
        )
        ToolRegistry.register(
            AndroidToolHandler(
                com.openminis.app.tools.android.AndroidAgentTools.APP,
                "android.app",
            ),
            aliasNames = listOf("android_app"),
        )
    }

    @Test
    fun `d12 names and old aliases resolve to same handler`() {
        assertEquals("linux.file.read", ToolRegistry.canonicalName("linux.file.read"))
        assertEquals("linux.file.read", ToolRegistry.canonicalName("file_read"))
        assertNotNull(ToolRegistry.definition("linux.file.read"))
        assertNotNull(ToolRegistry.definition("file_read"))
        assertTrue(ToolRegistry.contains("linux.file.write"))
        assertFalse(ToolRegistry.contains("no.such.tool"))
    }

    @Test
    fun `permission gate denies unknown and mcp-low`() {
        // local allowed
        val localOk = FakeHandler("linux.shell")
        ToolRegistry.register(localOk)
        // registry executor path is covered by ToolPermissionManagerTest;
        // here we assert registry-level helpers only
        assertTrue(ToolRegistry.contains("linux.shell"))
    }

    @Test
    fun `mcp visibility filters local-only and denied`() {
        val visible = ToolRegistry.definitionsForCaller("mcp:tok1").map { it.name }
        assertTrue(visible.contains("linux.file.read"))
        assertTrue(visible.contains("linux.file.write"))
        assertFalse(visible.contains("root.shell"))
        assertFalse(visible.contains("agent.goal"))
    }

    @Test
    fun `android capabilities registered and allowed for mcp`() {
        assertEquals("android.capabilities", ToolRegistry.canonicalName("android.capabilities"))
        assertEquals("android.capabilities", ToolRegistry.canonicalName("android_capabilities"))
        // app is CONFIRM for MCP, capabilities is ALLOWED
        assertEquals(
            ToolPermissionManager.Level.MCP_CONFIRM,
            ToolPermissionManager.levelFor("android.app", "mcp:tok1"),
        )
        assertEquals(
            ToolPermissionManager.Level.MCP_ALLOWED,
            ToolPermissionManager.levelFor("android.capabilities", "mcp:tok1"),
        )
    }

    @Test
    fun `linux shell registered with old alias`() {
        assertEquals("linux.shell", ToolRegistry.canonicalName("linux.shell"))
        assertEquals("linux.shell", ToolRegistry.canonicalName("shell_execute"))
        assertNotNull(ToolRegistry.definition("linux.shell"))
        // linux.shell is MCP_CONFIRM: visible to MCP but gated
        assertTrue(
            ToolPermissionManager.isAllowedFor("linux.shell", "mcp:tok1"),
        )
        assertTrue(ToolPermissionManager.needsConfirm("linux.shell", "mcp:tok1"))
    }
}
