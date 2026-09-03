package com.openminis.app.sandbox

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * JNI wrapper object mapping directly to methods in pty_bridge.c.
 */
internal object PtyBridge {
    private var isLoaded = false

    init {
        try {
            System.loadLibrary("pty_bridge")
            isLoaded = true
        } catch (t: Throwable) {
            Log.w("PtyBridge", "Failed to load libpty_bridge.so: " + t.message)
        }
    }

    val available: Boolean get() = isLoaded

    external fun forkExec(
        cmd: String,
        argv: Array<String>,
        envp: Array<String>?,
        cwd: String?,
        cols: Int,
        rows: Int,
        outPid: IntArray,
    ): Int

    external fun readBytes(fd: Int, buf: ByteArray, off: Int, len: Int): Int

    external fun writeBytes(fd: Int, buf: ByteArray, off: Int, len: Int): Int

    external fun setWindowSize(fd: Int, cols: Int, rows: Int): Int

    external fun closeFd(fd: Int): Int

    external fun sendSignal(pid: Int, sig: Int): Int

    external fun waitFor(pid: Int): Int
}

/**
 * Interactive PTY shell session.
 *
 * P2: PRoot removed. The interactive terminal is off (use chat shell_execute).
 * API shape kept so UI compiles; [start] emits a notice and stops.
 * An Ubuntu-backed PTY can be re-added later without changing the UI surface.
 */
class TerminalSession(private val context: Context) {

    companion object {
        private const val TAG = "TerminalSession"
        const val DEFAULT_COLS = 80
        const val DEFAULT_ROWS = 24

        @Suppress("unused")
        private val liveSessions = CopyOnWriteArrayList<WeakReference<TerminalSession>>()

        /** Kept for receivers; no live PTY to update. */
        fun broadcastTimezone(tz: String) = Unit

        /** Kept for receivers; no live PTY to update. */
        fun broadcastProxy(env: Map<String, String>) = Unit
    }

    enum class State { IDLE, BOOTING, RUNNING, STOPPED }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _outputBytes = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val outputBytes: SharedFlow<ByteArray> = _outputBytes.asSharedFlow()

    private val _clearVersion = MutableStateFlow(0)
    val clearVersion: StateFlow<Int> = _clearVersion.asStateFlow()

    val isRunning: Boolean get() = _state.value == State.RUNNING

    private val scope = CoroutineScope(Dispatchers.IO)
    private var readerJob: Job? = null

    @Volatile
    private var masterFd: Int = -1

    @Volatile
    private var childPid: Int = -1

    fun start(sessionId: String? = null, initialCols: Int = DEFAULT_COLS, initialRows: Int = DEFAULT_ROWS) {
        if (_state.value == State.RUNNING) return
        _state.value = State.BOOTING

        if (!PtyBridge.available) {
            val msg = "\r\n[minis] Native PTY bridge not available on this device.\r\n"
            scope.launch { _outputBytes.emit(msg.toByteArray(Charsets.UTF_8)) }
            _state.value = State.STOPPED
            return
        }

        val outPid = IntArray(1)
        val suPaths = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su")
        val suBinary = suPaths.firstOrNull { File(it).exists() } ?: "su"

        val script = "PID=\$(cat /data/adb/minis/run/ubuntu.pid 2>/dev/null); if [ -n \"\$PID\" ] && [ -d \"/proc/\$PID\" ] && [ -x /data/adb/minis/bin/minisd ]; then exec /data/adb/minis/bin/minisd --helper exec --pid \"\$PID\" --rootfs /data/adb/minis/rootfs --uid 10000 --gid 10000 --cwd /workspace -- /bin/bash -l; else echo -e \"\\r\\n[minis] Ubuntu container not running. Starting host shell...\\r\\n\"; exec /system/bin/sh; fi"

        val hasSu = File(suBinary).exists()
        val cmd = if (hasSu) suBinary else "/system/bin/sh"
        val argv = if (hasSu) {
            arrayOf("su", "-c", script)
        } else {
            arrayOf("/system/bin/sh")
        }

        val envp = arrayOf(
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
            "HOME=/workspace",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        )

        val fd = PtyBridge.forkExec(
            cmd = cmd,
            argv = argv,
            envp = envp,
            cwd = "/workspace",
            cols = initialCols,
            rows = initialRows,
            outPid = outPid,
        )

        if (fd < 0) {
            val msg = "\r\n[minis] Failed to spawn PTY: error code " + fd + "\r\n"
            scope.launch { _outputBytes.emit(msg.toByteArray(Charsets.UTF_8)) }
            _state.value = State.STOPPED
            return
        }

        masterFd = fd
        childPid = outPid[0]
        _state.value = State.RUNNING
        liveSessions.add(WeakReference(this))

        readerJob?.cancel()
        readerJob = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(4096)
            while (isActive && masterFd >= 0) {
                val n = PtyBridge.readBytes(masterFd, buf, 0, buf.size)
                if (n > 0) {
                    _outputBytes.emit(buf.copyOf(n))
                } else if (n < 0) {
                    break
                }
            }
            stop()
        }
    }

    fun sendRawBytes(bytes: ByteArray) {
        val fd = masterFd
        if (fd >= 0) {
            PtyBridge.writeBytes(fd, bytes, 0, bytes.size)
        }
    }

    fun sendText(text: String) {
        sendRawBytes(text.toByteArray(Charsets.UTF_8))
    }

    @Deprecated("Use sendText / sendRawBytes instead — real TTY doesn't line-buffer.")
    fun sendInput(text: String) {
        sendText(text)
    }

    fun sendInterrupt() {
        sendRawBytes(byteArrayOf(0x03)) // Ctrl+C
        val pid = childPid
        if (pid > 0) {
            PtyBridge.sendSignal(pid, 2) // SIGINT
        }
    }

    fun setWindowSize(newCols: Int, newRows: Int) {
        val fd = masterFd
        if (fd >= 0) {
            PtyBridge.setWindowSize(fd, newCols, newRows)
        }
    }

    fun stop() {
        if (_state.value == State.STOPPED) return
        _state.value = State.STOPPED

        readerJob?.cancel()
        readerJob = null

        val fd = masterFd
        if (fd >= 0) {
            masterFd = -1
            PtyBridge.closeFd(fd)
        }

        val pid = childPid
        if (pid > 0) {
            childPid = -1
            PtyBridge.sendSignal(pid, 15) // SIGTERM
        }
    }

    fun clearOutput() {
        _clearVersion.value = _clearVersion.value + 1
    }
}
