package com.openminis.app.tools.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BotToolPermissionTest {
    @Test
    fun botCoordinationIsLocalOnly() {
        for (tool in listOf("delegate_bot", "list_bots", "check_delegation")) {
            assertEquals(ToolPermissionManager.Level.MCP_ALLOWED, ToolPermissionManager.levelFor(tool, ToolPermissionManager.CALLER_LOCAL))
            assertEquals(ToolPermissionManager.Level.LOCAL_ONLY, ToolPermissionManager.levelFor(tool, "mcp:remote"))
            assertTrue(ToolPermissionManager.isAllowedFor(tool, ToolPermissionManager.CALLER_LOCAL))
            assertFalse(ToolPermissionManager.isAllowedFor(tool, "mcp:remote"))
            assertFalse(ToolPermissionManager.mcpVisibleTools().contains(tool))
        }
    }
}
