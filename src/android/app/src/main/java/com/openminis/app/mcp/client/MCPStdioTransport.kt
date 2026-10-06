package com.openminis.app.mcp.client

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader

/**
 * stdio transport for local MCP servers (`command` + `args` in servers.json`).
 * A cancelled request closes pipes and destroys the server process so a
 * blocking readLine cannot outlive the cancelled agent/tool call.
 */
class MCPStdioTransport(
    private val command: String,
    private val args: List<String> = emptyList(),
    private val env: Map<String, String> = emptyMap(),
) {

    companion object {
        private const val TAG = "MCPStdioTransport"
        private const val MAX_LINE = 64 * 1024
        /** A server's stderr is diagnostic only; keep its lines short. */
        private const val MAX_STDERR_LINE = 8 * 1024
    }

    @Volatile
    private var process: Process? = null
    @Volatile
    private var writer: java.io.BufferedWriter? = null
    @Volatile
    private var reader: BufferedReader? = null

    /**
     * [T-mcp-stdio-line-bound-android] The stdout line reader that refuses a line the
     * server never finished: see [MCPBoundedLineReader] for why a length check after
     * `readLine()` is not a bound.
     */
    @Volatile
    private var lines: MCPBoundedLineReader? = null

    suspend fun start() = withContext(Dispatchers.IO) {
        if (process != null) return@withContext
        val pb = ProcessBuilder(listOf(command) + args)
        pb.redirectErrorStream(false)
        env.forEach { (k, v) ->
            if (!v.contains('\u0000')) pb.environment()[k] = v
        }
        val p = try {
            pb.start()
        } catch (t: Throwable) {
            throw MCPTransportException("failed to start $command: ${t.message}")
        }
        process = p
        writer = p.outputStream.bufferedWriter()
        val stdout = p.inputStream.bufferedReader()
        reader = stdout
        lines = MCPBoundedLineReader(stdout, MAX_LINE, command)
        Thread {
            runCatching {
                p.errorStream.bufferedReader().use { err ->
                    val stderrLines = MCPBoundedLineReader(err, MAX_STDERR_LINE, "$command stderr")
                    while (true) {
                        val line = try {
                            stderrLines.readLine()
                        } catch (tooLong: MCPTransportException) {
                            Log.w(TAG, tooLong.message ?: "stderr line too long")
                            stderrLines.skipToEndOfLine()
                            null
                        } ?: break
                        Log.d(TAG, "[$command] stderr: ${line.take(500)}")
                    }
                }
            }
        }.apply { isDaemon = true; name = "mcp-stderr-$command" }.start()
    }

    /**
     * One request on the wire at a time. Sessions of one server share this pipe
     * and its single stdout reader, so two readers would interleave frames.
     */
    private val requestLock = Mutex()

    /**
     * Writes [frame] and returns the reply that carries the same `id`.
     * A frame without an `id` is a notification: JSON-RPC defines no reply to it,
     * so it is written and an empty object comes back without reading anything.
     * Frames the server sends on its own (notifications, requests) are skipped.
     */
    suspend fun send(frame: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        requestLock.withLock { exchange(frame) }
    }

    private suspend fun exchange(frame: JSONObject): JSONObject {
        val w = writer ?: throw MCPTransportException("stdio transport not started")
        val r = lines ?: throw MCPTransportException("stdio transport not started")
        val p = process ?: throw MCPTransportException("stdio transport process missing")
        val line = MCPClientCodec.encodeFrame(frame).replace("\n", "")
        try {
            w.write(line)
            w.write("\n")
            w.flush()
        } catch (t: Throwable) {
            throw MCPTransportException(
                "failed to write MCP request to $command: ${t.message}",
                if (!p.isAlive) MCPTransportFailureKind.PROCESS_KILLED else MCPTransportFailureKind.TRANSPORT_FAILURE,
            )
        }

        val expectedId = frame.opt("id") ?: return JSONObject()

        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { close() }
            Thread {
                try {
                    var reply: String?
                    var parsed: JSONObject? = null
                    while (true) {
                        reply = try {
                            r.readLine()
                        } catch (tooLong: MCPTransportException) {
                            // A server that floods one frame is broken: drop the whole
                            // transport (kills the process) instead of trying to resync.
                            close()
                            throw tooLong
                        }
                        if (reply == null || !continuation.isActive) break
                        val candidate = try {
                            JSONObject(reply)
                        } catch (t: Throwable) {
                            continuation.resumeWith(
                                Result.failure(MCPTransportException("invalid JSON from $command: ${reply.take(120)}")),
                            )
                            return@Thread
                        }
                        // Our reply, not a notification or a request the server made.
                        if (candidate.opt("id")?.toString() == expectedId.toString() &&
                            (candidate.has("result") || candidate.has("error"))
                        ) {
                            parsed = candidate
                            break
                        }
                    }
                    if (!continuation.isActive) return@Thread
                    if (reply == null) {
                        continuation.resumeWith(
                            Result.failure(
                                MCPTransportException(
                                    "$command closed stdout",
                                    if (!p.isAlive) MCPTransportFailureKind.PROCESS_KILLED else MCPTransportFailureKind.TRANSPORT_FAILURE,
                                ),
                            ),
                        )
                        return@Thread
                    }
                    continuation.resumeWith(Result.success(parsed!!))
                } catch (t: Throwable) {
                    if (!continuation.isActive) return@Thread
                    continuation.resumeWith(
                        Result.failure(
                            MCPTransportException(
                                "MCP stdio read failed: ${t.message}",
                                if (!p.isAlive) MCPTransportFailureKind.PROCESS_KILLED else MCPTransportFailureKind.TRANSPORT_FAILURE,
                            ),
                        ),
                    )
                }
            }.apply {
                isDaemon = true
                name = "mcp-read-$command"
                start()
            }
        }
    }

    @Synchronized
    fun close() {
        val p = process
        process = null
        val w = writer
        writer = null
        val r = reader
        reader = null
        lines = null

        // A reader thread may be blocked in BufferedReader.readLine(). Closing
        // that same reader first can wait on its lock and prevent us from ever
        // reaching Process.destroy(). Kill the subprocess first so stdout closes
        // and the blocked read unblocks, then close the streams.
        p?.let {
            it.destroy()
            if (it.isAlive) it.destroyForcibly()
        }
        w?.runCatching { close() }
        r?.runCatching { close() }
    }

    internal fun hasLiveProcessForTest(): Boolean = process?.isAlive == true
}
