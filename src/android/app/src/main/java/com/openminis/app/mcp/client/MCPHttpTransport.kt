package com.openminis.app.mcp.client

import kotlinx.coroutines.suspendCancellableCoroutine
import okio.Buffer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

/**
 * Streamable HTTP transport for the MCP client (spec 2025-06-18).
 * One POST per JSON-RPC frame; coroutine cancellation calls OkHttp Call.cancel()
 * so a hanging socket is actually closed instead of merely abandoning a waiter.
 */
class MCPHttpTransport(
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
    private val bearerToken: String? = null,
    private val client: OkHttpClient = defaultClient(),
) {

    companion object {
        private val JSON = "application/json".toMediaType()

        /** [T-android-mcp-bounded-response] Upper bound for one MCP HTTP reply. */
        internal const val MAX_RESPONSE_BYTES = 4L * 1024 * 1024

        private const val READ_CHUNK_BYTES = 8 * 1024L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    @Volatile
    private var sessionId: String? = null
    private val activeCalls = ConcurrentHashMap.newKeySet<Call>()

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

        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(reqBuilder.build())
            activeCalls += call
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    activeCalls -= call
                    if (!continuation.isActive) return
                    val timeout = e is SocketTimeoutException || e.cause is SocketTimeoutException
                    val error = MCPTransportException(
                        message = if (timeout) "MCP HTTP transport timed out: ${e.message}" else "MCP HTTP transport failed: ${e.message}",
                        kind = if (timeout) MCPTransportFailureKind.TRANSPORT_TIMEOUT else MCPTransportFailureKind.TRANSPORT_FAILURE,
                    )
                    continuation.resumeWith(Result.failure(error))
                }

                override fun onResponse(call: Call, response: Response) {
                    activeCalls -= call
                    if (!continuation.isActive) {
                        response.close()
                        return
                    }
                    try {
                        response.use { resp ->
                            val code = resp.code
                            val raw = readBoundedBody(resp)
                            if (code == 404 || code == 405) {
                                throw MCPTransportException("server does not support Streamable HTTP (HTTP $code): $raw")
                            }
                            if (code !in 200..299) {
                                throw MCPTransportException("HTTP $code: ${raw.take(200)}")
                            }
                            resp.header("Mcp-Session-Id")?.takeIf { it.isNotBlank() }?.let { sessionId = it }
                            continuation.resumeWith(Result.success(parseBody(raw, resp.header("Content-Type"))))
                        }
                    } catch (t: Throwable) {
                        if (continuation.isActive) continuation.resumeWith(Result.failure(t))
                    }
                }
            })
        }
    }

    private fun parseBody(raw: String, contentType: String?): JSONObject {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return JSONObject()
        if (trimmed.startsWith("{")) return JSONObject(trimmed)
        val dataLines = mutableListOf<String>()
        for (line in trimmed.lines()) {
            if (line.startsWith("data:")) dataLines.add(line.removePrefix("data:").trim())
        }
        if (dataLines.isEmpty()) {
            throw MCPTransportException("unrecognized MCP response body: ${trimmed.take(120)}")
        }
        for (line in dataLines.asReversed()) {
            val t = line.trim()
            if (t.startsWith("{")) return JSONObject(t)
        }
        throw MCPTransportException("no JSON-RPC frame in SSE body: ${trimmed.take(120)}")
    }

    /**
     * [T-android-mcp-bounded-response] Reads one MCP reply with a hard byte
     * budget and fails closed past it.
     *
     * A remote MCP server is untrusted input: without a bound, a single reply
     * could make the client buffer an arbitrarily large body in memory. The
     * declared Content-Length is checked first, then the streamed body is
     * counted chunk by chunk, so a chunked reply without a length cannot
     * bypass the budget either.
     */
    private fun readBoundedBody(response: Response): String {
        val body = response.body ?: return ""
        val declared = body.contentLength()
        if (declared > MAX_RESPONSE_BYTES) {
            throw MCPTransportException(
                "MCP response declares $declared bytes, over the $MAX_RESPONSE_BYTES byte limit",
            )
        }
        val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        val source = body.source()
        val buffer = Buffer()
        var total = 0L
        while (true) {
            val read = source.read(buffer, READ_CHUNK_BYTES)
            if (read == -1L) break
            total += read
            if (total > MAX_RESPONSE_BYTES) {
                throw MCPTransportException(
                    "MCP response exceeds the $MAX_RESPONSE_BYTES byte limit",
                )
            }
        }
        return buffer.readString(charset)
    }

    fun close() {
        activeCalls.toList().forEach(Call::cancel)
        activeCalls.clear()
    }

    internal fun activeCallCountForTest(): Int = activeCalls.size
}

enum class MCPTransportFailureKind {
    TRANSPORT_TIMEOUT,
    PROCESS_KILLED,
    TRANSPORT_FAILURE,
}

class MCPTransportException(
    message: String,
    val kind: MCPTransportFailureKind = MCPTransportFailureKind.TRANSPORT_FAILURE,
) : Exception(message)
