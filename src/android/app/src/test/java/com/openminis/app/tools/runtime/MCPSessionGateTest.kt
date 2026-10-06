package com.openminis.app.tools.runtime

import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.mcp.client.MCPClientCodec
import com.openminis.app.mcp.client.MCPClientSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A server switched off for one session must not be reachable from that session, whatever tool list
 * the model saw; other sessions keep it. The session here is never connected, so a call that gets
 * past the gate fails in the transport with a different message, which is how the test tells the two
 * apart without a network.
 */
class MCPSessionGateTest {
    private val config = MCPRepository.MCPServerConfig(id = "docs one", url = "http://127.0.0.1:9/mcp")
    private val tool = MCPClientCodec.RemoteTool("search", "Search docs", null)

    private fun handler(enabledIn: Set<String>) = MCPToolHandler(
        serverId = "docs_one",
        remoteTool = tool,
        session = MCPClientSession(config),
        configId = config.id,
        enabledInSession = { it in enabledIn },
    )

    private fun call(handler: MCPToolHandler, sessionId: String) = runBlocking {
        handler.execute("""{"query":"x"}""", sessionId, TestContext.dummy(), "call-1")
    }

    @Test
    fun `a session that switched the server off cannot call its tools`() {
        val result = call(handler(enabledIn = setOf("session-b")), "session-a")
        assertFalse(result.success)
        assertTrue(result.output, result.output.contains("switched off for this session"))
        assertTrue("the gate names the configured id", result.output.contains("docs one"))
    }

    @Test
    fun `a session that keeps the server on reaches it`() {
        val result = call(handler(enabledIn = setOf("session-b")), "session-b")
        assertFalse("not connected, so the call itself fails", result.success)
        assertFalse("but not because of the session gate", result.output.contains("switched off"))
    }
}
