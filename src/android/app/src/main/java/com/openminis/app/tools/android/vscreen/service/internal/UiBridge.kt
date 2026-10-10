/*
 * Adapted for Minis from ShadowAuto (`android-notes/ShadowAuto`, commit `611e5eb0e1c94befda3c17c97438912dfd30e8a8`); modified.
 * Original source: android-shell/src/main/java/com/silentauto/shell/UiBridge.java.
 * Licensed under Apache-2.0; see third_party/shadowauto/LICENSE.
 */
package com.openminis.app.tools.android.vscreen.service.internal

import android.annotation.SuppressLint
import android.app.UiAutomation
import android.graphics.Rect
import android.os.HandlerThread
import android.os.SystemClock
import android.util.SparseArray
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.openminis.app.tools.android.vscreen.LayoutDiagnostics
import com.openminis.app.tools.android.vscreen.SettlePolicy
import com.openminis.app.tools.android.vscreen.SettleTracker
import com.openminis.app.tools.android.vscreen.VirtualScreenPolicy
import com.openminis.app.tools.android.vscreen.WindowGeometry
import org.json.JSONArray
import org.json.JSONObject

internal class UiBridge(private val settle: SettleTracker) {
    @Volatile private var automation: UiAutomation? = null
    private var thread: HandlerThread? = null

    /** The one display whose window events count as "the screen reacted". */
    @Volatile private var targetDisplayId: Int = 0
    @Volatile private var windowDisplay: Map<Int, Int> = emptyMap()
    @Volatile private var lastWindowRefreshAt = 0L

    /** This reflection executes only in the separate root virtual-screen service process. */
    @SuppressLint("SoonBlockedPrivateApi")
    @Synchronized
    fun connect(): Boolean {
        if (automation != null) return true
        val worker = HandlerThread("minis-vscreen-ui-automation").apply { start() }
        return try {
            val connectionClass = Class.forName("android.app.UiAutomationConnection")
            val connection = connectionClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            val binderInterface = Class.forName("android.app.IUiAutomationConnection")
            val constructor = UiAutomation::class.java.getDeclaredConstructor(android.os.Looper::class.java, binderInterface)
                .apply { isAccessible = true }
            val instance = constructor.newInstance(worker.looper, connection) as UiAutomation
            // connect(int flags) exists since API 24 and is the one that takes the flag; the no-argument connect() is
            // flags = 0. FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES: with flags 0 the system unbinds every other
            // accessibility service (this app's own, TalkBack, other automation) for as long as this UiAutomation
            // exists (the log shows "unbindService ... MinisAccessibilityService" right after the registration), which
            // broke the physical screen whenever the virtual one was in use.
            val connectWithFlags = runCatching { UiAutomation::class.java.getDeclaredMethod("connect", Int::class.javaPrimitiveType) }.getOrNull()
            if (connectWithFlags != null) {
                connectWithFlags.isAccessible = true
                connectWithFlags.invoke(instance, UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            } else {
                UiAutomation::class.java.getDeclaredMethod("connect").apply { isAccessible = true }.invoke(instance)
            }
            runCatching {
                val info = instance.serviceInfo
                // No events: only window and node queries are used, and an event subscription would deliver every
                // UI event of the physical screen to this process too.
                info.eventTypes = 0
                info.flags = info.flags or
                    android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                // Settle detection listens to these; ask for everything so a ROM default cannot silence it.
                info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
                info.notificationTimeout = 0
                instance.serviceInfo = info
            }
            runCatching { instance.setOnAccessibilityEventListener { event -> onEvent(event) } }
            thread = worker
            automation = instance
            true
        } catch (error: Throwable) {
            worker.quitSafely()
            throw IllegalStateException("uiautomation_connect_failed", error)
        }
    }

    fun isConnected(): Boolean = automation != null

    /** Call right before an action: events from other displays must not count as the screen reacting. */
    fun beginObserving(displayId: Int) {
        targetDisplayId = displayId
        refreshWindowMap()
    }

    /**
     * Runs on the UiAutomation looper. Only a *semantic* event on a window that belongs to the
     * target display counts; everything on the physical screen (the user using the phone) is dropped.
     */
    private fun onEvent(event: AccessibilityEvent) {
        val type = event.eventType
        if (!SettlePolicy.isSemantic(type)) return
        val target = targetDisplayId
        if (target <= 0) return
        val now = SystemClock.uptimeMillis()
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            // The event does not say which display changed; compare this display's window set.
            val before = windowIdsOn(target)
            refreshWindowMap()
            if (before != windowIdsOn(target)) settle.onSemantic(now)
            return
        }
        val windowId = event.windowId
        var owner = windowDisplay[windowId]
        if (owner == null && now - lastWindowRefreshAt >= WINDOW_REFRESH_MIN_GAP_MS) {
            refreshWindowMap()
            owner = windowDisplay[windowId]
        }
        when {
            owner == target -> settle.onSemantic(now)
            // A window that appeared between two refreshes and cannot be placed yet: count window-level
            // events (it may be ours), ignore content noise.
            owner == null && SettlePolicy.mayIntroduceUnknownWindow(type) -> settle.onSemantic(now)
        }
    }

