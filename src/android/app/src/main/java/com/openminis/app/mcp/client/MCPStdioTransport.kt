package com.openminis.app.mcp.client

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
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
    }

    @Volatile
    private var process: Process? = null
    @Volatile
    private var writer: java.io.BufferedWriter? = null
    @Volatile
    private var reader: BufferedReader? = null

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
        reader = p.inputStream.bufferedReader()
        Thread {
            runCatching {
                p.errorStream.bufferedReader().use { err ->
                    while (true) {
                        val line = err.readLine() ?: break
                        Log.d(TAG, "[$command] stderr: ${line.take(500)}")
                    }
                }
            }
        }.apply { isDaemon = true; name = "mcp-stderr-$command" }.start()
    }

    suspend fun send(frame: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val w = writer ?: throw MCPTransportException("stdio transport not started")
        val r = reader ?: throw MCPTransportException("stdio transport not started")
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

        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { close() }
            Thread {
                try {
                    val reply = r.readLine()
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
                    if (reply.length > MAX_LINE) {
                        continuation.resumeWith(Result.failure(MCPTransportException("oversized reply from $command")))
                        return@Thread
                    }
                    val parsed = try {
                        JSONObject(reply)
                    } catch (t: Throwable) {
                        continuation.resumeWith(
                            Result.failure(MCPTransportException("invalid JSON from $command: ${reply.take(120)}")),
                        )
                        return@Thread
                    }
                    continuation.resumeWith(Result.success(parsed))
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
