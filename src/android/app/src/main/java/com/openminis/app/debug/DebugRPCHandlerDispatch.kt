package com.openminis.app.debug

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import com.openminis.app.BuildConfig
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.debug.DebugRPCHandler.Companion.CRASH_NAME
import com.openminis.app.debug.DebugRPCHandler.Companion.currentActivity
import com.openminis.app.logging.AppLogger
import com.openminis.app.runtime.RuntimePathRegistry
import com.openminis.app.runtime.files.WorkspaceFileClient
import com.openminis.app.tools.ExternalMountAccess
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

suspend fun DebugRPCHandler.handle(json: String): String {
    val parsed = try {
        JSONObject(json)
    } catch (_: Exception) {
        return errorResponse(-32700, "Parse error", null)
    }

    val id = parsed.opt("id")
    val jsonrpc = parsed.optString("jsonrpc")
    if (jsonrpc != "2.0") {
        return errorResponse(-32600, "Invalid Request: missing jsonrpc 2.0", id)
    }

    val method = parsed.optString("method")
    if (method.isEmpty()) {
        return errorResponse(-32600, "Invalid Request: missing method", id)
    }

    val params = parsed.optJSONObject("params") ?: JSONObject()

    return try {
        val result = dispatch(method, params)
        successResponse(result, id)
    } catch (e: RPCException) {
        errorResponse(e.code, e.message ?: "Unknown error", id)
    } catch (e: Exception) {
        errorResponse(-32000, e.message ?: "Internal error", id)
    }
}

