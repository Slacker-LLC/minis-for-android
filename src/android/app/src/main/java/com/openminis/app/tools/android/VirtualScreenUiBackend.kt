package com.openminis.app.tools.android

import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.openminis.app.logging.AppLogger
import com.openminis.app.tools.android.vscreen.VirtualScreenClient
import com.openminis.app.tools.android.vscreen.VirtualScreenPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/** Display-targeted Android UI operations over the Shizuku virtual-display service. */
internal object VirtualScreenUiBackend {
    suspend fun execute(
        sessionId: String,
        args: JSONObject,
        client: VirtualScreenClient,
    ): AndroidUiController.UiToolResult = withContext(Dispatchers.IO) {
        val action = args.optString("action", "observe")
        val displayId = args.optInt("displayId", -1)
        if (displayId <= 0) return@withContext error("VSCREEN_DISPLAY_UNAVAILABLE", "Pass a current non-physical displayId returned by android.vscreen.open")
        try {
            when (action) {
                "observe" -> observe(sessionId, displayId, args, client)
                "screenshot" -> screenshot(sessionId, displayId, args, client)
                "click" -> if (args.optString("ref").isBlank()) {
                    coordinateAction(sessionId, displayId, args, client, longPress = false)
                } else refAction(sessionId, displayId, args, client, action)
                "long_press" -> if (args.optString("ref").isBlank()) {
                    coordinateAction(sessionId, displayId, args, client, longPress = true)
                } else refAction(sessionId, displayId, args, client, action)
                "set_text" -> refAction(sessionId, displayId, args, client, action)
                "input_text" -> refAction(sessionId, displayId, args, client, action)
                "paste_text" -> error("VSCREEN_ACTION_UNSUPPORTED", "paste_text is not implemented on the virtual display; use set_text or input_text by ref")
                "ime_enter" -> imeEnter(sessionId, displayId, args, client)
                "scroll" -> scroll(sessionId, displayId, args, client)
                "wait" -> waitForText(sessionId, displayId, args, client)
                "wait_for_package" -> waitForPackage(displayId, args, client)
                "back", "home" -> simpleAct(sessionId, displayId, client, action, JSONObject().put("action", action))
                "recents", "notifications", "quick_settings" -> error(
                    "VSCREEN_ACTION_UNSUPPORTED",
                    "$action is not available on a virtual display; it will not fall back to the physical screen",
                )
                else -> error("INVALID_ACTION", "unknown android_ui action: $action")
            }
        } catch (cause: Throwable) {
            error(
                (cause as? VirtualScreenClient.VirtualScreenClientException)?.reasonCode ?: "vscreen_call_failed",
                cause.message ?: cause.javaClass.simpleName,
            )
        }
    }

    private fun observe(
        sessionId: String,
        displayId: Int,
        args: JSONObject,
        client: VirtualScreenClient,
    ): AndroidUiController.UiToolResult {
        val interactiveOnly = args.optBoolean("interactiveOnly", true)
        val dumpStart = SystemClock.elapsedRealtime()
        val raw = client.dump(displayId, if (interactiveOnly) "SIMPLE" else "FULL")
        val dumpMs = SystemClock.elapsedRealtime() - dumpStart
        val serializeStart = SystemClock.elapsedRealtime()
        val snapshot = VirtualScreenObservationRegistry.observe(
            sessionId, displayId, raw,
            VirtualScreenObserveOptions(
                maxNodes = args.optInt("maxNodes", 120),
                textFilter = args.optString("textFilter").takeIf { it.isNotBlank() },
                packageFilter = args.optString("packageFilter").takeIf { it.isNotBlank() },
                includeTree = !interactiveOnly,
            ),
        )
        snapshot.put("success", true).put("action", "observe")
        snapshot.put(
            "timing",
            JSONObject().put("dumpMs", dumpMs).put("serializeMs", SystemClock.elapsedRealtime() - serializeStart)
                .put("observationBytes", snapshot.toString().length),
        )
        logTiming("observe", snapshot.optJSONObject("timing"), snapshot)
        putDisplayGeometry(snapshot, client)
        return AndroidUiController.UiToolResult(snapshot, true)
    }

