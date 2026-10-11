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
    fun `click and scroll reach only a program that asked for the mouse`() = runBlocking {
        val registry = terminals()
        val t = registry.open("chat-1", 80, 24)
        eventually("session running") { t.session.isRunning }
        // A plain shell: nothing to click, nothing to scroll (its history is read with scrollback).
        assertEquals(AgentTerminals.MouseSupport.NONE, registry.mouseSupport(t))
        assertFalse(registry.click(t, 3, 5, 1))
        assertFalse(registry.scroll(t, 3))
        assertFalse(ptys[0].writtenText().contains("\u001B[<"))

        ptys[0].output.send("\u001B[?1049h\u001B[?1000h\u001B[?1006h".toByteArray())
        eventually("mouse reporting on") { registry.mouseSupport(t) == AgentTerminals.MouseSupport.REPORTING }
        assertTrue(registry.click(t, 3, 5, 1))
        eventually("click written") { ptys[0].writtenText().contains("\u001B[<0;5;3M\u001B[<0;5;3m") }
        assertTrue(registry.click(t, 1, 1, 2))
        eventually("double click written") { ptys[0].writtenText().contains("\u001B[<0;1;1M\u001B[<0;1;1m\u001B[<0;1;1M\u001B[<0;1;1m") }
        assertTrue(registry.scroll(t, 2))
        eventually("wheel written") { ptys[0].writtenText().contains("\u001B[<65;") }
    }

    @Test
    fun `a full-screen program without the mouse is scrolled with arrow keys`() = runBlocking {
        val registry = terminals()
        val t = registry.open("chat-1", 80, 24)
        eventually("session running") { t.session.isRunning }
        ptys[0].output.send("\u001B[?1049h".toByteArray())
        eventually("alternate screen") { registry.inFullScreen(t) }
        assertEquals(AgentTerminals.MouseSupport.ARROW_KEYS, registry.mouseSupport(t))
        assertFalse("no mouse: a click is refused", registry.click(t, 2, 2, 1))
        assertTrue(registry.scroll(t, 3))
        eventually("arrows written") { ptys[0].writtenText().contains("\u001B[B\u001B[B\u001B[B") }
    }

    @Test
    fun `the report says where the cursor is and marks a menu's selection`() = runBlocking {
        val registry = terminals()
        val t = registry.open("chat-1", 80, 24)
        eventually("session running") { t.session.isRunning }
        ptys[0].output.send("\u001B[?1049h  one\r\n\u001B[7m> two\u001B[0m\u001B[?25h".toByteArray())
        eventually("drawn") { registry.screen(t).contains("two") }
        assertTrue(registry.inFullScreen(t))
        assertEquals("  one\n⟦> two⟧", registry.screen(t, marks = true))
        assertTrue(registry.cursorAndModes(t), registry.cursorAndModes(t).startsWith("cursor row 2, col 6"))
        assertTrue(registry.cursorAndModes(t).contains("full-screen app"))
    }

    @Test
    fun `until_prompt ends when the shell's prompt is back, not on a short silence`() = runBlocking {
        val registry = terminals()
        val t = registry.open("chat-1", 80, 24)
        eventually("session running") { t.session.isRunning }
        // The shell reports its prompt (OSC 133;A), so the terminal can tell it is idle without asking the pty.
        ptys[0].output.send("\u001B]133;A\u0007minis$ ".toByteArray())
        eventually("prompt seen") { t.session.foregroundState() == TerminalSession.ForegroundState.IDLE }
        val started = System.currentTimeMillis()
        val end = registry.await(t, maxMs = 5_000, quietMs = 100, untilPrompt = true)
        assertEquals(AgentTerminals.End.PROMPT, end)
        // Never in the first moments: right after Enter the shell has not started the command yet.
        assertTrue(System.currentTimeMillis() - started >= 350)
    }

    @Test
    fun `until_prompt does not call a silent running command finished`() = runBlocking {
        val registry = terminals()
        val t = registry.open("chat-1", 80, 24)
        eventually("session running") { t.session.isRunning }
        ptys[0].output.send("\u001B]133;A\u0007minis$ ".toByteArray())
        eventually("prompt seen") { t.session.foregroundState() == TerminalSession.ForegroundState.IDLE }
        // A line is sent and no prompt comes back: the command is still running, however quiet the screen.
        registry.send(t, "make\r".toByteArray())
        eventually("busy") { t.session.foregroundState() == TerminalSession.ForegroundState.BUSY }
        assertEquals(AgentTerminals.End.TIMEOUT, registry.await(t, maxMs = 1_200, quietMs = 100, untilPrompt = true))
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
