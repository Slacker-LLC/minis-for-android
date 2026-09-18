package com.openminis.app.tools.android

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import com.openminis.app.accessibility.GatedCallOutcome
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.accessibility.PackageWindowVisibility
import com.openminis.app.data.ContextOffload
import com.openminis.app.offload.OffloadPermissionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Direct model-tool projection over the existing AccessibilityService. */
object AndroidUiController {
    data class UiToolResult(
        val json: JSONObject,
        val success: Boolean,
        val imageData: ByteArray? = null,
        val imageLinuxPath: String? = null,
        val imageHostPath: String? = null,
    )

    /** Window between an action and its re-observation, so evidence is read after the UI settles. */
    private const val ACTION_EVIDENCE_SETTLE_MS = 250L

    /** How long a scroll action waits for a matching scroll event before concluding. */
    private const val SCROLL_VERIFY_TIMEOUT_MS = 520L

    private const val SCROLL_GESTURE_DURATION_MS = 300L

    private val legacyBackwardDirections = setOf("backward", "up", "left")

    private val verticalScrollActionIds = setOf(
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id,
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id,
    )
    private val horizontalScrollActionIds = setOf(
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id,
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id,
    )

    suspend fun execute(
        context: Context,
        sessionId: String,
        args: JSONObject,
        toolId: String,
    ): UiToolResult {
        val action = args.optString("action", "observe")
        val allowed = OffloadPermissionManager.checkPermission(
            "a11y_cli",
            "android_ui $action",
            sessionId.ifBlank { OffloadPermissionManager.OFFLOAD_GLOBAL_SESSION_ID },
        )
        if (!allowed) return error(
            "PERMISSION_DENIED",
            "android-a11y-cli integration is disabled; enable it under Settings → Permissions → Integrations",
        )
        val service = MinisAccessibilityService.getInstance()
            ?: return error("ACCESSIBILITY_NOT_CONNECTED", "enable Minis under Android Settings → Accessibility")
        return withContext(Dispatchers.Default) {
            when (action) {
                "observe" -> observe(service, args, sessionId)
                "screenshot" -> screenshot(context, service, sessionId, args, toolId)
                "click" -> click(service, args, longPress = false)
                "long_press" -> click(service, args, longPress = true)
                "set_text" -> setText(context, service, args)
                "scroll" -> scroll(service, args)
                "back" -> global(service, AccessibilityService.GLOBAL_ACTION_BACK, "back")
                "home" -> global(service, AccessibilityService.GLOBAL_ACTION_HOME, "home")
                "wait" -> waitFor(service, args)
                else -> error("INVALID_ACTION", "unknown android_ui action: $action")
            }
        }
    }

    private fun observe(service: MinisAccessibilityService, args: JSONObject, sessionId: String): UiToolResult {
        // Observation and capture share the one window judgement: a screen that cannot
        // be reconciled with the surface that receives touch is refused, not reported.
        val windowSet = service.visibleWindowSet()
        AndroidUiObservationRegistry.windowSetRefusal(windowSet)?.let { (code, message) -> return error(code, message) }
        val result = AndroidUiObservationRegistry.observe(
            service,
            windowSet.roots,
            UiObserveOptions(
                interactiveOnly = args.optBoolean("interactiveOnly", true),
                maxDepth = args.optInt("maxDepth", 12).coerceIn(0, 30),
                maxNodes = args.optInt("maxNodes", 120).coerceIn(1, 500),
                textFilter = args.optString("textFilter", "").takeIf(String::isNotBlank),
                resourceIdFilter = args.optString("resourceIdFilter", "").takeIf(String::isNotBlank),
                packageFilter = args.optString("packageFilter", "").takeIf(String::isNotBlank),
            ),
        )
        AndroidDebugSessionStore.update(sessionId) {
            it.copy(lastUiGeneration = result.optLong("generation"), targetPackage = result.optString("package").ifBlank { it.targetPackage })
        }
        return UiToolResult(
            result.put("status", CapabilityStatus.AVAILABLE.name)
                .put("capturedWindows", windowSet.capturedWindows)
                .put("excludedWindows", windowSet.excludedWindows)
                .put("unreadableWindows", windowSet.unreadableWindows),
            true,
        )
    }