internal suspend fun DebugRPCHandler.dispatch(method: String, params: JSONObject): Any {
    return when (method) {
        "rpc.discover" -> DebugMethodRegistry.discover()
        "debug.appInfo" -> handleAppInfo()
        "debug.screenshot" -> handleScreenshot(params)
        "debug.ls" -> handleLS(params)
        "debug.rawLs" -> handleRawLS(params)
        "debug.readFile" -> handleReadFile(params)
        "debug.logs.list" -> handleLogsList()
        "debug.logs.read" -> handleLogsRead(params)
        "debug.logs.setEnabled" -> handleLogsSetEnabled(params)
        "debug.crash.list" -> handleCrashList(params)
        "debug.crash.read" -> handleCrashRead(params)
        "debug.tap" -> handleTap(params)
        "debug.scroll" -> handleScroll(params)
        "debug.inputText" -> handleInputText(params)
        "debug.setClipboard" -> handleSetClipboard(params)
        "debug.llmRequests" -> handleLLMRequests(params)
        "debug.llmRequests.clear" -> { LLMRequestLog.clear(); JSONObject().put("cleared", true) }
        "debug.agentTrace" -> handleAgentTrace(params)
        "debug.fetch" -> handleFetch(params)
        "debug.shellExecute" -> handleShellExecute(params)
        "debug.mcp.status" -> handleMcpStatus()
        "debug.mcp.start" -> handleMcpStart()
        "debug.mcp.stop" -> handleMcpStop()
        "debug.mcp.settoken" -> handleMcpSetToken(params)
        "debug.mcp.confirm.answer" -> handleMcpConfirmAnswer(params)
        "debug.update.check" -> handleUpdateCheck()
        "debug.update.download" -> handleUpdateDownload(params)
        "debug.update.install" -> handleUpdateInstall(params)
        "debug.permissions.list" -> handlePermissionsList()

        // UI introspection (Layer A parity with iOS)
        "debug.viewTree" -> handleViewTree(params)
        "debug.search" -> handleSearch(params)
        "debug.inspect" -> handleInspect(params)
        "debug.highlight" -> handleHighlight(params)
        "debug.cloudSync" -> handleCloudSync()
        "debug.writeFile" -> handleWriteFile(params)
        "debug.screenshot.capture" -> handleScreenshotCapture(params)
        "debug.screenshot.list" -> DebugScreenshotRing.listJson()
        "debug.screenshot.get" -> handleScreenshotGet(params)
        "debug.screenshot.clear" -> JSONObject().put("cleared", DebugScreenshotRing.clearAll())

        // Browser
        "debug.browser.listTabs" -> BrowserDebugMethods.listTabs(context)
        "debug.browser.pageInfo" -> BrowserDebugMethods.pageInfo(context, params)
        "debug.browser.executeJS" -> BrowserDebugMethods.executeJS(context, params)
        "debug.browser.getReadable" -> BrowserDebugMethods.getReadable(context, params)
        "debug.browser.getText" -> BrowserDebugMethods.getText(context, params)
        "debug.browser.screenshot" -> BrowserDebugMethods.screenshot(context, params)

        // Provider (read)
        "provider.types" -> ProviderDebugMethods.types()
        "provider.instances.list" -> ProviderDebugMethods.instancesList(context, params)
        "provider.models.list" -> ProviderDebugMethods.modelsList(context, params)
        "provider.slots.get" -> ProviderDebugMethods.slotsGet(context, params)
        "provider.quickTest" -> ProviderDebugMethods.quickTest(context, params)
        "provider.export" -> ProviderDebugMethods.export(context, params)
        "provider.import" -> ProviderDebugMethods.import(context, params)

        // Provider (mutate)
        "provider.instances.create" -> ProviderMutationMethods.instancesCreate(context, params)
        "provider.instances.update" -> ProviderMutationMethods.instancesUpdate(context, params)
        "provider.instances.delete" -> ProviderMutationMethods.instancesDelete(context, params)
        "provider.instances.test" -> ProviderMutationMethods.instancesTest(context, params)
        "provider.models.add" -> ProviderMutationMethods.modelsAdd(context, params)
        "provider.models.update" -> ProviderMutationMethods.modelsUpdate(context, params)
        "provider.models.delete" -> ProviderMutationMethods.modelsDelete(context, params)
        "provider.models.refresh" -> ProviderMutationMethods.modelsRefresh(context, params)
        "provider.models.setAgentLoop" -> ProviderMutationMethods.modelsSetAgentLoop(context, params)
        "provider.models.setDefaults" -> ProviderMutationMethods.modelsSetDefaults(context, params)
        "provider.slots.set" -> ProviderMutationMethods.slotsSet(context, params)

        // Chat (read)
        "chat.sessions.list" -> ChatDebugMethods.sessionsList(context, params)
        "chat.sessions.get" -> ChatDebugMethods.sessionsGet(context, params)
        "chat.sessions.usage" -> ChatDebugMethods.sessionsUsage(context, params)
        "chat.messages.list" -> ChatDebugMethods.messagesList(context, params)
        "chat.models.list" -> ChatDebugMethods.modelsList(context, params)

        // Chat (mutate)
        "chat.prompt" -> ChatMutationMethods.prompt(context, params)
        "chat.uiPrompt" -> ChatMutationMethods.uiPrompt(context, params)
        "chat.retry" -> ChatMutationMethods.retry(context, params)
        "chat.rerunFromToolBlock" -> ChatMutationMethods.rerunFromToolBlock(context, params)
        "chat.session.status" -> ChatMutationMethods.status(context, params)
        "chat.session.cancel" -> ChatMutationMethods.cancel(context, params)
        "chat.session.selectModel" -> ChatMutationMethods.selectModel(context, params)
        "chat.session.selectThinkingLevel" -> ChatMutationMethods.selectThinkingLevel(context, params)
        "chat.session.delete" -> ChatMutationMethods.delete(context, params)

        // Chat compact (mirrors iOS chat.compact.* namespace).
        "chat.compact.before" -> ChatMutationMethods.compactBefore(context, params)
        "chat.compact.markers.list" -> ChatMutationMethods.compactMarkersList(context, params)
        "chat.compact.revert" -> ChatMutationMethods.compactRevert(context, params)

        // Web question cards (ask_user_question tool) + cross-session search
        "chat.question.pending" -> QuestionRpcMethods.pending(context, params)
        "chat.question.answer" -> QuestionRpcMethods.answer(context, params)
        "chat.search" -> ChatSearchRpcMethods.search(context, params)
        "chat.feedback.put" -> FeedbackRpcMethods.put(context, params)
        "chat.feedback.delete" -> FeedbackRpcMethods.delete(context, params)
        "chat.feedback.listForMessages" -> FeedbackRpcMethods.listForMessages(context, params)

        // Agent bars (goal / todo / plan / deliverables)
        "agent.goal.get" -> AgentStateRpcMethods.goalGet(context, params)
        "agent.goal.set" -> AgentStateRpcMethods.goalSet(context, params)
        "agent.goal.setActive" -> AgentStateRpcMethods.goalSetActive(context, params)
        "agent.todo.get" -> AgentStateRpcMethods.todoGet(context, params)
        "agent.todo.replace" -> AgentStateRpcMethods.todoReplace(context, params)
        "agent.plan.get" -> AgentStateRpcMethods.planGet(context, params)
        "agent.plan.set" -> AgentStateRpcMethods.planSet(context, params)
        "agent.deliverables.list" -> AgentStateRpcMethods.deliverablesList(context, params)
        "agent.deliverables.clear" -> AgentStateRpcMethods.deliverablesClear(context, params)

        // Approvals (one-time dangerous-operation consent)
        "agent.approval.list" -> AgentRpcMethods.approvalsList(context, params)
        "agent.approval.answer" -> AgentRpcMethods.approvalsAnswer(context, params)

        // Jobs (DeepSeek Harness dsh-tool-jobs, minimal port)
        "agent.jobs.list" -> AgentRpcMethods.jobsList(context, params)
        "agent.jobs.cancel" -> AgentRpcMethods.jobsCancel(context, params)

        // Skills
        "skills.list" -> SkillRpcMethods.list(context)
        "skills.get" -> SkillRpcMethods.get(context, params)
        "skills.create" -> SkillRpcMethods.create(context, params)
        "skills.importUrl" -> SkillRpcMethods.importUrl(context, params)
        "skills.update" -> SkillRpcMethods.update(context, params)
        "skills.toggle" -> SkillRpcMethods.toggle(context, params)
        "skills.delete" -> SkillRpcMethods.delete(context, params)

        // Memory
        "memory.files.list" -> MemoryRpcMethods.filesList(context)
        "memory.files.read" -> MemoryRpcMethods.filesRead(context, params)
        "memory.files.write" -> MemoryRpcMethods.filesWrite(context, params)
        "memory.files.delete" -> MemoryRpcMethods.filesDelete(context, params)
        "memory.globalToggle" -> MemoryRpcMethods.globalToggle(context)
        "memory.setGlobalEnabled" -> MemoryRpcMethods.setGlobalEnabled(context, params)
        "soul.get" -> MemoryRpcMethods.soulGet(context)
        "soul.save" -> MemoryRpcMethods.soulSave(context, params)

        // MCP
        "mcp.list" -> McpRpcMethods.list(context)
        "mcp.get" -> McpRpcMethods.get(context, params)
        "mcp.create" -> McpRpcMethods.create(context, params)
        "mcp.update" -> McpRpcMethods.update(context, params)
        "mcp.import" -> McpRpcMethods.importJson(context, params)
        "mcp.importUrl" -> McpRpcMethods.importUrl(context, params)
        "mcp.toggle" -> McpRpcMethods.toggle(context, params)
        "mcp.delete" -> McpRpcMethods.delete(context, params)

        // Environment values remain write-only over RPC.
        "environments.list" -> EnvironmentRpcMethods.list(context)
        "environments.create" -> EnvironmentRpcMethods.create(context, params)
        "environments.update" -> EnvironmentRpcMethods.update(context, params)
        "environments.delete" -> EnvironmentRpcMethods.delete(context, params)

        // Shared folders and Android SAF-backed external mounts.
        "storage.shared.list" -> StorageRpcMethods.sharedList()
        "storage.mounts.list" -> StorageRpcMethods.mountsList(context)
        "storage.mounts.rename" -> StorageRpcMethods.mountsRename(context, params)
        "storage.mounts.setWritable" -> StorageRpcMethods.mountsSetWritable(context, params)
        "storage.mounts.remove" -> StorageRpcMethods.mountsRemove(context, params)

        // Scheduled Tasks — Web and native screens share ScheduledTaskManager.
        "scheduled.list" -> ScheduledTaskRpcMethods.list(context)
        "scheduled.get" -> ScheduledTaskRpcMethods.get(context, params)
        "scheduled.create" -> ScheduledTaskRpcMethods.create(context, params)
        "scheduled.update" -> ScheduledTaskRpcMethods.update(context, params)
        "scheduled.toggle" -> ScheduledTaskRpcMethods.toggle(context, params)
        "scheduled.delete" -> ScheduledTaskRpcMethods.delete(context, params)
        "scheduled.run" -> ScheduledTaskRpcMethods.run(context, params)
        "scheduled.runs" -> ScheduledTaskRpcMethods.runs(context, params)

        // Agent settings (main/sub agent knobs for the Web Remote)
        "agent.settings.get" -> AgentRpcMethods.settingsGet(context)
        "agent.settings.set" -> AgentRpcMethods.settingsSet(context, params)
        "agent.sessionPermission.get" -> AgentRpcMethods.sessionPermissionGet(context, params)
        "agent.sessionPermission.set" -> AgentRpcMethods.sessionPermissionSet(context, params)

        // Debug-only: direct CLI / offload-handler invocation (T344).
        // Registered solely on DEBUG builds so release APKs cannot expose it.
        "debug.rootCli.exec" -> {
            if (!BuildConfig.DEBUG) {
                throw RPCException(-32601, "Method not found: $method. Call 'rpc.discover' to list available methods.")
            }
            handleRootCliExec(params)
        }
        "debug.modelUse.exec" -> {
            if (!BuildConfig.DEBUG) {
                throw RPCException(-32601, "Method not found: $method. Call 'rpc.discover' to list available methods.")
            }
            handleModelUseExec(params)
        }
        // [T-android-sessions-cli-full] DEBUG-only invocation of the
        // SessionsOffloadHandler — parallels debug.modelUse.exec so test
        // harnesses can verify minis-sessions-cli (list / search /
        // messages, incl. --full) end-to-end without an in-shell prompt.
        "debug.sessions.exec" -> {
            if (!BuildConfig.DEBUG) {
                throw RPCException(-32601, "Method not found: $method. Call 'rpc.discover' to list available methods.")
            }
            handleSessionsExec(params)
        }
        // [T-minis-config-provider-add] DEBUG-only invocation of the
        // ConfigOffloadHandler — parallels debug.modelUse.exec so test
        // harnesses can exercise minis-config (get / set / set-batch /
        // audit-*) without driving an in-shell prompt. The handler
        // re-uses the production ConfigBridge code path; we override
        // skipConfirmation under the hood via a dedicated arg the
        // production CLI never exposes.
        "debug.minisConfig.exec" -> {
            if (!BuildConfig.DEBUG) {
                throw RPCException(-32601, "Method not found: $method. Call 'rpc.discover' to list available methods.")
            }
            handleMinisConfigExec(params)
        }

        else -> throw RPCException(-32601, "Method not found: $method. Call 'rpc.discover' to list available methods.")
    }
}

