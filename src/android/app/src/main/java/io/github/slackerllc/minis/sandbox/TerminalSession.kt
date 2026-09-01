package io.github.slackerllc.minis.sandbox

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

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

    /**
     * No-op under Ubuntu; interactive PTY removed at P2.
     */
    fun start(sessionId: String? = null, initialCols: Int = DEFAULT_COLS, initialRows: Int = DEFAULT_ROWS) {
        if (_state.value == State.RUNNING) return
        _state.value = State.BOOTING
        Log.w(TAG, "interactive PTY removed at P2; use chat shell_execute")
        val msg = "交互终端已随 PRoot 下线。请使用聊天中的 shell_execute。\r\n"
        kotlinx.coroutines.runBlocking { _outputBytes.emit(msg.toByteArray()) }
        _state.value = State.STOPPED
    }

    fun sendRawBytes(bytes: ByteArray) = Unit

    fun sendText(text: String) = Unit

    @Deprecated("Use sendText / sendRawBytes instead — real TTY doesn't line-buffer.")
    fun sendInput(text: String) = Unit

    fun sendInterrupt() = Unit

    fun setWindowSize(newCols: Int, newRows: Int) = Unit

    fun stop() {
        if (_state.value != State.STOPPED) _state.value = State.STOPPED
    }

    fun clearOutput() {
        _clearVersion.value = _clearVersion.value + 1
    }
}