    private suspend fun screenshot(
        context: Context,
        service: MinisAccessibilityService,
        sessionId: String,
        args: JSONObject,
        toolId: String,
    ): UiToolResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return error("SCREENSHOT_UNSUPPORTED_API", "Accessibility screenshots require Android 11 (API 30+)")
        }
        // Ported window policy: a capture whose window set cannot be reconciled with
        // the surfaces that receive touch is refused instead of being reported as whole.
        val windowSet = service.visibleWindowSet()
        val refusal = AndroidUiObservationRegistry.windowSetRefusal(windowSet)
        if (refusal != null) {
            return error(
                if (refusal.first == AndroidUiObservationRegistry.ERROR_WINDOW_LIST_UNAVAILABLE) {
                    "SCREENSHOT_WINDOW_UNAVAILABLE"
                } else {
                    "SCREENSHOT_WINDOW_UNKNOWN"
                },
                refusal.second,
            )
        }
        val excludedWindows = windowSet.excludedWindows
        val displayId = args.optInt("displayId", Display.DEFAULT_DISPLAY)
        val scale = args.optDouble("scale", 0.5).toFloat().coerceIn(0.1f, 1f)
        val shot = service.captureScreenshot(displayId)
        val raw = shot.bitmap ?: return error(
            shot.errorCode ?: "SCREENSHOT_FAILED",
            shot.errorMessage ?: "Accessibility takeScreenshot failed (FLAG_SECURE, OEM throttling, or disconnected service)",
        )
        val originalWidth = raw.width
        val originalHeight = raw.height
        val bitmap = if (scale == 1f) raw else {
            val scaled = Bitmap.createScaledBitmap(
                raw,
                (raw.width * scale).toInt().coerceAtLeast(1),
                (raw.height * scale).toInt().coerceAtLeast(1),
                true,
            )
            if (scaled !== raw) raw.recycle()
            scaled
        }
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        val width = bitmap.width
        val height = bitmap.height
        bitmap.recycle()
        // [T-eta-ui-coordinate-space] This capture is the frame that later
        // screenshot-space coordinates refer to.
        ScreenshotFrameRegistry.record(
            ScreenshotFrame(
                width = width,
                height = height,
                originalWidth = originalWidth,
                originalHeight = originalHeight,
            ),
        )
        val bytes = output.toByteArray()
        val linuxPath = ContextOffload.offloadImage(context, sessionId, bytes, toolId, "image/png")
        val json = JSONObject()
            .put("status", CapabilityStatus.AVAILABLE.name)
            .put("displayId", displayId)
            .put("originalWidth", originalWidth)
            .put("originalHeight", originalHeight)
            .put("width", width)
            .put("height", height)
            .put("scale", scale.toDouble())
            .put("sizeBytes", bytes.size)
            .put("quality", ScreenshotOutcomePolicy.classify(
                requested = true,
                hasImage = true,
                complete = ScreenshotOutcomePolicy.mayFallbackToRoot(
                    excludedPackagesPresent = excludedWindows > 0,
                    criticalWindowMissing = !windowSet.complete,
                ),
            ).wireName)
            .put("excludedWindows", excludedWindows)
            .put("capturedWindows", windowSet.capturedWindows)
            .put("unreadableWindows", windowSet.unreadableWindows)
            .put("path", linuxPath.ifEmpty { JSONObject.NULL })
            .put("note", "Screenshot may still be absent for FLAG_SECURE windows or OEM throttling")
        return UiToolResult(json, true, bytes, linuxPath.takeIf(String::isNotEmpty), null)
    }

    private suspend fun click(service: MinisAccessibilityService, args: JSONObject, longPress: Boolean): UiToolResult {
        val generation = args.optLong("generation", -1L)
        val ref = args.optString("ref", "")
        if (generation >= 0L && ref.isNotBlank()) {
            return when (val resolved = AndroidUiObservationRegistry.resolve(generation, ref)) {
                is UiRefResolution.Error -> error(resolved.code, resolved.message)
                is UiRefResolution.Found -> {
                    if (!resolved.node.isEnabled || !resolved.node.isVisibleToUser) {
                        error("UI_NODE_NOT_ACTIONABLE", "ref $ref is disabled or no longer visible")
                    } else {
                        val action = if (longPress) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
                        var fallback = false
                        var fallbackReport: UiActionEvidenceReport? = null
                        var ok = resolved.node.performAction(action)
                        // A refused node action provably never reached the system, which is
                        // the only case the ported fallback policy allows a gesture for.
                        if (!ok && GestureFallbackPolicy.mayFallbackToRoot(UiActionEvidenceSource.GESTURE_NOT_DISPATCHED.name)) {
                            val bounds = Rect().also(resolved.node::getBoundsInScreen)
                            val point = Path().apply {
                                moveTo(bounds.exactCenterX(), bounds.exactCenterY())
                                lineTo(bounds.exactCenterX() + 0.1f, bounds.exactCenterY() + 0.1f)
                            }
                            fallbackReport = service.dispatchGestureWithEvidence(point, 0L, if (longPress) 800L else 50L)
                            ok = fallbackReport.evidence == UiActionEvidence.ACCEPTED_NO_EVIDENCE
                            fallback = true
                        }
                        delay(ACTION_EVIDENCE_SETTLE_MS)
                        val foreground = service.foregroundWindow()
                        val change = AndroidUiObservationRegistry.changeEvidence(generation, ref)
                        val report = AndroidUiActionEvidence.ofClick(ok, change, fallbackReport)
                        UiToolResult(report.into(JSONObject()
                            .put("action", if (longPress) "long_press" else "click")
                            .put("generation", generation)
                            .put("ref", ref)
                            .put("success", ok)
                            .put("coordinateFallback", fallback)
                            .put("package", foreground.packageName ?: "")
                            .put("window", foreground.className ?: "")
                            .put("windowVisibility", foreground.visibility.name)
                            .put("verification", when (change) {
                                UiChangeObservation.CHANGED -> "UI_CHANGED"
                                UiChangeObservation.UNCHANGED -> "UI_STABLE"
                                UiChangeObservation.UNKNOWN -> "UI_UNCOMPARABLE"
                            })), ok)
                    }
                }
            }
        }
        if (!args.has("x") || !args.has("y")) {
            return error("INVALID_ARGS", "click requires generation+ref from observe, or explicit x+y coordinates")
        }
        val requestedX = args.optDouble("x", Double.NaN)
        val requestedY = args.optDouble("y", Double.NaN)
        if (!requestedX.isFinite() || !requestedY.isFinite() || requestedX < 0 || requestedY < 0) {
            return error("INVALID_ARGS", "x/y must be finite non-negative pixels")
        }
        // [T-eta-ui-coordinate-space] x/y belong to the last screenshot unless the
        // caller says otherwise: a coordinate read off a 50 % capture must not be
        // dispatched as if it were a device pixel.
        val space = UiCoordinateSpace.parse(
            args.optString("coordinateSpace", UiCoordinateSpace.DEFAULT.wireName),
        )
        val display = service.resources.displayMetrics
        val resolved = UiCoordinateSpacePolicy.resolvePoint(
            x = requestedX,
            y = requestedY,
            space = space,
            frame = ScreenshotFrameRegistry.latest(),
            screenWidth = display.widthPixels,
            screenHeight = display.heightPixels,
        )
        if (resolved is UiCoordinateResolution.Refused) return error(resolved.code, resolved.message)
        resolved as UiCoordinateResolution.Resolved
        val x = resolved.x
        val y = resolved.y
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()); lineTo(x.toFloat() + 0.1f, y.toFloat() + 0.1f) }
        val report = service.dispatchGestureWithEvidence(path, 0L, if (longPress) 800L else 50L)
        val ok = report.evidence.acceptedBySystem
        return UiToolResult(report.into(JSONObject()
            .put("action", if (longPress) "long_press" else "click")
            .put("coordinateSpace", space.wireName)
            .put("requestedX", requestedX).put("requestedY", requestedY)
            .put("x", x).put("y", y).put("success", ok).put("coordinateFallback", true)), ok)
    }

    private fun setText(context: Context, service: MinisAccessibilityService, args: JSONObject): UiToolResult {
        val resolved = resolveRef(args)
        if (resolved is UiRefResolution.Error) return error(resolved.code, resolved.message)
        resolved as UiRefResolution.Found
        if (!resolved.node.isEditable || !resolved.node.isEnabled) {
            return error("UI_NODE_NOT_EDITABLE", "the observed ref is not an enabled editable node")
        }
        val text = args.optString("text", "")
        var method = "ACTION_SET_TEXT"
        var ok = service.setNodeText(resolved.node, text)
        if (!ok) {
            // Safe Unicode fallback: focus + ACTION_PASTE, restoring the user's
            // previous clipboard without exposing its content to the model.
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard != null) {
                val previous = runCatching { clipboard.primaryClip }.getOrNull()
                try {
                    clipboard.setPrimaryClip(ClipData.newPlainText("Minis Android input", text))
                    resolved.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    ok = resolved.node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                    method = "CLIPBOARD_ACTION_PASTE"
                } finally {
                    if (previous != null) clipboard.setPrimaryClip(previous)
                    else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) clipboard.clearPrimaryClip()
                    else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            }
        }
        // Ported from Eta `setNodeText` (agent/accessibility/AgentAccessibilityService.kt @
        // c15de97): an accepted write is only verified when the refreshed node really
        // carries the requested text, and a password field is never read back.
        val verified = if (ok) service.verifyNodeText(resolved.node, text) else null
        val report = AndroidUiActionEvidence.ofTextInput(ok, verified)
        return UiToolResult(report.into(JSONObject().put("action", "set_text")
            .put("generation", resolved.locator.generation).put("ref", resolved.locator.ref)
            .put("success", ok).put("verified", verified ?: JSONObject.NULL).put("inputMethod", method)
            .put("unicode", "ACTION_SET_TEXT/clipboard supports Unicode; shell input is not used")), ok)
    }

    private suspend fun scroll(service: MinisAccessibilityService, args: JSONObject): UiToolResult {
        if (args.optString("ref", "").isNotBlank()) {
            val resolved = resolveRef(args)
            if (resolved is UiRefResolution.Error) return error(resolved.code, resolved.message)
            resolved as UiRefResolution.Found
            return scrollNode(service, resolved, args)
        }
        val x = args.optDouble("x", Double.NaN)
        val y = args.optDouble("y", Double.NaN)
        val deltaX = args.optDouble("deltaX", 0.0)
        val deltaY = args.optDouble("deltaY", 0.0)
        if (listOf(x, y, deltaX, deltaY).any { !it.isFinite() } || deltaX == 0.0 && deltaY == 0.0) {
            return error("INVALID_ARGS", "coordinate scroll requires finite x/y and a non-zero deltaX or deltaY")
        }
        // [T-eta-ui-coordinate-space] The anchor point AND the deltas belong to the
        // same space, so both go through the one contract.
        val space = UiCoordinateSpace.parse(
            args.optString("coordinateSpace", UiCoordinateSpace.DEFAULT.wireName),
        )
        val display = service.resources.displayMetrics
        val frame = ScreenshotFrameRegistry.latest()
        val resolvedPoint = UiCoordinateSpacePolicy.resolvePoint(x, y, space, frame, display.widthPixels, display.heightPixels)
        if (resolvedPoint is UiCoordinateResolution.Refused) return error(resolvedPoint.code, resolvedPoint.message)
        resolvedPoint as UiCoordinateResolution.Resolved
        val resolvedDelta = UiCoordinateSpacePolicy.resolveDelta(deltaX, deltaY, space, frame, display.widthPixels, display.heightPixels)
        if (resolvedDelta is UiCoordinateResolution.Refused) return error(resolvedDelta.code, resolvedDelta.message)
        resolvedDelta as UiCoordinateResolution.Resolved
        val path = Path().apply {
            moveTo(resolvedPoint.x.toFloat(), resolvedPoint.y.toFloat())
            lineTo((resolvedPoint.x + resolvedDelta.x).toFloat(), (resolvedPoint.y + resolvedDelta.y).toFloat())
        }
        val report = service.dispatchGestureWithEvidence(path, 0L, args.optLong("durationMs", 300L).coerceIn(50L, 5_000L))
        val ok = report.evidence.acceptedBySystem
        return UiToolResult(report.into(JSONObject().put("action", "scroll").put("success", ok)
            .put("coordinateFallback", true).put("coordinateSpace", space.wireName)
            .put("x", resolvedPoint.x).put("y", resolvedPoint.y)
            .put("deltaX", resolvedDelta.x).put("deltaY", resolvedDelta.y)), ok)
    }

    /**
     * Node-scoped scroll. The ported axis contract decides whether the request has an
     * axis at all, refuses an axis the node cannot scroll, and gates the bounds-scoped
     * gesture fallback on the action provably never reaching the system.
     */
    private suspend fun scrollNode(
        service: MinisAccessibilityService,
        resolved: UiRefResolution.Found,
        args: JSONObject,
    ): UiToolResult {
        val node = resolved.node
        val generation = resolved.locator.generation
        val wire = args.optString("direction", "forward")
        val actionIds = node.actionList.mapTo(HashSet()) { it.id }
        val hasVertical = verticalScrollActionIds.any(actionIds::contains)
        val hasHorizontal = horizontalScrollActionIds.any(actionIds::contains)
        val legacySign = if (wire.trim().lowercase() in legacyBackwardDirections) -1 else 1
        val direction = ScrollDirection.parse(wire) ?: legacyDirection(legacySign, hasVertical, hasHorizontal)

        if (
            direction != null &&
            ScrollAxisContract.exposesOnlyOppositeAxis(
                requestedAxis = direction.axis,
                hasVerticalActions = hasVertical,
                hasHorizontalActions = hasHorizontal,
            )
        ) {
            return errorWithEvidence(
                code = "SCROLL_AXIS_MISMATCH",
                message = "ref ${resolved.locator.ref} only exposes the opposite scroll axis; no gesture fallback was dispatched",
                report = UiActionEvidenceReport(
                    UiActionEvidence.DIRECTION_MISMATCH,
                    UiActionEvidenceSource.SCROLL_AXIS_MISMATCH,
                ),
            )
        }

        val axis = direction?.axis
        val observation = if (axis != null) {
            service.beginScrollObservation(node.packageName?.toString().orEmpty(), node.windowId)
        } else {
            null
        }
        val before = if (axis != null) AndroidUiObservationRegistry.sampleAnchors(service, generation) else null
        var accepted = node.performAction(
            if (legacySign > 0) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
        )
        var gestureReport: UiActionEvidenceReport? = null
        if (!accepted && GestureFallbackPolicy.mayFallbackToRoot(UiActionEvidenceSource.GESTURE_NOT_DISPATCHED.name)) {
            val bounds = Rect().also(node::getBoundsInScreen)
            val gesture = direction?.gestureWithin(bounds.left, bounds.top, bounds.right, bounds.bottom)
            if (gesture != null) {
                gestureReport = service.dispatchGestureWithEvidence(
                    gesturePath(gesture),
                    0L,
                    SCROLL_GESTURE_DURATION_MS,
                )
                accepted = gestureReport.evidence.acceptedBySystem
            }
        }
        if (!accepted && gestureReport == null) {
            observation?.let(service::endScrollObservation)
            return UiToolResult(
                AndroidUiActionEvidence.ofRefusedNodeAction().into(JSONObject()
                    .put("action", "scroll").put("ref", resolved.locator.ref).put("generation", generation)
                    .put("direction", wire).put("success", false)),
                false,
            )
        }
        if (axis != null) delay(SCROLL_VERIFY_TIMEOUT_MS)

        val eventDelta = if (axis != null && observation != null) {
            service.observedScrollDelta(observation, axis)
        } else {
            null
        }
        val anchorDelta = if (axis != null && before != null) {
            AndroidUiObservationRegistry.sampleAnchors(service, generation)
                ?.let { after -> RootScrollMotionContract.inferScrollDelta(anchorContentDeltas(before, after, axis)) }
        } else {
            null
        }
        observation?.let(service::endScrollObservation)
        val delta = eventDelta ?: anchorDelta
        val movementSource = when {
            eventDelta != null -> ScrollMovementSource.EVENT
            anchorDelta != null -> ScrollMovementSource.ANCHOR_MOTION
            else -> null
        }
        val atBoundary = delta == null && isAtScrollBoundary(node, direction)
        val scrollEvidence = if (direction == null) {
            if (atBoundary) ScrollEvidence.AT_BOUNDARY else ScrollEvidence.UNVERIFIED
        } else {
            ScrollEvidenceContract.classify(direction, delta, movementSource, atBoundary)
        }
        val report = when {
            gestureReport != null && !gestureReport.evidence.acceptedBySystem -> gestureReport
            else -> AndroidUiActionEvidence.ofScroll(scrollEvidence)
        }
        return UiToolResult(report.into(JSONObject()
            .put("action", "scroll").put("ref", resolved.locator.ref).put("generation", generation)
            .put("direction", wire).put("atBoundary", atBoundary)
            .put("scrollDelta", delta ?: JSONObject.NULL)
            .put("success", report.evidence.acceptedBySystem)), report.evidence.acceptedBySystem)
    }

    /**
     * Legacy FORWARD/BACKWARD carry no axis. The ported contract only lets a vertical
     * request use them when vertical direction evidence exists without horizontal
     * evidence; otherwise the request stays axis-unknown and no axis is ever compared.
     */
    private fun legacyDirection(legacySign: Int, hasVertical: Boolean, hasHorizontal: Boolean): ScrollDirection? =
        if (
            ScrollAxisContract.mayTreatLegacyActionsAsVertical(
                requestedAxis = ScrollAxis.VERTICAL,
                hasVerticalActions = hasVertical,
                hasHorizontalActions = hasHorizontal,
            )
        ) {
            if (legacySign > 0) ScrollDirection.DOWN else ScrollDirection.UP
        } else {
            null
        }

    /**
     * A node that no longer offers the requested legacy action while still offering the
     * opposite one has reached the end of its content. A node that cannot be refreshed
     * never reports a boundary.
     */
    private fun isAtScrollBoundary(node: AccessibilityNodeInfo, direction: ScrollDirection?): Boolean {
        if (direction == null) return false
        if (!runCatching { node.refresh() }.getOrDefault(false)) return false
        val actionIds = node.actionList.mapTo(HashSet()) { it.id }
        val forward = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in actionIds
        val backward = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD in actionIds
        val requested = if (direction.scrollDeltaSign > 0) forward else backward
        val opposite = if (direction.scrollDeltaSign > 0) backward else forward
        return !requested && opposite
    }

    private fun gesturePath(gesture: ScrollGesture): Path = Path().apply {
        moveTo(gesture.start.x.toFloat(), gesture.start.y.toFloat())
        lineTo(gesture.end.x.toFloat(), gesture.end.y.toFloat())
    }

    /**
     * Global navigation reports evidence like every other GUI action: the platform
     * accepting the call is not proof of an effect, a call that never started is a
     * refusal, and a call that started without returning stays unknown.
     */
    private suspend fun global(service: MinisAccessibilityService, action: Int, name: String): UiToolResult {
        val before = service.foregroundWindow()
        val outcome = service.performGlobalActionGated(action)
        if (outcome == GatedCallOutcome.COMPLETED_ACCEPTED) delay(ACTION_EVIDENCE_SETTLE_MS)
        val after = service.foregroundWindow()
        // A reading that failed is UNKNOWN and never claims that the foreground changed.
        val change = when {
            before.visibility != PackageWindowVisibility.VISIBLE -> UiChangeObservation.UNKNOWN
            after.visibility != PackageWindowVisibility.VISIBLE -> UiChangeObservation.UNKNOWN
            before.packageName != after.packageName || before.className != after.className -> UiChangeObservation.CHANGED
            else -> UiChangeObservation.UNCHANGED
        }
        val report = AndroidUiActionEvidence.ofGlobalAction(outcome, change)
        val success = report.evidence.acceptedBySystem
        return UiToolResult(report.into(JSONObject().put("action", name).put("success", success)
            .put("package", after.packageName ?: "")
            .put("window", after.className ?: "")
            .put("windowVisibility", after.visibility.name)
            .put("foregroundChanged", change == UiChangeObservation.CHANGED)), success)
    }

    private suspend fun waitFor(service: MinisAccessibilityService, args: JSONObject): UiToolResult {
        val timeout = args.optLong("timeoutMs", 5_000L).coerceIn(0L, 60_000L)
        val text = args.optString("textFilter", "").trim()
        val disappear = args.optString("mode", "appear") == "disappear"
        val started = System.currentTimeMillis()
        if (text.isEmpty()) {
            delay(timeout.coerceAtMost(10_000L))
            return UiToolResult(JSONObject().put("action", "wait").put("waitedMs", System.currentTimeMillis() - started).put("matched", true), true)
        }
        do {
            val present = service.rootNodes().any { containsText(it, text, 0, 30) }
            if (present != disappear) {
                return UiToolResult(JSONObject().put("action", "wait").put("textFilter", text)
                    .put("mode", if (disappear) "disappear" else "appear")
                    .put("matched", true).put("waitedMs", System.currentTimeMillis() - started), true)
            }
            delay(200L)
        } while (System.currentTimeMillis() - started < timeout)
        return UiToolResult(JSONObject().put("action", "wait").put("textFilter", text)
            .put("mode", if (disappear) "disappear" else "appear")
            .put("matched", false).put("timedOut", true).put("waitedMs", System.currentTimeMillis() - started), true)
    }

    private fun containsText(node: AccessibilityNodeInfo?, needle: String, depth: Int, maxDepth: Int): Boolean {
        if (node == null || depth > maxDepth) return false
        if (node.text?.toString()?.contains(needle, true) == true ||
            node.contentDescription?.toString()?.contains(needle, true) == true) return true
        for (index in 0 until node.childCount) if (containsText(node.getChild(index), needle, depth + 1, maxDepth)) return true
        return false
    }

    private fun resolveRef(args: JSONObject): UiRefResolution {
        val generation = args.optLong("generation", -1L)
        val ref = args.optString("ref", "")
        if (generation < 0L || ref.isBlank()) return UiRefResolution.Error(
            "INVALID_ARGS", "generation and ref from the latest observe are required",
        )
        return AndroidUiObservationRegistry.resolve(generation, ref)
    }

    private fun error(code: String, message: String): UiToolResult = UiToolResult(
        JSONObject().put("status", CapabilityStatus.UNAVAILABLE.name)
            .put("success", false).put("error", JSONObject().put("code", code).put("message", message)),
        false,
    )

    private fun errorWithEvidence(code: String, message: String, report: UiActionEvidenceReport): UiToolResult = UiToolResult(
        report.into(JSONObject().put("status", CapabilityStatus.UNAVAILABLE.name)
            .put("success", false).put("error", JSONObject().put("code", code).put("message", message))),
        false,
    )
}