// ── App Info ─────────────────────────────────────────────────────────────

internal suspend fun DebugRPCHandler.handleAppInfo(): JSONObject {
    val filesDir = context.filesDir
    val sessionRoots = listOf(
        "/var/minis/workspace",
        "/var/minis/attachments",
        "/var/minis/offloads",
        "/var/minis/browser",
    )
    val sessionsSize = runCatching {
        var total = 0L
        for (session in AppDatabase.getInstance(context).chatDao().listSessions()) {
            var size = 0L
            for (root in sessionRoots) {
                size += WorkspaceFileClient.treeSize(session.id, root)
            }
            total += size
        }
        total
    }.getOrDefault(0L)
    val memorySize = runCatching { WorkspaceFileClient.treeSize("", "/var/minis/memory") }.getOrDefault(0L)
    val skillsSize = runCatching { WorkspaceFileClient.treeSize("", "/var/minis/skills") }.getOrDefault(0L)
    val sharedSize = runCatching { WorkspaceFileClient.treeSize("", "/var/minis/shared") }.getOrDefault(0L)
    return JSONObject().apply {
        put("platform", "android")
        put("sdkVersion", Build.VERSION.SDK_INT)
        put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        put("androidVersion", Build.VERSION.RELEASE)
        val ubuntuRunning = com.openminis.app.runtime.ubuntu.UbuntuRuntime.snapshot.value.running
        put("ubuntu", ubuntuRunning)
        put("filesDir", filesDir.absolutePath)
        put("logFiles", AppLogger.listLogFiles().size)
        put("totalLogSize", AppLogger.totalSize())
        put("diskUsage", JSONObject().apply {
            put("filesDir", dirSize(filesDir))
            put("sessions", sessionsSize)
            put("memory", memorySize)
            put("skills", skillsSize)
            put("shared", sharedSize)
        })
    }
}

