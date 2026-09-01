package io.github.slackerllc.minis.mcp.server

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import io.github.slackerllc.minis.notification.McpConfirmNotifier
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * [T-android-mcp-server] On-device MCP server (07 §1/§2).
 *
 * Streamable HTTP, stateless, POST /mcp, JSON-RPC 2.0. Binds 127.0.0.1 only.
 * Bearer token required on every request (TokenStore); no token configured →
 * the manager refuses to start (fail-closed). Tools are served from
 * ToolRegistry/ToolExecutor through the D8 permission model: LOCAL_ONLY and
 * MCP_DENIED tools are invisible to `tools/list` and rejected on call.
 */
class MCPServer(private val context: Context?, private val port: Int = MCPServerManager.PORT) {

    companion object {
        private const val TAG = "MCPServer"
        private const val MAX_BODY_BYTES = 4 * 1024 * 1024
        private const val CODE_CONFIRM_REQUIRED = -32001
        private const val CODE_CONFIRM_REJECTED = -32002
        private const val CODE_CONFIRM_QUEUE_FULL = -32003

        /** A4: connection cap — local slowloris must not exhaust the accept loop. */
        const val MAX_CONNECTIONS = 16
    }

    @Volatile
    var isRunning: Boolean = false
        private set

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val connectionJobs = ConcurrentHashMap.newKeySet<Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val confirmQueue = ConfirmQueue()

    /** DEBUG-only: approve a pending confirm (see MCPServerManager.debugApproveConfirm). */
    fun approveConfirm(confirmId: String, method: String) = confirmQueue.approve(confirmId, method)
    private val connectionSlots = Semaphore(MAX_CONNECTIONS)

