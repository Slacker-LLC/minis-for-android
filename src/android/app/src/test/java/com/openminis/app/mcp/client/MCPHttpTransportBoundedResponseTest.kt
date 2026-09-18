package com.openminis.app.mcp.client

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * [T-android-mcp-bounded-response] A remote MCP server is untrusted input: one
 * reply must not be able to make the client buffer an arbitrarily large body.
 * These cases pin both rejection paths (declared length and streamed length)
 * and keep the normal JSON / SSE replies working.
 */
class MCPHttpTransportBoundedResponseTest {

    private fun client() = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .build()

    private fun oversizedBody(): String {
        val payload = "x".repeat(64 * 1024)
        val builder = StringBuilder()
        builder.append("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"padding\":\"")
        while (builder.length <= MCPHttpTransport.MAX_RESPONSE_BYTES) {
            builder.append(payload)
        }
        builder.append("\"}}")
        return builder.toString()
    }

    @Test
    fun declaredOversizedResponseIsRejectedBeforeReadingTheBody() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(oversizedBody()))
        server.start()
        val transport = MCPHttpTransport(server.url("/mcp").toString(), client = client())

        try {
            val failure = runCatching {
                transport.send(JSONObject("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
            }.exceptionOrNull()

            assertTrue("expected MCPTransportException, got $failure", failure is MCPTransportException)
            assertTrue(
                "rejection must name the declared size limit, got ${failure?.message}",
                failure?.message?.contains("declares") == true,
            )
        } finally {
            transport.close()
            server.shutdown()
        }
    }

    @Test
    fun chunkedOversizedResponseIsRejectedWhileStreaming() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setChunkedBody(oversizedBody(), 8 * 1024))
        server.start()
        val transport = MCPHttpTransport(server.url("/mcp").toString(), client = client())

        try {
            val failure = runCatching {
                transport.send(JSONObject("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
            }.exceptionOrNull()

            assertTrue("expected MCPTransportException, got $failure", failure is MCPTransportException)
            assertTrue(
                "streamed rejection must name the byte limit, got ${failure?.message}",
                failure?.message?.contains("exceeds") == true,
            )
        } finally {
            transport.close()
            server.shutdown()
        }
    }

    @Test
    fun normalJsonReplyStillParses() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[]}}",
            ),
        )
        server.start()
        val transport = MCPHttpTransport(server.url("/mcp").toString(), client = client())

        try {
            val reply = transport.send(
                JSONObject("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"),
            )
            assertEquals(1, reply.optInt("id"))
        } finally {
            transport.close()
            server.shutdown()
        }
    }

    @Test
    fun normalSseReplyStillParses() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"tools\":[]}}\n\n"),
        )
        server.start()
        val transport = MCPHttpTransport(server.url("/mcp").toString(), client = client())

        try {
            val reply = transport.send(
                JSONObject("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/list\"}"),
            )
            assertEquals(7, reply.optInt("id"))
        } finally {
            transport.close()
            server.shutdown()
        }
    }
}
