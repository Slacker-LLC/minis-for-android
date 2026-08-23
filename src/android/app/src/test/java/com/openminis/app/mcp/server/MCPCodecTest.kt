package com.openminis.app.mcp.server

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-mcp-server] Pure-logic coverage for the MCP server codec:
 * JSON-RPC parse/response framing, error codes, and the pinned
 * 2025-06-18 initialize / tools shapes.
 */
class MCPCodecTest {

    // ── parseRequest ───────────────────────────────────────────────────

    @Test
    fun `parse initialize frame`() {
        val req = MCPCodec.parseRequest(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"x","version":"1"}}}""",
        )!!
        assertEquals(1, req.id) // Int id preserved
        assertEquals("initialize", req.method)
        assertEquals("2025-06-18", req.params.getString("protocolVersion"))
    }

    @Test
    fun `parse id variants and absent params`() {
        val stringId = MCPCodec.parseRequest("""{"jsonrpc":"2.0","id":"abc","method":"ping"}""")!!
        assertEquals("abc", stringId.id)
        val nullId = MCPCodec.parseRequest("""{"jsonrpc":"2.0","id":null,"method":"ping"}""")!!
        assertNull(nullId.id)
        val noParams = MCPCodec.parseRequest("""{"jsonrpc":"2.0","id":7,"method":"ping"}""")!!
        assertEquals(0, noParams.params.length())
    }

    @Test
    fun `parse illegal input returns null`() {
        assertNull(MCPCodec.parseRequest("not json"))
        assertNull(MCPCodec.parseRequest("""{"jsonrpc":"1.0","id":1,"method":"ping"}""")) // unknown version
        assertNull(MCPCodec.parseRequest("""{"jsonrpc":"2.0","id":1}""")) // missing method
        assertNull(MCPCodec.parseRequest("""{"jsonrpc":"2.0","id":1,"method":"ping","params":[1]}""")) // params not object
    }

    // ── errorResponse ──────────────────────────────────────────────────

    @Test
    fun `error frame has correct jsonrpc code`() {
        val e = JSONObject(MCPCodec.errorResponse(2, MCPCodec.INVALID_REQUEST, "bad"))
        assertEquals("2.0", e.getString("jsonrpc"))
        assertEquals(2, e.getInt("id"))
        assertEquals(-32600, e.getJSONObject("error").getInt("code"))
        assertEquals("bad", e.getJSONObject("error").getString("message"))
    }

    @Test
    fun `error frame supports null id and other codes`() {
        assertTrue(JSONObject(MCPCodec.errorResponse(null, MCPCodec.METHOD_NOT_FOUND, "x")).isNull("id"))
        assertEquals(-32700, MCPCodec.PARSE_ERROR)
        assertEquals(-32601, MCPCodec.METHOD_NOT_FOUND)
        assertEquals(-32602, MCPCodec.INVALID_PARAMS)
        assertEquals(-32000, MCPCodec.INTERNAL_ERROR)
    }

    // ── response shapes ────────────────────────────────────────────────

    @Test
    fun `initialize response pins protocol version`() {
        val r = JSONObject(MCPCodec.response(1, MCPCodec.initializeResult()))
        assertEquals("2.0", r.getString("jsonrpc"))
        assertEquals(1, r.getInt("id"))
        val result = r.getJSONObject("result")
        assertEquals("2025-06-18", result.getString("protocolVersion"))
        assertFalse(result.getJSONObject("capabilities").getJSONObject("tools").getBoolean("listChanged"))
        assertEquals("minis", result.getJSONObject("serverInfo").getString("name"))
        assertEquals("0.1.0", result.getJSONObject("serverInfo").getString("version"))
    }

    @Test
    fun `tools call response has spec content shape`() {
        val r = JSONObject(MCPCodec.response("t1", MCPCodec.toolResult("done", false)))
        val result = r.getJSONObject("result")
        val content = result.getJSONArray("content")
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("done", content.getJSONObject(0).getString("text"))
        assertFalse(result.getBoolean("isError"))
        // error variant
        val err = JSONObject(MCPCodec.response("t2", MCPCodec.toolResult("boom", true))).getJSONObject("result")
        assertTrue(err.getBoolean("isError"))
        assertEquals("boom", err.getJSONArray("content").getJSONObject(0).getString("text"))
    }

    @Test
    fun `tools list and ping results`() {
        val tools = JSONArray().put(JSONObject().put("name", "echo").put("description", "d"))
        val list = JSONObject(MCPCodec.response(null, MCPCodec.toolsListResult(tools)))
        assertEquals(1, list.getJSONObject("result").getJSONArray("tools").length())
        assertTrue(list.isNull("id")) // null id must serialize as literal null, not drop the key
        val ping = JSONObject(MCPCodec.response(3, MCPCodec.pingResult()))
        assertEquals(0, ping.getJSONObject("result").length())
    }

    @Test
    fun `notifications are detected and get no response frame`() {
        assertTrue(MCPCodec.isNotification("notifications/initialized"))
        assertFalse(MCPCodec.isNotification("initialize"))
        assertTrue(MCPCodec.parseRequest("""{"jsonrpc":"2.0","method":"notifications/initialized"}""") != null)
        assertEquals("notifications/initialized", MCPCodec.METHOD_NOTIFICATIONS_INITIALIZED)
    }
}