internal fun DebugRPCHandler.dirSize(dir: File): Long {
    if (!dir.exists()) return 0
    return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}

// ── Screenshot ──────────────────────────────────────────────────────────

internal suspend fun DebugRPCHandler.handleScreenshot(params: JSONObject): JSONObject {
    // Clamp like handleScreenshotCapture: an unbounded scale would let a
    // client request a TB-sized bitmap and OOM the process.
    val scale = params.optDouble("scale", 1.0).toFloat().coerceIn(0.05f, 1.0f)

    val activity = currentActivity?.get()
        ?: throw RPCException(-32000, "No active Activity for screenshot")

    val bitmap = withContext(Dispatchers.Main) {
        captureActivityScreenshot(activity, scale)
    }

    val baos = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
    bitmap.recycle()
    val pngBytes = baos.toByteArray()
    val base64 = android.util.Base64.encodeToString(pngBytes, android.util.Base64.NO_WRAP)

    return JSONObject().apply {
        put("base64", base64)
        put("size", pngBytes.size)
        put("encoding", "png")
    }
}

internal suspend fun DebugRPCHandler.captureActivityScreenshot(activity: Activity, scale: Float): Bitmap =
    suspendCancellableCoroutine { cont ->
        val rootView = activity.window.decorView.rootView
        // Use PixelCopy for accurate capture on API 26+
        val width = (rootView.width * scale).toInt().coerceAtLeast(1)
        val height = (rootView.height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // Timeout guard: PixelCopy's callback is not guaranteed to
        // arrive (window destroyed etc.), which would otherwise leave
        // the calling coroutine suspended forever.
        val timeoutRunnable = Runnable {
            if (cont.isActive) {
                cont.resume(canvasCapture(rootView, scale))
            }
        }
        Handler(Looper.getMainLooper()).postDelayed(timeoutRunnable, 5_000L)
        android.view.PixelCopy.request(
            activity.window, bitmap,
            { result ->
                Handler(Looper.getMainLooper()).removeCallbacks(timeoutRunnable)
                if (result == android.view.PixelCopy.SUCCESS) {
                    cont.resume(bitmap)
                } else {
                    // Fallback to canvas draw
                    cont.resume(canvasCapture(rootView, scale))
                }
            },
            Handler(Looper.getMainLooper()),
        )
    }

internal fun DebugRPCHandler.canvasCapture(view: android.view.View, scale: Float): Bitmap {
    val width = (view.width * scale).toInt().coerceAtLeast(1)
    val height = (view.height * scale).toInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    if (scale != 1f) canvas.scale(scale, scale)
    view.draw(canvas)
    return bitmap
}

// ── File System ─────────────────────────────────────────────────────────

internal suspend fun DebugRPCHandler.handleLS(params: JSONObject): Any {
    val path = params.optString("path", "/")
    if (path.contains("..")) throw RPCException(-32602, "Invalid path: '..' not allowed")

    val recursive = params.optBoolean("recursive", false)
    val maxDepth = params.optInt("maxDepth", 3)

    if (isCanonicalGuestPath(path)) {
        return handleGuestLS(path, params.optString("sessionId"), recursive, maxDepth)
    }

    if (ExternalMountAccess.isPath(path)) {
        return handleExternalLS(path, recursive, maxDepth)
    }

    val hostFile = RuntimePathRegistry.resolveHostPath(path)
        ?: throw RPCException(-32602, "Cannot resolve path: $path")

    if (!hostFile.isDirectory) throw RPCException(-32602, "Not a directory: $path")

    return if (recursive) {
        listRecursive(hostFile, path, maxDepth, 0)
    } else {
        listFlat(hostFile)
    }
}

internal suspend fun DebugRPCHandler.handleGuestLS(
    path: String,
    sessionId: String,
    recursive: Boolean,
    maxDepth: Int,
): Any {
    return if (recursive) {
        listGuestRecursive(sessionId, path, maxDepth.coerceIn(0, 8), 0)
    } else {
        listGuestFlat(sessionId, path)
    }
}

internal suspend fun DebugRPCHandler.handleExternalLS(
    path: String,
    recursive: Boolean,
    maxDepth: Int,
): JSONArray {
    val boundedDepth = maxDepth.coerceIn(0, 8)
    return listExternalDirectory(path.trimEnd('/').ifEmpty { "/var/minis/mounts" }, recursive, boundedDepth, 0)
}

internal suspend fun DebugRPCHandler.listExternalDirectory(
    path: String,
    recursive: Boolean,
    maxDepth: Int,
    depth: Int,
): JSONArray {
    val array = JSONArray()
    var offset = 0
    while (true) {
        val page = ExternalMountAccess.list(path, 500, offset)
        val entries = page.optJSONArray("entries") ?: JSONArray()
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: continue
            val name = entry.optString("name")
            if (name.isEmpty()) continue
            val type = entry.optString("type", "other")
            val childPath = "$path/$name"
            val item = JSONObject().apply {
                put("name", name)
                if (recursive) put("path", childPath)
                put("type", if (type == "dir") "directory" else type)
                put("size", entry.optLong("size", 0L))
                put("modified", entry.optLong("modified", 0L))
            }
            if (recursive && type == "dir" && depth < maxDepth) {
                item.put("children", listExternalDirectory(childPath, true, maxDepth, depth + 1))
            }
            array.put(item)
        }
        val next = page.optInt("next_offset", -1)
        if (next < 0 || next <= offset) break
        offset = next
    }
    return array
}

