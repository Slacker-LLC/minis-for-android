package com.openminis.app.debug

import android.app.Activity
import android.os.Build
import com.openminis.app.BuildConfig
import com.openminis.app.debug.DebugRPCHandler.Companion.currentActivity
import com.openminis.app.logging.AppLogger
import com.openminis.app.runtime.ExecutionCoordinator
import com.openminis.app.runtime.files.WorkspaceFileClient
import com.openminis.app.tools.ExternalMountAccess
import java.io.File
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject

internal fun DebugRPCHandler.formatLLMRequestsText(raw: JSONObject): String {
    val arr = raw.optJSONArray("requests") ?: return ""
    val sb = StringBuilder()
    val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
    for (i in 0 until arr.length()) {
        val e = arr.optJSONObject(i) ?: continue
        val provider = e.optString("provider")
        val timestamp = e.optLong("timestamp", 0)
        val durMs = e.optLong("durationMs", 0)
        val status = e.optInt("responseStatusCode", 0)
        val usage = e.optJSONObject("usage")
        val usageStr = if (usage != null) {
            buildString {
                append("in:").append(usage.optInt("inputTokens", usage.optInt("input_tokens", 0)))
                append(" out:").append(usage.optInt("outputTokens", usage.optInt("output_tokens", 0)))
                val cr = usage.optInt("cacheReadTokens", usage.optInt("cache_read_tokens", 0))
                if (cr > 0) append(" cache_read:").append(cr)
            }
        } else ""
        sb.append("// --- #").append(i + 1).append(' ')
            .append(provider).append(' ')
            .append(if (timestamp > 0) ts.format(java.util.Date(timestamp)) else "")
            .append(' ').append(durMs).append("ms")
            .append(" HTTP ").append(status)
            .append(if (usageStr.isNotEmpty()) " | $usageStr" else "")
            .append(" ---\n")
        sb.append("// ").append(e.optString("requestMethod"))
            .append(' ').append(e.optString("requestURL")).append('\n')
        val body = e.opt("requestBody")
        if (body != null) sb.append(body.toString()).append('\n')
        val resp = e.optString("responseBody", "")
        if (resp.isNotEmpty()) {
            sb.append("// --- Response (").append(resp.length).append(" chars) ---\n")
            sb.append(resp).append('\n')
        }
        sb.append('\n')
    }
    return sb.toString()
}

/**
 * Aggregate "agent trace" view — alias for `debug.llmRequests` exposed
 * under the iOS-compatible name. `last=true` returns just the most recent
 * trace object instead of an array (mirrors iOS docs).
 */
internal fun DebugRPCHandler.handleAgentTrace(params: JSONObject): JSONObject {
    val onlyLast = params.optBoolean("last", false)
    if (onlyLast) {
        val tail = LLMRequestLog.getLast(1)
        if (tail.isEmpty()) return JSONObject().put("traces", org.json.JSONArray())
        // Reuse the same JSON shape `debug.llmRequests` produces so callers
        // get a consistent envelope (the underlying Entry → JSON mapping
        // lives in LLMRequestLog).
        val full = LLMRequestLog.toJSON(1)
        val arr = full.optJSONArray("requests")
        return arr?.optJSONObject(0) ?: JSONObject().put("traces", org.json.JSONArray())
    }
    val full = LLMRequestLog.toJSON(null)
    return JSONObject().put("traces", full.optJSONArray("requests") ?: org.json.JSONArray())
}

internal fun DebugRPCHandler.handleLogsSetEnabled(params: JSONObject): JSONObject {
    if (!params.has("enabled")) throw RPCException(-32602, "Missing 'enabled' param")
    val enabled = params.optBoolean("enabled", false)
    AppLogger.setEnabled(context, enabled)
    return JSONObject().put("enabled", AppLogger.isEnabled(context))
}

// ── Response helpers ────────────────────────────────────────────────────

internal fun DebugRPCHandler.metaObject(): JSONObject {
    return JSONObject().apply {
        put("app", "MinisApp")
        put("version", BuildConfig.VERSION_NAME)
        put("build", BuildConfig.VERSION_CODE)
        put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        put("platform", "android")
    }
}