    private fun windowIdsOn(displayId: Int): Set<Int> =
        windowDisplay.entries.filter { it.value == displayId }.map { it.key }.toSet()

    private fun refreshWindowMap() {
        val ui = automation ?: return
        lastWindowRefreshAt = SystemClock.uptimeMillis()
        runCatching {
            val all = UiAutomation::class.java.getMethod("getWindowsOnAllDisplays").invoke(ui) as? SparseArray<*> ?: return
            val fresh = HashMap<Int, Int>()
            for (index in 0 until all.size()) {
                val display = all.keyAt(index)
                (all.valueAt(index) as? List<*>)?.filterIsInstance<AccessibilityWindowInfo>()?.forEach { fresh[it.id] = display }
            }
            windowDisplay = fresh
        }
    }

    fun windows(displayId: Int): List<AccessibilityWindowInfo> {
        val ui = automation ?: throw IllegalStateException("uiautomation_not_connected")
        if (displayId == 0) throw IllegalArgumentException(VirtualScreenPolicy.PHYSICAL_DISPLAY_REFUSED)
        return windowsOnDisplay(ui, displayId).values
    }

    fun dump(displayId: Int, mode: String, displayWidth: Int, displayHeight: Int, rotation: Int = -1): String =
        VirtualScreenPolicy.fitDumpJson(dumpObject(displayId, mode, displayWidth, displayHeight, rotation).toString())

    /**
     * Flat observation: windows (summary), targets, inputs and non-actionable texts. The node tree is
     * only built for FULL; the model gets the same facts without the same facts repeated three times.
     */
    fun dumpObject(displayId: Int, mode: String, displayWidth: Int, displayHeight: Int, rotation: Int = -1): JSONObject {
        val ui = automation ?: throw IllegalStateException("uiautomation_not_connected")
        if (displayId == 0) throw IllegalArgumentException(VirtualScreenPolicy.PHYSICAL_DISPLAY_REFUSED)
        val result = windowsOnDisplay(ui, displayId)
        val full = mode.equals("FULL", true)
        val windowArray = JSONArray()
        val targets = JSONArray()
        val inputs = JSONArray()
        val texts = JSONArray()
        val geometry = ArrayList<WindowGeometry>()
        var scanned = 0
        var scanTruncated = false
        var outputTruncated = false

        for ((windowIndex, window) in result.values.withIndex()) {
            val budget = UiNodeUtils.SCAN_LIMIT - scanned
            if (budget <= 0) {
                scanTruncated = true
                break
            }
            val collected = UiNodeUtils.collect(window, full, windowIndex, budget)
            if (collected.scanned == 0) continue
            scanned += collected.scanned
            if (collected.scanTruncated) scanTruncated = true
            if (collected.outputTruncated) outputTruncated = true
            val targetOffset = targets.length()
            val type = runCatching { window.type }.getOrDefault(-1)
            val bounds = Rect().also { runCatching { window.getBoundsInScreen(it) } }
            val entry = JSONObject().put("windowIndex", windowIndex)
                .put("windowId", runCatching { window.id }.getOrDefault(-1))
                .put("type", type)
                .put("title", runCatching { window.title?.toString().orEmpty().take(128) }.getOrDefault(""))
                .put("package", collected.rootPackage)
                .put("bounds", "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}")
                .put("truncated", collected.scanTruncated)
            if (full) {
                UiNodeUtils.shiftTargetIndices(collected.root, targetOffset)
                entry.put("root", collected.root)
            }
            windowArray.put(entry)
            for (i in 0 until collected.targets.length()) {
                val item = collected.targets.optJSONObject(i) ?: continue
                if (targets.length() >= MAX_TARGETS_TOTAL) {
                    outputTruncated = true
                    break
                }
                targets.put(item.put("index", item.optInt("index", 0) + targetOffset))
            }
            for (i in 0 until collected.inputs.length()) inputs.put(collected.inputs.opt(i))
            for (i in 0 until collected.texts.length()) texts.put(collected.texts.opt(i))
            geometry.add(WindowGeometry(type, collected.rootPackage, bounds.left, bounds.top, bounds.right, bounds.bottom))
        }

        val out = JSONObject()
            .put("schema", 2)
            .put("displayId", displayId)
            .put("mode", if (full) "FULL" else "SIMPLE")
            .put("coordinateSpace", "display-local")
            .put("windowSource", result.source)
            .put("windowError", result.error)
            .put("availableDisplays", result.availableDisplays)
            .put("display", JSONObject().put("width", displayWidth).put("height", displayHeight).put("rotation", rotation))
            .put("windows", windowArray)
            .put("targets", targets)
            .put("inputs", inputs)
            .put("texts", texts)
            .put("scanNodes", scanned)
            .put("truncated", scanTruncated)
            .put("layoutWarnings", JSONArray(LayoutDiagnostics.warnings(displayWidth, displayHeight, geometry)))
        outputTruncated = fitToBudget(out, full) || outputTruncated
        out.put("outputTruncated", outputTruncated)
        return out
    }

