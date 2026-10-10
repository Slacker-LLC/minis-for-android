package com.openminis.app.runtime.terminal

import com.openminis.app.sandbox.TerminalSession
import com.openminis.app.sandbox.TerminalSession.ForegroundState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * Issue #183: whether a program runs in the terminal. The pty's foreground group is the main signal; the guest's
 * prompt marker (OSC 133;A) backs it up for a `su` that relays through its own pty, where the group never changes.
 */
class TerminalForegroundStateTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val launch = TerminalSession.Launch("fixture-su", arrayOf("fixture-su"), emptyArray())
    private val nanos = AtomicLong(0)
    private val prompt = "\u001B]133;A\u001B\\"

    private fun session(pty: FakePtyBackend) = TerminalSession(scope, { launch }, pty, nanoTime = { nanos.get() })

    @After fun tearDown() { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }

    private fun started(pty: FakePtyBackend): TerminalSession {
        val s = session(pty)
        s.start()
        assertTrue(waitUntil { s.state.value == TerminalSession.State.RUNNING })
        return s
    }

    private fun measure(s: TerminalSession, expect: (ForegroundState) -> Boolean) {
        nanos.addAndGet(2_000_000_000L)
        assertTrue("state: ${s.foregroundState()}", waitUntil { expect(s.foregroundState()) })
    }

    @Test fun `a foreground group other than the shell is busy`() {
        val pty = FakePtyBackend()
        val s = started(pty)
        measure(s) { it == ForegroundState.IDLE }
        pty.foreground = 300
        measure(s) { it == ForegroundState.BUSY }
    }

    @Test fun `a line sent with Enter is running until the prompt returns, even if the group never changes`() {
        val pty = FakePtyBackend()
        val s = started(pty)
        pty.emit("user@minis:~$ $prompt")
        measure(s) { it == ForegroundState.IDLE }
        // A su that relays through its own pty: the foreground group stays at the shell's.
        s.sendRawBytes("sleep 600\r".toByteArray())
        assertEquals(ForegroundState.BUSY, s.foregroundState())
        measure(s) { it == ForegroundState.BUSY }
        pty.emit("$prompt")
        assertTrue(waitUntil { s.foregroundState() == ForegroundState.IDLE })
    }

    @Test fun `without the prompt hook the group alone decides`() {
        val pty = FakePtyBackend()
        val s = started(pty)
        measure(s) { it == ForegroundState.IDLE }
        // A user rc file replaced the hook: Enter alone must not make an idle shell look busy forever.
        s.sendRawBytes("ls\r".toByteArray())
        assertEquals(ForegroundState.IDLE, s.foregroundState())
    }

    @Test fun `an unreadable group with the prompt showing counts as idle, and busy after Enter`() {
        val pty = FakePtyBackend()
        pty.foreground = -1
        val s = started(pty)
        assertEquals("nothing known before the first prompt", ForegroundState.UNKNOWN, s.foregroundState())
        pty.emit("$ $prompt")
        assertTrue(waitUntil { s.foregroundState() == ForegroundState.IDLE })
        s.sendRawBytes("make\r".toByteArray())
        assertEquals(ForegroundState.BUSY, s.foregroundState())
    }

    @Test fun `a stopped session is idle`() {
        val pty = FakePtyBackend()
        val s = started(pty)
        s.stop()
        assertTrue(waitUntil { s.state.value == TerminalSession.State.STOPPED })
        assertEquals(ForegroundState.IDLE, s.foregroundState())
    }

    @Test fun `the prompt marker is found whole, split across reads, and not in other escapes`() {
        val scanner = PromptMarkerScanner()
        fun feed(text: String) = text.toByteArray().let { scanner.feed(it, it.size) }
        assertTrue(feed("a$prompt"))
        assertFalse(feed("\u001B]133;B\u001B\\"))
        assertFalse(feed("\u001B]1337;A"))
        assertFalse(feed("\u001B]133"))
        assertTrue("the rest of the marker arrives in the next read", feed(";A\u001B\\"))
        assertFalse(feed("plain output"))
    }
}
