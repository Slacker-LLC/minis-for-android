package com.openminis.app.remote

import android.content.Context
import android.util.Log
import com.openminis.app.data.ContextOffload
import com.openminis.app.debug.ChatDebugMethods
import com.openminis.app.debug.ChatMutationMethods
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.tools.FileEditTool
import com.openminis.app.tools.FileWriteTool
import com.openminis.app.tools.internal.ShellOutputTruncator
import com.openminis.app.tools.internal.FileRevision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * Production remote-control HTTP bridge. It intentionally exposes only a small
 * allow-listed surface and reuses the same ChatViewModel/Session machinery as
 * the native UI. No API-key/provider secrets are ever returned.
 *
 * Security model:
 *  - disabled by default and refuses to start until a login password exists;
 *  - browser login uses PBKDF2-verified credentials + HttpOnly SameSite cookie;
 *  - a per-install bearer token remains available only for CLI/automation;
 *  - credentials are never accepted in query strings or returned by settings;
 *  - cookie-authenticated mutations are same-origin checked; no permissive CORS;
 *  - session filesystem paths resolve through PRootKernel guards.
 * TLS terminates at Cloudflare Tunnel / another reverse proxy for Internet use.
 */
class RemoteAccessServer(
    context: Context,
    private val port: Int,
    private val token: String,
    private val bindHost: String = "127.0.0.1",
) {
    companion object {
        private const val TAG = "RemoteAccessServer"
        private const val MAX_BODY = 4 * 1024 * 1024
        private const val MAX_EDIT_FILE_BYTES = 2L * 1024 * 1024
        private const val SOCKET_TIMEOUT_MS = 30_000
        private const val SESSION_TTL_MS = 12L * 60L * 60L * 1000L
        private const val LOGIN_LOCK_MS = 60_000L
        private const val SESSION_COOKIE = "minis_session"

        fun constantTimeTokenEquals(expected: String, provided: String?): Boolean {
            if (expected.isEmpty() || provided == null || expected.length != provided.length) return false
            var diff = 0
            for (i in expected.indices) diff = diff or (expected[i].code xor provided[i].code)
            return diff == 0
        }
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var stopped = false
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val connectionSlots = Semaphore(32)
    private val sessions = ConcurrentHashMap<String, Long>()
    private val secureRandom = SecureRandom()
    @Volatile private var failedLogins = 0
    @Volatile private var loginLockedUntil = 0L

    @Synchronized
    fun start(): Boolean {
        if (acceptJob != null) return true
        stopped = false
        val ss = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName(bindHost), port), 32)
            }
        } catch (e: Exception) {
            Log.e(TAG, "server failed to bind $bindHost:$port: ${e.message}", e)
            return false
        }
        serverSocket = ss
        Log.i(TAG, "Web remote listening on $bindHost:$port")
        acceptJob = scope.launch {
            while (!stopped) {
                try {
                    val socket = ss.accept()
                    if (!connectionSlots.tryAcquire()) {
                        runCatching { socket.close() }
                        continue
                    }
                    launch {
                        try { handle(socket) } finally { connectionSlots.release() }
                    }
                } catch (e: Exception) {
                    if (!stopped) Log.w(TAG, "accept failed: ${e.message}")
                }
            }
        }
        return true
    }

    fun stop() {
        stopped = true
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
        sessions.clear()
        scope.cancel()
    }

    private data class Request(
        val method: String,
        val rawPath: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: String,
    )

    private fun handle(socket: Socket) {
        socket.use { s ->
            s.soTimeout = SOCKET_TIMEOUT_MS
            val input = BufferedInputStream(s.getInputStream())
            val output = BufferedOutputStream(s.getOutputStream())
            try {
                val req = readRequest(input) ?: return
                if (req.method == "OPTIONS") {
                    respond(output, 204, "text/plain; charset=utf-8", "")
                    return
                }

                if (req.path.startsWith("/api/auth/")) {
                    routeAuth(req, output)
                    return
                }

                if (req.path.startsWith("/api/")) {
                    val auth = authenticate(req)
                    if (auth == AuthKind.NONE) {
                        respondJson(output, 401, JSONObject().put("error", "unauthorized"))
                        return
                    }
                    if (auth == AuthKind.COOKIE && isMutating(req.method) && !sameOrigin(req)) {
                        respondJson(output, 403, JSONObject().put("error", "cross-origin request rejected"))
                        return
                    }
                    routeApi(req, output)
                } else {
                    routeStatic(req, output)
                }
            } catch (e: BodyTooLargeException) {
                respondJson(output, 413, JSONObject().put("error", "request body too large"))
            } catch (e: IllegalArgumentException) {
                respondJson(output, 400, JSONObject().put("error", e.message ?: "bad request"))
            } catch (e: org.json.JSONException) {
                respondJson(output, 400, JSONObject().put("error", e.message ?: "invalid JSON"))
            } catch (e: Exception) {
                Log.w(TAG, "request failed: ${e.message}")
                respondJson(output, 500, JSONObject().put("error", e.message ?: "internal error"))
            }
        }
    }

    private enum class AuthKind { NONE, COOKIE, BEARER }

    private fun routeAuth(req: Request, out: BufferedOutputStream) {
        when (req.path) {
            "/api/auth/status" -> {
                requireMethod(req, "GET")
                val authenticated = authenticate(req) != AuthKind.NONE
                respondJson(out, 200, JSONObject().apply {
                    put("authenticated", authenticated)
                    if (authenticated) put("username", RemoteAccessPrefs.username(appContext))
                })
            }
            "/api/auth/login" -> {
                requireMethod(req, "POST")
                if (!RemoteAccessPrefs.hasPassword(appContext)) {
                    respondJson(out, 503, JSONObject().put("error", "Remote login password is not configured on the phone"))
                    return
                }
                val now = System.currentTimeMillis()
                if (now < loginLockedUntil) {
                    respondJson(out, 429, JSONObject().put("error", "too many login attempts").put("retryAfterMs", loginLockedUntil - now))
                    return
                }
                val body = JSONObject(req.body)
                val username = body.optString("username")
                val password = body.optString("password").toCharArray()
                val ok = RemoteAccessPrefs.verifyLogin(appContext, username, password)
                if (!ok) {
                    failedLogins += 1
                    if (failedLogins >= 5) loginLockedUntil = now + LOGIN_LOCK_MS
                    respondJson(out, 401, JSONObject().put("error", "invalid username or password"))
                    return
                }
                failedLogins = 0
                loginLockedUntil = 0L
                val id = newSessionId()
                sessions[id] = now + SESSION_TTL_MS
                val cookie = buildString {
                    append(SESSION_COOKIE).append('=').append(id)
                    append("; Path=/; HttpOnly; SameSite=Strict; Max-Age=").append(SESSION_TTL_MS / 1000L)
                    if (isHttps(req)) append("; Secure")
                }
                respondJson(out, 200, JSONObject().put("ok", true).put("username", RemoteAccessPrefs.username(appContext)), mapOf("Set-Cookie" to cookie))
            }
            "/api/auth/logout" -> {
                requireMethod(req, "POST")
                cookieValue(req, SESSION_COOKIE)?.let { sessions.remove(it) }
                val cookie = buildString {
                    append(SESSION_COOKIE).append("=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0")
                    if (isHttps(req)) append("; Secure")
                }
                respondJson(out, 200, JSONObject().put("ok", true), mapOf("Set-Cookie" to cookie))
            }
            else -> respondJson(out, 404, JSONObject().put("error", "not found"))
        }
    }

    private fun authenticate(req: Request): AuthKind {
        val bearer = req.headers["authorization"]
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring(7)?.trim()
        val headerToken = req.headers["x-minis-token"]?.trim()
        if (constantTimeTokenEquals(token, bearer ?: headerToken)) return AuthKind.BEARER

        val sid = cookieValue(req, SESSION_COOKIE) ?: return AuthKind.NONE
        val expiry = sessions[sid] ?: return AuthKind.NONE
        val now = System.currentTimeMillis()
        if (expiry <= now) {
            sessions.remove(sid)
            return AuthKind.NONE
        }
        sessions[sid] = now + SESSION_TTL_MS
        return AuthKind.COOKIE
    }

    private fun cookieValue(req: Request, name: String): String? =
        req.headers["cookie"]
            ?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotBlank() }

    private fun newSessionId(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun isHttps(req: Request): Boolean =
        req.headers["x-forwarded-proto"]?.equals("https", ignoreCase = true) == true ||
            req.headers["cf-visitor"]?.contains("\"scheme\":\"https\"") == true

    private fun isMutating(method: String): Boolean = method in setOf("POST", "PUT", "PATCH", "DELETE")

    private fun sameOrigin(req: Request): Boolean {
        val origin = req.headers["origin"] ?: return true
        val host = req.headers["host"] ?: return false
        val scheme = if (isHttps(req)) "https" else "http"
        return origin.equals("$scheme://$host", ignoreCase = true)
    }

    private fun routeStatic(req: Request, out: BufferedOutputStream) {
        if (req.method != "GET") {
            respond(out, 405, "text/plain; charset=utf-8", "Method Not Allowed")
            return
        }
        val asset = when (req.path) {
            "/", "/index.html" -> "remote/index.html"
            "/app.css" -> "remote/app.css"
            "/app.js" -> "remote/app.js"
            "/md.js" -> "remote/md.js"
            "/marked.js" -> "remote/marked.js"
            "/purify.js" -> "remote/purify.js"
            else -> null
        }
        if (asset == null) {
            respond(out, 404, "text/plain; charset=utf-8", "Not Found")
            return
        }
        val mime = when {
            asset.endsWith(".html") -> "text/html; charset=utf-8"
            asset.endsWith(".css") -> "text/css; charset=utf-8"
            asset.endsWith(".js") -> "application/javascript; charset=utf-8"
            else -> "application/octet-stream"
        }
        val bytes = appContext.assets.open(asset).use { it.readBytes() }
        respondBytes(out, 200, mime, bytes)
    }

    private fun routeApi(req: Request, out: BufferedOutputStream) = runBlocking {
        when (req.path) {
            "/api/status" -> respondJson(out, 200, JSONObject().apply {
                put("ok", true); put("platform", "android"); put("port", port); put("bindHost", bindHost)
                put("cloudflareTunnel", RemoteAccessPrefs.cloudflareTunnelEnabled(appContext))
                put("publicHostname", RemoteAccessPrefs.cloudflareHostname(appContext))
                put("tunnel", tunnelStatusJson())
            })
            "/api/settings" -> routeSettings(req, out)
            "/api/settings/restart" -> {
                requireMethod(req, "POST")
                respondJson(out, 200, JSONObject().put("ok", true).put("message", "Remote service restarting"))
                Thread({
                    runCatching { Thread.sleep(350L) }
                    runCatching { RemoteAccessService.restart(appContext) }
                }, "remote-restart").apply { isDaemon = true }.start()
            }
            "/api/sessions" -> {
                val limit = req.query["limit"]?.toIntOrNull() ?: 100
                respondJson(out, 200, ChatDebugMethods.sessionsList(appContext, JSONObject().put("limit", limit).put("includeEmpty", true)))
            }
            "/api/messages" -> {
                val sid = requireQuery(req, "sessionId")
                val limit = req.query["limit"]?.toIntOrNull() ?: 500
                respondJson(out, 200, ChatDebugMethods.messagesList(appContext, JSONObject().put("sessionId", sid).put("limit", limit).put("includeTools", true)))
            }
            "/api/session/status" -> {
                val sid = requireQuery(req, "sessionId")
                respondJson(out, 200, ChatMutationMethods.status(appContext, JSONObject().put("sessionId", sid)))
            }
            "/api/prompt" -> {
                requireMethod(req, "POST")
                val body = JSONObject(req.body)
                // Remote UI is asynchronous by default and polls the shared DB.
                if (!body.has("wait")) body.put("wait", false)
                respondJson(out, 200, ChatMutationMethods.prompt(appContext, body))
            }
            "/api/cancel" -> {
                requireMethod(req, "POST")
                respondJson(out, 200, ChatMutationMethods.cancel(appContext, JSONObject(req.body)))
            }
            "/api/files" -> {
                val sid = requireQuery(req, "sessionId")
                requireExistingSession(sid)
                val path = req.query["path"] ?: "/var/minis/workspace"
                respondJson(out, 200, listFiles(sid, path))
            }
            "/api/file" -> when (req.method) {
                "GET" -> {
                    val sid = requireQuery(req, "sessionId")
                    requireExistingSession(sid)
                    val path = requireQuery(req, "path")
                    val file = resolveSessionFile(sid, path)
                    if (!file.exists() || !file.isFile) throw IllegalArgumentException("not a file: $path")
                    if (file.length() > MAX_EDIT_FILE_BYTES) {
                        throw IllegalArgumentException("file is too large for the Web editor (${file.length()} bytes; max $MAX_EDIT_FILE_BYTES)")
                    }
                    val bytes = file.readBytes()
                    if (bytes.any { it == 0.toByte() }) throw IllegalArgumentException("binary files cannot be opened in the text editor")
                    val text = try {
                        StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes)).toString()
                    } catch (_: Exception) {
                        throw IllegalArgumentException("file is not valid UTF-8 text")
                    }
                    respondJson(out, 200, JSONObject().apply {
                        put("success", true); put("path", path); put("content", text); put("size", bytes.size)
                        put("sha256", FileRevision.sha256(bytes))
                    })
                }
                "POST", "PUT" -> {
                    val b = JSONObject(req.body)
                    val sid = b.optString("sessionId")
                    if (sid.isBlank()) throw IllegalArgumentException("sessionId is required")
                    requireExistingSession(sid)
                    val args = JSONObject().put("tool_title", "Write remote file")
                        .put("path", b.optString("path"))
                        .put("content", b.optString("content"))
                        .put("append", b.optBoolean("append", false))
                        .put("create_dirs", true)
                    b.optString("expectedSha256", "").takeIf { it.isNotBlank() }?.let { args.put("expected_sha256", it) }
                    val result = FileWriteTool.execute(args.toString(), sid, appContext)
                    val response = JSONObject().put("success", result.success).put("output", result.output)
                    if (result.success) {
                        // For overwrite mode this is the exact revision of the
                        // bytes the editor just wrote, even if an external shell
                        // mutates the file immediately after the write returns.
                        if (!b.optBoolean("append", false)) {
                            response.put("sha256", FileRevision.sha256(b.optString("content").toByteArray(StandardCharsets.UTF_8)))
                        } else {
                            val written = resolveSessionFile(sid, b.optString("path"))
                            if (written.exists() && written.isFile) response.put("sha256", FileRevision.sha256(written))
                        }
                    }
                    respondJson(out, if (result.success) 200 else 400, response)
                }
                else -> respondJson(out, 405, JSONObject().put("error", "method not allowed"))
            }
            "/api/edit" -> {
                requireMethod(req, "POST")
                val b = JSONObject(req.body)
                val sid = b.optString("sessionId")
                if (sid.isBlank()) throw IllegalArgumentException("sessionId is required")
                requireExistingSession(sid)
                val args = JSONObject().put("tool_title", "Edit remote file").put("path", b.optString("path"))
                if (b.has("edits")) args.put("edits", b.get("edits"))
                if (b.has("old_string")) args.put("old_string", b.get("old_string"))
                if (b.has("new_string")) args.put("new_string", b.get("new_string"))
                if (b.has("replace_all")) args.put("replace_all", b.get("replace_all"))
                val result = FileEditTool.execute(args.toString(), sid, appContext)
                respondJson(out, if (result.success) 200 else 400, JSONObject().put("success", result.success).put("output", result.output))
            }
            "/api/shell" -> {
                requireMethod(req, "POST")
                val b = JSONObject(req.body)
                val sid = b.optString("sessionId")
                val command = b.optString("command")
                if (sid.isBlank() || command.isBlank()) throw IllegalArgumentException("sessionId and command are required")
                requireExistingSession(sid)
                val timeoutMs = (b.optLong("timeoutMs", 900_000L)).coerceIn(1_000L, 3_600_000L)
                val result = ExecutionCoordinator.execute(sid, command, timeoutMs)
                val full = result.fullOutput ?: result.output
                val trunc = ShellOutputTruncator.truncateTail(full)
                val path = if (trunc.truncated) ContextOffload.offloadContent(appContext, sid, full, "web_${System.currentTimeMillis()}", "shell_execute", "log") else ""
                respondJson(out, 200, JSONObject().apply {
                    put("exitCode", result.exitCode); put("durationMs", result.durationMs); put("output", trunc.output)
                    if (path.isNotEmpty()) put("fullOutputPath", path)
                })
            }
            else -> respondJson(out, 404, JSONObject().put("error", "not found"))
        }
    }

    private suspend fun routeSettings(req: Request, out: BufferedOutputStream) {
        when (req.method) {
            "GET" -> {
                respondJson(out, 200, JSONObject().apply {
                    put("username", RemoteAccessPrefs.username(appContext))
                    put("passwordConfigured", RemoteAccessPrefs.hasPassword(appContext))
                    put("port", RemoteAccessPrefs.port(appContext))
                    put("lanAccess", RemoteAccessPrefs.lanAccessEnabled(appContext))
                    put("bindHost", RemoteAccessPrefs.bindHost(appContext))
                    put("cloudflareTunnelEnabled", RemoteAccessPrefs.cloudflareTunnelEnabled(appContext))
                    put("cloudflareTunnelTokenConfigured", RemoteAccessPrefs.hasCloudflareTunnelToken(appContext))
                    put("cloudflareHostname", RemoteAccessPrefs.cloudflareHostname(appContext))
                    put("tunnel", tunnelStatusJson())
                })
            }
            "PATCH", "PUT" -> {
                val body = JSONObject(req.body)
                val oldPort = RemoteAccessPrefs.port(appContext)
                val oldLan = RemoteAccessPrefs.lanAccessEnabled(appContext)
                val oldUser = RemoteAccessPrefs.username(appContext)

                val requestedUser = body.optString("username", oldUser).trim()
                val newPassword = body.optString("newPassword", "")
                val changesIdentity = requestedUser != oldUser || newPassword.isNotEmpty()
                if (changesIdentity) {
                    val current = body.optString("currentPassword", "").toCharArray()
                    if (!RemoteAccessPrefs.verifyLogin(appContext, oldUser, current)) {
                        respondJson(out, 403, JSONObject().put("error", "current password is incorrect"))
                        return
                    }
                    if (requestedUser != oldUser) RemoteAccessPrefs.setUsername(appContext, requestedUser)
                    if (newPassword.isNotEmpty()) RemoteAccessPrefs.setPassword(appContext, newPassword.toCharArray())
                }

                if (body.has("port")) {
                    val requestedPort = body.optInt("port", oldPort)
                    if (requestedPort !in 1024..65535) throw IllegalArgumentException("port must be 1024-65535")
                    RemoteAccessPrefs.setPort(appContext, requestedPort)
                }
                if (body.has("lanAccess")) {
                    RemoteAccessPrefs.setLanAccessEnabled(appContext, body.getBoolean("lanAccess"))
                }
                if (body.has("cloudflareHostname")) {
                    RemoteAccessPrefs.setCloudflareHostname(appContext, body.optString("cloudflareHostname"))
                }
                var tunnelTokenChanged = false
                if (body.has("cloudflareTunnelToken")) {
                    val supplied = body.optString("cloudflareTunnelToken").trim()
                    if (supplied.isNotEmpty()) {
                        RemoteAccessPrefs.setCloudflareTunnelToken(appContext, supplied)
                        tunnelTokenChanged = true
                    }
                }
                if (body.has("cloudflareTunnelEnabled")) {
                    val enableTunnel = body.getBoolean("cloudflareTunnelEnabled")
                    if (enableTunnel && !RemoteAccessPrefs.hasCloudflareTunnelToken(appContext)) {
                        throw IllegalArgumentException("Cloudflare Tunnel Token is required before enabling the tunnel")
                    }
                    RemoteAccessPrefs.setCloudflareTunnelEnabled(appContext, enableTunnel)
                    if (enableTunnel) {
                        if (tunnelTokenChanged) CloudflareTunnelManager.stop()
                        CloudflareTunnelManager.start(appContext)
                    } else {
                        CloudflareTunnelManager.stop()
                    }
                }

                val restartRequired = oldPort != RemoteAccessPrefs.port(appContext) ||
                    oldLan != RemoteAccessPrefs.lanAccessEnabled(appContext)
                respondJson(out, 200, JSONObject().apply {
                    put("ok", true)
                    put("restartRequired", restartRequired)
                    put("reauthRequired", changesIdentity)
                    put("port", RemoteAccessPrefs.port(appContext))
                    put("lanAccess", RemoteAccessPrefs.lanAccessEnabled(appContext))
                    put("username", RemoteAccessPrefs.username(appContext))
                    put("cloudflareTunnelEnabled", RemoteAccessPrefs.cloudflareTunnelEnabled(appContext))
                    put("cloudflareTunnelTokenConfigured", RemoteAccessPrefs.hasCloudflareTunnelToken(appContext))
                    put("cloudflareHostname", RemoteAccessPrefs.cloudflareHostname(appContext))
                    put("tunnel", tunnelStatusJson())
                })
                if (changesIdentity) sessions.clear()
            }
            else -> respondJson(out, 405, JSONObject().put("error", "method not allowed"))
        }
    }

    private fun tunnelStatusJson(): JSONObject {
        val status = CloudflareTunnelManager.status.value
        return JSONObject().apply {
            put("installed", status.installed)
            put("running", status.running)
            put("phase", status.phase)
            put("detail", status.detail)
            put("version", status.version)
        }
    }

    private suspend fun requireExistingSession(sessionId: String) {
        try {
            ChatDebugMethods.sessionsGet(appContext, JSONObject().put("sessionId", sessionId))
        } catch (_: Exception) {
            throw IllegalArgumentException("session not found: $sessionId")
        }
    }

    private fun resolveSessionFile(sessionId: String, linuxPath: String): File {
        if (linuxPath.contains("..")) throw IllegalArgumentException("'..' is not allowed")
        return PRootKernel.resolveSessionHostPath(sessionId, linuxPath, appContext)
            ?: throw IllegalArgumentException("cannot resolve path")
    }

    private fun listFiles(sessionId: String, linuxPath: String): JSONObject {
        if (linuxPath.contains("..")) throw IllegalArgumentException("'..' is not allowed")
        val dir = PRootKernel.resolveSessionHostPath(sessionId, linuxPath, appContext)
            ?: throw IllegalArgumentException("cannot resolve path")
        if (!dir.exists() || !dir.isDirectory) throw IllegalArgumentException("not a directory: $linuxPath")
        val items = JSONArray()
        dir.listFiles()?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))?.take(1000)?.forEach { f ->
            items.put(JSONObject().apply {
                put("name", f.name); put("directory", f.isDirectory); put("size", f.length()); put("modified", f.lastModified())
                put("path", linuxPath.trimEnd('/') + "/" + f.name)
            })
        }
        return JSONObject().put("path", linuxPath).put("items", items)
    }

    private fun requireMethod(req: Request, method: String) {
        if (req.method != method) throw IllegalArgumentException("$method required")
    }
    private fun requireQuery(req: Request, key: String): String = req.query[key]?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("missing query parameter: $key")

    private class BodyTooLargeException : RuntimeException()

    private fun readRequest(input: BufferedInputStream): Request? {
        val requestLine = readLine(input) ?: return null
        val first = requestLine.split(' ', limit = 3)
        if (first.size < 2) throw IllegalArgumentException("invalid request line")
        val method = first[0].uppercase()
        val rawPath = first[1]
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        if (length > MAX_BODY) throw BodyTooLargeException()
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val n = input.read(bytes, offset, length - offset)
            if (n < 0) break
            offset += n
        }
        val pathPart = rawPath.substringBefore('?')
        val query = parseQuery(rawPath.substringAfter('?', ""))
        return Request(method, rawPath, decode(pathPart), query, headers, bytes.copyOf(offset).toString(StandardCharsets.UTF_8))
    }

    private fun readLine(input: BufferedInputStream): String? {
        val out = java.io.ByteArrayOutputStream()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString("UTF-8")
            if (prev == '\r'.code && b == '\n'.code) {
                val data = out.toByteArray()
                return String(data, 0, (data.size - 1).coerceAtLeast(0), StandardCharsets.UTF_8)
            }
            out.write(b)
            prev = b
            if (out.size() > 16 * 1024) throw IllegalArgumentException("header line too long")
        }
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.split('&').mapNotNull { part ->
            val idx = part.indexOf('=')
            val k = decode(if (idx < 0) part else part.substring(0, idx))
            if (k.isBlank()) null else k to decode(if (idx < 0) "" else part.substring(idx + 1))
        }.toMap()
    }
    private fun decode(v: String): String = URLDecoder.decode(v, StandardCharsets.UTF_8.name())

    private fun respondJson(
        out: BufferedOutputStream,
        code: Int,
        obj: JSONObject,
        extraHeaders: Map<String, String> = emptyMap(),
    ) = respond(out, code, "application/json; charset=utf-8", obj.toString(), extraHeaders)

    private fun respond(
        out: BufferedOutputStream,
        code: Int,
        contentType: String,
        body: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ) = respondBytes(out, code, contentType, body.toByteArray(StandardCharsets.UTF_8), extraHeaders)

    private fun respondBytes(
        out: BufferedOutputStream,
        code: Int,
        contentType: String,
        bytes: ByteArray,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        val reason = when (code) {
            200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized";
            403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed";
            413 -> "Payload Too Large"; 429 -> "Too Many Requests"; 503 -> "Service Unavailable";
            else -> "Error"
        }
        val headers = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n")
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("X-Frame-Options: DENY\r\n")
            append("Permissions-Policy: camera=(), microphone=(), geolocation=()\r\n")
            append("Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'\r\n")
            for ((name, value) in extraHeaders) append(name).append(": ").append(value).append("\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.UTF_8)
        out.write(headers); out.write(bytes); out.flush()
    }
}
