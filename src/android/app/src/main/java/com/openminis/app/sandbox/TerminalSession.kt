package com.openminis.app.sandbox

import android.content.Context
import android.util.Log
import com.openminis.app.runtime.minisd.WorkspaceFileClient
import com.openminis.app.runtime.ubuntu.UbuntuPaths
import com.openminis.app.runtime.ubuntu.UbuntuRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import java.io.IOException

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

internal interface PtyBackend {
    val available: Boolean
    fun open(launch: TerminalSession.Launch, cols: Int, rows: Int, outPid: IntArray): Int
    suspend fun read(fd: Int, bytes: ByteArray): Int
    fun write(fd: Int, bytes: ByteArray, offset: Int): Int
    fun resize(fd: Int, cols: Int, rows: Int)
    fun close(fd: Int)
    fun terminateAndWait(pid: Int)
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
            prepareLaunch(sessionId, context.applicationInfo.uid,
                { UbuntuRuntime.ensureReady() },
                { WorkspaceFileClient.info(it, "/workspace"); Unit },
                { UbuntuRuntime.findSu() })
        },
        NativePtyBackend,
    )

    internal data class Launch(val cmd: String, val argv: Array<String>, val env: Array<String>)

    companion object {
        const val DEFAULT_COLS = 80
        const val DEFAULT_ROWS = 24
        private const val RETRY_IO = -11 // EAGAIN on Android/Linux.

        /** Retained receiver hooks; shell environment is captured at launch. */
        fun broadcastTimezone(tz: String) = Unit
        fun broadcastProxy(env: Map<String, String>) = Unit

        internal suspend fun prepareLaunch(
            sessionId: String?,
            appUid: Int,
            ensureReady: suspend () -> UbuntuRuntime.Snapshot,
            prepareWorkspace: suspend (String?) -> Unit,
            findSu: () -> String?,
        ): Launch {
            require(sessionId == null || UbuntuPaths.isSafeSessionId(sessionId)) { "Invalid terminal session id" }
            val ready = ensureReady()
            check(ready.statusFresh && ready.running && !ready.mock && ready.lastError == null) {
                ready.lastError ?: "Ubuntu runtime is not ready"
            }
            check(appUid > 0 && ready.guestUid == appUid && ready.guestGid == appUid) {
                "Ubuntu runtime identity does not match the app"
            }
            val pid = checkNotNull(ready.pid?.takeIf { it > 0 }) { "Ubuntu keeper pid is missing" }
            // The broker creates and validates all canonical session directories.
            prepareWorkspace(sessionId)
            val su = checkNotNull(findSu()) { "Root launcher is unavailable" }
            val script = buildLaunchScript(appUid, appUid, pid, sessionId)
            return Launch(su, arrayOf(su, "-c", script), arrayOf(
                "TERM=xterm-256color", "LANG=C.UTF-8", "LC_ALL=C.UTF-8", "HOME=/home/minis",
                "PATH=/system/bin:/system/xbin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "MINIS_CHAT_SESSION_ID=${sessionId.orEmpty()}",
            ))
        }

        internal fun buildLaunchScript(guestUid: Int, guestGid: Int, keeperPid: Int, sessionId: String?): String {
            require(guestUid > 0 && guestGid > 0 && keeperPid > 0)
            require(sessionId == null || UbuntuPaths.isSafeSessionId(sessionId))
            val sessionArg = sessionId?.let { " --session-root '${UbuntuPaths.HOST_MINIS}/sessions/$it'" }.orEmpty()
            return "exec /data/adb/minis/bin/minisd --helper exec --pid $keeperPid --rootfs /data/adb/minis/rootfs$sessionArg --uid $guestUid --gid $guestGid --cwd /workspace -- /bin/bash -l"
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
        val run = synchronized(lock) {
            if (activeRun != null) return
            Run().also {
                activeRun = it
                _state.value = State.BOOTING
                it.job = scope.launch(start = CoroutineStart.LAZY) { runTerminal(it, sessionId, initialCols, initialRows) }
            }
        }
        run.job.start()
    }

    private suspend fun runTerminal(run: Run, sessionId: String?, cols: Int, rows: Int) {
        var fd = -1
        val pid = IntArray(1)
        try {
            check(backend.available) { "Native PTY bridge is unavailable" }
            val launch = prepare(sessionId)
            currentCoroutineContext().ensureActive()
            fd = backend.open(launch, cols, rows, pid)
            check(fd >= 0 && pid[0] > 0) { "Failed to spawn PTY: $fd" }
            currentCoroutineContext().ensureActive()
            synchronized(lock) { if (activeRun === run) _state.value = State.RUNNING }
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
                try { if (fd >= 0) backend.close(fd) } finally {
                    if (pid[0] > 0) backend.terminateAndWait(pid[0])
                }
            } finally {
                synchronized(lock) {
                    if (activeRun === run) { activeRun = null; _state.value = State.STOPPED }
                }
            }
        }
    }

    fun sendRawBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        synchronized(lock) { activeRun?.input?.trySend(Input.Bytes(bytes.copyOf())) }
    }
    fun sendText(text: String) = sendRawBytes(text.toByteArray(Charsets.UTF_8))
    @Deprecated("Use sendText / sendRawBytes instead — real TTY doesn't line-buffer.")
    fun sendInput(text: String) = sendText(text)
    fun sendInterrupt() = sendRawBytes(byteArrayOf(0x03)) // The TTY signals the foreground process group.
    fun setWindowSize(newCols: Int, newRows: Int) {
        if (newCols <= 0 || newRows <= 0) return
        synchronized(lock) { activeRun?.input?.trySend(Input.Resize(newCols, newRows)) }
    }
    fun stop() {
        synchronized(lock) {
            val run = activeRun
            activeRun = null
            _state.value = State.STOPPED
            run?.input?.cancel()
            run?.job?.cancel()
        }
    }
    fun clearOutput() { _clearVersion.value += 1 }
}
