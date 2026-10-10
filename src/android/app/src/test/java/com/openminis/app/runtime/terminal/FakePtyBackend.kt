package com.openminis.app.runtime.terminal

import com.openminis.app.sandbox.TerminalSession
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A PTY that never touches the OS: output is pushed with [emit], what the terminal writes lands in [writtenText],
 * and the foreground process group is whatever [foreground] says (the shell is group 100).
 */
internal class FakePtyBackend : PtyBackend {
    override val available = true
    @Volatile var output = Channel<ByteArray>(Channel.UNLIMITED)
    @Volatile private var exited = false
    private val written = java.io.ByteArrayOutputStream()
    @Volatile var closed = false
    @Volatile var foreground = 100
    @Volatile var opened = 0

    override fun open(launch: TerminalSession.Launch, cols: Int, rows: Int, outPid: IntArray): Int {
        opened++
        // A restarted shell gets a fresh pty: the old one's output is at end-of-file.
        if (exited) { output = Channel(Channel.UNLIMITED); exited = false }
        outPid[0] = 4242
        return 7
    }

    override suspend fun read(fd: Int, bytes: ByteArray): Int {
        // Like the native read: wait a little, then say "nothing yet" (-11) so the loop can write and sample.
        val result = withTimeoutOrNull(20) { output.receiveCatching() } ?: return -11
        val chunk = result.getOrNull() ?: return 0
        chunk.copyInto(bytes)
        return chunk.size
    }

    override fun write(fd: Int, bytes: ByteArray, offset: Int): Int {
        synchronized(written) { written.write(bytes, offset, bytes.size - offset) }
        return bytes.size - offset
    }

    override fun resize(fd: Int, cols: Int, rows: Int) = Unit
    override fun close(fd: Int) { closed = true }
    override fun terminateAndWait(pid: Int) = Unit
    override fun foregroundPgid(fd: Int): Int = foreground

    fun emit(text: String) { output.trySend(text.toByteArray()) }
    fun writtenText(): String = synchronized(written) { written.toString(Charsets.ISO_8859_1) }
    /** The shell exits: the read returns end-of-file. */
    fun exit() { exited = true; output.close() }
}

/** Polls [condition] until true or [timeoutMs]; the sessions run on real dispatchers. */
fun waitUntil(timeoutMs: Long = 4_000, condition: () -> Boolean): Boolean {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        if (condition()) return true
        Thread.sleep(10)
    }
    return condition()
}