internal fun DebugRPCHandler.successResponse(result: Any, id: Any?): String {
    return JSONObject().apply {
        put("jsonrpc", "2.0")
        put("id", id ?: JSONObject.NULL)
        put("result", result)
        put("_meta", metaObject())
    }.toString()
}

fun DebugRPCHandler.errorJSON(code: Int, message: String): String {
    return JSONObject().apply {
        put("jsonrpc", "2.0")
        put("id", JSONObject.NULL)
        put("error", JSONObject().apply {
            put("code", code)
            put("message", message)
        })
        put("_meta", metaObject())
    }.toString()
}

internal fun DebugRPCHandler.errorResponse(code: Int, message: String, id: Any?): String {
    return JSONObject().apply {
        put("jsonrpc", "2.0")
        put("id", id ?: JSONObject.NULL)
        put("error", JSONObject().apply {
            put("code", code)
            put("message", message)
        })
        put("_meta", metaObject())
    }.toString()
}

internal fun DebugRPCHandler.handleFetch(params: JSONObject): JSONObject {
    val url = params.optString("url").ifEmpty { throw RPCException(-32602, "Missing 'url' param") }
    val result = JSONObject()

    // Test 1: DNS
    try {
        val host = java.net.URI(url).host
        val addrs = java.net.InetAddress.getAllByName(host)
        result.put("dns", addrs.joinToString(", ") { it.hostAddress })
    } catch (e: Exception) {
        result.put("dns_error", "${e.javaClass.simpleName}: ${e.message}")
    }

    // Test 2: HttpURLConnection
    try {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.requestMethod = "GET"
        result.put("httpurlconn_status", conn.responseCode)
        conn.disconnect()
    } catch (e: Exception) {
        result.put("httpurlconn_error", "${e.javaClass.simpleName}: ${e.message}")
    }

    // Test 3: OkHttp
    try {
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val req = okhttp3.Request.Builder().url(url).get().build()
        val resp = client.newCall(req).execute()
        result.put("okhttp_status", resp.code)
        resp.close()
    } catch (e: Exception) {
        result.put("okhttp_error", "${e.javaClass.simpleName}: ${e.message}")
    }

    // Test 4: Proxy info
    try {
        val selector = java.net.ProxySelector.getDefault()
        val proxies = selector.select(java.net.URI(url))
        result.put("proxies", proxies.joinToString(", ") { it.toString() })
    } catch (e: Exception) {
        result.put("proxy_error", e.message)
    }

    return result
}

// ── Shell Execute (Direct Ubuntu guest) ──────────────────────────────────

/**
 * Run a command inside the Direct Ubuntu guest for the given session and return
 * `{ output, exit_code }`. Mirrors iOS `debug.shellExecute`. Debug-only;
 * meant for integration-test harnesses that need to drive shell tools
 * (`minis-browser-use`, `minis-open`, …) without going through the agent.
 *
 * Params:
 *   command  (string, required) — command line to run under /bin/sh -c.
 *   session  (string)           — session id. Defaults to "debug-rpc" so
 *                                 callers don't accidentally mutate a
 *                                 real chat's shell state.
 *   timeout  (int)              — timeout in seconds. Default 60.
 */
internal suspend fun DebugRPCHandler.handleShellExecute(params: JSONObject): JSONObject {
    val command = params.optString("command").ifEmpty {
        throw RPCException(-32602, "Missing 'command' param")
    }
    val session = params.optString("session", "debug-rpc").ifEmpty { "debug-rpc" }
    val timeoutSec = params.optInt("timeout", 60).coerceIn(1, 900)

    // Mirror ChatViewModel's terminal lineCallback: scan raw lines for
    // OSC MinisOpenURL markers before TerminalSanitizer strips them and
    // hand captured URLs to the URL handoff coordinator so test harnesses driving
    // `minis-open` via this RPC trigger the same in-app preview flow as
    // real chat shell output.
    val capturedUrls = mutableListOf<String>()
    val result = try {
        ExecutionCoordinator.execute(
            sessionId = session,
            command = command,
            timeout = timeoutSec * 1000L,
            lineCallback = { rawLine ->
                val (_, urls) = com.openminis.app.terminal.MinisUrlMarker.extract(rawLine)
                capturedUrls.addAll(urls)
            },
        )
    } catch (e: Exception) {
        throw RPCException(-32000, "Shell execute failed: ${e.message}")
    }
    for (raw in capturedUrls) {
        com.openminis.app.terminal.MinisOpenUrlBroker.offer(raw)
    }
    return JSONObject()
        .put("output", result.output)
        .put("exit_code", result.exitCode)
        .put("session", session)
}