    /**
     * Tell the model the display it is working on: size and the coordinate rule. Without this it had to
     * take a screenshot just to learn where things are, and then guessed which pixel space x/y meant.
     */
    internal fun putDisplayGeometry(into: JSONObject, client: VirtualScreenClient) {
        val size = client.displaySize() ?: return
        into.put("displayWidth", size.first).put("displayHeight", size.second)
            .put(
                "coordinates",
                "x/y and node bounds are pixels of this " + size.first + "x" + size.second +
                    " display, origin top-left (x 0.." + (size.first - 1) + ", y 0.." + (size.second - 1) +
                    "); click/scroll x,y use this space by default. A screenshot is at this resolution too.",
            )
    }

    private fun refAction(
        sessionId: String,
        displayId: Int,
        args: JSONObject,
        client: VirtualScreenClient,
        action: String,
    ): AndroidUiController.UiToolResult {
        val generation = args.optLong("generation", -1L)
        val ref = args.optString("ref").trim()
        if (generation < 0 || ref.isBlank()) return error("INVALID_UI_REF", "$action requires generation and ref from a recent observe")
        val found = when (val resolution = VirtualScreenObservationRegistry.locate(sessionId, displayId, generation, ref)) {
            is VirtualScreenRefResolution.Found -> resolution
            is VirtualScreenRefResolution.Error -> return error(resolution.code, resolution.message)
        }
        val target = found.target
        val request = JSONObject().put("action", action).put("locator", target.locator)
            .put("scanTruncated", found.scanTruncated)
        when (action) {
            "click" -> if ("click" !in target.actions) return error("UI_ACTION_NOT_SUPPORTED", "The selected ref is not clickable")
            "long_press" -> request.put("durationMs", args.optInt("durationMs", 700).coerceIn(300, 5_000))
            "set_text" -> {
                if ("input" !in target.actions) return error("UI_TARGET_NOT_EDITABLE", "set_text requires an editable ref")
                val text = args.optString("text")
                if (text.length > 4_000) return error("TEXT_TOO_LONG", "set_text is limited to 4000 characters")
                request.put("text", text)
            }
            "input_text" -> {
                if ("input" !in target.actions) return error("UI_TARGET_NOT_EDITABLE", "input_text requires an editable ref")
                val text = args.optString("text")
                if (text.isEmpty() || text.length > 1_000) return error("INVALID_INPUT_TEXT", "input_text requires 1..1000 characters")
                request.put("text", text)
            }
        }
        return act(sessionId, displayId, client, action, request, generation, target.packageName)
    }

    private fun imeEnter(
        sessionId: String,
        displayId: Int,
        args: JSONObject,
        client: VirtualScreenClient,
    ): AndroidUiController.UiToolResult {
        val request = JSONObject().put("action", "ime_enter")
        var generation: Long? = null
        var packageName = ""
        val ref = args.optString("ref").trim()
        if (ref.isNotEmpty()) {
            val g = args.optLong("generation", -1L)
            if (g < 0) return error("INVALID_UI_REF", "ime_enter with ref requires generation from a recent observe")
            val found = when (val resolution = VirtualScreenObservationRegistry.locate(sessionId, displayId, g, ref)) {
                is VirtualScreenRefResolution.Found -> resolution
                is VirtualScreenRefResolution.Error -> return error(resolution.code, resolution.message)
            }
            request.put("locator", found.target.locator).put("scanTruncated", found.scanTruncated)
            generation = g
            packageName = found.target.packageName
        }
        // Without a ref the service presses the IME action of whatever field has input focus.
        return act(sessionId, displayId, client, "ime_enter", request, generation, packageName)
    }

    private fun simpleAct(
        sessionId: String,
        displayId: Int,
        client: VirtualScreenClient,
        action: String,
        request: JSONObject,
    ): AndroidUiController.UiToolResult = act(sessionId, displayId, client, action, request, null, "")

