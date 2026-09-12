package com.openminis.app.sandbox

import android.content.Context
import android.util.Log
import com.openminis.app.runtime.files.WorkspaceFileClient
import com.openminis.app.runtime.terminal.PtyBackend
import com.openminis.app.runtime.ubuntu.DirectRootRunner
import com.openminis.app.runtime.ubuntu.RootNetworkProxy
import com.openminis.app.runtime.ubuntu.UbuntuRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/** JNI entry points implemented by pty_bridge.c. */
internal object PtyBridge {
    val available = try {
        System.loadLibrary("pty_bridge")
        true
    } catch (error: UnsatisfiedLinkError) {
        Log.w("PtyBridge", "Failed to load libpty_bridge.so: ${error.message}")
        false
    }
    external fun forkExec(cmd: String, argv: Array<String>, envp: Array<String>?, cwd: String?, cols: Int, rows: Int, outPid: IntArray): Int
    external fun readBytes(fd: Int, buf: ByteArray, off: Int, len: Int): Int
    external fun writeBytes(fd: Int, buf: ByteArray, off: Int, len: Int): Int
    external fun setWindowSize(fd: Int, cols: Int, rows: Int): Int
    external fun closeFd(fd: Int): Int
    external fun terminateAndWait(pid: Int): Int
}

private object NativePtyBackend : PtyBackend {
    override val available get() = PtyBridge.available
    override fun open(launch: TerminalSession.Launch, cols: Int, rows: Int, outPid: IntArray) =
        PtyBridge.forkExec(launch.cmd, launch.argv, launch.env, "/", cols, rows, outPid)
    override suspend fun read(fd: Int, bytes: ByteArray) = PtyBridge.readBytes(fd, bytes, 0, bytes.size)
    override fun write(fd: Int, bytes: ByteArray, offset: Int) =
        PtyBridge.writeBytes(fd, bytes, offset, minOf(bytes.size - offset, 64 * 1024))
    override fun resize(fd: Int, cols: Int, rows: Int) { PtyBridge.setWindowSize(fd, cols, rows) }
    override fun close(fd: Int) { PtyBridge.closeFd(fd) }
    override fun terminateAndWait(pid: Int) { PtyBridge.terminateAndWait(pid) }
}

