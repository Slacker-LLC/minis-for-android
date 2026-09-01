package com.openminis.app.mcp.server

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.Socket

/**
 * [T-android-mcp-server] End-to-end JVM coverage for [MCPServer]: real
 * ServerSocket on 127.0.0.1, okhttp client, initialize / 401 / 404 paths.
 * tools/call needs an Android Context (ToolExecutor), so it's left to
 * instrumented tests.
 */
class MCPServerTest {

    private var port = 0
    private var server: MCPServer? = null
    private val client = OkHttpClient()

    @Before
    fun setUp() {
        TokenStore.setInMemoryForTest(
            listOf(TokenStore.Token(id = "t1", token = "secret-token")),
        )
        com.openminis.app.tools.runtime.ToolRegistry.register(
            com.openminis.app.tools.runtime.LinuxShellHandler(),
            aliasNames = listOf("shell_execute"),
        )
        // A fixed port is flaky across back-to-back JVM tests because a just-closed
        // loopback socket can still make the next bind fail with EADDRINUSE. Reserve
        // an ephemeral loopback port for this test case, then let MCPServer bind it.
        port = java.net.ServerSocket(
            0,
            1,
            java.net.InetAddress.getByName("127.0.0.1"),
        ).use { it.localPort }
        server = MCPServer(null, port).also { it.start() }
        assertTrue("MCPServer failed to bind test port $port", server!!.isRunning)
    }

    @After
    fun tearDown() {
        server?.stop()
        com.openminis.app.tools.runtime.ToolRegistry.unregister("linux.shell")
        TokenStore.setInMemoryForTest(emptyList())
    }

    @Test
    fun `initialize returns pinned protocol version`() {
        val body = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}"""
        val resp = post(body, token = "secret-token")
        assertEquals(200, resp.code)
        val text = resp.body!!.string()
        assertTrue(text.contains("\"protocolVersion\":\"2025-06-18\""))
        assertTrue(text.contains("\"serverInfo\""))
    }

    @Test
    fun `UTF-8 request body is framed in bytes`() {
        val body = """{"jsonrpc":"2.0","id":18,"method":"initialize","params":{"instructions":"你好"}}"""

        val resp = post(body, token = "secret-token")

        assertEquals(200, resp.code)
        assertTrue(resp.body!!.string().contains("\"serverInfo\""))
    }

    @Test
    fun `truncated request body is rejected without dispatch`() {
        val body = """{"jsonrpc":"2.0","id":19,"method":"ping"}""".toByteArray(Charsets.UTF_8)
        val requestHead = "POST /mcp HTTP/1.1\r\n" +
            "Authorization: Bearer secret-token\r\n" +
            "Content-Length: ${body.size + 1}\r\n\r\n"

        val response = Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 2_000
            val output = socket.getOutputStream()
            output.write(requestHead.toByteArray(Charsets.US_ASCII))
            output.write(body)
            output.flush()
            socket.shutdownOutput()
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }

        assertTrue(response.startsWith("HTTP/1.1 400 Bad Request"))
        assertTrue(response.contains("\"error\":\"bad request\""))
    }

    @Test
    fun `missing or wrong token gets 401`() {
        val body = """{"jsonrpc":"2.0","id":2,"method":"ping"}"""
        val respNoToken = post(body, token = null)
        assertEquals(401, respNoToken.code)
        val respWrong = post(body, token = "nope")
        assertEquals(401, respWrong.code)
    }

    @Test
    fun `wrong path gets 404`() {
        val body = """{"jsonrpc":"2.0","id":3,"method":"ping"}"""
        val req = Request.Builder()
            .url("http://127.0.0.1:$port/not-mcp")
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer secret-token")
            .build()
        client.newCall(req).execute().use { assertEquals(404, it.code) }
    }

    @Test
    fun `ping returns empty result`() {
        val body = """{"jsonrpc":"2.0","id":4,"method":"ping"}"""
        val resp = post(body, token = "secret-token")
        assertEquals(200, resp.code)
        assertTrue(resp.body!!.string().contains("\"result\":{}"))
    }

    @Test
    fun `CONFIRM tool without confirm_id gets confirm_required`() {
        val body = """{"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"android.app.force_stop","arguments":{}}}"""
        val resp = post(body, token = "secret-token")
        assertEquals(200, resp.code)
        val json = org.json.JSONObject(resp.body!!.string())
        val err = json.getJSONObject("error")
        assertEquals(-32001, err.getInt("code"))
        assertEquals("confirm_required", err.getString("message"))
        val data = err.getJSONObject("data")
        assertTrue(data.getString("confirm_id").isNotEmpty())
        assertEquals(120_000L, data.getLong("expires_in_ms"))
    }