    /**
     * One Binder call that locates, acts, waits for the screen to settle and returns the new screen.
     * The new screen is registered as a fresh generation and attached, so the model does not need a
     * separate observe before its next step.
     */
    private fun act(
        sessionId: String,
        displayId: Int,
        client: VirtualScreenClient,
        action: String,
        request: JSONObject,
        generationBefore: Long?,
        targetPackage: String,
    ): AndroidUiController.UiToolResult {
        val packageName = client.foregroundPackageName?.takeIf(String::isNotBlank) ?: targetPackage.takeIf(String::isNotBlank).orEmpty()
        if (packageName.isNotEmpty()) request.put("package", packageName)
        val binderStart = SystemClock.elapsedRealtime()
        val response = JSONObject(client.act(displayId, request.toString()))
        val binderMs = SystemClock.elapsedRealtime() - binderStart

        val serializeStart = SystemClock.elapsedRealtime()
        val observed = response.optJSONObject("observation")?.let {
            VirtualScreenObservationRegistry.observe(sessionId, displayId, it)
        }
        val ok = response.optBoolean("ok", false)
        val timing = (response.optJSONObject("timing") ?: JSONObject())
            .put("binderMs", binderMs).put("serializeMs", SystemClock.elapsedRealtime() - serializeStart)

        if (!ok) {
            val code = when (val serviceCode = response.optString("error")) {
                "stale_target" -> "STALE_UI_REF"
                "target_not_editable" -> "UI_TARGET_NOT_EDITABLE"
                "target_not_actionable" -> "UI_NODE_NOT_ACTIONABLE"
                "" -> "vscreen_call_failed"
                else -> serviceCode
            }
            val result = JSONObject().put("success", false).put("action", action).put("displayId", displayId)
                .put("error", code)
                .put("message", (response.optString("detail").ifBlank { "the display-targeted operation was rejected" }).take(512))
                .put("evidenceSource", "vscreen_uiautomation")
            // A stale target comes with the screen as it is now: the next ref can be taken from it directly.
            if (observed != null) result.put("observation", observed)
            attachTiming(result, timing, observed)
            return AndroidUiController.UiToolResult(result, false)
        }

        if (response.has("packagePresent")) {
            val denial = VirtualScreenAppPresencePolicy.denialCode(packageName, response.optBoolean("packagePresent", true))
            if (denial != null) {
                VirtualScreenObservationRegistry.clearSession(sessionId)
                return error(denial, "$packageName no longer has a window on display $displayId; do not fall back to the physical screen")
            }
        }
        val changed = response.optBoolean("semanticSignal", false) || run {
            val before = generationBefore?.let(VirtualScreenObservationRegistry::observedFingerprint)
            val after = observed?.optLong("generation")?.let(VirtualScreenObservationRegistry::observedFingerprint)
            before != null && after != null && before != after
        }
        val outcome = if (changed) "accepted_with_effect" else "accepted_without_evidence"
        val result = actionResult(action, displayId, true, outcome).json
            .put("outcome", outcome)
            .put("settledBy", response.optString("settledBy"))
            .put("resolvedBy", response.optString("resolvedBy"))
        // grace_expired = nothing reacted inside the action's grace window; the screen may simply be slow.
        if (response.optString("settledBy") == "grace_expired" && !changed) {
            result.put("hint", "no visible change yet; use wait/wait_for_package if a page transition is expected")
        }
        if (observed != null) result.put("observation", observed)
        attachTiming(result, timing, observed)
        return AndroidUiController.UiToolResult(result, true)
    }

    private fun attachTiming(result: JSONObject, timing: JSONObject, observed: JSONObject?) {
        observed?.let {
            timing.put("observationBytes", it.toString().length)
                .put("targetCount", it.optJSONArray("targets")?.length() ?: 0)
                .put("scanNodes", it.optInt("scanNodes", 0))
        }
        result.put("timing", timing)
        logTiming(result.optString("action"), timing, observed)
    }

    /** One line per UI action: where the time went. Read this on the device before tuning any threshold. */
    private fun logTiming(action: String, timing: JSONObject?, observed: JSONObject?) {
        if (timing == null) return
        AppLogger.info(
            "VScreenTiming",
            "action=$action " + listOf(
                "binderMs", "resolveMs", "actionMs", "firstChangeMs", "settleMs", "dumpMs", "serializeMs", "totalMs",
                "observationBytes", "targetCount", "scanNodes",
            ).filter(timing::has).joinToString(" ") { "$it=${timing.opt(it)}" } +
                (observed?.optJSONArray("layoutWarnings")?.let { " layoutWarnings=${it.length()}" } ?: ""),
        )
    }

