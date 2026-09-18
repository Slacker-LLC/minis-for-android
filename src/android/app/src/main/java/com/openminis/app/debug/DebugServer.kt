package com.openminis.app.debug

import android.content.Context
import android.util.Log
import com.openminis.app.network.BoundedHttp
import com.openminis.app.network.BoundedHttpException
import com.openminis.app.network.BoundedHttpRequestReader
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Debug-only JSON-RPC 2.0 server on port 5321.
 * Listens on 127.0.0.1 only — reachable via `adb forward` or on-device,
 * never from the local network.
 * Mirrors the iOS DebugServer for parity with the debug-server CLI skill.
 *
 * IMPORTANT: Only start this in debug builds. Never start in release.
 */
class DebugServer(
    private val context: Context,
    private val port: Int = 5321,
) {
    companion object {
        private const val TAG = "DebugServer"

        /**
         * [T-android-debugserver-auth] Remote-connection auth decision, kept
         * pure for unit testing. EVERY connection must present the device
         * token. Loopback is not exempt: on Android any local process or web
         * page can reach 127.0.0.1, and this RPC surface can read files,
         * export API keys, run shell commands and drive the UI — a loopback
         * exemption would be an unauthenticated backdoor on the device.
         * The developer workflow reads the token via adb run-as instead.
         */
        fun isAuthorized(isLoopback: Boolean, providedToken: String?, expectedToken: String): Boolean {
            if (expectedToken.isEmpty()) return false
            val provided = providedToken ?: return false
            if (provided.length != expectedToken.length) return false
            // Constant-time compare — don't leak the token via timing.
            var diff = 0
            for (i in expectedToken.indices) {
                diff = diff or (provided[i].code xor expectedToken[i].code)
            }
            return diff == 0
        }

    }

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val connectionJobs = java.util.concurrent.ConcurrentHashMap.newKeySet<Job>()
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, t ->
                Log.e(TAG, "uncaught coroutine exception: ${t.message}", t)
            },
    )
    private val rpcHandler = DebugRPCHandler(context)


    /**
     * [T-android-debugserver-auth] Per-install token required from ALL
     * clients (loopback included). Generated once, persisted in filesDir so
     * the developer can read it with:
     *   adb shell run-as <applicationId> cat files/debug_server_token
     * The token value is intentionally never logged: logcat is readable by
     * more than adb on many devices, and leaking it would void the only
     * barrier protecting the RPC surface.
     */
    private val authToken: String by lazy {
        val f = java.io.File(context.filesDir, "debug_server_token")
        if (f.exists()) {
            f.readText().trim().ifEmpty { generateToken(f) }
        } else {
            generateToken(f)
        }
    }

    private fun generateToken(f: java.io.File): String {
        val bytes = ByteArray(24)
        java.security.SecureRandom().nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        runCatching { f.writeText(token) }
        return token
    }

    @Volatile
    private var stopped = false

    @Synchronized
    fun start() {
        if (acceptJob != null) return
        stopped = false

        acceptJob = scope.launch {
            try {
                // Loopback only (127.0.0.1) — reachable via adb forward / on-device
                val ss = ServerSocket(port, 10, InetAddress.getByName("127.0.0.1"))
                serverSocket = ss
                Log.i(TAG, "Debug server listening on 127.0.0.1:$port (loopback only)")
                Log.i(TAG, "All clients must send X-Minis-Token (see files/debug_server_token)")

                while (!stopped) {
                    try {
                        val client = ss.accept()
                        var job: Job? = null
                        job = scope.launch {
                            try {
                                handleConnection(client)
                            } catch (t: Throwable) {
                                // Never let one broken connection take the
                                // whole process down (OOM aside).
                                Log.w(TAG, "connection handler crashed: ${t.message}")
                            } finally {
                                job?.let(connectionJobs::remove)
                            }
                        }
                        connectionJobs.add(job)
                    } catch (e: Exception) {
                        if (!stopped) Log.w(TAG, "Accept error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start server on port $port: ${e.message}")
            }
        }
    }

    @Synchronized
    fun stop() {
        stopped = true
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
        connectionJobs.forEach { it.cancel() }
        connectionJobs.clear()
        Log.i(TAG, "Server stopped")
    }

    private fun handleConnection(socket: Socket) {
        socket.use { s ->
            val output = s.getOutputStream()
            try {
                s.soTimeout = 30_000
                val reader = BoundedHttpRequestReader(s.getInputStream())

                // Read HTTP request line (bounded — an unbounded readLine
                // would let a peer allocate unbounded memory).
                val head = reader.readHead() ?: return
                val method = head.method
                // [T-android-debugserver-skill] Path (query stripped) so the
                // authenticated GET skill routes can be dispatched.
                val path = head.target.substringBefore('?')

                // Handle CORS preflight
                if (method == "OPTIONS") {
                    sendCorsPreflightResponse(output)
                    return
                }

                // Read headers
                val contentLength = head.contentLength
                val accept = head.header("accept").orEmpty().lowercase()
                // [T-android-debugserver-auth] Token via X-Minis-Token or
                // Authorization: Bearer — either spelling accepted.
                val authorization = head.header("authorization").orEmpty()
                val providedToken = if (authorization.lowercase().startsWith("bearer ")) {
                    authorization.substring(7).trim()
                } else {
                    head.header("x-minis-token")
                }

                // [T-android-debugserver-auth] Gate BEFORE any RPC dispatch:
                // every connection — loopback included — needs the token.
                val isLoopback = s.inetAddress?.isLoopbackAddress == true
                if (!isAuthorized(isLoopback, providedToken, authToken)) {
                    val peer = if (isLoopback) "loopback" else s.inetAddress?.hostAddress ?: "?"
                    Log.w(TAG, "401 unauthorized $peer (missing/wrong token)")
                    sendResponse(
                        output, 401,
                        rpcHandler.errorJSON(
                            -32000,
                            "Unauthorized — send X-Minis-Token (see `adb shell run-as <applicationId> cat files/debug_server_token`)"
                        ),
                    )
                    return
                }
                // [T-android-debugserver-skill] Self-serve bootstrap routes,
                // mirroring iOS: a client that only has curl can pull the manual
                // and a working reference client straight off the device.
                //
                // Deliberately placed AFTER the auth gate above: unlike iOS
                // (whose /skill is unauthenticated because every RPC is still
                // sealed by the v1 envelope), Android's RPCs are plaintext, so
                // the token remains the barrier for every client and must not
                // be bypassed. `adb forward` only provides transport to the
                // loopback listener; the client still sends the same per-install
                // token as any other connection.
                if (method == "GET") {
                    val wantsHuman = accept.contains("text/html") ||
                        accept.contains("text/markdown") ||
                        accept.contains("text/plain")
                    when (path) {
                        "/schema" -> {
                            sendResponse(output, 200, schemaJSON())
                            return
                        }
                        "/", "/skill", "/skill/" -> {
                            // A bare `GET /` from a tool (no human Accept) keeps
                            // returning the machine schema, same contract as iOS.
                            if (path == "/" && !wantsHuman) {
                                sendResponse(output, 200, schemaJSON())
                            } else {
                                sendSkill(output, wantsHuman)
                            }
                            return
                        }
                        "/skill/examples/python", "/skill/examples/minis_rpc_android.py" -> {
                            sendSkillAsset(output, "examples/minis_rpc_android.py", "text/x-python; charset=utf-8")
                            return
                        }
                        "/skill/examples/curl", "/skill/examples/curl.md" -> {
                            sendSkillAsset(output, "examples/curl.md", "text/markdown; charset=utf-8")
                            return
                        }
                    }
                }

                if (method != "POST") {
                    sendResponse(output, 405, rpcHandler.errorJSON(-32600, "Only POST accepted"))
                    return
                }

                if (contentLength <= 0) {
                    sendResponse(output, 400, rpcHandler.errorJSON(-32700, "Empty body"))
                    return
                }

                // Content-Length is a byte count. Decode only after exactly
                // that many bytes have arrived, using strict UTF-8.
                val jsonBody = BoundedHttp.decodeUtf8(reader.readBody(contentLength))

                val responseJSON = runBlocking {
                    rpcHandler.handle(jsonBody)
                }

                sendResponse(output, 200, responseJSON)
            } catch (e: BoundedHttpException) {
                sendResponse(
                    output,
                    e.statusCode,
                    rpcHandler.errorJSON(-32700, e.message ?: "Parse error"),
                )
            } catch (e: Exception) {
                Log.w(TAG, "Connection error: ${e.message}")
            }
        }
    }

    /// [T-android-debugserver-skill] Capability descriptor. Mirrors the iOS
    /// shape so a shared client can detect the platform. `auth` keeps the
    /// legacy "token-lan" capability id for shared-client compatibility;
    /// `token_required` records the actual Android policy: every connection,
    /// including loopback/adb-forwarded clients, must present the token.
    private fun schemaJSON(): String {
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
        // Escape for JSON: a versionName containing a quote would otherwise
        // break the schema document.
        val escaped = version.replace("\\", "\\\\").replace("\"", "\\\"")
        return """{"app":"MinisApp","platform":"android","version":"$escaped",""" +
            """"rpc":"jsonrpc-2.0","auth":"token-lan","token_required":"all","transport":"plaintext",""" +
            """"rpc_path":"/","skill_path":"/skill"}"""
    }

    /// GET / and /skill. Human clients get raw markdown; tools get JSON with the
    /// manual plus every reference client inlined, so one request bootstraps.
    private fun sendSkill(output: OutputStream, wantsHuman: Boolean) {
        val skill = readSkillAsset("SKILL.md")
        if (skill == null) {
            sendResponse(output, 503, """{"error":"skill assets not bundled in this build"}""")
            return
        }
        if (wantsHuman) {
            sendResponse(output, 200, skill, "text/markdown; charset=utf-8")
            return
        }
        val py = readSkillAsset("examples/minis_rpc_android.py") ?: ""
        val curl = readSkillAsset("examples/curl.md") ?: ""
        val payload = org.json.JSONObject().apply {
            put("skill", skill)
            put("clients", org.json.JSONObject().apply {
                put("python", py)
                put("curl", curl)
            })
            put("client_paths", org.json.JSONObject().apply {
                put("python", "/skill/examples/python")
                put("curl", "/skill/examples/curl")
            })
        }
        sendResponse(output, 200, payload.toString())
    }

    private fun sendSkillAsset(output: OutputStream, assetPath: String, contentType: String) {
        val body = readSkillAsset(assetPath)
        if (body == null) {
            sendResponse(output, 503, """{"error":"asset not bundled: $assetPath"}""")
            return
        }
        sendResponse(output, 200, body, contentType)
    }

    /// Reads from the DEBUG-only asset source set staged by
    /// scripts/gen_debug_skill_android.sh. Absent in release (where this whole
    /// server never starts) and absent if the generator didn't run.
    private fun readSkillAsset(relPath: String): String? = try {
        context.assets.open("debug-skill/$relPath").bufferedReader().use { it.readText() }
    } catch (_: Exception) {
        null
    }

    private fun sendResponse(
        output: OutputStream,
        statusCode: Int,
        body: String,
        contentType: String = "application/json",
    ) {
        BoundedHttp.writeResponse(output, statusCode, body, contentType)
    }

    private fun sendCorsPreflightResponse(output: OutputStream) {
        // Preflight answers 204 WITHOUT any Access-Control-Allow-* headers,
        // so browsers refuse the cross-origin call outright.
        BoundedHttp.writeResponse(output, 204, "", contentType = null)
    }
}
