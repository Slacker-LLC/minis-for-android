package com.openminis.app.mcp.client

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * [T-mcp-param-headers-android] The wiring end of the ported `x-mcp-header` support
 * (Mangi-11/Eta @ c15de97): the headers a tool parameter produced really do leave on the
 * `tools/call` request, and they cannot displace the session's own authorization — the
 * precedence Eta's client keeps by applying parameter headers before `applyAuthorization`.
 */
class MCPHttpTransportParamHeaderTest {

    private fun client() = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .build()

    @Test
    fun parameterHeadersRideTheCallAndLoseToTheSessionToken() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":{}}"""))
        server.start()
        val transport = MCPHttpTransport(
            url = server.url("/mcp").toString(),
            headers = mapOf("X-Config" to "from-config"),
            bearerToken = "session-token",
            client = client(),
        )
        try {
            transport.send(
                frame = MCPClientCodec.buildToolsCall("lookup", JSONObject().put("apiKey", "s3cret"), 1),
                extraHeaders = mapOf(
                    "Mcp-Param-X-Api-Key" to "s3cret",
                    "Authorization" to "Bearer from-a-tool-parameter",
                ),
            )

            val recorded = server.takeRequest()
            assertEquals("s3cret", recorded.getHeader("Mcp-Param-X-Api-Key"))
            assertEquals("from-config", recorded.getHeader("X-Config"))
            assertEquals("Bearer session-token", recorded.getHeader("Authorization"))
            assertEquals("tools/call", JSONObject(recorded.body.readUtf8()).getString("method"))
        } finally {
            transport.close()
            server.shutdown()
        }
    }

    @Test
    fun aCallWithoutParameterHeadersSendsNone() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":{}}"""))
        server.start()
        val transport = MCPHttpTransport(
            url = server.url("/mcp").toString(),
            client = client(),
        )
        try {
            transport.send(MCPClientCodec.buildToolsList(null))

            val recorded = server.takeRequest()
            assertEquals(null, recorded.getHeader("Mcp-Param-X-Api-Key"))
        } finally {
            transport.close()
            server.shutdown()
        }
    }
}