    private fun coordinateAction(
        sessionId: String,
        displayId: Int,
        args: JSONObject,
        client: VirtualScreenClient,
        longPress: Boolean,
    ): AndroidUiController.UiToolResult {
        if (!args.has("x") || !args.has("y")) return error("coordinates_required", "click/long_press requires a recent ref or explicit x/y coordinates")
        val x = args.optDouble("x", Double.NaN)
        val y = args.optDouble("y", Double.NaN)
        if (!x.isFinite() || !y.isFinite()) return error("invalid_coordinates", "x and y must be finite display-local coordinates")
        val frame = ScreenshotFrameRegistry.latest(sessionId, displayId)
        val size = client.displaySize()
        val resolved = UiCoordinateSpacePolicy.resolvePoint(
            x,
            y,
            UiCoordinateSpace.parse(args.optString("coordinateSpace", "screen")),
            frame,
            size?.first ?: 0,
            size?.second ?: 0,
        )
        if (resolved is UiCoordinateResolution.Refused) return error(resolved.code, resolved.message)
        val point = resolved as UiCoordinateResolution.Resolved
        val action = if (longPress) "long_press" else "click"
        val request = JSONObject().put("action", if (longPress) "long_press" else "tap")
            .put("x", point.x.roundToInt()).put("y", point.y.roundToInt())
        if (longPress) request.put("durationMs", args.optInt("durationMs", 700).coerceIn(300, 5_000))
        return act(sessionId, displayId, client, action, request, null, "")
    }

    private fun scroll(
        sessionId: String,
        displayId: Int,
        args: JSONObject,
        client: VirtualScreenClient,
    ): AndroidUiController.UiToolResult {
        val direction = args.optString("direction").trim().lowercase()
        val ref = args.optString("ref").trim()
        var startX: Double
        var startY: Double
        var deltaX = args.optDouble("deltaX", 0.0)
        var deltaY = args.optDouble("deltaY", 0.0)
        var refGeneration = args.optLong("generation", -1L)
        val frame = ScreenshotFrameRegistry.latest(sessionId, displayId)
        val displaySize = client.displaySize()
        val screenWidth = displaySize?.first ?: 0
        val screenHeight = displaySize?.second ?: 0
        val coordinateSpace = UiCoordinateSpace.parse(args.optString("coordinateSpace", "screen"))

        var locator: JSONObject? = null
        var scanTruncated = false
        var targetPackage = ""
        if (ref.isNotBlank()) {
            if (refGeneration < 0) return error("INVALID_UI_REF", "scroll with ref requires generation from a recent observe")
            val target = when (val resolved = VirtualScreenObservationRegistry.locate(sessionId, displayId, refGeneration, ref)) {
                is VirtualScreenRefResolution.Found -> {
                    // The service re-verifies the container in the same call as the swipe.
                    locator = resolved.target.locator
                    scanTruncated = resolved.scanTruncated
                    targetPackage = resolved.target.packageName
                    resolved.target
                }
                is VirtualScreenRefResolution.Error -> return error(resolved.code, resolved.message)
            }
            val bounds = target.bounds ?: return error("UI_BOUNDS_UNAVAILABLE", "The selected ref has no usable display-local bounds")
            startX = bounds.centerX.toDouble()
            startY = bounds.centerY.toDouble()
            if (deltaX == 0.0 && deltaY == 0.0) {
                val distanceX = (bounds.right - bounds.left).coerceIn(48, 400).toDouble()
                val distanceY = (bounds.bottom - bounds.top).coerceIn(80, 520).toDouble()
                when (direction) {
                    "forward", "down" -> deltaY = -distanceY
                    "backward", "up" -> deltaY = distanceY
                    "left" -> deltaX = distanceX
                    "right" -> deltaX = -distanceX
                    else -> return error("INVALID_SCROLL_DIRECTION", "scroll requires a supported direction")
                }
            }
        } else {
            if (!args.has("x") || !args.has("y")) return error("SCROLL_COORDINATES_REQUIRED", "scroll requires a recent ref or explicit x/y coordinates")
            startX = args.optDouble("x")
            startY = args.optDouble("y")
            if (deltaX == 0.0 && deltaY == 0.0) {
                val distance = 320.0
                when (direction) {
                    "forward", "down" -> deltaY = -distance
                    "backward", "up" -> deltaY = distance
                    "left" -> deltaX = distance
                    "right" -> deltaX = -distance
                    else -> return error("INVALID_SCROLL_DIRECTION", "scroll requires a supported direction")
                }
            }
            val resolvedPoint = UiCoordinateSpacePolicy.resolvePoint(startX, startY, coordinateSpace, frame, screenWidth, screenHeight)
            if (resolvedPoint is UiCoordinateResolution.Refused) return error(resolvedPoint.code, resolvedPoint.message)
            val point = resolvedPoint as UiCoordinateResolution.Resolved
            startX = point.x
            startY = point.y
            val resolvedDelta = UiCoordinateSpacePolicy.resolveDelta(deltaX, deltaY, coordinateSpace, frame, screenWidth, screenHeight)
            if (resolvedDelta is UiCoordinateResolution.Refused) return error(resolvedDelta.code, resolvedDelta.message)
            val delta = resolvedDelta as UiCoordinateResolution.Resolved
            deltaX = delta.x
            deltaY = delta.y
        }
        if (screenWidth <= 0 || screenHeight <= 0) return error("VSCREEN_DISPLAY_SIZE_UNKNOWN", "The current display geometry is unavailable; reopen the virtual display")
        val endX = (startX + deltaX).coerceIn(0.0, (screenWidth - 1).toDouble())
        val endY = (startY + deltaY).coerceIn(0.0, (screenHeight - 1).toDouble())
        val request = JSONObject().put("action", "swipe")
            .put("startX", startX.toInt().coerceIn(0, screenWidth - 1))
            .put("startY", startY.toInt().coerceIn(0, screenHeight - 1))
            .put("endX", endX.toInt())
            .put("endY", endY.toInt())
            .put("durationMs", args.optInt("durationMs", 300).coerceIn(100, 2_000))
        if (locator != null) request.put("locator", locator).put("scanTruncated", scanTruncated)
        return act(sessionId, displayId, client, "scroll", request, if (ref.isNotBlank()) refGeneration else null, targetPackage)
    }