internal suspend fun DebugRPCHandler.listGuestFlat(sessionId: String, path: String): JSONArray {
    val array = JSONArray()
    for (entry in WorkspaceFileClient.listAll(sessionId, path)) {
        array.put(JSONObject().apply {
            put("name", entry.optString("name"))
            put("type", if (entry.optString("type") == "dir") "directory" else entry.optString("type"))
            put("size", entry.optLong("size", 0L))
            put("modified", entry.optLong("modified", 0L))
        })
    }
    return array
}

internal suspend fun DebugRPCHandler.listGuestRecursive(
    sessionId: String,
    path: String,
    maxDepth: Int,
    depth: Int,
): JSONArray {
    val array = JSONArray()
    for (entry in WorkspaceFileClient.listAll(sessionId, path)) {
        val name = entry.optString("name")
        val type = entry.optString("type")
        val childPath = "$path/$name"
        val item = JSONObject().apply {
            put("name", name)
            put("path", childPath)
            put("type", if (type == "dir") "directory" else type)
            put("size", entry.optLong("size", 0L))
            put("modified", entry.optLong("modified", 0L))
        }
        if (type == "dir" && depth < maxDepth) {
            item.put("children", listGuestRecursive(sessionId, childPath, maxDepth, depth + 1))
        }
        array.put(item)
    }
    return array
}

/**
 * [diag] Raw list of an App-owned directory, bypassing guest-path
 * resolution. Constrained to filesDir to avoid poking at arbitrary paths.
 * Use `minis-sessions` (default) to enumerate session attachments,
 * workspaces and other App-owned data.
 */
internal fun DebugRPCHandler.handleRawLS(params: JSONObject): Any {
    val subPath = params.optString("path", "minis-sessions")
    if (subPath.contains("..")) throw RPCException(-32602, "Invalid path: '..' not allowed")
    val recursive = params.optBoolean("recursive", true)
    val maxDepth = params.optInt("maxDepth", 4)

    val base = context.filesDir
    val target = if (subPath.isEmpty() || subPath == "/") base else File(base, subPath)
    if (!target.exists()) throw RPCException(-32602, "Not found: ${target.absolutePath}")
    if (!target.isDirectory) throw RPCException(-32602, "Not a directory: ${target.absolutePath}")

    val root = JSONObject()
    root.put("root", target.absolutePath)
    root.put("entries",
        if (recursive) listRecursive(target, target.absolutePath, maxDepth, 0)
        else listFlat(target)
    )
    return root
}

internal fun DebugRPCHandler.listFlat(dir: File): JSONArray {
    val files = dir.listFiles() ?: return JSONArray()
    val array = JSONArray()
    for (file in files.sortedBy { it.name }) {
        array.put(JSONObject().apply {
            put("name", file.name)
            put("type", if (file.isDirectory) "directory" else "file")
            put("size", file.length())
            put("modified", file.lastModified())
        })
    }
    return array
}

internal fun DebugRPCHandler.listRecursive(dir: File, virtualPath: String, maxDepth: Int, depth: Int): JSONArray {
    val array = JSONArray()
    val files = dir.listFiles()?.sortedBy { it.name } ?: return array
    for (file in files) {
        val childPath = if (virtualPath == "/") "/${file.name}" else "$virtualPath/${file.name}"
        val entry = JSONObject().apply {
            put("name", file.name)
            put("path", childPath)
            put("type", if (file.isDirectory) "directory" else "file")
            put("size", file.length())
            put("modified", file.lastModified())
        }
        if (file.isDirectory && depth < maxDepth) {
            entry.put("children", listRecursive(file, childPath, maxDepth, depth + 1))
        }
        array.put(entry)
    }
    return array
}

