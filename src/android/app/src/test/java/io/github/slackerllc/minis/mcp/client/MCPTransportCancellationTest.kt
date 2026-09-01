package io.github.slackerllc.minis.mcp.client

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class MCPTransportCancellationTest {

    @Test
    fun hangingHttpRequestTimesOutAndCleansActiveCall() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        val client = OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(150, TimeUnit.MILLISECONDS)
            .writeTimeout(1, TimeUnit.SECONDS)
            .build()
        val transport = MCPHttpTransport(server.url("/mcp").toString(), client = client)

        try {
            val failure = runCatching {
                withTimeout(3_000) {
                    transport.send(JSONObject("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                }
            }.exceptionOrNull()

            assertTrue(failure is MCPTransportException)
            assertEquals(
                MCPTransportFailureKind.TRANSPORT_TIMEOUT,
                (failure as MCPTransportException).kind,
            )
            withTimeout(2_000) {
                while (transport.activeCallCountForTest() != 0) delay(10)
            }
            assertEquals(0, transport.activeCallCountForTest())
        } finally {
            transport.close()
            server.shutdown()
        }
    }

    @Test
    fun coroutineCancellationCancelsHangingHttpCallAndCleansIt() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        val transport = MCPHttpTransport(server.url("/mcp").toString())

        try {
            val request = async {
                transport.send(JSONObject("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}"))
            }
            withTimeout(2_000) {
                while (transport.activeCallCountForTest() == 0) delay(10)
            }

            request.cancelAndJoin()
            withTimeout(2_000) {
                while (transport.activeCallCountForTest() != 0) delay(10)
            }
            assertTrue(request.isCancelled)
            assertEquals(0, transport.activeCallCountForTest())
        } finally {
            transport.close()
            server.shutdown()
        }
    }

    @Test
    fun coroutineCancellationDestroysHangingStdioProcess() = runBlocking {
        val shell = File("/bin/sh")
        val tail = File("/usr/bin/tail").takeIf { it.canExecute() } ?: File("/bin/tail")
        assumeTrue(shell.canExecute() && tail.canExecute())

        val transport = MCPStdioTransport(
            command = shell.absolutePath,
            args = listOf("-c", "exec ${tail.absolutePath} -f /dev/null"),
        )
        transport.start()
        assertTrue(transport.hasLiveProcessForTest())

        try {
            val request = async {
                transport.send(JSONObject("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}"))
            }
            delay(100)
            request.cancelAndJoin()
            withTimeout(2_000) {
                while (transport.hasLiveProcessForTest()) delay(10)
            }

            assertTrue(request.isCancelled)
            assertFalse(transport.hasLiveProcessForTest())
        } finally {
            transport.close()
        }
    }
}
