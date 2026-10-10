package com.openminis.app.runtime.terminal

import com.openminis.app.sandbox.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The agent's terminals on a fake pty: output reaches a screen, input reaches the pty, limits and endings hold. */
class AgentTerminalsTest {
    private class FakePty : PtyBackend {
        override val available = true
        val output = Channel<ByteArray>(Channel.UNLIMITED)
        val written = java.io.ByteArrayOutputStream()
        @Volatile var closed = false
        override fun open(launch: TerminalSession.Launch, cols: Int, rows: Int, outPid: IntArray): Int { outPid[0] = 4242; return 7 }
        override suspend fun read(fd: Int, bytes: ByteArray): Int {
            // Like the native read: wait a short while, then say "nothing yet" (-11) so the loop can write.
            val result = kotlinx.coroutines.withTimeoutOrNull(30) { output.receiveCatching() } ?: return -11
            val chunk = result.getOrNull() ?: return 0
            chunk.copyInto(bytes)
            return chunk.size
        }
        override fun write(fd: Int, bytes: ByteArray, offset: Int): Int {
            synchronized(written) { written.write(bytes, offset, bytes.size - offset) }
            return bytes.size - offset
        }
        override fun resize(fd: Int, cols: Int, rows: Int) = Unit
        override fun close(fd: Int) { closed = true; output.close() }
        override fun terminateAndWait(pid: Int) = Unit
        fun writtenText(): String = synchronized(written) { written.toString(Charsets.ISO_8859_1) }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ptys = ArrayList<FakePty>()
    private val launch = TerminalSession.Launch("fixture-su", arrayOf("fixture-su"), emptyArray())

    private fun terminals(perSession: Int = 3, total: Int = 6) = AgentTerminals(
        newSession = {
            val pty = FakePty().also { ptys.add(it) }
            TerminalSession(scope, { launch }, pty)
        },
        scope = scope,
        maxPerSession = perSession,
        maxTotal = total,
    )

    @After
    fun tearDown() {
        ptys.forEach { it.output.close() }
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private fun eventually(what: String, block: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < until) {
            if (block()) return
            Thread.sleep(20)
        }
        throw AssertionError("timed out waiting for $what")
    }

    @Test
    fun `output shows up as the rendered screen and input reaches the pty`() = runBlocking {
        val registry = terminals()
        val t = registry.open("chat-1", 80, 24)
        eventually("session running") { t.session.isRunning }
        ptys[0].output.send("hello \u001B[31mworld\u001B[0m\r\nminis$ ".toByteArray())
        val end = withTimeout(5_000) { registry.await(t, maxMs = 3_000, quietMs = 200) }
        assertEquals(AgentTerminals.End.QUIET, end)
        assertEquals("hello world\nminis$", registry.screen(t))

        registry.send(t, "ls\r".toByteArray())
        eventually("input written") { ptys[0].writtenText().contains("ls\r") }
    }

    @Test
    fun `a program's status report ends the wait, and an older one does not`() = runBlocking {
        val registry = terminals()
        val t = registry.open("chat-1", 80, 24)
        eventually("session running") { t.session.isRunning }
        ptys[0].output.send("\u001B]7501;state=done:app=pi\u001B\\".toByteArray())
        eventually("status stored") { registry.status(t).isNotEmpty() }
        // The done record was there before this wait began, so it is not an answer.
        assertEquals(AgentTerminals.End.TIMEOUT, registry.await(t, maxMs = 250, quietMs = 10_000))
        val baseline = registry.baseline(t)
        ptys[0].output.send("\u001B]7501;state=blocked:app=pi:kind=permission\u001B\\".toByteArray())
        assertEquals(AgentTerminals.End.STATUS, registry.await(t, maxMs = 3_000, quietMs = 10_000, statusBaseline = baseline))
    }

    @Test
    fun `wait_for matches on the screen`() = runBlocking {
        val registry = terminals()
        val t = registry.open("chat-1", 80, 24)
        eventually("session running") { t.session.isRunning }
        ptys[0].output.send("Password: ".toByteArray())
        assertEquals(AgentTerminals.End.MATCH, registry.await(t, maxMs = 3_000, waitFor = Regex("Password:")))
    }

    @Test
    fun `an exited program ends the wait`() = runBlocking {
        val registry = terminals()
        val t = registry.open("chat-1", 80, 24)
        eventually("session running") { t.session.isRunning }
        ptys[0].output.close()
        eventually("exit noticed") { t.exited }
        assertEquals(AgentTerminals.End.EXITED, registry.await(t, maxMs = 3_000))
    }

    @Test
    fun `limits per chat and in total are refused with a reason`() {
        val registry = terminals(perSession = 2, total = 3)
        registry.open("a", 80, 24)
        registry.open("a", 80, 24)
        val perSession = runCatching { registry.open("a", 80, 24) }.exceptionOrNull()
        assertTrue(perSession is AgentTerminals.LimitExceeded)
        registry.open("b", 80, 24)
        val total = runCatching { registry.open("c", 80, 24) }.exceptionOrNull()
        assertTrue(total is AgentTerminals.LimitExceeded)
    }

    @Test
    fun `closing a chat closes its terminals and only those`() {
        val registry = terminals()
        val a1 = registry.open("a", 80, 24)
        registry.open("a", 80, 24)
        val b = registry.open("b", 80, 24)
        registry.closeSession("a")
        assertNull(registry.get(a1.id))
        assertEquals(listOf(b.id), registry.list().map { it.id })
        assertFalse(registry.close("nope"))
    }

    @Test
    fun `sizes are clamped`() {
        val registry = terminals()
        val t = registry.open("a", 5, 500)
        assertEquals(AgentTerminals.MIN_COLS, t.cols)
        assertEquals(AgentTerminals.MAX_ROWS, t.rows)
    }
}
