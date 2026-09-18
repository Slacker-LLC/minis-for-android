package com.openminis.app.mcp.client

import com.openminis.app.data.repository.MCPRepository
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-mcp-tool-bounds] Discovery has to end. The page guard alone lets a
 * server stream pages forever, and a huge tool set lands in every provider
 * request, so the total is capped and the refusal names the limit.
 */
class MCPClientSessionDiscoveryLimitTest {

    private fun toolsReply(count: Int, nextCursor: String? = null): String {
        val tools = JSONArray()
        for (index in 0 until count) {
            tools.put(JSONObject().put("name", "tool_$index"))
        }
        val result = JSONObject().put("tools", tools)
        if (nextCursor != null) result.put("nextCursor", nextCursor)
        return JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("result", result)
            .toString()
    }

    private fun queueHandshake(server: MockWebServer) {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"jsonrpc":"2.0","id":0,"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"mock","version":"1"}}}""",
                ),
        )
        // notifications/initialized still occupies a request slot.
        server.enqueue(MockResponse().setResponseCode(202))
    }

    @Test
    fun `more tools than the cap fails closed`() = runBlocking {
        val server = MockWebServer()
        queueHandshake(server)
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(toolsReply(MCPClientSession.MAX_DISCOVERED_TOOLS + 1)),
        )
        server.start()
        val session = MCPClientSession(
            MCPRepository.MCPServerConfig(id = "mock", url = server.url("/mcp").toString()),
        )

        try {
            session.connect()
            val failure = runCatching { session.listTools() }.exceptionOrNull()

            assertTrue("expected MCPTransportException, got $failure", failure is MCPTransportException)
            assertTrue(
                "refusal must name the cap, got ${failure?.message}",
                failure?.message?.contains("more than ${MCPClientSession.MAX_DISCOVERED_TOOLS} tools") == true,
            )
        } finally {
            session.close()
            server.shutdown()
        }
    }

    @Test
    fun `exactly the cap is still accepted`() = runBlocking {
        val server = MockWebServer()
        queueHandshake(server)
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(toolsReply(MCPClientSession.MAX_DISCOVERED_TOOLS)),
        )
        server.start()
        val session = MCPClientSession(
            MCPRepository.MCPServerConfig(id = "mock", url = server.url("/mcp").toString()),
        )

        try {
            session.connect()
            val tools = session.listTools()
            assertEquals(MCPClientSession.MAX_DISCOVERED_TOOLS, tools.size)
        } finally {
            session.close()
            server.shutdown()
        }
    }
}
