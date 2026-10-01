package com.openminis.app.tools.android

import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
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
                "scroll" -> scroll(sessionId, displayId, args, client)
                "wait" -> waitForText(sessionId, displayId, args, client)
                "wait_for_package" -> waitForPackage(displayId, args, client)
                "back" -> actionResult(action, displayId, client.back(displayId), "display-targeted BACK key")
                "home" -> actionResult(action, displayId, client.home(displayId), "display-targeted HOME key")
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
        val mode = if (args.optBoolean("interactiveOnly", true)) "SIMPLE" else "FULL"
        val raw = client.dump(displayId, mode)
        val snapshot = VirtualScreenObservationRegistry.observe(sessionId, displayId, raw)
        val packageFilter = args.optString("packageFilter").trim()
        if (packageFilter.isNotEmpty()) {
            val targets = snapshot.optJSONArray("targets") ?: JSONArray()
            val filtered = JSONArray()
            for (i in 0 until targets.length()) {
                val target = targets.optJSONObject(i) ?: continue
                if (target.optString("label").contains(packageFilter, ignoreCase = true) ||
                    raw.contains(packageFilter, ignoreCase = true)
                ) filtered.put(target)
            }
            snapshot.put("targets", filtered)
        }
        snapshot.put("success", true).put("action", "observe")
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

    private suspend fun refAction(
        sessionId: String,
        displayId: Int,
        args: JSONObject,
        client: VirtualScreenClient,
        action: String,
    ): AndroidUiController.UiToolResult {
        val generation = args.optLong("generation", -1L)
        val ref = args.optString("ref").trim()
        if (generation < 0 || ref.isBlank()) return error("INVALID_UI_REF", "$action requires generation and ref from a recent observe")
        val before = client.dump(displayId, "SIMPLE")
        val target = when (val resolution = VirtualScreenObservationRegistry.resolve(sessionId, displayId, generation, ref, before)) {
            is VirtualScreenRefResolution.Found -> resolution.target
            is VirtualScreenRefResolution.Error -> return error(resolution.code, resolution.message)
        }
        val succeeded = when (action) {
            "click" -> {
                if ("click" !in target.actions) return error("UI_ACTION_NOT_SUPPORTED", "The selected ref is not clickable")
                client.clickTarget(displayId, target.index)
            }
            "long_press" -> {
                val bounds = target.bounds ?: return error("UI_BOUNDS_UNAVAILABLE", "The selected ref has no usable display-local bounds")
                client.longPress(displayId, bounds.centerX, bounds.centerY, args.optInt("durationMs", 700).coerceIn(300, 5_000))
            }
            "set_text" -> {
                if ("input" !in target.actions) return error("UI_TARGET_NOT_EDITABLE", "set_text requires an editable ref")
                val text = args.optString("text")
                if (text.length > 4_000) return error("TEXT_TOO_LONG", "set_text is limited to 4000 characters")
                client.setTextTarget(displayId, target.index, text)
            }
            "input_text" -> {
                if ("input" !in target.actions) return error("UI_TARGET_NOT_EDITABLE", "input_text requires an editable ref")
                val text = args.optString("text")
                if (text.isEmpty() || text.length > 1_000) return error("INVALID_INPUT_TEXT", "input_text requires 1..1000 characters")
                if (!client.focusTarget(displayId, target.index)) return error("UI_TARGET_FOCUS_FAILED", "Could not focus the selected editable ref")
                client.inputText(displayId, text)
            }
            else -> false
        }
        if (!succeeded) return actionResult(action, displayId, false, "the display-targeted operation was rejected")
        delay(ACTION_EVIDENCE_SETTLE_MS)
        if (action == "click" || action == "long_press") {
            ensureAppRemains(sessionId, displayId, target.packageName, client)?.let { return it }
        }
        val after = runCatching { client.dump(displayId, "SIMPLE") }.getOrNull()
        val changed = after != null && VirtualScreenObservationRegistry.fingerprint(after) != VirtualScreenObservationRegistry.fingerprint(before)
        return actionResult(
            action,
            displayId,
            true,
            if (changed) "accepted_with_effect" else "accepted_without_evidence",
        ).also { it.json.put("evidenceSource", "vscreen_uiautomation") }
    }

    private suspend fun coordinateAction(
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
        val success = if (longPress) {
            client.longPress(displayId, point.x.roundToInt(), point.y.roundToInt(), args.optInt("durationMs", 700).coerceIn(300, 5_000))
        } else {
            client.tap(displayId, point.x.roundToInt(), point.y.roundToInt())
        }
        if (!success) return actionResult(if (longPress) "long_press" else "click", displayId, false, "display-local input was rejected")
        delay(ACTION_EVIDENCE_SETTLE_MS)
        ensureAppRemains(sessionId, displayId, client.foregroundPackageName.orEmpty(), client)?.let { return it }
        return actionResult(if (longPress) "long_press" else "click", displayId, true, "display-local input accepted")
    }

    private fun ensureAppRemains(
        sessionId: String,
        displayId: Int,
        targetPackageName: String,
        client: VirtualScreenClient,
    ): AndroidUiController.UiToolResult? {
        val packageName = client.foregroundPackageName?.takeIf(String::isNotBlank)
            ?: targetPackageName.takeIf(String::isNotBlank)
            ?: return null
        val hasWindow = client.hasPackageWindow(displayId, packageName)
        val denial = VirtualScreenAppPresencePolicy.denialCode(packageName, hasWindow) ?: return null
        VirtualScreenObservationRegistry.clearSession(sessionId)
        return error(
            denial,
            "$packageName no longer has a window on display $displayId; do not fall back to the physical screen",
        )
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

        if (ref.isNotBlank()) {
            if (refGeneration < 0) return error("INVALID_UI_REF", "scroll with ref requires generation from a recent observe")
            val before = client.dump(displayId, "SIMPLE")
            val target = when (val resolved = VirtualScreenObservationRegistry.resolve(sessionId, displayId, refGeneration, ref, before)) {
                is VirtualScreenRefResolution.Found -> resolved.target
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
        val success = client.swipe(
            displayId,
            startX.toInt().coerceIn(0, screenWidth - 1),
            startY.toInt().coerceIn(0, screenHeight - 1),
            endX.toInt(),
            endY.toInt(),
            args.optInt("durationMs", 300).coerceIn(100, 2_000),
        )
        return actionResult("scroll", displayId, success, if (success) "display-targeted swipe accepted" else "display-targeted swipe rejected")
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
                val keys = if (includeDescription) setOf("text", "contentDescription", "hint", "label", "title") else setOf("text", "label", "title")
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

    private const val ACTION_EVIDENCE_SETTLE_MS = 250L
    private const val PACKAGE_WAIT_POLL_MS = 150L
}
