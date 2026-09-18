package com.openminis.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Path
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.openminis.app.logging.AppLogger
import com.openminis.app.tools.android.AndroidUiActionEvidence
import com.openminis.app.tools.android.GestureDispatchOutcome
import com.openminis.app.tools.android.ScrollAxis
import com.openminis.app.tools.android.ScrollEvidenceContract
import com.openminis.app.tools.android.UiActionEvidence
import com.openminis.app.tools.android.UiActionEvidenceReport
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * MinisAccessibilityService — host-side AccessibilityService backing the
 * `android-a11y-cli` offload command. The user must enable it manually
 * under Settings → Accessibility; we never start it programmatically.
 */
class MinisAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MinisA11y"
        const val SERVICE_ID = "com.openminis.app/.accessibility.MinisAccessibilityService"
        private const val EVENT_RING_CAP = 1024
        private const val DEFAULT_GESTURE_TIMEOUT_MS = 5000L
        private const val PINCH_TIMEOUT_SLACK_MS = 2000L
        private const val SCROLL_EVENT_OBSERVATION_TIMEOUT_MS = 3_520L
        private const val SCROLL_EVENT_TYPE = "TYPE_VIEW_SCROLLED"

        @Volatile
        private var _instance: MinisAccessibilityService? = null

        fun getInstance(): MinisAccessibilityService? = _instance
    }

    data class RecordedEvent(
        val type: String,
        val packageName: String?,
        val className: String?,
        val text: String?,
        val timestamp: Long,
        val windowId: Int = -1,
        val uptimeMs: Long = 0L,
        val scrollDeltaX: Int = 0,
        val scrollDeltaY: Int = 0,
    )

    private val eventRing = ConcurrentLinkedQueue<RecordedEvent>()
    private val eventListeners = CopyOnWriteArrayList<(RecordedEvent) -> Unit>()

    val nodeRegistry: NodeRegistry = NodeRegistry()

    /** Only scroll events inside this short, self-opened window count as evidence. */
    private val scrollObservationGate = ScrollEventObservationGate()

    /** Shared late-call gate for every accessibility call that is posted to the main thread. */
    private val mainThreadCallBridge = MainThreadCallBridge()

    override fun onCreate() {
        super.onCreate()
        _instance = this
        AppLogger.info(TAG, "service created")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        _instance = this
        // [T-android-a11y-miui-service-failure] Breadcrumb on (re)bind. On OEM
        // ROMs (MIUI etc.) the system kills + rebinds this service repeatedly,
        // surfacing "此服务出现故障"; a connect log lets us correlate a degraded
        // window in stall/diag logs with a kill/rebind. No self-restart logic —
        // remediation is the user whitelisting autostart + battery (see
        // SystemPermissionsScreen OEM guidance).
        AppLogger.info(TAG, "service connected (manufacturer=${android.os.Build.MANUFACTURER})")
        // [T-android-a11y-force-stop-recovery] Latch that the grant existed, so
        // a later absence can be recognized as a revocation rather than a
        // never-configured service. This is the one callback that only fires
        // when the user has genuinely granted it.
        try {
            AccessibilityRecoveryManager.markGranted(this)
            AccessibilityRecoveryManager.refreshRevokedState(this)
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "markGranted failed: ${t.message}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val rec = RecordedEvent(
            type = AccessibilityEvent.eventTypeToString(event.eventType),
            packageName = event.packageName?.toString(),
            className = event.className?.toString(),
            text = event.text?.joinToString(" ") { it?.toString() ?: "" }?.takeIf { it.isNotBlank() },
            timestamp = System.currentTimeMillis(),
            windowId = event.windowId,
            uptimeMs = android.os.SystemClock.uptimeMillis(),
            // Scroll deltas require API 28; older devices keep the event without a delta.
            scrollDeltaX = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) event.scrollDeltaX else 0,
            scrollDeltaY = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) event.scrollDeltaY else 0,
        )
        eventRing.offer(rec)
        while (eventRing.size > EVENT_RING_CAP) eventRing.poll()
        for (listener in eventListeners) {
            try { listener(rec) } catch (_: Throwable) {}
        }
    }

    override fun onInterrupt() {
        AppLogger.info(TAG, "service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (_instance === this) _instance = null
        eventRing.clear()
        nodeRegistry.clear()
        eventListeners.clear()
        AppLogger.info(TAG, "service destroyed")
    }

    fun snapshotEvents(): List<RecordedEvent> = eventRing.toList()

    fun addEventListener(listener: (RecordedEvent) -> Unit) {
        eventListeners.add(listener)
    }

    fun removeEventListener(listener: (RecordedEvent) -> Unit) {
        eventListeners.remove(listener)
    }

    /**
     * Every window that observation, capture and ref resolution are allowed to see,
     * decided by the one shared [ScreenshotWindowPolicy].
     *
     * [complete] is false when the window list cannot be read or when a window that
     * could receive touch has no resolvable package. Either way the observed set
     * cannot be reconciled with the surface that receives touch, so callers that
     * promise consistency must refuse instead of reporting a partial view.
     */
    data class VisibleWindowSet(
        val roots: List<AccessibilityNodeInfo>,
        val capturedWindows: Int,
        val excludedWindows: Int,
        val blockedUnknownWindows: Int,
        /** Windows the policy allows but whose node tree cannot be read, so they stay undisclosed. */
        val unreadableWindows: Int,
        val complete: Boolean,
    )

    fun visibleWindowSet(): VisibleWindowSet {
        val out = ArrayList<AccessibilityNodeInfo>()
        val enumerated = try { windows } catch (_: Throwable) { null }
        if (enumerated == null || enumerated.isEmpty()) {
            // Only an unreadable window list falls back to the active root; an excluded
            // or blocked window must never come back through this path. The policy was
            // never evaluated here, so the set stays incomplete.
            try { rootInActiveWindow?.let { out.add(it) } } catch (_: Throwable) {}
            return VisibleWindowSet(
                roots = out,
                capturedWindows = out.size,
                excludedWindows = 0,
                blockedUnknownWindows = 0,
                unreadableWindows = 0,
                complete = false,
            )
        }
        val decisions = windowDecisions(enumerated)
        var unreadable = 0
        for (decision in decisions) {
            if (decision.decision != ScreenshotWindowPolicy.Decision.CAPTURE) continue
            val root = enumerated.firstOrNull { it.id == decision.windowId }?.root
            if (root == null) unreadable += 1 else out.add(root)
        }
        val blocked = decisions.count { it.decision == ScreenshotWindowPolicy.Decision.BLOCK_UNKNOWN }
        return VisibleWindowSet(
            roots = out,
            capturedWindows = out.size,
            excludedWindows = decisions.count { it.decision == ScreenshotWindowPolicy.Decision.EXCLUDE },
            blockedUnknownWindows = blocked,
            unreadableWindows = unreadable,
            complete = blocked == 0,
        )
    }

    fun rootNodes(): List<AccessibilityNodeInfo> = visibleWindowSet().roots

    /** Per-window decision of the ported screenshot/observation window policy. */
    data class WindowDecision(
        val windowId: Int,
        val packageName: String?,
        val decision: ScreenshotWindowPolicy.Decision,
    )

    /**
     * Own accessibility overlay and configured exclusions never enter the observed
     * or captured set; an application window whose package cannot be resolved is
     * blocked instead of being silently captured.
     *
     * Minis owns exactly its own package, so that package is the configured
     * exclusion set (the accessibility overlay and the app's own windows).
     */
    private fun windowDecisions(
        enumerated: List<android.view.accessibility.AccessibilityWindowInfo>,
    ): List<WindowDecision> = enumerated.map { w ->
        val resolved = try { w.root?.packageName?.toString() } catch (_: Throwable) { null }
        WindowDecision(
            windowId = w.id,
            packageName = resolved,
            decision = ScreenshotWindowPolicy.decide(
                isAccessibilityOverlay = w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
                isApplicationWindow = w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION,
                active = w.isActive,
                focused = w.isFocused,
                resolvedPackage = resolved,
                ownPackage = packageName,
                excludedPackages = setOf(packageName),
            ),
        )
    }

    fun windowInfos(): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        try {
            for (w in windows ?: emptyList()) {
                out.add(mapOf(
                    "windowId" to w.id,
                    "type" to when (w.type) {
                        android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION -> "application"
                        android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
                        android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "input_method"
                        android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "overlay"
                        else -> "other"
                    },
                    "title" to (w.title?.toString() ?: ""),
                    "focused" to w.isFocused,
                    "active" to w.isActive,
                ))
            }
        } catch (_: Throwable) {}
        return out
    }

    /** Three-state foreground query: a failed read stays UNKNOWN, never "gone". */
    data class ForegroundWindow(
        val visibility: PackageWindowVisibility,
        val packageName: String?,
        val className: String?,
    )

    fun foregroundWindow(): ForegroundWindow {
        val active = try {
            rootInActiveWindow
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "foreground window query failed: ${t.message}")
            return ForegroundWindow(PackageWindowVisibility.UNKNOWN, null, null)
        }
        val pkg = try { active?.packageName?.toString() } catch (_: Throwable) { null }
        val cls = try { active?.className?.toString() } catch (_: Throwable) { null }
        return ForegroundWindow(
            visibility = PackageWindowVisibilityResolver.resolve(
                queried = active != null,
                packageResolved = pkg != null,
            ),
            packageName = pkg,
            className = cls,
        )
    }

    /** Legacy pair projection for callers that cannot act on UNKNOWN anyway. */
    fun foregroundPackage(): Pair<String?, String?> {
        val foreground = foregroundWindow()
        return foreground.packageName to foreground.className
    }

    /**
     * Opens the short observation window that turns scroll events into evidence for
     * the next scroll action. Only one observation is active at a time.
     */
    fun beginScrollObservation(
        packageName: String,
        windowId: Int,
        validForMillis: Long = SCROLL_EVENT_OBSERVATION_TIMEOUT_MS,
    ): ScrollEventObservationGate.Observation =
        scrollObservationGate.begin(packageName, windowId, validForMillis)

    fun endScrollObservation(observation: ScrollEventObservationGate.Observation) {
        scrollObservationGate.end(observation)
    }

    /**
     * Sums the delta of matching scroll events seen inside [observation] on [axis].
     * Returns `null` when no accepted event carried a delta, which is not zero motion.
     */
    fun observedScrollDelta(observation: ScrollEventObservationGate.Observation, axis: ScrollAxis): Int? {
        var total: Int? = null
        for (event in eventRing) {
            if (event.type != SCROLL_EVENT_TYPE) continue
            val delta = when (axis) {
                ScrollAxis.VERTICAL -> ScrollEvidenceContract.normalizeAccessibilityDelta(event.scrollDeltaY)
                ScrollAxis.HORIZONTAL -> ScrollEvidenceContract.normalizeAccessibilityDelta(event.scrollDeltaX)
            } ?: continue
            if (delta == 0) continue
            val accepted = scrollObservationGate.withMatchingObservation(
                packageName = event.packageName.orEmpty(),
                windowId = event.windowId,
                eventTimeMillis = event.uptimeMs,
            ) { }
            if (!accepted) continue
            total = (total ?: 0) + delta
        }
        return total
    }

    /**
     * Evidence-returning gesture bridge. The call passes a [MainThreadCallGate], so
     * a dispatch that is still queued when the deadline expires is refused and a
     * late callback cannot write back a result the caller never waited for.
     */
    fun dispatchGestureWithEvidence(
        path: Path,
        startTime: Long,
        durationMs: Long,
        timeoutMs: Long = DEFAULT_GESTURE_TIMEOUT_MS,
    ): UiActionEvidenceReport {
        val stroke = GestureDescription.StrokeDescription(path, startTime, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchThroughGate(gesture, timeoutMs)
    }

    /** Boolean projection for callers that only need "the gesture ran to completion". */
    fun dispatchSimpleGesture(
        path: Path,
        startTime: Long,
        durationMs: Long,
        timeoutMs: Long = DEFAULT_GESTURE_TIMEOUT_MS,
    ): Boolean = dispatchGestureWithEvidence(path, startTime, durationMs, timeoutMs).evidence ==
        UiActionEvidence.ACCEPTED_NO_EVIDENCE

    /**
     * Evidence-returning pinch. Multi-stroke gestures share the same gate as single
     * strokes, so a pinch that never started is refused instead of reported as failed.
     */
    fun dispatchPinchWithEvidence(cx: Float, cy: Float, scale: Float, durationMs: Long): UiActionEvidenceReport {
        val initialOffset = 200f
        val finalOffset = (initialOffset * scale).coerceAtLeast(20f)
        val (s0, e0) = if (scale > 1f) {
            (cx - finalOffset to cy) to (cx - initialOffset to cy)
        } else {
            (cx - initialOffset to cy) to (cx - finalOffset to cy)
        }
        val (s1, e1) = if (scale > 1f) {
            (cx + finalOffset to cy) to (cx + initialOffset to cy)
        } else {
            (cx + initialOffset to cy) to (cx + finalOffset to cy)
        }
        val p0 = Path().apply { moveTo(s0.first, s0.second); lineTo(e0.first, e0.second) }
        val p1 = Path().apply { moveTo(s1.first, s1.second); lineTo(e1.first, e1.second) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p0, 0, durationMs))
            .addStroke(GestureDescription.StrokeDescription(p1, 0, durationMs))
            .build()
        return dispatchThroughGate(gesture, durationMs + PINCH_TIMEOUT_SLACK_MS)
    }

    /**
     * Global accessibility actions run on the main thread behind the same late-call
     * gate as gestures: a call that never started provably did not run, and a call
     * that started without returning may have run, so neither may be reported as a
     * plain failure.
     */
    fun performGlobalActionGated(action: Int): GatedCallOutcome =
        when (val result = mainThreadCallBridge.call { performGlobalAction(action) }) {
            is MainThreadCallBridge.Result.Completed ->
                if (result.value) GatedCallOutcome.COMPLETED_ACCEPTED else GatedCallOutcome.COMPLETED_REFUSED

            MainThreadCallBridge.Result.NotDispatched -> GatedCallOutcome.NOT_DISPATCHED
            MainThreadCallBridge.Result.OutcomeUnknown -> GatedCallOutcome.OUTCOME_UNKNOWN
        }

    /**
     * [T-android-a11y-late-gesture] Single dispatch path for every gesture. Only a
     * call that wins `PENDING -> RUNNING` on the main thread may dispatch; once the
     * bridge deadline expires a not-yet-started call is cancelled, so the queued
     * lambda is refused and the first settled outcome wins.
     */
    private fun dispatchThroughGate(gesture: GestureDescription, timeoutMs: Long): UiActionEvidenceReport {
        val gate = MainThreadCallGate()
        val done = CountDownLatch(1)
        val outcome = AtomicReference<GestureDispatchOutcome?>(null)

        fun settle(result: GestureDispatchOutcome) {
            if (outcome.compareAndSet(null, result)) done.countDown()
        }

        val callback = object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                gate.finish()
                settle(GestureDispatchOutcome.COMPLETED)
            }

            override fun onCancelled(g: GestureDescription?) {
                gate.finish()
                settle(GestureDispatchOutcome.CANCELLED)
            }
        }
        val posted = Handler(Looper.getMainLooper()).post {
            if (!gate.tryStart()) {
                AppLogger.info(TAG, "gesture dispatch refused: the sync bridge already returned")
                settle(GestureDispatchOutcome.NOT_DISPATCHED)
                return@post
            }
            val dispatched = try {
                dispatchGesture(gesture, callback, null)
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "dispatchGesture failed: ${t.message}")
                false
            }
            if (!dispatched) {
                gate.finish()
                settle(GestureDispatchOutcome.NOT_DISPATCHED)
            }
        }
        if (!posted) settle(GestureDispatchOutcome.NOT_DISPATCHED)
        if (done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            return AndroidUiActionEvidence.ofGesture(outcome.get() ?: GestureDispatchOutcome.NOT_DISPATCHED)
        }
        // The deadline expired: a call that never started is cancelled and may never
        // run; a running gesture stays in flight and its late result is not observed.
        if (gate.cancelIfPending()) settle(GestureDispatchOutcome.TIMED_OUT)
        return AndroidUiActionEvidence.ofGesture(outcome.get() ?: GestureDispatchOutcome.TIMED_OUT)
    }

    /**
     * Result of [captureScreenshot]. On success [bitmap] is non-null and
     * caller is responsible for `bitmap.recycle()`.
     */
    data class ShotResult(
        val bitmap: Bitmap?,
        val errorCode: String? = null,
        val errorMessage: String? = null,
    )

    /**
     * Synchronously captures a system-wide screenshot via
     * `AccessibilityService.takeScreenshot` (API 30+). Blocks the calling
     * thread up to [timeoutMs]. Caller must check `Build.VERSION.SDK_INT`
     * before invoking — annotated `@RequiresApi(R)`.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun captureScreenshot(displayId: Int = Display.DEFAULT_DISPLAY, timeoutMs: Long = 5_000): ShotResult {
        val done = CountDownLatch(1)
        val resultRef = java.util.concurrent.atomic.AtomicReference(
            ShotResult(null, "TIMEOUT", "takeScreenshot timed out")
        )
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            takeScreenshot(displayId, executor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    try {
                        val hwBuffer: HardwareBuffer = screenshot.hardwareBuffer
                        val cs: ColorSpace? = screenshot.colorSpace
                        val bmp = Bitmap.wrapHardwareBuffer(hwBuffer, cs)
                        try { hwBuffer.close() } catch (_: Throwable) {}
                        if (bmp == null) {
                            resultRef.set(ShotResult(null, "DECODE_FAILED", "wrapHardwareBuffer returned null"))
                        } else {
                            // Copy to a software bitmap so we can encode + scale freely.
                            val sw = bmp.copy(Bitmap.Config.ARGB_8888, false)
                            bmp.recycle()
                            resultRef.set(ShotResult(sw))
                        }
                    } catch (t: Throwable) {
                        resultRef.set(ShotResult(null, "DECODE_FAILED", "${t.javaClass.simpleName}: ${t.message ?: ""}"))
                    } finally {
                        done.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    resultRef.set(ShotResult(null, "TAKE_SCREENSHOT_FAILED", "takeScreenshot error code=$errorCode"))
                    done.countDown()
                }
            })
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                AppLogger.warning(TAG, "takeScreenshot timed out after ${timeoutMs}ms")
            }
        } catch (t: Throwable) {
            resultRef.set(ShotResult(null, "INTERNAL", "${t.javaClass.simpleName}: ${t.message ?: ""}"))
        } finally {
            executor.shutdown()
        }
        return resultRef.get()
    }

    fun setNodeText(node: AccessibilityNodeInfo, text: String): Boolean {
        return setNodeText(node, text, text.length)
    }

    /**
     * [T-eta-text-insert] Writes [text] and puts the cursor at [cursor]. Ported from Eta
     * `setNodeText(node, text, cursor)` (agent/accessibility/AgentAccessibilityService.kt @
     * c15de97): the write is the answer, and the cursor is placed afterwards so a field that
     * refuses the selection still keeps the text.
     */
    fun setNodeText(node: AccessibilityNodeInfo, text: String, cursor: Int): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        placeCursor(node, cursor.coerceIn(0, text.length))
        return true
    }

    /**
     * True when the cursor really moved. The node is refreshed first: a stale node would take the
     * selection of the text it no longer carries.
     */
    fun placeCursor(node: AccessibilityNodeInfo, cursor: Int): Boolean {
        val selection = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        }
        return runCatching { node.refresh() }.getOrDefault(false) &&
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection)
    }

    /**
     * Read-back verification for a text write. Ported from Eta `setNodeText`
     * (Mangi-11/Eta @ c15de97): a password field is never read, and an accepted write
     * is only verified when the refreshed node really carries the requested text.
     * `null` means the write cannot be verified at all.
     */
    fun verifyNodeText(node: AccessibilityNodeInfo, text: String): Boolean? = when {
        node.isPassword -> null
        else -> runCatching { node.refresh() }.getOrDefault(false) && node.text?.toString() == text
    }
}
