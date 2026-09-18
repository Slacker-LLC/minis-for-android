package com.openminis.app.tools.android

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.tools.ToolExecutionResult
import org.json.JSONObject
import java.io.File

/** Six high-cohesion Android development/debug tools over existing app seams. */
object AndroidAgentTools {
    const val CAPABILITIES = "android_capabilities"
    const val APP = "android_app"
    const val UI = "android_ui"
    const val LOGS = "android_logs"
    const val DIAGNOSE = "android_diagnose"
    const val DEPLOY = "android_deploy"

    val names = setOf(CAPABILITIES, APP, UI, LOGS, DIAGNOSE, DEPLOY)

    fun definitions(): List<AgentToolDefinition> = listOf(
        AgentToolDefinition(
            name = CAPABILITIES,
            description = "Read the actual Android capability matrix without silently triggering Root authorization. " +
                "Use action=get first. action=active_root_probe is the only path that may ask the user for su authorization. " +
                "action=probe_native_chroot runs isolated chroot/mount/namespace probes after Root is authorized; guest runtime uses the managed Direct Ubuntu chroot.",
            parameters = commonParams() + mapOf(
                "action" to AgentToolParam("string", "get (passive), active_root_probe, or probe_native_chroot", listOf("get", "active_root_probe", "probe_native_chroot")),
            ),
            required = listOf("tool_title", "action"),
            propertyOrdering = listOf("tool_title", "action"),
            timeoutMs = 90_000L,
        ),
        AgentToolDefinition(
            name = APP,
            description = "Inspect and control an Android package with PackageManager/ActivityManager first and authorized Root or Shizuku only where shell privilege is required. " +
                "Actions: search, info, launch, stop, restart, freeze, unfreeze, install, uninstall. search resolves a display name to a real package among the launcher apps this app can see. freeze/unfreeze are pm disable-user/pm enable: the package keeps its data but cannot run until it is enabled again, and an unlucky target can be a system app, so they require one-time approval. Android 11 package visibility is reported honestly. Install/uninstall require one-time approval and never assume QUERY_ALL_PACKAGES.",
            parameters = commonParams() + packageParams() + artifactParams() + mapOf(
                "action" to AgentToolParam(
                    "string",
                    "App action. search lists launcher apps by label or package (no packageName needed) so a display name can be resolved to a real package before any other action",
                    listOf("search", "info", "launch", "stop", "restart", "freeze", "unfreeze", "install", "uninstall"),
                ),
                "query" to AgentToolParam("string", "search only: keyword matched against app label or package name"),
                "limit" to AgentToolParam("integer", "search only: max rows (default 20, max 50)"),
                "activity" to AgentToolParam("string", "Optional explicit launch Activity"),
                "userId" to AgentToolParam("integer", "Optional Android user/profile id"),
                "keepData" to AgentToolParam("boolean", "Keep package data when uninstalling"),
                "allowDowngrade" to AgentToolParam("boolean", "Allow version downgrade during install"),
                "grantRuntimePermissions" to AgentToolParam("boolean", "Request pm -g during privileged install"),
            ),
            required = listOf("tool_title", "action"),
            propertyOrdering = listOf("tool_title", "action", "packageName", "activity", "artifactPath", "searchRoot", "userId"),
            timeoutMs = 240_000L,
        ),
        AgentToolDefinition(
            name = UI,
            description = "Observe and operate Android UI through the existing MinisAccessibilityService; no second Accessibility implementation. " +
                "Prefer observe (compact interactive nodes) then actions by generation+ref. Refs are bound to a UI fingerprint and return STALE_UI_REF after a screen change; the tool never guesses old coordinates. " +
                "Every action reports evidence plus its evidenceSource instead of a bare boolean: accepted-with-effect, accepted-without-evidence, direction-mismatch, timed-out and rejected are different outcomes, and a truncated snapshot refuses ref actions. " +
                "Coordinates are screenshot-space by default: x/y read off the returned screenshot image are converted through that capture's scale, and an action in that space is refused rather than misclicked when there is no capture or the screen changed; send coordinateSpace=screen for real device pixels. " +
                "Screenshot uses the existing API-30 Accessibility route and returns structured FLAG_SECURE/OEM failures. wait_for_package waits for a package to become (or stop being) the foreground app and reports an unreadable foreground as unknown rather than as a miss. " +
                "set_text writes the whole value (at most 4000 characters, empty clears); input_text types into what is already there, at the field's own selection (at most 1000 characters, never empty), and refuses (TEXT_CONTENT_UNAVAILABLE/TEXT_SELECTION_UNAVAILABLE) when the field does not hand over enough to reconstruct it - then send the full value with set_text. paste_text is the same selection-aware write with a clipboard fallback for editors that refuse the direct write. ime_enter presses the field's own IME action (search/done/send) on whatever has input focus, or on ref. Actions: observe, screenshot, click, long_press, set_text, input_text, paste_text, ime_enter, scroll, back, home, recents, notifications, quick_settings, wait, wait_for_package.",
            parameters = commonParams() + mapOf(
                "action" to AgentToolParam(
                    "string",
                    "UI action. back/home/recents/notifications/quick_settings are system panels driven through the same evidence path as gestures",
                    listOf(
                        "observe", "screenshot", "click", "long_press", "set_text", "input_text",
                        "paste_text", "scroll", "wait",
                        "ime_enter",
                        "wait_for_package",
                        UiGlobalAction.BACK.wireName, UiGlobalAction.HOME.wireName,
                        UiGlobalAction.RECENTS.wireName, UiGlobalAction.NOTIFICATIONS.wireName,
                        UiGlobalAction.QUICK_SETTINGS.wireName,
                    ),
                ),
                "generation" to AgentToolParam("integer", "Observation generation required with ref"),
                "ref" to AgentToolParam("string", "Short-lived uN ref from observe"),
                "interactiveOnly" to AgentToolParam("boolean", "Return only actionable nodes (default true)"),
                "maxDepth" to AgentToolParam("integer", "Maximum observation depth (default 12, max 30)"),
                "maxNodes" to AgentToolParam("integer", "Maximum returned nodes (default 120, max 500)"),
                "textFilter" to AgentToolParam("string", "Text/content-description filter"),
                "match" to AgentToolParam(
                    "string",
                    "wait: how textFilter is matched - contains (default, case-insensitive), exact, prefix or regex",
                    listOf("contains", "exact", "prefix", "regex"),
                ),
                "include_desc" to AgentToolParam(
                    "boolean",
                    "wait: also match the content description (default true)",
                ),
                "resourceIdFilter" to AgentToolParam("string", "Resource-id filter"),
                "packageFilter" to AgentToolParam("string", "Package filter"),
                "text" to AgentToolParam("string", "Text for set_text"),
                "x" to AgentToolParam("number", "Explicit pixel X coordinate in coordinateSpace (last-resort fallback)"),
                "y" to AgentToolParam("number", "Explicit pixel Y coordinate in coordinateSpace (last-resort fallback)"),
                "coordinateSpace" to AgentToolParam(
                    "string",
                    "Pixel space of x/y and deltaX/deltaY. screenshot (default) = pixels of the most recent " +
                        "android_ui screenshot image (that image is scaled, so this is what you actually see); " +
                        "screen = real device pixels, which is what observe node bounds and the screenshot's " +
                        "originalWidth/originalHeight are in. A screenshot-space action fails closed when no screenshot " +
                        "was taken yet, when the screen size changed since, or when the point is outside the image.",
                    UiCoordinateSpace.wireValues,
                ),
                "deltaX" to AgentToolParam("number", "Coordinate-scroll horizontal delta"),
                "deltaY" to AgentToolParam("number", "Coordinate-scroll vertical delta"),
                "direction" to AgentToolParam("string", "forward/backward/up/down/left/right"),
                "durationMs" to AgentToolParam("integer", "Gesture or plain-wait duration"),
                "timeoutMs" to AgentToolParam("integer", "Wait timeout (max 60000)"),
                "mode" to AgentToolParam("string", "Wait for the text or the foreground package to appear or disappear", listOf("appear", "disappear")),
                "packageName" to AgentToolParam("string", "wait_for_package: package that must become (or stop being) the foreground app"),
                "scale" to AgentToolParam("number", "Screenshot scale 0.1..1.0"),
            ),
            required = listOf("tool_title", "action"),
            propertyOrdering = listOf("tool_title", "action", "generation", "ref", "interactiveOnly", "maxDepth", "maxNodes", "textFilter"),
            timeoutMs = 120_000L,
        ),
        AgentToolDefinition(
            name = LOGS,
            description = "Cursor-based, token-bounded Android logcat. Mark a cursor immediately before a UI action, then read since that cursor so logs are attributed to the real action rather than a guessed time window. " +
                "Actions: mark_cursor, snapshot, watch, read, stop, clear. watch reuses JobRegistry; large raw output reuses SpillPolicy and returns a /var/minis/offloads pointer instead of flooding context. Full-device logs require authorized Root/Shizuku; ordinary mode is explicitly PARTIAL.",
            parameters = commonParams() + packageParams() + mapOf(
                "action" to AgentToolParam("string", "Log action", listOf("mark_cursor", "snapshot", "watch", "read", "stop", "clear")),
                "cursor" to AgentToolParam("string", "Cursor id from mark_cursor"),
                "jobId" to AgentToolParam("string", "Job id from watch"),
                "buffer" to AgentToolParam("string", "logcat buffer", listOf("main", "system", "crash", "events", "radio", "all")),
                "priority" to AgentToolParam("string", "Minimum priority", listOf("V", "D", "I", "W", "E", "F")),
                "maxLines" to AgentToolParam("integer", "Bounded snapshot line count (max 500)"),
                "durationSeconds" to AgentToolParam("integer", "Bounded watch window (1..300 seconds)"),
            ),
            required = listOf("tool_title", "action"),
            propertyOrdering = listOf("tool_title", "action", "packageName", "cursor", "jobId", "buffer", "priority"),
            timeoutMs = 180_000L,
        ),
        AgentToolDefinition(
            name = DIAGNOSE,
            description = "Structured Android diagnosis for process, memory, crash, ANR, stack, or all. Every section carries real capability status; DropBox, tombstones, /data/anr, debuggerd and source line numbers are never assumed. " +
                "Pass the log cursor marked before reproduction when diagnosing a crash. A source location appears only when an actual stack frame contains file:line.",
            parameters = commonParams() + packageParams() + mapOf(
                "action" to AgentToolParam("string", "Diagnosis section", listOf("process", "memory", "crash", "anr", "stack", "all")),
                "cursor" to AgentToolParam("string", "Optional log cursor tied to reproduction"),
            ),
            required = listOf("tool_title", "action", "packageName"),
            propertyOrdering = listOf("tool_title", "action", "packageName", "cursor"),
            timeoutMs = 180_000L,
        ),
        AgentToolDefinition(
            name = DEPLOY,
            description = "Inspect, install, and launch a real APK artifact. Actions: inspect_apk, install, launch, install_and_launch. " +
                "Builds remain in shell_execute (for example ./gradlew assembleDebug under the Ubuntu 24.04 guest runtime); this tool does not create a parallel Gradle executor. " +
                "Provide artifactPath, or searchRoot to discover actual APKs only under Gradle build/outputs/apk metadata. Never assumes app/build/outputs/apk/debug/app-debug.apk. " +
                "Continuous self-update of Minis for Android is UNSUPPORTED: replacing this APK kills the current Agent process.",
            parameters = commonParams() + packageParams() + artifactParams() + mapOf(
                "action" to AgentToolParam("string", "Deploy action", listOf("inspect_apk", "install", "launch", "install_and_launch")),
                "activity" to AgentToolParam("string", "Optional explicit launch Activity"),
                "allowDowngrade" to AgentToolParam("boolean", "Allow pm install downgrade"),
                "grantRuntimePermissions" to AgentToolParam("boolean", "Request pm -g"),
                "userId" to AgentToolParam("integer", "Optional Android user/profile id"),
            ),
            required = listOf("tool_title", "action"),
            propertyOrdering = listOf("tool_title", "action", "artifactPath", "searchRoot", "packageName", "activity"),
            timeoutMs = 300_000L,
        ),
    )

