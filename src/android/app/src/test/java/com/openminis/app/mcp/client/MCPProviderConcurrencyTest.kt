package com.openminis.app.mcp.client

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MCPProviderConcurrencyTest {

    @Test
    fun `bounded connector runs servers concurrently without exceeding limit`() = runTest {
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)

        val results = MCPProvider.mapConcurrentBounded(
            items = (1..6).toList(),
            maxConcurrency = 2,
            timeoutMs = 1_000,
        ) { item ->
            val now = active.incrementAndGet()
            peak.updateAndGet { previous -> maxOf(previous, now) }
            try {
                delay(100)
                item * 10
            } finally {
                active.decrementAndGet()
            }
        }

        assertEquals(2, peak.get())
        assertEquals(listOf(10, 20, 30, 40, 50, 60), results.map { it.getOrThrow() })
    }

    @Test
    fun `one server timeout does not fail sibling connections`() = runTest {
        val results = MCPProvider.mapConcurrentBounded(
            items = listOf("slow", "fast"),
            maxConcurrency = 2,
            timeoutMs = 100,
        ) { item ->
            if (item == "slow") delay(1_000) else delay(10)
            item
        }

        assertTrue(results[0].exceptionOrNull() is TimeoutCancellationException)
        assertEquals("fast", results[1].getOrThrow())
    }

    @Test
    fun `http request is cancelled when coroutine times out`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{}")
                .setBodyDelay(5, TimeUnit.SECONDS),
        )
        server.start()
        try {
            val transport = MCPHttpTransport(server.url("/mcp").toString())
            val error = runCatching {
                withTimeout(100) {
                    transport.send(JSONObject().put("jsonrpc", "2.0").put("id", 1))
                }
            }.exceptionOrNull()
            assertTrue(error is TimeoutCancellationException)
        } finally {
            server.shutdown()
        }
    }
}