internal suspend fun DebugRPCHandler.handleReadFile(params: JSONObject): JSONObject {
    val path = params.optString("path")
    if (path.isEmpty()) throw RPCException(-32602, "Invalid params: 'path' is required")
    if (path.contains("..")) throw RPCException(-32602, "Invalid path: '..' not allowed")

    if (isCanonicalGuestPath(path)) {
        return handleGuestReadFile(params)
    }

    if (ExternalMountAccess.isPath(path)) {
        val metadata = ExternalMountAccess.info(path)
        val fileSize = metadata.optLong("size", 0L).coerceAtLeast(0L)
        val offset = params.optLong("offset", 0L).coerceAtLeast(0L)
        val limit = params.optInt("limit", 524_288).coerceIn(0, 16 * 1024 * 1024)
        val forceBase64 = params.optBoolean("base64", false)
        val bytes = readExternalRange(path, offset, limit)
        return formatReadResult(bytes, fileSize, offset, forceBase64)
    }

    val hostFile = RuntimePathRegistry.resolveHostPath(path)
        ?: throw RPCException(-32602, "Cannot resolve path: $path")

    if (!hostFile.exists()) throw RPCException(-32602, "File not found: $path")

    val fileSize = hostFile.length()
    val offset = params.optLong("offset", 0).coerceAtLeast(0)
    val limit = params.optInt("limit", 524_288).coerceIn(0, 16 * 1024 * 1024)
    val forceBase64 = params.optBoolean("base64", false)

    val bytes = hostFile.inputStream().use { stream ->
        if (offset > 0) {
            // InputStream.skip is not guaranteed to skip the full amount;
            // loop until the requested offset is reached.
            var remaining = offset
            while (remaining > 0) {
                val skipped = stream.skip(remaining)
                if (skipped <= 0) break
                remaining -= skipped
            }
        }
        stream.readNBytes(limit)
    }

    val isText = !forceBase64 && bytes.all {
        it in 0x09..0x0D || it in 0x20..0x7E || it.toInt() and 0xFF > 0x7F
    }

    return JSONObject().apply {
        put("size", fileSize)
        if (isText) {
            put("content", String(bytes, Charsets.UTF_8))
            put("encoding", "utf8")
        } else {
            put("content", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
            put("encoding", "base64")
        }
        put("bytesRead", bytes.size)
        if (offset + bytes.size < fileSize) put("truncated", true)
    }
}

internal suspend fun DebugRPCHandler.readExternalRange(path: String, offset: Long, limit: Int): ByteArray {
    if (limit == 0) return ByteArray(0)
    val output = ByteArrayOutputStream(limit.coerceAtMost(524_288))
    var nextOffset = offset
    while (output.size() < limit) {
        val length = minOf(WorkspaceFileClient.MAX_READ_CHUNK, limit - output.size())
        val chunk = WorkspaceFileClient.readChunk(null, path, nextOffset, length)
        if (chunk.offset != nextOffset) {
            throw RPCException(-32000, "Workspace read returned offset ${chunk.offset}, expected $nextOffset: $path")
        }
        output.write(chunk.bytes)
        nextOffset += chunk.bytes.size
        if (chunk.eof || chunk.bytes.isEmpty()) break
    }
    return output.toByteArray()
}

internal fun DebugRPCHandler.formatReadResult(
    bytes: ByteArray,
    fileSize: Long,
    offset: Long,
    forceBase64: Boolean,
): JSONObject {
    val isText = !forceBase64 && bytes.all {
        it in 0x09..0x0D || it in 0x20..0x7E || it.toInt() and 0xFF > 0x7F
    }
    return JSONObject().apply {
        put("size", fileSize)
        if (isText) {
            put("content", String(bytes, Charsets.UTF_8))
            put("encoding", "utf8")
        } else {
            put("content", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
            put("encoding", "base64")
        }
        put("bytesRead", bytes.size)
        if (offset + bytes.size < fileSize) put("truncated", true)
    }
}

internal suspend fun DebugRPCHandler.handleGuestReadFile(params: JSONObject): JSONObject {
    val path = params.optString("path")
    val sessionId = params.optString("sessionId")
    val offset = params.optLong("offset", 0L).coerceAtLeast(0L)
    val limit = params.optInt("limit", 524_288).coerceIn(0, 16 * 1024 * 1024)
    val metadata = WorkspaceFileClient.info(sessionId, path)
    val fileSize = metadata.optLong("size", 0L).coerceAtLeast(0L)
    val output = ByteArrayOutputStream(limit.coerceAtMost(524_288))
    var nextOffset = offset
    var eof = limit == 0
    while (!eof && output.size() < limit) {
        val chunk = WorkspaceFileClient.readChunk(sessionId, path, nextOffset)
        if (chunk.bytes.isEmpty()) {
            eof = true
            break
        }
        val remaining = limit - output.size()
        val count = minOf(remaining, chunk.bytes.size)
        output.write(chunk.bytes, 0, count)
        nextOffset += count
        eof = chunk.eof || count < chunk.bytes.size
    }
    val bytes = output.toByteArray()
    val isText = bytes.all {
        it in 0x09..0x0D || it in 0x20..0x7E || it.toInt() and 0xFF > 0x7F
    }
    return JSONObject().apply {
        put("size", fileSize)
        if (isText) {
            put("content", String(bytes, Charsets.UTF_8))
            put("encoding", "utf8")
        } else {
            put("content", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
            put("encoding", "base64")
        }
        put("bytesRead", bytes.size)
        if (!eof || offset + bytes.size < fileSize) put("truncated", true)
    }
}

// ── Logging ──────────────────────────────────────────────────────────────

internal fun DebugRPCHandler.handleLogsList(): JSONObject {
    val files = AppLogger.listLogFiles()
    val array = JSONArray()
    for (file in files) {
        array.put(JSONObject().apply {
            put("name", file.name)
            put("size", file.length())
            put("modified", file.lastModified())
        })
    }
    return JSONObject().apply {
        put("totalSize", AppLogger.totalSize())
        put("files", array)
    }
}

internal fun DebugRPCHandler.handleLogsRead(params: JSONObject): JSONObject {
    val name = params.optString("name")
    if (name.isEmpty()) throw RPCException(-32602, "Invalid params: 'name' is required")
    if (name.contains("/") || name.contains("..")) {
        throw RPCException(-32602, "Invalid params: 'name' must be a filename")
    }

    val content = AppLogger.readLog(name)
        ?: throw RPCException(-32602, "Log file not found: $name")

    val offset = params.optInt("offset", 0).coerceAtLeast(0)
    val limit = params.optInt("limit", 524_288).coerceIn(0, 16 * 1024 * 1024)
    val sliced = content.substring(
        offset.coerceAtMost(content.length),
        (offset + limit).coerceAtMost(content.length),
    )

    return JSONObject().apply {
        put("name", name)
        put("size", content.length)
        put("content", sliced)
        put("bytesRead", sliced.length)
        if (sliced.length < content.length - offset) put("truncated", true)
    }
}

// ── Crash reports ───────────────────────────────────────────────────────
//
// [T-android-debug-crashes] Crash triage over the debug server. Both crash
// writers already drop files into filesDir/logs — ACRA's CrashFileSender
// writes "crash-<stamp>.log" (Java/Kotlin) and NativeCrashHandler writes
// "native-crash-<stamp>.log" — but reaching them previously meant knowing
// that convention and shelling out through `adb run-as`, which is
// unavailable to any client that isn't on a USB cable. A crashed app also
// stops answering RPC, so the crash is exactly when the debug server is
// least able to explain itself; these methods make the post-restart
// "what just died?" question answerable in one call.

internal fun DebugRPCHandler.crashFiles(): List<File> {
    val dir = File(context.filesDir, "logs")
    if (!dir.isDirectory) return emptyList()
    return (dir.listFiles() ?: emptyArray())
        .filter { it.isFile && CRASH_NAME.matches(it.name) }
        .sortedByDescending { it.lastModified() }
}

internal fun DebugRPCHandler.handleCrashList(params: JSONObject): JSONObject {
    val limit = params.optInt("limit", 20).coerceIn(1, 200)
    val files = crashFiles()
    val array = JSONArray()
    for (f in files.take(limit)) {
        array.put(JSONObject().apply {
            put("name", f.name)
            put("size", f.length())
            put("modified", f.lastModified())
            put("native", f.name.startsWith("native-crash-"))
            // First stack line is what triage keys on, so surface it here
            // and save a read call when scanning a list of crashes.
            put("summary", crashSummary(f))
        })
    }
    return JSONObject().apply {
        put("count", files.size)
        put("returned", array.length())
        put("crashes", array)
    }
}

/** Exception type + first app frame — enough to tell crashes apart. */
internal fun DebugRPCHandler.crashSummary(f: File): String = try {
    val lines = f.bufferedReader().useLines { seq -> seq.take(400).toList() }
    val i = lines.indexOfFirst { it.startsWith("--- Stack Trace ---") }
    val body = if (i >= 0) lines.drop(i + 1) else lines
    val head = body.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    val frame = body.firstOrNull { it.trimStart().startsWith("at com.openminis") }
        ?.trim()?.removePrefix("at ").orEmpty()
    listOf(head, frame).filter { it.isNotEmpty() }.joinToString("  |  ")
} catch (e: Exception) {
    "(unreadable: ${e.message})"
}

internal fun DebugRPCHandler.handleCrashRead(params: JSONObject): JSONObject {
    val files = crashFiles()
    if (files.isEmpty()) throw RPCException(-32000, "No crash reports on device")
    // No name → newest, which is what "why did it just die?" wants.
    val name = params.optString("name").takeIf { it.isNotEmpty() }
    if (name != null && (name.contains("/") || name.contains(".."))) {
        throw RPCException(-32602, "Invalid params: 'name' must be a filename")
    }
    val file = if (name == null) files.first()
    else files.firstOrNull { it.name == name }
        ?: throw RPCException(-32602, "Crash report not found: $name")

    val full = file.readText()
    // Default to the stack only: the ACRA reports embed 200 logcat lines
    // plus a Build dump, so a whole file is ~27KB of which the useful part
    // is the first ~40 lines.
    val stackOnly = params.optBoolean("stackOnly", true)
    val content = if (!stackOnly) full else {
        val start = full.indexOf("--- Stack Trace ---")
        val end = full.indexOf("--- Logcat")
        when {
            start < 0 -> full
            end > start -> full.substring(0, end).trimEnd()
            else -> full.substring(0, minOf(full.length, start + 4000))
        }
    }
    val limit = params.optInt("limit", 262_144)
    val sliced = content.take(limit)
    return JSONObject().apply {
        put("name", file.name)
        put("modified", file.lastModified())
        put("native", file.name.startsWith("native-crash-"))
        put("fileSize", file.length())
        put("stackOnly", stackOnly)
        put("content", sliced)
        if (sliced.length < content.length) put("truncated", true)
    }
}

// ── Touch & Input Simulation ────────────────────────────────────────────

internal suspend fun DebugRPCHandler.handleTap(params: JSONObject): JSONObject {
    val x = params.optDouble("x", -1.0).toFloat()
    val y = params.optDouble("y", -1.0).toFloat()
    if (x < 0 || y < 0) throw RPCException(-32602, "Invalid params: 'x' and 'y' are required")

    val activity = currentActivity?.get()
        ?: throw RPCException(-32000, "No active Activity")

    withContext(Dispatchers.Main) {
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        activity.dispatchTouchEvent(down)
        down.recycle()

        val up = MotionEvent.obtain(downTime, downTime + 50, MotionEvent.ACTION_UP, x, y, 0)
        up.source = InputDevice.SOURCE_TOUCHSCREEN
        activity.dispatchTouchEvent(up)
        up.recycle()
    }

    return JSONObject().apply {
        put("ok", true)
        put("point", JSONObject().put("x", x).put("y", y))
    }
}

internal suspend fun DebugRPCHandler.handleScroll(params: JSONObject): JSONObject {
    val x = params.optDouble("x", -1.0).toFloat()
    val y = params.optDouble("y", -1.0).toFloat()
    val deltaX = params.optDouble("deltaX", 0.0).toFloat()
    val deltaY = params.optDouble("deltaY", 0.0).toFloat()

    if (x < 0 || y < 0) throw RPCException(-32602, "Invalid params: 'x' and 'y' are required")
    if (deltaX == 0f && deltaY == 0f) throw RPCException(-32602, "At least one of deltaX/deltaY must be non-zero")

    val activity = currentActivity?.get()
        ?: throw RPCException(-32000, "No active Activity")

    withContext(Dispatchers.Main) {
        val downTime = SystemClock.uptimeMillis()
        val steps = 10
        val stepDuration = 16L // ~60fps

        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        activity.dispatchTouchEvent(down)
        down.recycle()

        for (i in 1..steps) {
            val frac = i.toFloat() / steps
            val moveX = x + deltaX * frac
            val moveY = y + deltaY * frac
            val move = MotionEvent.obtain(downTime, downTime + i * stepDuration, MotionEvent.ACTION_MOVE, moveX, moveY, 0)
            move.source = InputDevice.SOURCE_TOUCHSCREEN
            activity.dispatchTouchEvent(move)
            move.recycle()
        }

        val up = MotionEvent.obtain(downTime, downTime + (steps + 1) * stepDuration, MotionEvent.ACTION_UP, x + deltaX, y + deltaY, 0)
        up.source = InputDevice.SOURCE_TOUCHSCREEN
        activity.dispatchTouchEvent(up)
        up.recycle()
    }

    return JSONObject().apply {
        put("ok", true)
        put("start", JSONObject().put("x", x).put("y", y))
        put("delta", JSONObject().put("x", deltaX).put("y", deltaY))
    }
}

internal suspend fun DebugRPCHandler.handleInputText(params: JSONObject): JSONObject {
    val text = params.optString("text")
    if (text.isEmpty()) throw RPCException(-32602, "Invalid params: 'text' is required")

    val activity = currentActivity?.get()
        ?: throw RPCException(-32000, "No active Activity")

    // [T-debug-inputtext-mainthread] Instrumentation.sendStringSync throws
    // "This method can not be called from the main application thread" —
    // it BLOCKS waiting for the injected key events to be processed by the
    // main thread, so it must be dispatched off-main. Note it maps chars
    // through KeyCharacterMap, so only ASCII-typable text lands; CJK input
    // needs the IME path and is out of scope for this harness hook.
    withContext(Dispatchers.IO) {
        val instrumentation = android.app.Instrumentation()
        instrumentation.sendStringSync(text)
    }

    return JSONObject().apply {
        put("ok", true)
        put("length", text.length)
    }
}

// ── LLM Request Tracking ──────────────────────────────────────────────

internal suspend fun DebugRPCHandler.handleSetClipboard(params: JSONObject): JSONObject {
    val text = params.optString("text")
    if (text.isEmpty()) throw RPCException(-32602, "Invalid params: 'text' is required")
    val label = params.optString("label", "minis-debug")

    // ClipboardManager.setPrimaryClip must run on a Looper thread, and the
    // write is only honoured while this app holds focus.
    withContext(Dispatchers.Main) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
    }

    // Read it straight back: a silent no-op here (focus lost, OEM policy)
    // would otherwise show up much later as a paste that pasted nothing.
    val readBack = withContext(Dispatchers.Main) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        cm.primaryClip?.getItemAt(0)?.text?.length ?: -1
    }

    return JSONObject().apply {
        put("ok", readBack == text.length)
        put("length", text.length)
        put("clipboardLength", readBack)
    }
}

// ── LLM Request Tracking ──────────────────────────────────────────────


internal fun DebugRPCHandler.handleLLMRequests(params: JSONObject): JSONObject {
    val last = if (params.has("last") && !params.isNull("last")) params.optInt("last", -1).takeIf { it > 0 } else null
    val raw = LLMRequestLog.toJSON(last)
    if (!params.optBoolean("formatted", false)) return raw
    return JSONObject().apply {
        put("count", raw.optInt("count", 0))
        put("text", formatLLMRequestsText(raw))
    }
}
