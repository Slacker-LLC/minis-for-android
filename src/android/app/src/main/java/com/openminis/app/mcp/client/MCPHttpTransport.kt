package com.openminis.app.mcp.client

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
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

    suspend fun send(frame: JSONObject): JSONObject {
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
        val request = reqBuilder.build()

        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (!continuation.isActive) {
                            response.close()
                            return
                        }
                        try {
                            response.use { resp ->
                                val code = resp.code
                                val raw = resp.body?.string().orEmpty()
                                if (code == 404 || code == 405) {
                                    throw MCPTransportException(
                                        "server does not support Streamable HTTP (HTTP $code): $raw",
                                    )
                                }
                                if (code !in 200..299) {
                                    throw MCPTransportException("HTTP $code: ${raw.take(200)}")
                                }
                                resp.header("Mcp-Session-Id")
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let { sessionId = it }
                                continuation.resume(parseBody(raw, resp.header("Content-Type")))
                            }
                        } catch (t: Throwable) {
                            if (continuation.isActive) continuation.resumeWithException(t)
                        }
                    }
                },
            )
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
        // Requests are individually cancellable through coroutine cancellation;
        // OkHttp pools idle connections per client, so no transport-wide close is needed.
    }
}

class MCPTransportException(message: String) : Exception(message)