    private suspend fun waitForText(
        sessionId: String,
        displayId: Int,
        args: JSONObject,
        client: VirtualScreenClient,
    ): AndroidUiController.UiToolResult {
        val needle = args.optString("textFilter").trim()
        val timeout = args.optInt("timeoutMs", 5_000).coerceIn(0, 60_000)
        val mode = args.optString("mode", "appear").lowercase()
        if (mode !in setOf("appear", "disappear")) return error("INVALID_WAIT_MODE", "mode must be appear or disappear")
        val deadline = System.currentTimeMillis() + timeout
        var raw = client.dump(displayId, "SIMPLE")
        while (true) {
            val matched = needle.isEmpty() || matchesText(raw, needle, args.optString("match", "contains"), args.optBoolean("include_desc", true))
            if ((mode == "appear" && matched) || (mode == "disappear" && !matched)) {
                val snapshot = VirtualScreenObservationRegistry.observe(sessionId, displayId, raw)
                    .put("success", true).put("action", "wait")
                return AndroidUiController.UiToolResult(snapshot, true)
            }
            if (System.currentTimeMillis() >= deadline) return error("WAIT_TIMEOUT", "Timed out waiting for virtual-display text condition")
            delay(PACKAGE_WAIT_POLL_MS)
            raw = client.dump(displayId, "SIMPLE")
        }
    }

    private suspend fun waitForPackage(
        displayId: Int,
        args: JSONObject,
        client: VirtualScreenClient,
    ): AndroidUiController.UiToolResult {
        val rawPackage = args.optString("packageName").trim()
        val packageName = runCatching { AndroidPackageController.requirePackageName(rawPackage) }.getOrElse {
            return error("INVALID_PACKAGE", "wait_for_package requires a valid packageName")
        }
        val mode = args.optString("mode", "appear").lowercase()
        if (mode !in setOf("appear", "disappear")) return error("INVALID_WAIT_MODE", "mode must be appear or disappear")
        val timeout = args.optInt("timeoutMs", 10_000).coerceIn(0, 60_000)
        val deadline = System.currentTimeMillis() + timeout
        var visible = client.hasPackageWindow(displayId, packageName)
        while (true) {
            if ((mode == "appear" && visible) || (mode == "disappear" && !visible)) {
                return actionResult("wait_for_package", displayId, true, "display-targeted window presence satisfied")
                    .also { it.json.put("packageName", packageName).put("visible", visible) }
            }
            if (System.currentTimeMillis() >= deadline) {
                return error("WAIT_TIMEOUT", "Timed out waiting for package on virtual display")
                    .also { it.json.put("packageName", packageName).put("visible", visible) }
            }
            delay(PACKAGE_WAIT_POLL_MS)
            visible = client.hasPackageWindow(displayId, packageName)
        }
    }