    /** Keeps the payload under the policy cap by dropping detail, never by invalidating the JSON. */
    private fun fitToBudget(out: JSONObject, full: Boolean): Boolean {
        var trimmed = false
        fun size() = out.toString().toByteArray(Charsets.UTF_8).size
        if (size() <= VirtualScreenPolicy.MAX_DUMP_BYTES) return false
        if (full) {
            val windows = out.getJSONArray("windows")
            for (i in windows.length() - 1 downTo 0) {
                windows.getJSONObject(i).remove("root")
                trimmed = true
                if (size() <= VirtualScreenPolicy.MAX_DUMP_BYTES) return true
            }
        }
        val texts = out.getJSONArray("texts")
        val targets = out.getJSONArray("targets")
        while (size() > VirtualScreenPolicy.MAX_DUMP_BYTES && (texts.length() > 0 || targets.length() > 0)) {
            val array = if (texts.length() > 0) texts else targets
            repeat(maxOf(1, array.length() / 10)) { if (array.length() > 0) array.remove(array.length() - 1) }
            trimmed = true
        }
        return trimmed
    }

    fun hasWindowOnDisplay(displayId: Int, packageName: String): Boolean {
        if (displayId == 0) return false
        return windowsOnDisplay(automation ?: return false, displayId).values.any { window ->
            UiNodeUtils.containsPackage(runCatching { window.root }.getOrNull(), packageName)
        }
    }

    fun findInputFocus(displayId: Int) = windows(displayId).firstNotNullOfOrNull { window ->
        UiNodeUtils.findInputFocus(runCatching { window.root }.getOrNull())
    }

    fun setText(displayId: Int, text: String): Boolean {
        if (displayId == 0) throw IllegalArgumentException(VirtualScreenPolicy.PHYSICAL_DISPLAY_REFUSED)
        for (window in windowsOnDisplay(automation ?: return false, displayId).values) {
            if (UiNodeUtils.setText(runCatching { window.root }.getOrNull(), text)) return true
        }
        return false
    }

    /** This reflection executes only in the separate root virtual-screen service process. */
    @SuppressLint("SoonBlockedPrivateApi")
    @Synchronized
    fun disconnect() {
        val old = automation
        automation = null
        targetDisplayId = 0
        windowDisplay = emptyMap()
        runCatching { old?.setOnAccessibilityEventListener(null) }
        runCatching { UiAutomation::class.java.getDeclaredMethod("disconnect").apply { isAccessible = true }.invoke(old) }
        thread?.quitSafely()
        thread = null
    }

    private fun windowsOnDisplay(ui: UiAutomation, displayId: Int): WindowResult {
        try {
            val allMethod = UiAutomation::class.java.getMethod("getWindowsOnAllDisplays")
            val all = allMethod.invoke(ui) as? SparseArray<*> ?: return WindowResult(emptyList(), "getWindowsOnAllDisplays", "unexpected_result")
            val ids = JSONArray()
            for (index in 0 until all.size()) ids.put(all.keyAt(index))
            val values = (all.get(displayId) as? List<*>)?.filterIsInstance<AccessibilityWindowInfo>().orEmpty()
            return WindowResult(values, "getWindowsOnAllDisplays", if (values.isEmpty()) "no_windows_for_display" else "", ids)
        } catch (_: NoSuchMethodException) {
            // Older Android versions expose only the current windows list.
        } catch (error: Throwable) {
            return WindowResult(emptyList(), "getWindowsOnAllDisplays", error.javaClass.simpleName)
        }
        return try {
            val values = ui.windows.filter { windowDisplayId(it) == displayId }
            val ids = JSONArray().apply { values.forEach { put(windowDisplayId(it)) } }
            WindowResult(values, "getWindowsFiltered", if (values.isEmpty()) "no_verified_windows_for_display" else "", ids)
        } catch (error: Throwable) {
            WindowResult(emptyList(), "getWindowsFiltered", error.javaClass.simpleName)
        }
    }

    private fun windowDisplayId(window: AccessibilityWindowInfo): Int = runCatching {
        AccessibilityWindowInfo::class.java.getMethod("getDisplayId").invoke(window) as Int
    }.getOrDefault(-1)

    private data class WindowResult(
        val values: List<AccessibilityWindowInfo>,
        val source: String,
        val error: String,
        val availableDisplays: JSONArray = JSONArray(),
    )

    companion object {
        private const val MAX_TARGETS_TOTAL = 400
        private const val WINDOW_REFRESH_MIN_GAP_MS = 50L
    }
}
