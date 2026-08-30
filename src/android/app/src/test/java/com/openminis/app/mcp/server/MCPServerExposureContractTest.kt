package com.openminis.app.mcp.server

import com.openminis.app.tools.runtime.ToolPermissionManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MCPServerExposureContractTest {

    @Before
    fun reset() {
        MCPServerManager.stop()
        TokenStore.setInMemoryForTest(emptyList())
        MCPServerManager.refreshConfigured()
    }

    @After
    fun cleanup() {
        MCPServerManager.stop()
        TokenStore.setInMemoryForTest(emptyList())
        MCPServerManager.refreshConfigured()
    }

    @Test
    fun `endpoint remains loopback-only streamable HTTP path`() {
        assertEquals("127.0.0.1", MCPServerManager.HOST)
        assertEquals(18789, MCPServerManager.PORT)
        assertEquals("/mcp", MCPServerManager.PATH)
        assertEquals("http://127.0.0.1:18789/mcp", MCPServerManager.endpointUrl())
    }

    @Test
    fun `generated settings token is random and explicitly scoped to direct allowed tools`() {
        val first = MCPServerManager.createOrRotateManagedToken()
        assertNotNull(first)
        first!!
        assertTrue(first.token.matches(Regex("[0-9a-f]{64}")))
        assertTrue(first.scope.isNotEmpty())
        assertTrue(
            first.scope.all {
                ToolPermissionManager.levelFor("mcp:${first.id}".let { _ -> it }, "mcp:${first.id}") !=
                    ToolPermissionManager.Level.MCP_CONFIRM
            },
        )
        assertTrue(
            first.scope.all {
                ToolPermissionManager.levelFor(it, "mcp:${first.id}") ==
                    ToolPermissionManager.Level.MCP_ALLOWED
            },
        )
        assertTrue(MCPServerManager.status().configured)

        val second = MCPServerManager.createOrRotateManagedToken()
        assertNotNull(second)
        assertNotEquals(first.token, second!!.token)
        assertEquals(first.id, second.id)
    }

    @Test
    fun `scope filter hides tools outside a restricted token`() {
        assertTrue(MCPServer.scopeAllows(emptySet(), "system.info"))
        val restricted = setOf("system.info")
        assertTrue(MCPServer.scopeAllows(restricted, "system.info"))
        assertFalse(MCPServer.scopeAllows(restricted, "linux.shell"))
    }

    @Test
    fun `managed scope cannot be emptied into legacy unrestricted semantics`() {
        val token = MCPServerManager.createOrRotateManagedToken()
        assertNotNull(token)
        assertFalse(MCPServerManager.updateManagedTokenScope(emptySet()))
        assertEquals(token!!.scope, MCPServerManager.managedToken()!!.scope)
    }

    @Test
    fun `revoking last managed token closes configured gate`() {
        assertNotNull(MCPServerManager.createOrRotateManagedToken())
        assertTrue(MCPServerManager.status().configured)
        assertTrue(MCPServerManager.revokeManagedToken())
        assertFalse(MCPServerManager.status().configured)
        assertFalse(MCPServerManager.running)
    }
}