// ── MCP Server (P4 smoke control) ──────────────────────────────────────

internal fun DebugRPCHandler.handleMcpStatus(): JSONObject {
    val s = com.openminis.app.mcp.server.MCPServerManager.status()
    return JSONObject()
        .put("running", s.running)
        .put("configured", s.configured)
        .put("port", s.port)
}

internal fun DebugRPCHandler.handleMcpStart(): JSONObject {
    val ok = com.openminis.app.mcp.server.MCPServerManager.start()
    return JSONObject().put("started", ok)
}

internal fun DebugRPCHandler.handleMcpStop(): JSONObject {
    com.openminis.app.mcp.server.MCPServerManager.stop()
    return JSONObject().put("stopped", true)
}

internal fun DebugRPCHandler.handleMcpSetToken(params: JSONObject): JSONObject {
    val id = params.optString("id", "default")
    val token = params.optString("token")
    if (token.isBlank()) throw RPCException(-32602, "token required")
    val scope = params.optJSONArray("scope")?.let { arr ->
        (0 until arr.length()).map { arr.getString(it) }.toSet()
    } ?: emptySet()
    com.openminis.app.mcp.server.TokenStore.save(
        listOf(com.openminis.app.mcp.server.TokenStore.Token(id = id, token = token, scope = scope)),
    )
    com.openminis.app.mcp.server.MCPServerManager.init(context)
    return JSONObject().put("saved", true).put("configured", com.openminis.app.mcp.server.TokenStore.isConfigured)
}

/** DEBUG-only: approve a pending MCP confirm without the phone notification
 *  (OEMs like HyperOS collapse notification actions, blocking the E2E path). */
internal fun DebugRPCHandler.handleMcpConfirmAnswer(params: JSONObject): JSONObject {
    val id = params.optString("confirm_id").ifEmpty {
        throw RPCException(-32602, "confirm_id required")
    }
    val method = params.optString("method").ifEmpty {
        throw RPCException(-32602, "method required")
    }
    val result = com.openminis.app.mcp.server.MCPServerManager.debugApproveConfirm(id, method)
    if (result == null) throw RPCException(-32001, "MCP server is not running")
    return JSONObject().put("approved", true).put("result", result)
}

// ── Update checker (T33) — exposed only via DebugRPC so the e2e flow can
// be exercised before the production UI entry point lands.
internal suspend fun DebugRPCHandler.handleUpdateCheck(): JSONObject {
    return when (val r = com.openminis.app.data.UpdateChecker.check()) {
        is com.openminis.app.data.UpdateChecker.CheckResult.UpdateAvailable -> JSONObject()
            .put("status", "update_available")
            .put("tag_name", r.tagName)
            .put("version_name", r.versionName)
            .put("release_name", r.releaseName)
            .put("apk_url", r.apkUrl)
            .put("apk_size", r.apkSizeBytes)
            .put("changelog", r.changelog)
        com.openminis.app.data.UpdateChecker.CheckResult.UpToDate ->
            JSONObject().put("status", "up_to_date")
        com.openminis.app.data.UpdateChecker.CheckResult.NoReleaseAvailable ->
            JSONObject().put("status", "no_release")
        is com.openminis.app.data.UpdateChecker.CheckResult.NoApkAsset ->
            JSONObject().put("status", "no_apk_asset").put("tag_name", r.tagName)
        com.openminis.app.data.UpdateChecker.CheckResult.Forbidden ->
            JSONObject().put("status", "forbidden")
        com.openminis.app.data.UpdateChecker.CheckResult.NetworkUnreachable ->
            JSONObject().put("status", "network_unreachable")
        is com.openminis.app.data.UpdateChecker.CheckResult.Error ->
            JSONObject().put("status", "error").put("message", r.message)
    }
}