    /**
     * A2/A1: binds synchronously so [isRunning] is authoritative when this
     * returns (manager reads it right after), and the accept loop checks
     * [isActive] + [isRunning] so [stop] terminates it instead of spinning
     * on a closed socket.
     */
    @Synchronized
    fun start() {
        if (acceptJob != null) return
        val ss = try {
            ServerSocket(port, 10, InetAddress.getByName("127.0.0.1"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind MCP server on 127.0.0.1:$port: ${e.message}")
            return
        }
        serverSocket = ss
        isRunning = true
        Log.i(TAG, "MCP server listening on 127.0.0.1:$port")
        acceptJob = scope.launch {
            try {
                while (isActive && isRunning) {
                    val client = try {
                        ss.accept()
                    } catch (e: Exception) {
                        if (isActive && isRunning) Log.w(TAG, "Accept error: ${e.message}")
                        continue
                    }
                    var job: Job? = null
                    job = scope.launch {
                        try {
                            handleConnection(client)
                        } catch (t: Throwable) {
                            Log.w(TAG, "connection handler crashed: ${t.message}")
                        } finally {
                            job?.let(connectionJobs::remove)
                        }
                    }
                    connectionJobs.add(job)
                }
            } finally {
                isRunning = false
            }
        }
    }

    @Synchronized
    fun stop() {
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
        connectionJobs.forEach { it.cancel() }
        connectionJobs.clear()
        isRunning = false
    }

    private suspend fun handleConnection(socket: Socket) {
        val s = socket
        var slotAcquired = false
        try {
            // A4: reject when all connection slots are taken
            if (!connectionSlots.tryAcquire()) {
                runCatching {
                    val w = java.io.PrintWriter(s.getOutputStream(), true)
                    sendHttp(w, 503, "{\"error\":\"server busy\"}")
                }
                return
            }
            slotAcquired = true
            s.soTimeout = 30_000
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            val writer = java.io.PrintWriter(s.getOutputStream(), true)

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ", limit = 3)
            if (parts.size < 2 || parts[0] != "POST") {
                sendHttp(writer, 405, "{\"error\":\"method not allowed\"}")
                return
            }
            val path = parts[1].substringBefore('?')
            if (path != "/mcp") {
                sendHttp(writer, 404, "{\"error\":\"not found\"}")
                return
            }

            var contentLength = 0
            var token: String? = null
            var headerCount = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                headerCount++
                if (headerCount > 100) {
                    sendHttp(writer, 400, "{\"error\":\"too many headers\"}")
                    return
                }
                val lower = line.lowercase()
                if (lower.startsWith("content-length:")) {
                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                }
                if (lower.startsWith("authorization:")) {
                    val v = line.substringAfter(":").trim()
                    if (v.lowercase().startsWith("bearer ")) token = v.substring(7).trim()
                }
            }

            if (contentLength <= 0 || contentLength > MAX_BODY_BYTES) {
                sendHttp(writer, 400, "{\"error\":\"bad content length\"}")
                return
            }
            val body = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = reader.read(body, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            val raw = String(body, 0, read)

            // fail-closed auth: token required on every request
            val tokenRecord = token?.let { TokenStore.find(it) }
            if (tokenRecord == null) {
                sendHttp(writer, 401, "{\"error\":\"unauthorized\"}")
                return
            }

            val request = MCPCodec.parseRequest(raw)
            if (request == null) {
                sendHttp(writer, 200, MCPCodec.errorResponse(null, MCPCodec.PARSE_ERROR, "Parse error"))
                return
            }
            val response = dispatch(request, tokenRecord)
            sendHttp(writer, 200, response ?: "")
        } finally {
            if (slotAcquired) connectionSlots.release()
            runCatching { s.close() }
        }
    }

    /** Returns the JSON-RPC response body, or null for notifications (no reply). */
    private suspend fun dispatch(req: MCPCodec.MCPRequest, tokenRecord: TokenStore.Token): String? {
        val caller = "mcp:${tokenRecord.id}"
        return try {
            when (req.method) {
                MCPCodec.METHOD_INITIALIZE -> MCPCodec.response(req.id, MCPCodec.initializeResult())
                MCPCodec.METHOD_PING -> MCPCodec.response(req.id, MCPCodec.pingResult())
                MCPCodec.METHOD_TOOLS_LIST -> {
                    val defs = io.github.slackerllc.minis.tools.runtime.ToolRegistry.definitionsForCaller(caller)
                    val arr = JSONArray()
                    for (d in defs) {
                        arr.put(d.toAnthropicJson())
                    }
                    MCPCodec.response(req.id, MCPCodec.toolsListResult(arr))
                }
                MCPCodec.METHOD_TOOLS_CALL -> handleToolCall(req, tokenRecord, caller)
                else -> {
                    if (MCPCodec.isNotification(req.method)) {
                        null
                    } else {
                        MCPCodec.errorResponse(req.id, MCPCodec.METHOD_NOT_FOUND, "Method not found: ${req.method}")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "dispatch ${req.method} crashed: ${t.message}")
            // A6: fixed client-facing text; details stay in the log
            MCPCodec.errorResponse(req.id, MCPCodec.INTERNAL_ERROR, "Internal error")
        }
    }

    private suspend fun handleToolCall(
        req: MCPCodec.MCPRequest,
        tokenRecord: TokenStore.Token,
        caller: String,
    ): String {
        val params = req.params
        val name = params.optString("name")
        val arguments = params.optJSONObject("arguments") ?: JSONObject()
        if (name.isEmpty()) {
            return MCPCodec.errorResponse(req.id, MCPCodec.INVALID_PARAMS, "Missing tool name")
        }
        // Aliases are a compatibility input surface only; policy, scope,
        // confirmation, and execution must all operate on one canonical name.
        val canonicalName = io.github.slackerllc.minis.tools.runtime.ToolRegistry.canonicalName(name) ?: name

        // D8 gate: level + token scope
        if (!io.github.slackerllc.minis.tools.runtime.ToolPermissionManager.tokenCanCall(
                canonicalName, caller, tokenRecord.scope,
                io.github.slackerllc.minis.tools.runtime.ToolPermissionManager.Level.MCP_CONFIRM,
            )
        ) {
            return MCPCodec.errorResponse(
                req.id, MCPCodec.INVALID_REQUEST,
                "permission_denied: $canonicalName",
            )
        }

        // A5: liveness first — a dead Ubuntu runtime answers immediately
        // without burning a user confirmation (ACC-T-02 semantics). One-shot
        // revive: when minisd is already up this is a cheap status refresh.
        if (canonicalName.startsWith("linux.") && !canonicalName.startsWith("linux.file.") && !MCPServerManager.linuxToolsAvailable()) {
            if (!io.github.slackerllc.minis.runtime.ubuntu.UbuntuRuntime.ensureReady().running) {
                val err = io.github.slackerllc.minis.tools.ToolExecutionResult(
                    output = "Error: ubuntu_runtime_unavailable: $canonicalName",
                    success = false,
                )
                return MCPCodec.response(req.id, MCPCodec.toolResult(err.output, isError = true))
            }
        }

        // MCP_CONFIRM gate: one-shot confirm issued/consumed before execution (07 §6).
        // On issue, post the phone notification with 批准/拒绝 actions so the
        // user can answer without foregrounding the app (McpConfirmNotifier).
        var confirmConsumed = false
        if (io.github.slackerllc.minis.tools.runtime.ToolPermissionManager.needsConfirm(canonicalName, caller)) {
            val confirmId = params.optString("confirm_id")
            if (confirmId.isEmpty()) {
                val id = confirmQueue.issue(canonicalName, canonicalName)
                if (id == null) {
                    return errorWithData(req.id, CODE_CONFIRM_QUEUE_FULL, "confirm_queue_full", JSONObject())
                }
                Log.i(TAG, "confirm required for $canonicalName (confirm_id=$id)")
                // context is null in unit-test paths: log only, no notification.
                context?.let {
                    McpConfirmNotifier.show(it, canonicalName, canonicalName, id, ConfirmQueue.DEFAULT_TTL_MILLIS)
                }
                return errorWithData(
                    req.id, CODE_CONFIRM_REQUIRED, "confirm_required",
                    JSONObject()
                        .put("confirm_id", id)
                        .put("expires_in_ms", ConfirmQueue.DEFAULT_TTL_MILLIS),
                )
            }
            val consume = confirmQueue.consume(confirmId, canonicalName)
            if (consume != ConfirmQueue.Result.OK) {
                return errorWithData(
                    req.id, CODE_CONFIRM_REJECTED, "confirm_rejected",
                    JSONObject()
                        .put("confirm_id", confirmId)
                        .put("reason", consume.name.lowercase()),
                )
            }
            confirmConsumed = true
        } else {
            confirmConsumed = true
        }

        val result = io.github.slackerllc.minis.tools.runtime.ToolExecutor.execute(
            name = canonicalName,
            argsJson = arguments.toString(),
            sessionId = "mcp",
            context = context ?: return MCPCodec.response(
                req.id,
                MCPCodec.toolResult("Error: no app context", isError = true),
            ),
            caller = caller,
            toolId = "",
            confirmBypassed = confirmConsumed,
        )
        return MCPCodec.response(
            req.id,
            MCPCodec.toolResult(result.output, isError = !result.success),
        )
    }

    /**
     * Error frame with a data payload: {"jsonrpc":"2.0","id":…,"error":
     * {"code":…,"message":…,"data":{…}}}. Mirrors [MCPCodec.errorResponse];
     * org.json drops null ids, so an explicit null id becomes JSONObject.NULL.
     */
    private fun errorWithData(id: Any?, code: Int, message: String, data: JSONObject): String =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id ?: JSONObject.NULL)
            .put(
                "error",
                JSONObject().put("code", code).put("message", message).put("data", data),
            )
            .toString()

    private fun sendHttp(writer: java.io.PrintWriter, code: Int, body: String) {
        writer.print("HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\n")
        writer.print("Content-Type: application/json\r\n")
        writer.print("Content-Length: ${body.toByteArray().size}\r\n")
        writer.print("Connection: close\r\n\r\n")
        writer.print(body)
        writer.flush()
    }
}
