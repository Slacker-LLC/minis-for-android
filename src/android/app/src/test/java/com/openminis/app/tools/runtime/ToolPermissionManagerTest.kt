package com.openminis.app.tools.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolPermissionManagerTest {

    @Test
    fun `unknown tools are denied for every caller`() {
        assertEquals(ToolPermissionManager.Level.MCP_DENIED, ToolPermissionManager.levelFor("no.such.tool", "local_agent"))
        assertEquals(ToolPermissionManager.Level.MCP_DENIED, ToolPermissionManager.levelFor("no.such.tool", "mcp:tok1"))
        assertFalse(ToolPermissionManager.isRegistered("no.such.tool"))
        assertFalse(ToolPermissionManager.isAllowedFor("no.such.tool", "mcp:tok1"))
    }

    @Test
    fun `linux shell local allowed remote confirm`() {
        assertEquals(ToolPermissionManager.Level.MCP_ALLOWED, ToolPermissionManager.levelFor("linux.shell", "local_agent"))
        assertEquals(ToolPermissionManager.Level.MCP_CONFIRM, ToolPermissionManager.levelFor("linux.shell", "mcp:tok1"))
        assertTrue(ToolPermissionManager.needsConfirm("linux.shell", "mcp:tok1"))
        assertFalse(ToolPermissionManager.needsConfirm("linux.shell", "local_agent"))
    }

    @Test
    fun `local only root shell invisible to mcp`() {
        assertEquals(ToolPermissionManager.Level.LOCAL_ONLY, ToolPermissionManager.levelFor("root.shell", "local_agent"))
        assertEquals(ToolPermissionManager.Level.LOCAL_ONLY, ToolPermissionManager.levelFor("root.shell", "mcp:tok1"))
        assertFalse(ToolPermissionManager.isAllowedFor("root.shell", "mcp:tok1"))
        assertTrue(ToolPermissionManager.localOnlyTools.contains("root.shell"))
        assertFalse(ToolPermissionManager.mcpVisibleTools().contains("root.shell"))
    }

    @Test
    fun `logs clear is mcp denied`() {
        assertFalse(ToolPermissionManager.isAllowedFor("android.logs.clear", "mcp:tok1"))
        assertFalse(ToolPermissionManager.mcpVisibleTools().contains("android.logs.clear"))
        assertTrue(ToolPermissionManager.isAllowedFor("android.logs.read", "mcp:tok1"))
    }

    @Test
    fun `mcp visible set excludes local only and denied`() {
        val visible = ToolPermissionManager.mcpVisibleTools()
        assertTrue(visible.contains("linux.file.read"))
        assertTrue(visible.contains("android.app.list"))
        assertFalse(visible.contains("root.shell"))
        assertFalse(visible.contains("agent.goal"))
        assertFalse(visible.contains("system.permissions"))
    }

    @Test
    fun `token scope subset and ceiling`() {
        // subset limits
        assertTrue(
            ToolPermissionManager.tokenCanCall(
                "linux.file.read", "mcp:tok1",
                allowedSubset = setOf("linux.file.read", "system.info"),
                maxLevel = ToolPermissionManager.Level.MCP_ALLOWED,
            ),
        )
        assertFalse(
            ToolPermissionManager.tokenCanCall(
                "android.app.list", "mcp:tok1",
                allowedSubset = setOf("linux.file.read"),
                maxLevel = ToolPermissionManager.Level.MCP_ALLOWED,
            ),
        )
        // ceiling: CONFIRM tool needs CONFIRM ceiling, not ALLOWED
        assertFalse(
            ToolPermissionManager.tokenCanCall(
                "linux.shell", "mcp:tok1",
                allowedSubset = emptySet(),
                maxLevel = ToolPermissionManager.Level.MCP_ALLOWED,
            ),
        )
        assertTrue(
            ToolPermissionManager.tokenCanCall(
                "linux.shell", "mcp:tok1",
                allowedSubset = emptySet(),
                maxLevel = ToolPermissionManager.Level.MCP_CONFIRM,
            ),
        )
        // LOCAL_ONLY never passes
        assertFalse(
            ToolPermissionManager.tokenCanCall(
                "root.shell", "mcp:tok1",
                allowedSubset = emptySet(),
                maxLevel = ToolPermissionManager.Level.MCP_CONFIRM,
            ),
        )
    }

    @Test
    fun `wildcard group matches child tools`() {
        // android.diagnose.* group → CONFIRM for MCP
        assertEquals(
            ToolPermissionManager.Level.MCP_CONFIRM,
            ToolPermissionManager.levelFor("android.diagnose.process", "mcp:tok1"),
        )
        assertTrue(ToolPermissionManager.isRegistered("android.diagnose.process"))
        // explicit key beats wildcard: clear is DENIED while read is CONFIRM
        assertEquals(
            ToolPermissionManager.Level.MCP_DENIED,
            ToolPermissionManager.levelFor("android.logs.clear", "mcp:tok1"),
        )
        assertEquals(
            ToolPermissionManager.Level.MCP_CONFIRM,
            ToolPermissionManager.levelFor("android.logs.read", "mcp:tok1"),
        )
        // mcp.* group → LOCAL_ONLY
        assertEquals(
            ToolPermissionManager.Level.LOCAL_ONLY,
            ToolPermissionManager.levelFor("mcp.github", "mcp:tok1"),
        )
    }

    @Test
    fun `whole-tool entries are not dead for local agent`() {
        // 三号评审发现：整名注册的工具若权限表只有通配键，本地会被拒死。
        for (tool in listOf("android.diagnose", "android.deploy", "system.jobs", "android.logs")) {
            assertTrue("$tool should be allowed locally", ToolPermissionManager.isAllowedFor(tool, "local_agent"))
        }
        assertTrue(ToolPermissionManager.needsConfirm("android.deploy", "mcp:tok1"))
        assertTrue(ToolPermissionManager.needsConfirm("system.jobs", "mcp:tok1"))
    }

    @Test
    fun `local caller can use local only tools while mcp cannot`() {
        assertTrue(ToolPermissionManager.isAllowedFor("android.logs.clear", "local_agent"))
        assertTrue(ToolPermissionManager.isAllowedFor("root.shell", "local_agent"))
        assertFalse(ToolPermissionManager.isAllowedFor("root.shell", "mcp:tok1"))
    }

    @Test
    fun `agent tools are local only for mcp`() {
        for (tool in listOf("agent.goal", "agent.todo", "agent.subagent", "agent.ask", "agent.ralph")) {
            assertEquals(ToolPermissionManager.Level.LOCAL_ONLY, ToolPermissionManager.levelFor(tool, "mcp:tok1"))
            assertEquals(ToolPermissionManager.Level.MCP_ALLOWED, ToolPermissionManager.levelFor(tool, "local_agent"))
            assertFalse(ToolPermissionManager.mcpVisibleTools().contains(tool))
        }
    }

    @Test
    fun `system jobs kill confirm list allowed`() {
        assertEquals(ToolPermissionManager.Level.MCP_CONFIRM, ToolPermissionManager.levelFor("system.jobs.kill", "mcp:tok1"))
        assertEquals(ToolPermissionManager.Level.MCP_ALLOWED, ToolPermissionManager.levelFor("system.jobs.list", "mcp:tok1"))
        assertTrue(ToolPermissionManager.needsConfirm("system.jobs.kill", "mcp:tok1"))
        assertFalse(ToolPermissionManager.needsConfirm("system.jobs.list", "mcp:tok1"))
        assertTrue(ToolPermissionManager.mcpVisibleTools().contains("system.jobs.kill"))
        assertTrue(ToolPermissionManager.mcpVisibleTools().contains("system.jobs.list"))
    }

    @Test
    fun `linux file image read allowed for mcp`() {
        assertEquals(ToolPermissionManager.Level.MCP_ALLOWED, ToolPermissionManager.levelFor("linux.file.image.read", "mcp:tok1"))
        assertTrue(ToolPermissionManager.isAllowedFor("linux.file.image.read", "mcp:tok1"))
        assertTrue(ToolPermissionManager.mcpVisibleTools().contains("linux.file.image.read"))
    }

    @Test
    fun `memory tools local allowed but local only for mcp`() {
        for (tool in listOf("memory_write", "memory_get")) {
            assertEquals(ToolPermissionManager.Level.MCP_ALLOWED, ToolPermissionManager.levelFor(tool, "local_agent"))
            assertEquals(ToolPermissionManager.Level.LOCAL_ONLY, ToolPermissionManager.levelFor(tool, "mcp:tok1"))
            assertFalse(ToolPermissionManager.isAllowedFor(tool, "mcp:tok1"))
            assertFalse(ToolPermissionManager.mcpVisibleTools().contains(tool))
        }
    }

    @Test
    fun `agent ralph is local only for mcp`() {
        assertEquals(ToolPermissionManager.Level.MCP_ALLOWED, ToolPermissionManager.levelFor("agent.ralph", "local_agent"))
        assertEquals(ToolPermissionManager.Level.LOCAL_ONLY, ToolPermissionManager.levelFor("agent.ralph", "mcp:tok1"))
        assertFalse(ToolPermissionManager.isAllowedFor("agent.ralph", "mcp:tok1"))
        assertFalse(ToolPermissionManager.mcpVisibleTools().contains("agent.ralph"))
    }

    @Test
    fun `new privacy tools require mcp confirmation`() {
        for (tool in listOf(
            "android.calendar.read", "android.contacts.search", "android.location.get",
            "android.wifi.info", "android.wifi.scan", "android.bluetooth.status",
            "android.app.usage", "android.media.images", "android.media.info",
        )) {
            assertEquals(ToolPermissionManager.Level.MCP_CONFIRM, ToolPermissionManager.levelFor(tool, "mcp:tok1"))
        }
    }

    @Test
    fun `browser use confirm for mcp`() {
        for (tool in listOf("browser_use", "android.browser")) {
            assertEquals(ToolPermissionManager.Level.MCP_ALLOWED, ToolPermissionManager.levelFor(tool, "local_agent"))
            assertEquals(ToolPermissionManager.Level.MCP_CONFIRM, ToolPermissionManager.levelFor(tool, "mcp:tok1"))
            assertTrue(ToolPermissionManager.needsConfirm(tool, "mcp:tok1"))
            assertFalse(ToolPermissionManager.needsConfirm(tool, "local_agent"))
            assertTrue(ToolPermissionManager.mcpVisibleTools().contains(tool))
        }
    }
}
