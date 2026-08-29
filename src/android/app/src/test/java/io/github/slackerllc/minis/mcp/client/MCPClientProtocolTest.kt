package io.github.slackerllc.minis.mcp.client

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP client protocol tests against the 2025-06-18 spec: initialize
 * handshake (version check), tools/list pagination, tools/call shapes,
 * and the codec's pure parse paths.
 */
class MCPClientProtocolTest {

    @Test
    fun `initialize frame carries spec version and clientInfo`() {
        val f = MCPClientCodec.buildInitialize("minis-android")
        assertEquals("2.0", f.getString("jsonrpc"))
        assertEquals("initialize", f.getString("method"))
        assertEquals("2025-06-18", f.getJSONObject("params").getString("protocolVersion"))
        assertEquals("minis-android", f.getJSONObject("params").getJSONObject("clientInfo").getString("name"))
    }

    @Test
    fun `parse initialize result rejects mismatched version`() {
        val good = MCPClientCodec.parseInitializeResult(
            JSONObject(
                """{"jsonrpc":"2.0","id":0,"result":{"protocolVersion":"2025-06-18",
                "capabilities":{"tools":{}},"serverInfo":{"name":"x","version":"1"}}}""",
            ),
        )
        assertNotNull(good)
        assertEquals("x", good?.serverName)
        // error frame -> null
        assertNull(
            MCPClientCodec.parseInitializeResult(
                JSONObject("""{"jsonrpc":"2.0","id":0,"error":{"code":-32600,"message":"bad"}}"""),
            ),
        )
    }

    @Test
    fun `tools list pagination follows nextCursor`() {
        val page1 = MCPClientCodec.parseToolsList(
            JSONObject(
                """{"result":{"tools":[{"name":"a","description":"A"}],
                "nextCursor":"c2"}}""",
            ),
        )
        assertEquals(1, page1?.tools?.size)
        assertEquals("c2", page1?.nextCursor)
        val page2 = MCPClientCodec.parseToolsList(
            JSONObject("""{"result":{"tools":[{"name":"b"}]}}"""),
        )
        assertEquals(1, page2?.tools?.size)
        assertNull(page2?.nextCursor)
    }

    @Test
    fun `call result parses text content and isError`() {
        val ok = MCPClientCodec.parseCallResult(
            JSONObject(
                """{"result":{"content":[{"type":"text","text":"hi"}],"isError":false}}""",
            ),
        )
        assertEquals("hi", ok?.content)
        assertEquals(false, ok?.isError)
        val err = MCPClientCodec.parseCallResult(
            JSONObject("""{"result":{"content":[{"type":"text","text":"boom"}],"isError":true}}"""),
        )
        assertEquals(true, err?.isError)
        // rejected frame -> null
        assertNull(
            MCPClientCodec.parseCallResult(
                JSONObject("""{"error":{"code":-32602}}"""),
            ),
        )
    }

    @Test
    fun `full http session handshake list and call against MockWebServer`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setHeader("Mcp-Session-Id", "sess-1")
                .setBody(
                    """{"jsonrpc":"2.0","id":0,"result":{"protocolVersion":"2025-06-18",
                    "capabilities":{"tools":{}},"serverInfo":{"name":"mock","version":"1"}}}""",
                ),
        )
        // notifications/initialized consumes one request slot (no reply frame
        // expected, but MockWebServer needs a queued response per request).
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"jsonrpc":"2.0","id":1,"result":{"tools":[
                    {"name":"echo","description":"echoes","input_schema":{"type":"object","properties":{"text":{"type":"string"}}}}
                ]}}"""),
        )
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"jsonrpc":"2.0","id":100,"result":{"content":[{"type":"text","text":"pong"}]}}"""),
        )
        server.start()
        try {
            val cfg = io.github.slackerllc.minis.data.repository.MCPRepository.MCPServerConfig(
                id = "mock",
                url = server.url("/mcp").toString(),
            )
            val session = MCPClientSession(cfg)
            session.connect()
            assertEquals("mock", session.serverName)
            val tools = session.listTools()
            assertEquals(1, tools.size)
            assertEquals("echo", tools[0].name)
            val result = session.callTool("echo", JSONObject().put("text", "ping"))
            assertEquals("pong", result.content)
            // session id header must be sent on follow-up requests
            // (request order: initialize, notifications, tools/list, tools/call)
            server.takeRequest() // initialize
            server.takeRequest() // notifications/initialized
            val listRequest = server.takeRequest() // tools/list
            assertEquals("sess-1", listRequest.getHeader("Mcp-Session-Id"))
        } finally {
            sessionCloseSafe(server)
        }
    }

    @Test
    fun `session rejects protocol mismatch`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody(
                    """{"jsonrpc":"2.0","id":0,"result":{"protocolVersion":"2024-11-05",
                    "capabilities":{},"serverInfo":{"name":"old","version":"0"}}}""",
                ),
        )
        server.start()
        try {
            val cfg = io.github.slackerllc.minis.data.repository.MCPRepository.MCPServerConfig(
                id = "old",
                url = server.url("/mcp").toString(),
            )
            val session = MCPClientSession(cfg)
            val ex = runCatching { session.connect() }.exceptionOrNull()
            assertNotNull(ex)
            assertTrue(ex?.message?.contains("protocol mismatch") == true)
        } finally {
            server.shutdown()
        }
    }

    private fun sessionCloseSafe(server: MockWebServer) {
        server.shutdown()
    }
}