internal suspend fun DebugRPCHandler.handleUpdateDownload(params: JSONObject): JSONObject {
    val url = params.optString("url").ifEmpty {
        throw RPCException(-32602, "Missing 'url' parameter")
    }
    return when (val r = com.openminis.app.data.UpdateChecker.download(context, url)) {
        is com.openminis.app.data.UpdateChecker.DownloadResult.Success -> JSONObject()
            .put("status", "ok")
            .put("path", r.file.absolutePath)
            .put("size", r.file.length())
        is com.openminis.app.data.UpdateChecker.DownloadResult.Error -> JSONObject()
            .put("status", "error")
            .put("message", r.message)
    }
}

internal fun DebugRPCHandler.handleUpdateInstall(params: JSONObject): JSONObject {
    val path = params.optString("path").ifEmpty {
        throw RPCException(-32602, "Missing 'path' parameter")
    }
    val file = java.io.File(path)
    if (!file.exists()) throw RPCException(-32000, "APK not found: $path")
    val canInstall = com.openminis.app.data.UpdateChecker.canInstall(context)
    return try {
        com.openminis.app.data.UpdateChecker.installApk(context, file)
        JSONObject().put("status", "launched").put("can_install", canInstall)
    } catch (e: Exception) {
        JSONObject()
            .put("status", "error")
            .put("can_install", canInstall)
            .put("message", e.message ?: e.javaClass.simpleName)
    }
}

// ── UI introspection (Layer A) ───────────────────────────────────────────

internal suspend fun DebugRPCHandler.handleViewTree(params: JSONObject): Any {
    val maxDepth = params.optInt("maxDepth", 50).coerceIn(1, 200)
    val activity = currentActivity?.get()
        ?: throw RPCException(-32000, "No active Activity")
    val trees = DebugInspectorMethods.viewTree(activity, maxDepth)
    return if (trees.length() == 1) trees.get(0) else trees
}

internal suspend fun DebugRPCHandler.handleSearch(params: JSONObject): JSONArray {
    val keyword = params.optString("keyword").ifEmpty {
        throw RPCException(-32602, "Invalid params: 'keyword' is required")
    }
    val scope = params.optString("scope", "all").ifEmpty { "all" }
    val activity = currentActivity?.get()
        ?: throw RPCException(-32000, "No active Activity")
    return DebugInspectorMethods.search(activity, keyword, scope)
}

internal suspend fun DebugRPCHandler.handleInspect(params: JSONObject): JSONObject {
    val address = params.optString("address").ifEmpty {
        throw RPCException(-32602, "Invalid params: 'address' is required")
    }
    val activity = currentActivity?.get()
        ?: throw RPCException(-32000, "No active Activity")
    return DebugInspectorMethods.inspect(activity, address)
        ?: JSONObject().put("error", "Node not found at address $address (call viewTree/search first)")
}

internal suspend fun DebugRPCHandler.handleHighlight(params: JSONObject): JSONObject {
    val address = params.optString("address").ifEmpty {
        throw RPCException(-32602, "Invalid params: 'address' is required")
    }
    val color = params.optString("color", "red").ifEmpty { "red" }
    val duration = params.optDouble("duration", 2.0)
    val activity = currentActivity?.get()
        ?: throw RPCException(-32000, "No active Activity")
    val ok = DebugInspectorMethods.highlight(activity, address, color, duration)
    return JSONObject().put("ok", ok)
}

/**
 * Stub for parity with iOS `debug.cloudSync`. Android does not have an
 * iCloud-equivalent built into MinisApp, so we report disabled and an
 * empty device list rather than fail the call. Lets cross-platform
 * harnesses skip the check uniformly.
 */
internal fun DebugRPCHandler.handleCloudSync(): JSONObject {
    return JSONObject().apply {
        put("enabled", false)
        put("status", "unsupported")
        put("reason", "Android build does not include iCloud sync")
        put("devices", JSONArray())
        put("deviceCount", 0)
    }
}

