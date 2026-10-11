package com.openminis.app.runtime.terminal

import com.openminis.app.sandbox.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * Issue #183: the agent's terminals are reclaimed after an hour unused, but only a shell at its prompt. A build or a
 * coding agent still running in the foreground is not closed, and neither is a terminal whose state cannot be told.
 */
class AgentTerminalsReclaimTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ptys = ArrayList<FakePtyBackend>()
    private val launch = TerminalSession.Launch("fixture-su", arrayOf("fixture-su"), emptyArray())
    private val wallMs = AtomicLong(1_000_000L)
    private val nanos = AtomicLong(0)
    private val hour = 60L * 60 * 1000

    /** One terminal allowed in total, so opening a second one only works if the first was reclaimed. */
    private fun terminals() = AgentTerminals(
        newSession = {
            val pty = FakePtyBackend().also { ptys.add(it) }
            TerminalSession(scope, { launch }, pty, nanoTime = { nanos.get() })
        },
        scope = scope,
        now = { wallMs.get() },
        maxPerSession = 3,
        maxTotal = 1,
    )

    @After fun tearDown() {
        ptys.forEach { it.exit() }
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private fun running(t: AgentTerminals.Terminal) = waitUntil { t.session.state.value == TerminalSession.State.RUNNING }

    private fun measured(t: AgentTerminals.Terminal) {
        nanos.addAndGet(2_000_000_000L)
        assertTrue("the prompt is measured", waitUntil { t.session.foregroundState() != TerminalSession.ForegroundState.UNKNOWN })
    }

    @Test fun `an idle shell unused for an hour is reclaimed to make room`() {
        val agent = terminals()
        val first = agent.open("chat", 100, 32)
        assertTrue(running(first))
        measured(first)
        wallMs.addAndGet(hour + 1)
        val second = agent.open("chat", 100, 32)
        assertNull("the idle shell was closed", agent.get(first.id))
        assertNotNull(agent.get(second.id))
    }

    @Test fun `a foreground program is not reclaimed however long it was unused`() {
        val agent = terminals()
        val first = agent.open("chat", 100, 32)
        assertTrue(running(first))
        measured(first)
        ptys[0].foreground = 200 // a build holds the foreground
        nanos.addAndGet(2_000_000_000L)
        assertTrue(waitUntil { first.session.foregroundState() == TerminalSession.ForegroundState.BUSY })
        wallMs.addAndGet(5 * hour)
        try {
            agent.open("chat", 100, 32)
            fail("the app is at its limit and the running terminal must stay")
        } catch (_: AgentTerminals.LimitExceeded) {
        }
        assertNotNull(agent.get(first.id))
        assertEquals(TerminalSession.State.RUNNING, first.session.state.value)
    }

    @Test fun `a terminal whose state cannot be told is not reclaimed`() {
        val agent = terminals()
        val first = agent.open("chat", 100, 32)
        assertTrue(running(first))
        ptys[0].foreground = -1 // the kernel does not answer
        nanos.addAndGet(2_000_000_000L)
        wallMs.addAndGet(5 * hour)
        try {
            agent.open("chat", 100, 32)
            fail("unknown must count as busy")
        } catch (_: AgentTerminals.LimitExceeded) {
        }
        assertNotNull(agent.get(first.id))
    }

    @Test fun `a terminal used within the hour is kept even when idle`() {
        val agent = terminals()
        val first = agent.open("chat", 100, 32)
        assertTrue(running(first))
        measured(first)
        wallMs.addAndGet(hour / 2)
        try {
            agent.open("chat", 100, 32)
            fail("not unused for an hour yet")
        } catch (_: AgentTerminals.LimitExceeded) {
        }
        assertNotNull(agent.get(first.id))
    }
}