/** A single IO coroutine owns each PTY, including all IO and final child reaping. */
class TerminalSession internal constructor(
    private val scope: CoroutineScope,
    private val prepare: suspend (String?) -> Launch,
    private val backend: PtyBackend,
) {
    constructor(context: Context) : this(
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
        { sessionId ->
            if (!UbuntuRuntime.isInitialized) UbuntuRuntime.init(context.applicationContext)
            WorkspaceFileClient.info(sessionId, "/workspace")
            val direct = UbuntuRuntime.prepareLaunch(sessionId, interactive = true)
            Launch(
                cmd = direct.argv.first(),
                argv = direct.argv.toTypedArray(),
                env = arrayOf(
                    "PATH=/system/bin:/system/xbin:/vendor/bin",
                    "TERM=xterm-256color",
                    "LANG=C.UTF-8",
                    "LC_ALL=C.UTF-8",
                    "HOME=/home/minis",
                    "MINIS_CHAT_SESSION_ID=${sessionId.orEmpty()}",
                ),
                pidFile = direct.pidFile,
            )
        },
        NativePtyBackend,
    )

    internal data class Launch(
        val cmd: String,
        val argv: Array<String>,
        val env: Array<String>,
        val pidFile: File? = null,
    )

    companion object {
        const val DEFAULT_COLS = 80
        const val DEFAULT_ROWS = 24
        private const val RETRY_IO = -11 // EAGAIN on Android/Linux.

        /** Weak registry for both booting and running terminals. */
        private val liveSessions = CopyOnWriteArrayList<WeakReference<TerminalSession>>()
        private val registryLock = Any()

        fun broadcastTimezone(tz: String) {
            val dead = mutableListOf<WeakReference<TerminalSession>>()
            for (ref in liveSessions) {
                val session = ref.get()
                if (session == null) {
                    dead += ref
                    continue
                }
                if (session.isRunning) session.applyTimezone(tz)
            }
            liveSessions.removeAll(dead.toSet())
        }

        fun broadcastProxy(env: Map<String, String>) {
            val dead = mutableListOf<WeakReference<TerminalSession>>()
            for (ref in liveSessions) {
                val session = ref.get()
                if (session == null) {
                    dead += ref
                    continue
                }
                if (session.isRunning) session.applyEnvMap(env, RootNetworkProxy.PROXY_ENV_KEYS)
            }
            liveSessions.removeAll(dead.toSet())
        }

        /** Stop every terminal before the rootfs, mounts, or proxy are changed. */
        fun stopAll() {
            synchronized(registryLock) {
                val dead = mutableListOf<WeakReference<TerminalSession>>()
                val current = buildList {
                    for (ref in liveSessions) {
                        val session = ref.get()
                        if (session == null) dead += ref else add(session)
                    }
                }
                liveSessions.removeAll(dead.toSet())
                // Stop while holding the same registry lock used by start.
                // Taking a snapshot and stopping afterward lets a new run be
                // registered on the same TerminalSession in between; the old
                // snapshot would then stop the replacement PTY as well.
                current.forEach(TerminalSession::stopActiveRun)
                liveSessions.removeAll { ref -> ref.get() == null || ref.get() in current }
            }
        }

        /**
         * Stop every terminal and wait for its PTY/Root cleanup to finish.
         *
         * Rootfs and mount maintenance must not race the runTerminal finally
         * block. A Terminal can also be the coroutine that discovered the
         * maintenance need while preparing its own launch; that run is still
         * pre-PTY, so leave it alone instead of self-cancelling and joining it.
         */
        suspend fun stopAllAndJoin() {
            val callerJob = currentCoroutineContext()[Job]
            val stopped = mutableSetOf<TerminalSession>()
            val jobs = synchronized(registryLock) {
                val dead = mutableListOf<WeakReference<TerminalSession>>()
                val current = buildList {
                    for (ref in liveSessions) {
                        val session = ref.get()
                        if (session == null) dead += ref else add(session)
                    }
                }
                liveSessions.removeAll(dead.toSet())
                current.mapNotNull { session ->
                    val job = session.activeJob()
                    if (job == null || job === callerJob) {
                        null
                    } else {
                        session.stopActiveRun()
                        stopped += session
                        job
                    }
                }.also {
                    liveSessions.removeAll { ref -> ref.get() == null || ref.get() in stopped }
                }
            }
            withContext(NonCancellable) {
                jobs.forEach { it.join() }
            }
        }
    }

    enum class State { IDLE, BOOTING, RUNNING, STOPPED }
    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()
    private val _outputBytes = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val outputBytes: SharedFlow<ByteArray> = _outputBytes.asSharedFlow()
    private val _clearVersion = MutableStateFlow(0)
    val clearVersion: StateFlow<Int> = _clearVersion.asStateFlow()
    val isRunning: Boolean get() = _state.value == State.RUNNING

    private sealed interface Input {
        data class Bytes(val bytes: ByteArray) : Input
        data class Resize(val cols: Int, val rows: Int) : Input
    }
    private class Run {
        val input = Channel<Input>(Channel.UNLIMITED)
        lateinit var job: Job
    }
    private val lock = Any()
    private var activeRun: Run? = null

    fun start(sessionId: String? = null, initialCols: Int = DEFAULT_COLS, initialRows: Int = DEFAULT_ROWS) {
        val run = synchronized(registryLock) {
            synchronized(lock) {
                if (activeRun != null) return
                Run().also {
                    activeRun = it
                    _state.value = State.BOOTING
                    it.job = scope.launch(start = CoroutineStart.LAZY) { runTerminal(it, sessionId, initialCols, initialRows) }
                }
            }.also {
                liveSessions.removeAll { ref -> ref.get() === this || ref.get() == null }
                liveSessions.add(WeakReference(this))
            }
        }
        run.job.start()
    }

    private suspend fun runTerminal(run: Run, sessionId: String?, cols: Int, rows: Int) {
        var fd = -1
        val pid = IntArray(1)
        var rootPidFile: File? = null
        try {
            check(backend.available) { "Native PTY bridge is unavailable" }
            val launch = prepare(sessionId)
            rootPidFile = launch.pidFile
            currentCoroutineContext().ensureActive()
            fd = backend.open(launch, cols, rows, pid)
            check(fd >= 0 && pid[0] > 0) { "Failed to spawn PTY: $fd" }
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                if (activeRun === run) {
                    _state.value = State.RUNNING
                }
            }
            val buffer = ByteArray(4096)
            var pending: ByteArray? = null
            var offset = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                if (pending == null) {
                    when (val input = run.input.tryReceive().getOrNull()) {
                        is Input.Bytes -> { pending = input.bytes; offset = 0 }
                        is Input.Resize -> backend.resize(fd, input.cols, input.rows)
                        null -> Unit
                    }
                }
                pending?.let { bytes ->
                    val written = backend.write(fd, bytes, offset)
                    if (written != RETRY_IO) {
                        if (written <= 0) throw IOException("PTY write failed: $written")
                        offset += written
                        if (offset >= bytes.size) pending = null
                    }
                }
                // Native read polls for at most 50 ms; writes are nonblocking.
                val count = backend.read(fd, buffer)
                if (count == RETRY_IO) continue
                if (count <= 0) break
                synchronized(lock) { if (activeRun === run) _outputBytes.tryEmit(buffer.copyOf(count)) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            synchronized(lock) {
                if (activeRun === run) _outputBytes.tryEmit("\r\n[minis] ${error.message}\r\n".toByteArray())
            }
        } finally {
            run.input.cancel()
            // Only this coroutine ever touches these descriptors and child pid.
            try {
                DirectRootRunner.cleanupProcessGroup(rootPidFile)
                try { if (fd >= 0) backend.close(fd) } finally {
                    if (pid[0] > 0) backend.terminateAndWait(pid[0])
                }
            } finally {
                val removeLiveSession = synchronized(lock) {
                    if (activeRun === run) {
                        activeRun = null
                        _state.value = State.STOPPED
                        true
                    } else {
                        // A stopped run may finish after a replacement run has
                        // already registered this same session. Its cleanup
                        // must not unregister the replacement from broadcasts.
                        false
                    }
                }
                if (removeLiveSession) liveSessions.removeAll { it.get() === this || it.get() == null }
            }
        }
    }

    fun sendRawBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        synchronized(lock) { activeRun?.input?.trySend(Input.Bytes(bytes.copyOf())) }
    }

    fun sendText(text: String) {
        if (text.isEmpty()) return
        sendRawBytes(normalizeLineEndings(text).toByteArray(Charsets.UTF_8))
    }

    private fun normalizeLineEndings(text: String): String {
        if ('\n' !in text && '\r' !in text) return text
        val sb = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            when (val char = text[index]) {
                '\r' -> {
                    sb.append('\r')
                    if (index + 1 < text.length && text[index + 1] == '\n') index++
                }
                '\n' -> sb.append('\r')
                else -> sb.append(char)
            }
            index++
        }
        return sb.toString()
    }

    @Deprecated("Use sendText / sendRawBytes instead — real TTY doesn't line-buffer.")
    fun sendInput(text: String) {
        sendRawBytes((normalizeLineEndings(text) + "\r").toByteArray(Charsets.UTF_8))
    }

    fun sendInterrupt() = sendRawBytes(byteArrayOf(0x03)) // The TTY signals the foreground process group.

    fun setWindowSize(newCols: Int, newRows: Int) {
        if (newCols <= 0 || newRows <= 0) return
        synchronized(lock) { activeRun?.input?.trySend(Input.Resize(newCols, newRows)) }
    }

    fun stop() {
        stopActiveRun()
        liveSessions.removeAll { it.get() === this || it.get() == null }
    }

    private fun activeJob(): Job? = synchronized(lock) { activeRun?.job }

    private fun stopActiveRun() {
        synchronized(lock) {
            val run = activeRun
            activeRun = null
            _state.value = State.STOPPED
            run?.input?.cancel()
            run?.job?.cancel()
        }
    }

    private fun applyTimezone(tz: String) {
        if (!isRunning) return
        val escaped = tz.replace("'", "'\\''")
        sendRawBytes("export TZ='$escaped'\r".toByteArray(Charsets.UTF_8))
    }

    private fun applyEnvMap(env: Map<String, String>, previousKeys: Set<String> = emptySet()) {
        if (!isRunning) return
        val commands = buildString {
            for (key in previousKeys - env.keys) {
                if (key.matches(Regex("^[A-Za-z_][A-Za-z0-9_]*$"))) {
                    append("unset ").append(key).append("\r")
                }
            }
            for ((key, value) in env) {
                if (!key.matches(Regex("^[A-Za-z_][A-Za-z0-9_]*$"))) continue
                val escaped = value.replace("'", "'\\''")
                append("export ").append(key).append("='").append(escaped).append("'\r")
            }
        }
        sendRawBytes(commands.toByteArray(Charsets.UTF_8))
    }

    fun clearOutput() { _clearVersion.value += 1 }
}