/** Write a file into the guest namespace or an authorized external mount. */
internal suspend fun DebugRPCHandler.handleWriteFile(params: JSONObject): JSONObject {
    val path = params.optString("path").ifEmpty {
        throw RPCException(-32602, "Invalid params: 'path' is required")
    }
    if (path.contains("..")) throw RPCException(-32602, "Invalid path: '..' not allowed")
    if (!params.has("content")) throw RPCException(-32602, "Invalid params: 'content' is required")
    val content = params.optString("content")
    val encoding = params.optString("encoding", "utf8").lowercase().ifEmpty { "utf8" }
    val overwrite = params.optBoolean("overwrite", true)

    val bytes = when (encoding) {
        "utf8" -> content.toByteArray(Charsets.UTF_8)
        "base64" -> try {
            android.util.Base64.decode(content, android.util.Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            throw RPCException(-32602, "Invalid base64 content: ${e.message}")
        }
        else -> throw RPCException(-32602, "Invalid encoding: '$encoding' (must be utf8 or base64)")
    }
    if (bytes.size.toLong() > WorkspaceFileClient.MAX_FILE_BYTES) {
        throw RPCException(-32602, "File exceeds ${WorkspaceFileClient.MAX_FILE_BYTES} bytes")
    }

    if (isCanonicalGuestPath(path)) {
        if (!overwrite) {
            val existing = runCatching { WorkspaceFileClient.info(params.optString("sessionId"), path) }
                .getOrNull()
            if (existing?.optBoolean("exists", false) == true) {
                throw RPCException(-32000, "File exists and overwrite=false: $path")
            }
        }
        val size = WorkspaceFileClient.writeBytes(params.optString("sessionId"), path, bytes)
        return JSONObject().apply {
            put("ok", true)
            put("path", path)
            put("size", size)
        }
    }

    if (ExternalMountAccess.isPath(path)) {
        if (!overwrite && runCatching { ExternalMountAccess.info(path) }
                .getOrNull()?.optBoolean("exists", false) == true) {
            throw RPCException(-32000, "File exists and overwrite=false: $path")
        }
        val size = ExternalMountAccess.write(path, bytes, append = false)
        return JSONObject().apply {
            put("ok", true)
            put("path", path)
            put("size", size)
        }
    }

    throw RPCException(
        -32602,
        "path must be inside the App-owned guest workspace or an authorized external mount: $path",
    )
}

internal fun DebugRPCHandler.isCanonicalGuestPath(path: String): Boolean {
    val roots = listOf(
        "/var/minis",
        "/workspace",
        "/memory",
        "/skills",
        "/shared",
        "/home/minis",
    )
    return roots.any { path == it || path.startsWith("$it/") } &&
        !path.startsWith("/var/minis/mounts")
}

internal suspend fun DebugRPCHandler.handleScreenshotCapture(params: JSONObject): JSONObject {
    val label = params.optString("label", "")
    val scale = params.optDouble("scale", 0.5).toFloat().coerceIn(0.05f, 1.0f)
    val activity = currentActivity?.get()
        ?: throw RPCException(-32000, "No active Activity")
    val entry = DebugScreenshotRing.capture(activity, label, scale)
    return DebugScreenshotRing.summary(entry)
}

internal fun DebugRPCHandler.handleScreenshotGet(params: JSONObject): JSONObject {
    val id = if (params.has("id") && !params.isNull("id")) params.optInt("id") else null
    val entry = DebugScreenshotRing.get(id)
        ?: throw RPCException(-32602, "Screenshot not found${if (id != null) " for id=$id" else " (buffer empty)"}")
    return JSONObject().apply {
        put("id", entry.id)
        put("label", entry.label)
        put("timestamp", entry.timestamp)
        put("base64", android.util.Base64.encodeToString(entry.pngBytes, android.util.Base64.NO_WRAP))
        put("size", entry.pngBytes.size)
        put("encoding", "png")
    }
}

/**
 * Dump the live OffloadPermissionManager registry — one entry per tool with
 * the resolved level (default + persisted overrides applied). Used to
 * verify T34/T35 alignment without driving the Settings UI.
 */
internal fun DebugRPCHandler.handlePermissionsList(): JSONObject {
    val arr = org.json.JSONArray()
    for (tool in com.openminis.app.offload.OffloadPermissionManager.toolRegistry) {
        arr.put(JSONObject()
            .put("toolName", tool.toolName)
            .put("displayName", tool.displayName)
            .put("category", tool.category.name)
            .put("defaultLevel", tool.defaultLevel.name)
            .put("currentLevel", com.openminis.app.offload.OffloadPermissionManager.getLevel(tool.toolName).name)
            .put("showInSettings", tool.showInSettings))
    }
    return JSONObject().put("tools", arr).put("count", arr.length())
}

/**
 * T344: Direct invocation of [com.openminis.app.runtime.guest.ShizukuOffloadHandler]
 * for e2e harnesses. Bypasses the agent loop so debug clients can verify Shizuku
 * CLI behavior without driving a chat. DEBUG-build only — gated in [dispatch].
 *
 * Params (one of):
 *   - {"args": ["exec", "id"]}            — argv past `android-shizuku-cli`
 *   - {"command": "exec id"}              — single string, whitespace-split
 */
internal fun DebugRPCHandler.handleShizukuExec(params: JSONObject): JSONObject {
    val argvTail: List<String> = when {
        params.has("args") -> {
            val arr = params.optJSONArray("args")
                ?: throw RPCException(-32602, "args must be an array of strings")
            List(arr.length()) { arr.optString(it) }
        }
        params.has("command") -> {
            params.optString("command").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        }
        else -> throw RPCException(-32602, "Missing 'args' (array) or 'command' (string)")
    }
    AppLogger.info("DebugRPC", "debug.shizuku.exec argv=${argvTail.joinToString(" ")}")
    val handler = com.openminis.app.runtime.guest.ShizukuOffloadHandler(context)
    // ShizukuOffloadHandler.handle() drops argv[0]; prepend the CLI name so the
    // tail aligns with what `android-shizuku-cli` would receive in-shell.
    val request = com.openminis.app.runtime.guest.NativeOffloadRequest(
        pid = -1,
        argv = listOf("android-shizuku-cli") + argvTail,
        env = emptyMap(),
        cwd = "/",
        sessionId = null,
    )
    val result = handler.handle(request)
    return JSONObject().apply {
        put("exitCode", result.exitCode)
        put("output", result.output)
        put("argv", JSONArray(argvTail))
    }
}

/**
 * Direct invocation of [com.openminis.app.runtime.guest.ModelUseOffloadHandler]
 * for e2e harnesses. Mirrors [handleShizukuExec]; lets callers exercise the
 * `minis-model-use` CLI without going through a real Ubuntu shell prompt.
 * DEBUG-only.
 */
internal fun DebugRPCHandler.handleModelUseExec(params: JSONObject): JSONObject {
    val argvTail: List<String> = when {
        params.has("args") -> {
            val arr = params.optJSONArray("args")
                ?: throw RPCException(-32602, "args must be an array of strings")
            List(arr.length()) { arr.optString(it) }
        }
        params.has("command") -> {
            params.optString("command").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        }
        else -> throw RPCException(-32602, "Missing 'args' (array) or 'command' (string)")
    }

    // Optional `input` blob: stage it in the App-owned global workspace and
    // inject `--input <linuxPath>` so the handler reads it through the same
    // secure WorkspaceFileClient path as a real guest invocation. Rootfs is
    // Root-owned and is never used as an App-side temporary directory.
    var stagedInputPath: String? = null
    val finalArgv: List<String> = if (params.has("input")) {
        val inputBlob = params.optString("input", "")
        val linuxPath = "/var/minis/workspace/.debug-modeluse-input-${java.util.UUID.randomUUID()}.json"
        try {
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                WorkspaceFileClient.writeBytes(null, linuxPath, inputBlob.toByteArray(Charsets.UTF_8))
            }
            stagedInputPath = linuxPath
        } catch (error: Exception) {
            throw RPCException(-32603, "cannot stage model-use input in App-owned workspace: ${error.message}")
        }
        argvTail + listOf("--input", linuxPath)
    } else argvTail

    AppLogger.info("DebugRPC", "debug.modelUse.exec argv=${finalArgv.joinToString(" ")}")
    val app = context.applicationContext as com.openminis.app.MinisApp
    val handler = com.openminis.app.runtime.guest.ModelUseOffloadHandler(context, app.providerRepository)
    val request = com.openminis.app.runtime.guest.NativeOffloadRequest(
        pid = -1,
        argv = listOf("minis-model-use") + finalArgv,
        env = emptyMap(),
        cwd = "/",
        sessionId = null,
    )
    val result = try {
        handler.handle(request)
    } finally {
        stagedInputPath?.let { path ->
            runCatching {
                kotlinx.coroutines.runBlocking(Dispatchers.IO) { WorkspaceFileClient.delete(null, path) }
            }.onFailure { error ->
                AppLogger.warning("DebugRPC", "failed to remove staged model-use input: ${error.message}")
            }
        }
    }
    return JSONObject().apply {
        put("exitCode", result.exitCode)
        put("output", result.output)
        put("argv", JSONArray(finalArgv))
    }
}

