package com.openminis.app.mcp.client

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * stdio transport for local MCP servers (`command` + `args` in servers.json).
 * One JSON-RPC frame per line on stdin/stdout, per the spec's stdio transport.
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

    private var process: Process? = null
    private var writer: java.io.BufferedWriter? = null
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
        // Drain stderr so a chatty server never fills the pipe and deadlocks.
        Thread {
            val err = p.errorStream.bufferedReader()
            while (true) {
                val line = err.readLine() ?: break
                Log.d(TAG, "[$command] stderr: ${line.take(500)}")
            }
        }.apply { isDaemon = true; name = "mcp-stderr-$command" }.start()
    }

    suspend fun send(frame: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val w = writer ?: throw MCPTransportException("stdio transport not started")
        val r = reader ?: throw MCPTransportException("stdio transport not started")
        val line = MCPClientCodec.encodeFrame(frame).replace("\n", "")
        w.write(line)
        w.write("\n")
        w.flush()
        val reply = r.readLine() ?: throw MCPTransportException("$command closed stdout")
        if (reply.length > MAX_LINE) {
            throw MCPTransportException("oversized reply from $command")
        }
        try {
            JSONObject(reply)
        } catch (t: Throwable) {
            throw MCPTransportException("invalid JSON from $command: ${reply.take(120)}")
        }
    }

    fun close() {
        writer?.runCatching { close() }
        reader?.runCatching { close() }
        process?.destroy()
        process = null
        writer = null
        reader = null
    }
}
