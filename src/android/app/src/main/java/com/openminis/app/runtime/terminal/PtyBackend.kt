package com.openminis.app.runtime.terminal

import com.openminis.app.sandbox.TerminalSession

/** PTY IO boundary shared by the terminal lifecycle and its native adapter. */
internal interface PtyBackend {
    val available: Boolean
    fun open(launch: TerminalSession.Launch, cols: Int, rows: Int, outPid: IntArray): Int
    suspend fun read(fd: Int, bytes: ByteArray): Int
    fun write(fd: Int, bytes: ByteArray, offset: Int): Int
    fun resize(fd: Int, cols: Int, rows: Int)
    fun close(fd: Int)
    fun terminateAndWait(pid: Int)
}