    private fun screenshot(
        sessionId: String,
        displayId: Int,
        args: JSONObject,
        client: VirtualScreenClient,
    ): AndroidUiController.UiToolResult {
        val size = client.displaySize()
        val maxDim = if (size == null) 1_280 else {
            val scale = args.optDouble("scale", 1.0).takeIf { it.isFinite() }?.coerceIn(0.1, 1.0) ?: 1.0
            (maxOf(size.first, size.second) * scale).toInt().coerceIn(256, 2_560)
        }
        val pfd = client.screenshot(displayId, maxDim, 85)
        val bytes = readBoundedScreenshot(pfd)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (size != null && options.outWidth > 0 && options.outHeight > 0) {
            ScreenshotFrameRegistry.record(
                ScreenshotFrame(options.outWidth, options.outHeight, size.first, size.second),
                sessionId,
                displayId,
            )
        }
        val result = JSONObject()
            .put("success", true)
            .put("action", "screenshot")
            .put("displayId", displayId)
            .put("coordinateSpace", "display-local")
            .put("imageWidth", options.outWidth)
            .put("imageHeight", options.outHeight)
            .put("originalWidth", size?.first ?: JSONObject.NULL)
            .put("originalHeight", size?.second ?: JSONObject.NULL)
            .put("mimeType", "image/jpeg")
        putDisplayGeometry(result, client)
        return AndroidUiController.UiToolResult(result, true, imageData = bytes, imageMimeType = "image/jpeg")
    }

    private fun readBoundedScreenshot(pfd: ParcelFileDescriptor): ByteArray =
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > VirtualScreenPolicy.MAX_SCREENSHOT_BYTES) {
                    throw VirtualScreenClient.VirtualScreenClientException("screenshot_too_large", "JPEG exceeds the VScreen response cap")
                }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }

    private fun matchesText(raw: String, needle: String, mode: String, includeDescription: Boolean): Boolean {
        val values = collectTextValues(runCatching { JSONObject(raw) }.getOrNull() ?: return false, includeDescription)
        return when (mode.lowercase()) {
            "exact" -> values.any { it.equals(needle, ignoreCase = true) }
            "prefix" -> values.any { it.startsWith(needle, ignoreCase = true) }
            "regex" -> runCatching { Regex(needle, RegexOption.IGNORE_CASE).let { pattern -> values.any(pattern::containsMatchIn) } }.getOrDefault(false)
            else -> values.any { it.contains(needle, ignoreCase = true) }
        }
    }

    private fun collectTextValues(value: Any?, includeDescription: Boolean): List<String> = buildList {
        when (value) {
            is JSONObject -> {
                val keys = if (includeDescription) setOf("text", "desc", "contentDescription", "hint", "label", "title") else setOf("text", "label", "title")
                val iterator = value.keys()
                while (iterator.hasNext()) {
                    val key = iterator.next()
                    val item = value.opt(key)
                    if (key in keys && item is String) add(item)
                    addAll(collectTextValues(item, includeDescription))
                }
            }
            is JSONArray -> for (i in 0 until value.length()) addAll(collectTextValues(value.opt(i), includeDescription))
        }
    }

    private fun actionResult(action: String, displayId: Int, success: Boolean, detail: String): AndroidUiController.UiToolResult =
        AndroidUiController.UiToolResult(
            JSONObject()
                .put("success", success)
                .put("action", action)
                .put("displayId", displayId)
                .put("coordinateSpace", "display-local")
                .put("evidenceSource", "vscreen_uiautomation")
                .put("outcome", if (success) "accepted_with_effect" else "rejected")
                .put("detail", detail.take(512)),
            success,
        )

    private fun error(code: String, message: String): AndroidUiController.UiToolResult =
        AndroidUiController.UiToolResult(
            JSONObject().put("success", false).put("error", code).put("message", message.take(512)),
            false,
        )

    private const val PACKAGE_WAIT_POLL_MS = 150L
}