/**
 * [T-android-sessions-cli-full] Direct invocation of
 * [com.openminis.app.runtime.guest.SessionsOffloadHandler] for e2e
 * harnesses. Mirrors [handleModelUseExec]; lets callers exercise the
 * `minis-sessions-cli` CLI (list / search / messages, incl. --full)
 * without going through a real Ubuntu shell prompt. DEBUG-only.
 */
internal fun DebugRPCHandler.handleSessionsExec(params: JSONObject): JSONObject {
    val argvTail: List<String> = when {
        params.has("args") -> {
            val arr = params.optJSONArray("args")
                ?: throw RPCException(-32602, "args must be an array of strings")
            List(arr.length()) { arr.optString(it) }
        }
        params.has("command") -> {
            params.optString("command").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        }
        else -> throw RPCException(-32602, "Missing 'args' (array) or 'command' (string)")
    }

    AppLogger.info("DebugRPC", "debug.sessions.exec argv=${argvTail.joinToString(" ")}")
    val app = context.applicationContext as com.openminis.app.MinisApp
    val handler = com.openminis.app.runtime.guest.SessionsOffloadHandler(app.chatRepository)
    val request = com.openminis.app.runtime.guest.NativeOffloadRequest(
        pid = -1,
        argv = listOf("minis-sessions-cli") + argvTail,
        env = emptyMap(),
        cwd = "/",
        sessionId = null,
    )
    val result = handler.handle(request)
    return JSONObject().apply {
        put("exitCode", result.exitCode)
        put("output", result.output)
        put("argv", JSONArray(argvTail))
    }
}