    @Test
    fun `linux tool with Ubuntu down answers unavailable without burning a confirm`() {
        val body = """{"jsonrpc":"2.0","id":16,"method":"tools/call","params":{"name":"linux.shell","arguments":{"cmd":"echo hi"}}}"""
        val text = post(body, token = "secret-token").body!!.string()
        assertFalse(text.contains("confirm_required"))
        assertFalse(text.contains("-32001"))
        assertTrue(text.contains("ubuntu_runtime_unavailable"))
    }

    @Test
    fun `legacy alias uses canonical policy before MCP gate`() {
        val body = """{"jsonrpc":"2.0","id":17,"method":"tools/call","params":{"name":"shell_execute","arguments":{"cmd":"echo hi"}}}"""
        val text = post(body, token = "secret-token").body!!.string()
        assertTrue(text.contains("ubuntu_runtime_unavailable"))
        assertFalse(text.contains("permission_denied"))
    }

    @Test
    fun `valid confirm_id proceeds to execution and cannot be reused`() {
        val issueBody = """{"jsonrpc":"2.0","id":11,"method":"tools/call","params":{"name":"android.app.force_stop","arguments":{}}}"""
        val issued = org.json.JSONObject(post(issueBody, token = "secret-token").body!!.string())
        val confirmId = issued.getJSONObject("error").getJSONObject("data").getString("confirm_id")

        // User approves first — the notification path (McpConfirmReceiver) answers
        // the server's queue via ConfirmQueue.shared.
        assertEquals(ConfirmQueue.Result.OK, ConfirmQueue.shared!!.approve(confirmId, "android.app.force_stop"))

        // consume OK → enters the execution path (result frame, no confirm_required)
        val execBody = """{"jsonrpc":"2.0","id":12,"method":"tools/call","params":{"name":"android.app.force_stop","arguments":{},"confirm_id":"$confirmId"}}"""
        val execText = post(execBody, token = "secret-token").body!!.string()
        assertFalse(execText.contains("confirm_required"))
        assertTrue(org.json.JSONObject(execText).has("result"))

        // same id again → REUSED
        val replayBody = """{"jsonrpc":"2.0","id":13,"method":"tools/call","params":{"name":"android.app.force_stop","arguments":{},"confirm_id":"$confirmId"}}"""
        val replay = org.json.JSONObject(post(replayBody, token = "secret-token").body!!.string())
        val err = replay.getJSONObject("error")
        assertEquals(-32002, err.getInt("code"))
        assertEquals("confirm_rejected", err.getString("message"))
        assertEquals("reused", err.getJSONObject("data").getString("reason"))
    }

    @Test
    fun `unknown confirm_id gets confirm_rejected`() {
        val body = """{"jsonrpc":"2.0","id":14,"method":"tools/call","params":{"name":"android.app.force_stop","arguments":{},"confirm_id":"c-nope"}}"""
        val json = org.json.JSONObject(post(body, token = "secret-token").body!!.string())
        val err = json.getJSONObject("error")
        assertEquals(-32002, err.getInt("code"))
        assertEquals("confirm_rejected", err.getString("message"))
        assertEquals("unknown", err.getJSONObject("data").getString("reason"))
    }

    @Test
    fun `ALLOWED tool executes without confirm`() {
        val body = """{"jsonrpc":"2.0","id":15,"method":"tools/call","params":{"name":"android.capabilities","arguments":{}}}"""
        val text = post(body, token = "secret-token").body!!.string()
        assertTrue(org.json.JSONObject(text).has("result"))
        assertFalse(text.contains("confirm_required"))
        assertFalse(text.contains("-32001"))
    }

    private fun post(body: String, token: String?): okhttp3.Response {
        val b = Request.Builder()
            .url("http://127.0.0.1:$port/mcp")
            .post(body.toRequestBody("application/json".toMediaType()))
        if (token != null) b.header("Authorization", "Bearer $token")
        return client.newCall(b.build()).execute()
    }
}
