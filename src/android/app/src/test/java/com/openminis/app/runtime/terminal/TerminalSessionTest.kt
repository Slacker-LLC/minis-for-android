package com.openminis.app.runtime.terminal

import com.openminis.app.runtime.minisd.MinisdResponse
import com.openminis.app.runtime.ubuntu.UbuntuRuntime
import com.openminis.app.sandbox.PtyBackend
import com.openminis.app.sandbox.TerminalSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalSessionTest {
    private val ready = UbuntuRuntime.Snapshot(running = true, statusFresh = true, pid = 8123, guestUid = 10347, guestGid = 10347)
    private val launch = TerminalSession.Launch("fixture-su", arrayOf("fixture-su"), emptyArray())

    @Test
    fun `runtime and broker session workspace are prepared before constructing guest launch`() = runTest {
        val calls = mutableListOf<String>()
        val launch = TerminalSession.prepareLaunch("session-42", 10347,
            { calls += "runtime"; ready },
            { calls += "workspace:$it" },
            { calls += "su"; "/system/bin/su" })
        assertEquals(listOf("runtime", "workspace:session-42", "su"), calls)
        assertEquals("/system/bin/su", launch.cmd)
        val script = launch.argv.last()
        assertTrue(script.contains("--pid 8123"))
        assertTrue(script.contains("--uid 10347 --gid 10347"))
        assertTrue(script.contains("--session-root '/data/adb/minis/sessions/session-42'"))
        assertTrue(launch.env.contains("MINIS_CHAT_SESSION_ID=session-42"))
        assertTrue(launch.env.contains("HOME=/home/minis"))
        assertFalse(script.contains("mkdir"))
        assertFalse(script.contains("/system/bin/sh"))
    }

    @Test
    fun `invalid session ids are rejected before any runtime or root work`() = runTest {
        for (id in listOf("", " ", ".", "..", "a/b", "x;id", "x'", "x\n", "a".repeat(129))) {
            val error = runCatching {
                TerminalSession.prepareLaunch(id, 10347, { error("must not prepare") }, {}, { error("must not find su") })
            }.exceptionOrNull()
            assertTrue("id=$id error=$error", error is IllegalArgumentException)
        }
    }

    @Test
    fun `unready stale mock and wrong identity runtimes fail closed`() = runTest {
        for (snapshot in listOf(ready.copy(running = false), ready.copy(statusFresh = false), ready.copy(mock = true),
            ready.copy(guestUid = 10000), ready.copy(guestGid = 0), ready.copy(guestGid = null), ready.copy(pid = null),
            ready.copy(lastError = "fixture failure"))) {
            var workspaceCalled = false
            val error = runCatching {
                TerminalSession.prepareLaunch(null, 10347, { snapshot }, { workspaceCalled = true }, { "/system/bin/su" })
            }.exceptionOrNull()
            assertTrue(error is IllegalStateException)
            assertFalse(workspaceCalled)
        }
    }

    @Test
    fun `workspace failure or missing su never starts a host shell`() = runTest {
        for (failWorkspace in listOf(true, false)) {
            val backend = FakePty()
            val session = TerminalSession(this, {
                TerminalSession.prepareLaunch(it, 10347, { ready },
                    { if (failWorkspace) error("workspace refused") }, { null })
            }, backend)
            session.start("session-42")
            runCurrent()
            assertEquals(TerminalSession.State.STOPPED, session.state.value)
            assertEquals(0, backend.opened)
        }
    }

    @Test
    fun `duplicate starts during boot and cancellation cannot create a late PTY`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var preparations = 0
        val backend = FakePty()
        val session = TerminalSession(this, { preparations++; gate.await(); launch }, backend)
        session.start()
        runCurrent()
        session.start()
        assertEquals(TerminalSession.State.BOOTING, session.state.value)
        session.stop()
        session.stop()
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, preparations)
        assertEquals(0, backend.opened)
        assertEquals(TerminalSession.State.STOPPED, session.state.value)
    }

    @Test
    fun `EOF and repeated stop close and reap exactly once`() = runTest {
        val backend = FakePty()
        backend.reads.trySend(0)
        val session = TerminalSession(this, { launch }, backend)
        session.start()
        runCurrent()
        session.stop()
        session.stop()
        runCurrent()
        assertEquals(TerminalSession.State.STOPPED, session.state.value)
        assertEquals(listOf(11), backend.closed)
        assertEquals(listOf(101), backend.reaped)
        assertEquals(1, backend.readCount)
    }

    @Test
    fun `old reader cleanup cannot stop a restarted session`() = runTest {
        val oldReader = CompletableDeferred<Unit>()
        val backend = FakePty().apply {
            readHook = { fd ->
                if (fd == 11) withContext(NonCancellable) { oldReader.await(); 0 } else reads.receive()
            }
        }
        val session = TerminalSession(this, { launch }, backend)
        session.start()
        runCurrent()
        session.start() // RUNNING is also idempotent.
        assertEquals(1, backend.opened)
        session.stop()
        session.start()
        runCurrent()
        assertEquals(2, backend.opened)
        oldReader.complete(Unit)
        runCurrent()
        assertEquals(TerminalSession.State.RUNNING, session.state.value)
        assertEquals(listOf(11), backend.closed)
        session.stop()
        runCurrent()
        assertEquals(listOf(11, 12), backend.closed)
        assertEquals(listOf(101, 102), backend.reaped)
    }

    @Test
    fun `partial writes retain input order and caller buffer ownership`() = runTest {
        val backend = FakePty()
        val session = TerminalSession(this, { launch }, backend)
        session.start()
        runCurrent()
        val first = "ab".toByteArray()
        session.sendRawBytes(first)
        first[0] = 'z'.code.toByte()
        session.sendText("cd")
        session.sendInterrupt()
        repeat(6) { backend.reads.trySend(-11) }
        runCurrent()
        assertEquals("abcd\u0003", backend.written.toString())
        session.stop()
        runCurrent()
    }

    @Test
    fun `runtime status parses actual guest gid`() {
        val response = MinisdResponse(1, 1, true, JSONObject().put("running", true).put("uid", 10347).put("gid", 10347), null)
        assertEquals(10347, UbuntuRuntime.mergeSnapshot(UbuntuRuntime.Snapshot(), response).guestGid)
    }

    private class FakePty : PtyBackend {
        override val available = true
        var opened = 0
        var readCount = 0
        val reads = Channel<Int>(Channel.UNLIMITED)
        var readHook: (suspend (Int) -> Int)? = null
        val closed = mutableListOf<Int>()
        val reaped = mutableListOf<Int>()
        val written = StringBuilder()
        override fun open(launch: TerminalSession.Launch, cols: Int, rows: Int, outPid: IntArray): Int {
            opened++
            outPid[0] = 100 + opened
            return 10 + opened
        }
        override suspend fun read(fd: Int, bytes: ByteArray): Int {
            readCount++
            return readHook?.invoke(fd) ?: reads.receive()
        }
        override fun write(fd: Int, bytes: ByteArray, offset: Int): Int { written.append(bytes[offset].toInt().toChar()); return 1 }
        override fun resize(fd: Int, cols: Int, rows: Int) = Unit
        override fun close(fd: Int) { closed += fd }
        override fun terminateAndWait(pid: Int) { reaped += pid }
    }
}