    suspend fun execute(
        name: String,
        argsJson: String,
        sessionId: String?,
        context: Context,
        toolId: String,
    ): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrElse {
            return ToolExecutionResult("Error: invalid JSON arguments: ${it.message}", false)
        }
        val title = args.optString("tool_title", name)
        val sid = sessionId.orEmpty()
        return try {
            val executed = when (name) {
                CAPABILITIES -> capabilityResult(context, sid, args, title)
                APP -> appResult(context, sid, args, title)
                UI -> uiResult(context, sid, args, title, toolId)
                LOGS -> logsResult(context, sid, args, title)
                DIAGNOSE -> diagnoseResult(context, sid, args, title, toolId)
                DEPLOY -> deployResult(context, sid, args, title)
                else -> ToolExecutionResult("Unknown Android tool: $name", false, toolTitle = title)
            }
            val risk = AndroidOperationRiskPolicy.classify(name, args.optString("action"))
            val decorated = runCatching {
                JSONObject(executed.output)
                    .put("risk", risk.name)
                    .put("requiresOneTimeApproval", AndroidOperationRiskPolicy.requiresOneTimeApproval(risk))
                    .toString(2)
            }.getOrNull()
            if (decorated == null) executed else executed.copy(output = decorated)
        } catch (t: Throwable) {
            ToolExecutionResult("Error: ${t.message ?: t.javaClass.simpleName}", false, toolTitle = title)
        }
    }

    private suspend fun capabilityResult(context: Context, sid: String, args: JSONObject, title: String): ToolExecutionResult {
        val action = args.optString("action", "get")
        val result = when (action) {
            "get" -> AndroidCapabilityResolver.resolve(context)
            "active_root_probe" -> JSONObject()
                .put("activeRootProbe", rootProbeJson(PrivilegedCommandRunner.requestActiveRootProbe(context, sid)))
                .put("capabilities", AndroidCapabilityResolver.resolve(context))
            "probe_native_chroot" -> probeNativeChroot(context, sid)
            else -> throw IllegalArgumentException("unknown android_capabilities action: $action")
        }
        val success = action == "get" || action == "probe_native_chroot" ||
            result.optJSONObject("activeRootProbe")?.optBoolean("authorized") == true
        return jsonResult(result, success, title)
    }

    private suspend fun appResult(context: Context, sid: String, args: JSONObject, title: String): ToolExecutionResult {
        val action = args.optString("action")
        val packageName = args.optString("packageName", "")
        val userId = args.optInt("userId", -1).takeIf { it >= 0 }
        val result = when (action) {
            "search" -> AndroidPackageController.search(
                context,
                args.optString("query", "").ifBlank { null },
                if (args.has("limit")) args.optInt("limit") else null,
            )
            "info" -> AndroidPackageController.info(context, sid, packageName)
            "launch" -> AndroidPackageController.launch(context, sid, packageName, args.optString("activity", "").ifBlank { null })
            "stop" -> AndroidPackageController.stop(context, sid, packageName, userId)
            "freeze" -> AndroidPackageController.setPackageEnabled(
                context, sid, packageName, enabled = false, userId = userId,
            )
            "unfreeze" -> AndroidPackageController.setPackageEnabled(
                context, sid, packageName, enabled = true, userId = userId,
            )
            "restart" -> AndroidPackageController.restart(context, sid, packageName, args.optString("activity", "").ifBlank { null }, userId)
            "install" -> {
                val artifact = inspectArtifact(context, sid, args)
                AndroidPackageController.install(context, sid, artifact, args.optBoolean("allowDowngrade"), args.optBoolean("grantRuntimePermissions"), userId)
            }
            "uninstall" -> AndroidPackageController.uninstall(context, sid, packageName, args.optBoolean("keepData"), userId)
            else -> throw IllegalArgumentException("unknown android_app action: $action")
        }
        val success = when (action) {
            "search" -> true
            "info" -> true
            "launch" -> result.optBoolean("launched")
            "stop" -> result.optBoolean("stopped")
            "freeze" -> result.optBoolean("frozen")
            "unfreeze" -> result.optBoolean("unfrozen")
            "restart" -> result.optBoolean("restarted")
            "install" -> result.optBoolean("installed")
            "uninstall" -> result.optBoolean("uninstalled")
            else -> false
        }
        return jsonResult(result, success, title)
    }

    private suspend fun uiResult(
        context: Context,
        sid: String,
        args: JSONObject,
        title: String,
        toolId: String,
    ): ToolExecutionResult {
        val result = AndroidUiController.execute(context, sid, args, toolId)
        return ToolExecutionResult(
            output = result.json.toString(2),
            success = result.success,
            imageData = result.imageData,
            imageMimeType = result.imageData?.let { "image/png" },
            imageFilePath = result.imageHostPath,
            imageLinuxPath = result.imageLinuxPath,
            toolTitle = title,
        )
    }

    private suspend fun logsResult(context: Context, sid: String, args: JSONObject, title: String): ToolExecutionResult {
        val action = args.optString("action")
        val packageName = args.optString("packageName", "").ifBlank {
            AndroidDebugSessionStore.get(sid).targetPackage.orEmpty()
        }.ifBlank { null }
        val result = when (action) {
            "mark_cursor" -> AndroidLogManager.markCursor(context, sid, packageName)
            "snapshot" -> AndroidLogManager.snapshot(
                context, sid, packageName,
                args.optString("buffer", "all"), args.optString("priority", "V"), args.optInt("maxLines", 300),
            )
            "watch" -> AndroidLogManager.watch(context, sid, packageName, args.optInt("durationSeconds", 30))
            "read" -> when {
                args.optString("cursor", "").isNotBlank() -> AndroidLogManager.readSince(context, sid, args.getString("cursor"))
                args.optString("jobId", "").isNotBlank() -> AndroidLogManager.readJob(context, sid, args.getString("jobId"))
                else -> throw IllegalArgumentException("android_logs read requires cursor or jobId")
            }
            "stop" -> AndroidLogManager.stop(args.optString("jobId"))
            "clear" -> AndroidLogManager.clear(context, sid)
            else -> throw IllegalArgumentException("unknown android_logs action: $action")
        }
        val success = !result.has("success") || result.optBoolean("success") ||
            action in setOf("mark_cursor", "snapshot", "watch", "read", "stop")
        return jsonResult(result, success, title)
    }

    private suspend fun diagnoseResult(
        context: Context,
        sid: String,
        args: JSONObject,
        title: String,
        toolId: String,
    ): ToolExecutionResult {
        val result = AndroidDiagnoseController.diagnose(
            context, sid, args.optString("packageName"), args.optString("action", "all"),
            args.optString("cursor", "").ifBlank { null }, toolId,
        )
        return jsonResult(result, true, title)
    }

    private suspend fun deployResult(context: Context, sid: String, args: JSONObject, title: String): ToolExecutionResult {
        val action = args.optString("action")
        val userId = args.optInt("userId", -1).takeIf { it >= 0 }
        val result = when (action) {
            "inspect_apk" -> inspectArtifact(context, sid, args).toJson()
            "install" -> {
                val artifact = inspectArtifact(context, sid, args)
                AndroidPackageController.install(context, sid, artifact, args.optBoolean("allowDowngrade"), args.optBoolean("grantRuntimePermissions"), userId)
            }
            "launch" -> {
                val packageName = args.optString("packageName", "").ifBlank {
                    AndroidDebugSessionStore.get(sid).targetPackage.orEmpty()
                }
                AndroidPackageController.launch(context, sid, packageName, args.optString("activity", "").ifBlank { null })
            }
            "install_and_launch" -> {
                val artifact = inspectArtifact(context, sid, args)
                val install = AndroidPackageController.install(context, sid, artifact, args.optBoolean("allowDowngrade"), args.optBoolean("grantRuntimePermissions"), userId)
                if (!install.optBoolean("installed")) JSONObject().put("installed", false).put("install", install)
                else JSONObject().put("installed", true).put("install", install)
                    .put("launch", AndroidPackageController.launch(context, sid, artifact.packageName, args.optString("activity", "").ifBlank { null }))
            }
            else -> throw IllegalArgumentException("unknown android_deploy action: $action")
        }
        val success = when (action) {
            "inspect_apk" -> true
            "install" -> result.optBoolean("installed")
            "launch" -> result.optBoolean("launched")
            "install_and_launch" -> result.optBoolean("installed") && result.optJSONObject("launch")?.optBoolean("launched") == true
            else -> false
        }
        return jsonResult(result, success, title)
    }

    private suspend fun inspectArtifact(context: Context, sid: String, args: JSONObject): ApkArtifact {
        val artifact = AndroidApkInspector.inspect(
            context,
            sid,
            artifactPath = args.optString("artifactPath", "").ifBlank { null },
            searchRoot = args.optString("searchRoot", "").ifBlank { null },
        )
        AndroidDebugSessionStore.update(sid) {
            it.copy(targetPackage = artifact.packageName, artifactPath = artifact.linuxPath ?: artifact.hostPath)
        }
        return artifact
    }

    private suspend fun probeNativeChroot(context: Context, sid: String): JSONObject {
        val root = RootCommandRunner.cachedProbe()
            ?: return JSONObject().put("status", CapabilityStatus.REQUIRES_USER_GRANT.name)
                .put("detail", "run active_root_probe first")
        if (!root.authorized) return JSONObject().put("status", CapabilityStatus.REQUIRES_USER_GRANT.name)
            .put("detail", root.error ?: "root not authorized")
        val probeDir = File(context.cacheDir, "native-chroot-probe").apply { mkdirs() }
        val script = """
            set +e
            chroot / /system/bin/true >/dev/null 2>&1; c=${'$'}?
            unshare -m /system/bin/true >/dev/null 2>&1; n=${'$'}?
            mount --bind '${probeDir.absolutePath}' '${probeDir.absolutePath}' >/dev/null 2>&1; m=${'$'}?
            [ ${'$'}m -eq 0 ] && umount '${probeDir.absolutePath}' >/dev/null 2>&1
            printf 'chroot=%s\nnamespace=%s\nbindMount=%s\n' "${'$'}c" "${'$'}n" "${'$'}m"
        """.trimIndent()
        val result = PrivilegedCommandRunner.run(
            context, sid, listOf("sh", "-c", script), "Native chroot 隔离能力探测",
            CommandRisk.ROOT_SETUP, 30_000L, rootOnly = true,
        )
        val values = result.stdout.lineSequence().mapNotNull { line ->
            val split = line.split('=', limit = 2)
            if (split.size == 2) split[0] to split[1].toIntOrNull() else null
        }.toMap()
        return JSONObject()
            .put("status", if (result.success && values.values.all { it == 0 }) CapabilityStatus.AVAILABLE.name else CapabilityStatus.PARTIAL.name)
            .put("backend", result.backend.name.lowercase())
            .put("chroot", probeOperation(values["chroot"]))
            .put("mountNamespace", probeOperation(values["namespace"]))
            .put("bindMount", probeOperation(values["bindMount"]))
            .put("selinuxMode", root.selinuxMode ?: "unknown")
            .put("nativeChrootExperimental", false)
            .put("nativeChrootDefault", true)
            .put("security", "chroot is not a container or sandbox; project builds must drop privileges")
            .put("stderr", result.stderr.take(2_000))
    }

    private fun probeOperation(exit: Int?): JSONObject = JSONObject()
        .put("status", when (exit) {
            0 -> CapabilityStatus.AVAILABLE.name
            null -> CapabilityStatus.UNAVAILABLE.name
            else -> CapabilityStatus.UNAVAILABLE.name
        })
        .put("exitCode", exit ?: JSONObject.NULL)

    private fun rootProbeJson(probe: RootProbeResult): JSONObject = JSONObject()
        .put("authorized", probe.authorized)
        .put("effectiveUid", probe.effectiveUid ?: JSONObject.NULL)
        .put("effectiveGid", probe.effectiveGid ?: JSONObject.NULL)
        .put("groups", org.json.JSONArray(probe.groups))
        .put("effectiveCapabilities", probe.effectiveCapabilitiesHex ?: JSONObject.NULL)
        .put("selinuxContext", probe.selinuxContext ?: JSONObject.NULL)
        .put("selinuxMode", probe.selinuxMode ?: JSONObject.NULL)
        .put("error", probe.error ?: JSONObject.NULL)

    private fun jsonResult(json: JSONObject, success: Boolean, title: String): ToolExecutionResult =
        ToolExecutionResult(json.toString(2), success, toolTitle = title)

    private fun commonParams(): Map<String, AgentToolParam> = mapOf(
        "tool_title" to AgentToolParam("string", "Concise user-visible summary in the user's language"),
    )

    private fun packageParams(): Map<String, AgentToolParam> = mapOf(
        "packageName" to AgentToolParam("string", "Exact Android package name; never guess it from a display label"),
    )

    private fun artifactParams(): Map<String, AgentToolParam> = mapOf(
        "artifactPath" to AgentToolParam("string", "Explicit absolute APK path, preferably under /var/minis/workspace"),
        "searchRoot" to AgentToolParam("string", "Absolute project root used to discover APKs under real Gradle build/outputs/apk directories"),
    )
}
