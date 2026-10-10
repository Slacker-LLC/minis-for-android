package com.openminis.app.runtime.terminal

import com.openminis.app.sandbox.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The user's terminals belong to the app: detaching from a tab never ends it, and the ways a shell does end are explicit. */
class TerminalSessionManagerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ptys = ArrayList<FakePtyBackend>()
    private val launch = TerminalSession.Launch("fixture-su", arrayOf("fixture-su"), emptyArray())
    private val clock = java.util.concurrent.atomic.AtomicLong(0)
    private val keepAliveCounts = java.util.Collections.synchronizedList(ArrayList<Int>())

    private fun manager(maxTabs: Int = TerminalSessionManager.MAX_TABS) = TerminalSessionManager(
        newSession = {
            val pty = FakePtyBackend().also { ptys.add(it) }
            TerminalSession(scope, { launch }, pty, nanoTime = { clock.get() })
        },
        scope = scope,
        keepAlive = TerminalKeepAlive { keepAliveCounts.add(it) },
        maxTabs = maxTabs,
        titleFor = { "Terminal $it" },
    )

    @After fun tearDown() {
        ptys.forEach { it.exit() }
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private fun running(t: UserTerminal) = waitUntil { t.session.state.value == TerminalSession.State.RUNNING }

    @Test fun `detaching from a tab does not stop its shell and output keeps arriving`() {
        val m = manager()
        val t = m.open()
        assertTrue(running(t))
        m.attach(t.id)
        m.detach(t.id)
        assertEquals(0, m.attachedCount(t.id))
        assertEquals(TerminalSession.State.RUNNING, t.session.state.value)
        // Nothing is looking, and the program keeps printing.
        ptys[0].emit("hello while away\r\n")
        assertTrue(waitUntil { t.emulator.screenText(0).contains("hello while away") })
        assertEquals(TerminalSession.State.RUNNING, t.session.state.value)
        assertNotNull(m.get(t.id))
    }

    @Test fun `output and scrollback survive attach and detach`() {
        val m = manager()
        val t = m.open(rows = 5)
        assertTrue(running(t))
        repeat(30) { ptys[0].emit("line $it\r\n") }
        assertTrue(waitUntil { t.emulator.screenText(0).contains("line 29") })
        m.attach(t.id); m.detach(t.id); m.attach(t.id)
        assertTrue("scrollback is still there", t.emulator.screenText(100).contains("line 0"))
    }

    @Test fun `closing the tab ends the shell and removes it`() {
        val m = manager()
        val a = m.open(); val b = m.open()
        assertTrue(running(a)); assertTrue(running(b))
        assertEquals(b.id, m.selectedId.value)
        assertTrue(m.close(b.id))
        assertTrue(waitUntil { b.session.state.value == TerminalSession.State.STOPPED })
        assertEquals(listOf(a.id), m.tabs.value.map { it.id })
        assertEquals("the neighbour is selected", a.id, m.selectedId.value)
        assertEquals(TerminalSession.State.RUNNING, a.session.state.value)
        assertFalse(m.close("nope"))
    }

    @Test fun `a shell that exits on its own marks the tab ended and keeps it`() {
        val m = manager()
        val t = m.open()
        assertTrue(running(t))
        ptys[0].exit()
        assertTrue(waitUntil { t.exited.value })
        assertNotNull("the tab stays so the user can read the output", m.get(t.id))
        assertEquals(0, m.runningCount())
    }

    @Test fun `end all closes every tab`() {
        val m = manager()
        val tabs = List(3) { m.open() }
        tabs.forEach { assertTrue(running(it)) }
        m.closeAll()
        assertTrue(m.tabs.value.isEmpty())
        tabs.forEach { assertTrue(waitUntil { it.session.state.value == TerminalSession.State.STOPPED }) }
        assertNull(m.selectedId.value)
    }

    @Test fun `tabs are limited and the limit is reported`() {
        val m = manager(maxTabs = 2)
        m.open(); m.open()
        try {
            m.open()
            error("the third tab must be refused")
        } catch (e: TerminalSessionManager.LimitExceeded) {
            assertTrue(e.message!!.contains("2"))
        }
        assertEquals(2, m.tabs.value.size)
    }

    @Test fun `each tab keeps its chat session binding and its own name`() {
        val m = manager()
        val plain = m.open()
        val bound = m.open(sessionId = "chat-7")
        assertNull(plain.sessionId)
        assertEquals("chat-7", bound.sessionId)
        m.rename(bound.id, "  build  ")
        assertEquals("build", bound.title.value)
        m.rename(bound.id, "   ")
        assertEquals("a blank name changes nothing", "build", bound.title.value)
        m.rename(bound.id, "x".repeat(100))
        assertEquals(UserTerminal.MAX_TITLE, bound.title.value.length)
    }

    @Test fun `selecting works and unknown ids are ignored`() {
        val m = manager()
        val a = m.open(); val b = m.open()
        m.select(a.id)
        assertEquals(a.id, m.selected()?.id)
        m.select("missing")
        assertEquals(a.id, m.selected()?.id)
        assertEquals(b.id, m.tabs.value.last().id)
    }

    @Test fun `busy means a program holds the foreground or the state is unknown, never an idle shell`() {
        val m = manager()
        val t = m.open()
        assertTrue(running(t))
        // Before the first quiet moment the prompt has not been measured: unknown counts as busy.
        assertTrue(m.isBusy(t.id))
        clock.addAndGet(2_000_000_000L)
        assertTrue("the idle shell is measured", waitUntil { !m.isBusy(t.id) })
        ptys[0].foreground = 200 // `sleep 600` takes the foreground
        clock.addAndGet(2_000_000_000L)
        assertTrue(waitUntil { m.isBusy(t.id) })
        assertEquals(1, m.busyCount())
        ptys[0].foreground = 100 // and finishes
        clock.addAndGet(2_000_000_000L)
        assertTrue(waitUntil { !m.isBusy(t.id) })
        assertEquals(0, m.busyCount())
    }

    @Test fun `an ended tab is not busy and can be restarted in place`() {
        val m = manager()
        val t = m.open()
        assertTrue(running(t))
        ptys[0].exit()
        assertTrue(waitUntil { t.exited.value })
        assertFalse(m.isBusy(t.id))
        // A fresh pty for the restart.
        m.restart(t.id)
        assertTrue(running(t))
        assertFalse(t.exited.value)
        assertEquals(2, ptys[0].opened)
    }

    @Test fun `the keep alive hears the number of live shells`() {
        val m = manager()
        val a = m.open()
        assertTrue(running(a))
        assertTrue(waitUntil { keepAliveCounts.lastOrNull() == 1 })
        val b = m.open()
        assertTrue(running(b))
        assertTrue(waitUntil { keepAliveCounts.lastOrNull() == 2 })
        m.close(a.id)
        assertTrue(waitUntil { keepAliveCounts.lastOrNull() == 1 })
        m.closeAll()
        assertTrue(waitUntil { keepAliveCounts.lastOrNull() == 0 })
    }

    @Test fun `a runtime stop with a program running leaves a notice and an idle one does not`() {
        val m = manager()
        val t = m.open()
        assertTrue(running(t))
        clock.addAndGet(2_000_000_000L)
        assertTrue(waitUntil { !m.isBusy(t.id) })
        TerminalSession.stopAll()
        assertNull("an idle shell stopping is not a lost task", m.maintenanceNotice.value)

        val m2 = manager()
        val t2 = m2.open()
        assertTrue(running(t2))
        clock.addAndGet(2_000_000_000L)
        assertTrue(waitUntil { !m2.isBusy(t2.id) })
        ptys.last().foreground = 300
        clock.addAndGet(2_000_000_000L)
        assertTrue(waitUntil { m2.isBusy(t2.id) })
        TerminalSession.stopAll()
        val notice = m2.maintenanceNotice.value
        assertNotNull("the user is told a running task was ended", notice)
        assertEquals(1, notice!!.busy)
        m2.dismissMaintenanceNotice()
        assertNull(m2.maintenanceNotice.value)
    }
}
