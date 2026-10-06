package com.openminis.app.mcp.client

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Framing rules of the STDIO transport against a scripted server:
 * notifications get no reply, replies are matched by id, and the server's own
 * messages are not mistaken for answers.
 */
class MCPStdioFramingTest {

    /**
     * Answers every request with a server notification, a result for someone
     * else's id and then the real result; says nothing to notifications.
     */
    private val noisyServer = """
        while IFS= read -r line; do
          id=${'$'}(printf '%s' "${'$'}line" | sed -n 's/.*"id":\([0-9][0-9]*\).*/\1/p')
          [ -z "${'$'}id" ] && continue
          echo '{"jsonrpc":"2.0","method":"notifications/message","params":{"level":"info"}}'
          echo '{"jsonrpc":"2.0","id":999,"result":{"who":"someone else"}}'
          echo "{\"jsonrpc\":\"2.0\",\"id\":${'$'}id,\"result\":{\"echo\":${'$'}id}}"
        done
    """.trimIndent()

    private fun transport(): MCPStdioTransport {
        val sh = File("/bin/sh")
        assumeTrue(sh.canExecute() && File("/usr/bin/sed").canExecute() || File("/bin/sed").canExecute())
        return MCPStdioTransport(command = sh.absolutePath, args = listOf("-c", noisyServer))
    }

    private fun request(id: Int) = JSONObject("""{"jsonrpc":"2.0","id":$id,"method":"tools/list"}""")

    @Test
    fun aNotificationIsWrittenAndNothingIsReadBack() = runBlocking {
        val t = transport()
        t.start()
        try {
            val reply = withTimeout(3_000) {
                t.send(JSONObject("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
            }
            assertEquals(0, reply.length())
        } finally {
            t.close()
        }
    }

    @Test
    fun theReplyIsTheOneWithOurIdNotTheServersOwnMessages() = runBlocking {
        val t = transport()
        t.start()
        try {
            val reply = withTimeout(3_000) { t.send(request(7)) }
            assertEquals(7, reply.getInt("id"))
            assertEquals(7, reply.getJSONObject("result").getInt("echo"))
        } finally {
            t.close()
        }
    }

    @Test
    fun concurrentRequestsEachGetTheirOwnReply() = runBlocking {
        val t = transport()
        t.start()
        try {
            val replies = withTimeout(5_000) {
                (1..8).map { id -> async { id to t.send(request(id)) } }.awaitAll()
            }
            replies.forEach { (id, reply) -> assertEquals(id, reply.getJSONObject("result").getInt("echo")) }
            assertTrue(replies.size == 8)
        } finally {
            t.close()
        }
    }
}
