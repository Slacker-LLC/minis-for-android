package com.openminis.app.runtime.terminal

import com.openminis.app.sandbox.TerminalSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalSessionTest {
    private val launch = TerminalSession.Launch("fixture-su", arrayOf("fixture-su"), emptyArray())

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
        session.start()
        assertEquals(1, backend.opened)
        session.stop()
        session.start()
        runCurrent()
        assertEquals(2, backend.opened)
        oldReader.complete(Unit)
        runCurrent()
        assertEquals(TerminalSession.State.RUNNING, session.state.value)
        TerminalSession.broadcastTimezone("UTC")
        repeat(32) { backend.reads.trySend(-11) }
        runCurrent()
        assertTrue(backend.written.toString().contains("export TZ='UTC'\r"))
        assertEquals(listOf(11), backend.closed)
        session.stop()
        runCurrent()
        assertEquals(listOf(11, 12), backend.closed)
        assertEquals(listOf(101, 102), backend.reaped)
    }

    @Test
    fun `proxy broadcast clears stale helper variables`() = runTest {
        val readStarted = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        val backend = FakePty().apply {
            var firstRead = true
            readHook = {
                if (firstRead) {
                    firstRead = false
                    readStarted.complete(Unit)
                    withContext(NonCancellable) {
                        releaseRead.await()
                    }
                    -11
                } else {
                    reads.receive()
                }
            }
        }
        val session = TerminalSession(this, { launch }, backend)
        session.start()
        runCurrent()
        readStarted.await()

        TerminalSession.broadcastProxy(mapOf("http_proxy" to "http://127.0.0.1:18787"))
        TerminalSession.broadcastProxy(emptyMap())
        releaseRead.complete(Unit)
        repeat(1024) { backend.reads.trySend(-11) }
        runCurrent()

        assertTrue(backend.written.toString().contains("export http_proxy='http://127.0.0.1:18787'\r"))
        assertTrue(backend.written.toString().contains("unset http_proxy\r"))
        session.stop()
        runCurrent()
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
    fun `global stop reaps booting and running terminals`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val bootingBackend = FakePty()
        val runningBackend = FakePty().apply { reads.trySend(0) }
        val booting = TerminalSession(this, { gate.await(); launch }, bootingBackend)
        val running = TerminalSession(this, { launch }, runningBackend)

        booting.start()
        running.start()
        runCurrent()
        TerminalSession.stopAll()
        gate.complete(Unit)
        runCurrent()

        assertEquals(TerminalSession.State.STOPPED, booting.state.value)
        assertEquals(TerminalSession.State.STOPPED, running.state.value)
        assertEquals(0, bootingBackend.opened)
        assertEquals(listOf(11), runningBackend.closed)
        assertEquals(listOf(101), runningBackend.reaped)
    }

    @Test
    fun `joined global stop waits for terminal cleanup`() = runTest {
        val readStarted = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        val backend = FakePty().apply {
            readHook = {
                readStarted.complete(Unit)
                withContext(NonCancellable) {
                    releaseRead.await()
                }
                -11
            }
        }
        val session = TerminalSession(this, { launch }, backend)
        session.start()
        runCurrent()
        readStarted.await()

        val stopper = launch { TerminalSession.stopAllAndJoin() }
        runCurrent()
        assertFalse(stopper.isCompleted)
        assertEquals(TerminalSession.State.STOPPED, session.state.value)

        releaseRead.complete(Unit)
        stopper.join()
        runCurrent()
        assertEquals(listOf(11), backend.closed)
        assertEquals(listOf(101), backend.reaped)
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
