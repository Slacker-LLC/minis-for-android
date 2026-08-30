package io.github.slackerllc.minis.tools

import io.github.slackerllc.minis.tools.runtime.ToolPermissionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the telephony tool registrations: canonical names registered in the
 * permission table with MCP_CONFIRM (privacy-sensitive), alias canonicalization
 * wired by the registry, and handler definitions carry the expected params.
 */
class AndroidTelephonyToolsTest {

    @Test
    fun `telephony tools are confirm-gated for mcp`() {
        assertEquals(
            ToolPermissionManager.Level.MCP_CONFIRM,
            ToolPermissionManager.levelFor("android.sms.read", "mcp:tok"),
        )
        assertEquals(
            ToolPermissionManager.Level.MCP_CONFIRM,
            ToolPermissionManager.levelFor("android.call_log.read", "mcp:tok"),
        )
        assertEquals(
            ToolPermissionManager.Level.MCP_ALLOWED,
            ToolPermissionManager.levelFor("android.sms.read", ToolPermissionManager.CALLER_LOCAL),
        )
        assertTrue(ToolPermissionManager.isRegistered("android.sms.read"))
        assertTrue(ToolPermissionManager.isRegistered("android.call_log.read"))
    }

    @Test
    fun `handler definitions describe the canonical tools`() {
        val sms = AndroidSmsReadHandler().definition
        assertEquals("android.sms.read", sms.name)
        assertTrue(sms.parameters.containsKey("folder"))
        assertTrue(sms.parameters.containsKey("limit"))

        val callLog = AndroidCallLogReadHandler().definition
        assertEquals("android.call_log.read", callLog.name)
        assertTrue(callLog.parameters.containsKey("min_duration_sec"))
    }
}