/**
 * [T-minis-config-provider-add] DEBUG-only minis-config invocation
 * that BYPASSES the user-confirmation gate. Targets the same code
 * path the offload CLI hits (ConfigBridge.performWriteBatch /
 * readField / auditList), so harnesses can verify add / set / get
 * end-to-end without driving the in-app confirm dialog.
 *
 * Params:
 *   - `subcommand` (required): "set" | "get" | "audit-list"
 *   - subcommand=set:    `path` (string) + `value_json` (string)
 *   - subcommand=get:    `path` (string)
 *   - subcommand=audit-list: optional `limit` (int)
 */
internal fun DebugRPCHandler.handleMinisConfigExec(params: JSONObject): JSONObject {
    val sub = params.optString("subcommand", "").takeIf { it.isNotEmpty() }
        ?: throw RPCException(
            -32602,
            "Missing 'subcommand' — one of: set, get, topics, topic-help, audit-list",
        )

    return when (sub) {
        "set" -> {
            // Accepts EITHER a single `path` + `value_json`, or `items:
            // [{path, value_json}, …]` for a multi-path batch. The batch form
            // matters because performWriteBatch applies its items as ONE unit
            // — writing two paths as two calls cannot exercise that.
            val items = params.optJSONArray("items")?.also { arr ->
                if (arr.length() == 0) {
                    throw RPCException(-32602, "'items' must not be empty for subcommand=set")
                }
                for (i in 0 until arr.length()) {
                    val it = arr.optJSONObject(i)
                        ?: throw RPCException(-32602, "items[$i] must be an object")
                    if (it.optString("path", "").isEmpty()) {
                        throw RPCException(-32602, "Missing 'path' in items[$i]")
                    }
                    if (it.optString("value_json", "").isEmpty()) {
                        throw RPCException(-32602, "Missing 'value_json' in items[$i]")
                    }
                }
            } ?: run {
                val path = params.optString("path", "").takeIf { it.isNotEmpty() }
                    ?: throw RPCException(
                        -32602,
                        "Missing 'path' (or 'items') for subcommand=set",
                    )
                val valueJson = params.optString("value_json", "").takeIf { it.isNotEmpty() }
                    ?: throw RPCException(-32602, "Missing 'value_json' for subcommand=set")
                JSONArray().put(
                    JSONObject().apply {
                        put("path", path)
                        put("value_json", valueJson)
                    },
                )
            }
            // Default stays TRUE for backward compatibility: existing
            // harnesses call this unattended and would hang on a sheet that
            // nobody can tap. Passing `skipConfirmation:false` is what lets a
            // test drive the REAL confirmation gate — without it that path is
            // permanently unreachable from automation, which is precisely
            // what iOS DebugRPCConfig calls out (it defaults the other way,
            // preferring fidelity over convenience).
            val skip = params.optBoolean("skipConfirmation", true)
            AppLogger.info(
                "DebugRPC",
                "debug.minisConfig.exec set items=${items.length()} skipConfirmation=$skip",
            )
            // Hop to the main thread because performWriteBatch is a
            // suspend fun that uses Dispatchers.Main internally.
            kotlinx.coroutines.runBlocking {
                com.openminis.app.config.ConfigBridge.performWriteBatch(
                    items = items,
                    caption = "debug.minisConfig.exec",
                    actorRaw = "debug-rpc",
                    sessionId = null,
                    skipConfirmation = skip,
                )
            }
        }
        "get" -> {
            val path = params.optString("path", "").takeIf { it.isNotEmpty() }
                ?: throw RPCException(-32602, "Missing 'path' for subcommand=get")
            // filter/page/pageSize were hardcoded to null/0/0, which made
            // large collections (models, sessions) unreadable from
            // automation — readField supports all three, so forward them.
            // 0/0 preserves the previous "no pagination" behaviour.
            // NOTE: readField's page is 1-BASED (it maps page<=0 to page 1),
            // so page=0 and page=1 both return the first page.
            val filter = params.optString("filter", "").takeIf { it.isNotEmpty() }
            val page = params.optInt("page", 0)
            val pageSize = params.optInt("pageSize", 0)
            AppLogger.info("DebugRPC", "debug.minisConfig.exec get path=$path")
            com.openminis.app.config.ConfigBridge.readField(
                path = path,
                filter = filter,
                page = page,
                pageSize = pageSize,
            )
        }
        // Discovery. Without these a caller has to know a collection's
        // writable paths in advance; `topics` is `minis-config --help`'s
        // index and `topic-help` is `minis-config <topic> --help`.
        "topics" -> JSONObject().apply {
            put("ok", true)
            put("topics", com.openminis.app.config.ConfigBridge.allTopics())
        }
        "topic-help" -> {
            val topic = params.optString("topic", "").takeIf { it.isNotEmpty() }
                ?: throw RPCException(-32602, "Missing 'topic' for subcommand=topic-help")
            // "No fields" has TWO causes that must not be conflated: an
            // unregistered topic (caller typo — an error), and a registered
            // COLLECTION that currently has no children, e.g. `thinkingrules`
            // before the user authors a rule. The latter is a legitimate
            // empty result; reporting it as "unknown topic" sends the caller
            // hunting for a name that is in fact correct.
            //
            // allTopics() is the authority because it includes every
            // registered collection basePath regardless of child count.
            val known = com.openminis.app.config.ConfigBridge.allTopics()
                .let { arr -> (0 until arr.length()).any { arr.optString(it) == topic } }
            if (!known) {
                throw RPCException(-32602, "Unknown topic '$topic' — call subcommand=topics")
            }
            val fields = com.openminis.app.config.ConfigBridge.fieldsForTopic(topic)
            JSONObject().apply {
                put("ok", true)
                put("topic", topic)
                // Explicit, so an empty list is unambiguously "registered but
                // has no children right now" rather than a silent oddity.
                put("empty", fields.length() == 0)
                put("fields", fields)
            }
        }
        "audit-list" -> {
            val limit = params.optInt("limit", 100).coerceIn(1, 1000)
            val scope = params.optString("scope", "").takeIf { it.isNotEmpty() }
            com.openminis.app.config.ConfigBridge.auditList(limit, scope)
        }
        else -> throw RPCException(
            -32602,
            "Unknown subcommand '$sub' — one of: set, get, topics, topic-help, audit-list",
        )
    }
}
