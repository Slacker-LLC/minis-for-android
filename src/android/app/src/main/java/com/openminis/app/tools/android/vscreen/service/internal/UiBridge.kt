/*
 * Adapted for Minis from ShadowAuto (`android-notes/ShadowAuto`, commit `611e5eb0e1c94befda3c17c97438912dfd30e8a8`); modified.
 * Original source: android-shell/src/main/java/com/silentauto/shell/UiBridge.java.
 * Licensed under Apache-2.0; see third_party/shadowauto/LICENSE.
 */
package com.openminis.app.tools.android.vscreen.service.internal

import android.annotation.SuppressLint
import android.app.UiAutomation
import android.os.HandlerThread
import android.util.SparseArray
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.openminis.app.tools.android.vscreen.VirtualScreenPolicy
import org.json.JSONArray
import org.json.JSONObject

internal class UiBridge {
    @Volatile private var automation: UiAutomation? = null
    private var thread: HandlerThread? = null

    /** This reflection executes only in Shizuku's separate shell UserService process. */
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
            val connect = runCatching { UiAutomation::class.java.getDeclaredMethod("connect") }
                .getOrElse { UiAutomation::class.java.getDeclaredMethod("connect", Int::class.javaPrimitiveType) }
                .apply { isAccessible = true }
            if (connect.parameterTypes.isEmpty()) connect.invoke(instance) else connect.invoke(instance, 0)
            runCatching {
                val info = instance.serviceInfo
                info.flags = info.flags or
                    android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                instance.serviceInfo = info
            }
            thread = worker
            automation = instance
            true
        } catch (error: Throwable) {
            worker.quitSafely()
            throw IllegalStateException("uiautomation_connect_failed", error)
        }
    }

    fun isConnected(): Boolean = automation != null

    fun dump(displayId: Int, mode: String): String {
        val ui = automation ?: throw IllegalStateException("uiautomation_not_connected")
        if (displayId == 0) throw IllegalArgumentException(VirtualScreenPolicy.PHYSICAL_DISPLAY_REFUSED)
        val result = windowsOnDisplay(ui, displayId)
        val windowArray = JSONArray()
        val targets = JSONArray()
        val inputs = JSONArray()
        val out = JSONObject()
            .put("displayId", displayId)
            .put("mode", if (mode.equals("FULL", true)) "FULL" else "SIMPLE")
            .put("coordinateSpace", "display-local")
            .put("windowSource", result.source)
            .put("windowError", result.error)
            .put("availableDisplays", result.availableDisplays)
        var truncated = false

        for ((windowIndex, window) in result.values.withIndex()) {
            val collected = UiNodeUtils.collect(window, mode, windowIndex)
            val root = collected.root ?: continue
            val targetOffset = targets.length()
            val inputOffset = inputs.length()
            UiNodeUtils.shiftTargetIndices(root, targetOffset)
            val entry = JSONObject().put("windowIndex", windowIndex)
                .put("type", runCatching { window.type }.getOrDefault(-1))
                .put("title", runCatching { window.title?.toString().orEmpty().take(128) }.getOrDefault(""))
                .put("truncated", collected.truncated).put("root", root)
            windowArray.put(entry)
            appendTargets(targets, collected.targets, targetOffset)
            appendArray(inputs, collected.inputs)
            if (collected.truncated) truncated = true
            out.put("windows", windowArray).put("targets", targets).put("inputs", inputs)
            if (out.toString().toByteArray(Charsets.UTF_8).size > VirtualScreenPolicy.MAX_DUMP_BYTES) {
                windowArray.remove(windowArray.length() - 1)
                trimArray(targets, targetOffset)
                trimArray(inputs, inputOffset)
                truncated = true
                break
            }
        }
        out.put("windows", windowArray).put("targets", targets).put("inputs", inputs)
        if (truncated) out.put("truncated", true)
        return VirtualScreenPolicy.fitDumpJson(out.toString())
    }

    fun clickTarget(displayId: Int, targetIndex: Int): Boolean {
        if (displayId == 0) throw IllegalArgumentException(VirtualScreenPolicy.PHYSICAL_DISPLAY_REFUSED)
        if (targetIndex < 1) return false
        val windows = windowsOnDisplay(automation ?: return false, displayId).values
        var remaining = targetIndex
        for (window in windows) {
            val root = runCatching { window.root }.getOrNull() ?: continue
            val count = countTargets(root)
            if (remaining <= count) return UiNodeUtils.clickTarget(root, remaining)
            remaining -= count
        }
        return false
    }

    fun setText(displayId: Int, text: String): Boolean {
        if (displayId == 0) throw IllegalArgumentException(VirtualScreenPolicy.PHYSICAL_DISPLAY_REFUSED)
        for (window in windowsOnDisplay(automation ?: return false, displayId).values) {
            if (UiNodeUtils.setText(runCatching { window.root }.getOrNull(), text)) return true
        }
        return false
    }

    fun hasWindowOnDisplay(displayId: Int, packageName: String): Boolean {
        if (displayId == 0) return false
        return windowsOnDisplay(automation ?: return false, displayId).values.any { window ->
            UiNodeUtils.containsPackage(runCatching { window.root }.getOrNull(), packageName)
        }
    }

    /** This reflection executes only in Shizuku's separate shell UserService process. */
    @SuppressLint("SoonBlockedPrivateApi")
    @Synchronized
    fun disconnect() {
        val old = automation
        automation = null
        runCatching { UiAutomation::class.java.getDeclaredMethod("disconnect").apply { isAccessible = true }.invoke(old) }
        thread?.quitSafely()
        thread = null
    }

    private fun appendTargets(destination: JSONArray, source: JSONArray, offset: Int) {
        for (index in 0 until source.length()) {
            val item = source.optJSONObject(index) ?: continue
            item.put("index", item.optInt("index", 0) + offset)
            destination.put(item)
        }
    }

    private fun appendArray(destination: JSONArray, source: JSONArray) {
        for (index in 0 until source.length()) destination.put(source.opt(index))
    }

    private fun trimArray(array: JSONArray, keep: Int) {
        while (array.length() > keep) array.remove(array.length() - 1)
    }

    private fun countTargets(root: AccessibilityNodeInfo?): Int = countTargets(root, intArrayOf(0))

    private fun countTargets(node: AccessibilityNodeInfo?, visited: IntArray): Int {
        if (node == null || visited[0] >= MAX_NODES) return 0
        visited[0]++
        val visible = runCatching { node.isVisibleToUser && node.isEnabled }.getOrDefault(false)
        val editable = runCatching { node.isEditable || node.className?.toString()?.contains("EditText", true) == true }
            .getOrDefault(false)
        val clickable = runCatching { node.isClickable || (node.actions and AccessibilityNodeInfo.ACTION_CLICK) != 0 }
            .getOrDefault(false)
        val longClickable = runCatching { node.isLongClickable }.getOrDefault(false)
        var total = if (visible && (editable || clickable || longClickable)) 1 else 0
        val childCount = runCatching { node.childCount }.getOrDefault(0).coerceIn(0, MAX_NODES)
        for (index in 0 until childCount) {
            if (visited[0] >= MAX_NODES) break
            total += countTargets(runCatching { node.getChild(index) }.getOrNull(), visited)
        }
        return total
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
        private const val MAX_NODES = 180
    }
}
