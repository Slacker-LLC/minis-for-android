package com.openminis.app.mcp.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Streamable HTTP transport for the MCP client (spec 2025-06-18).
 * One POST per JSON-RPC frame; accepts `application/json` or
 * `text/event-stream` responses (SSE `data:` lines are unwrapped).
 * Maintains the optional `Mcp-Session-Id` header returned by the server.
 */
class MCPHttpTransport(
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
    private val bearerToken: String? = null,
    private val client: OkHttpClient = defaultClient(),
) {

    companion object {
        private val JSON = "application/json".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    @Volatile
    private var sessionId: String? = null

    suspend fun send(frame: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val body = MCPClientCodec.encodeFrame(frame).toRequestBody(JSON)
        val reqBuilder = Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")
            .post(body)
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }
        if (!bearerToken.isNullOrBlank()) {
            reqBuilder.header("Authorization", "Bearer $bearerToken")
        }
        sessionId?.let { reqBuilder.header("Mcp-Session-Id", it) }

        client.newCall(reqBuilder.build()).execute().use { resp ->
            val code = resp.code
            val raw = resp.body?.string().orEmpty()
            if (code == 404 || code == 405) {
                // Spec: server without streamable HTTP returns 404/405 —
                // client must fall back to SSE transport; we surface a clear error.
                throw MCPTransportException(
                    "server does not support Streamable HTTP (HTTP $code): $raw",
                )
            }
            if (code !in 200..299) {
                throw MCPTransportException("HTTP $code: ${raw.take(200)}")
            }
            resp.header("Mcp-Session-Id")?.takeIf { it.isNotBlank() }?.let { sessionId = it }
            parseBody(raw, resp.header("Content-Type"))
        }
    }

    /** JSON body or SSE stream: unwrap `data:` lines and join them. */
    private fun parseBody(raw: String, contentType: String?): JSONObject {
        val trimmed = raw.trim()
        // Empty body is valid for notifications (HTTP 202/204) — return an
        // empty frame; callers of request/response methods will reject it.
        if (trimmed.isEmpty()) return JSONObject()
        if (trimmed.startsWith("{")) {
            return JSONObject(trimmed)
        }
        // SSE: collect data: lines
        val dataLines = mutableListOf<String>()
        for (line in trimmed.lines()) {
            when {
                line.startsWith("data:") -> dataLines.add(line.removePrefix("data:").trim())
            }
        }
        if (dataLines.isEmpty()) {
            throw MCPTransportException("unrecognized MCP response body: ${trimmed.take(120)}")
        }
        // The final data line of a JSON-RPC response carries the frame.
        for (line in dataLines.asReversed()) {
            val t = line.trim()
            if (t.startsWith("{")) return JSONObject(t)
        }
        throw MCPTransportException("no JSON-RPC frame in SSE body: ${trimmed.take(120)}")
    }

    fun close() {
        // OkHttp pools connections per client; nothing to release eagerly.
    }
}

class MCPTransportException(message: String) : Exception(message)
