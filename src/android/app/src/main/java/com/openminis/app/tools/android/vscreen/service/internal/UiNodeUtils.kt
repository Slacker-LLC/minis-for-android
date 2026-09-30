/*
 * Adapted for Minis from ShadowAuto (`android-notes/ShadowAuto`, commit `611e5eb0e1c94befda3c17c97438912dfd30e8a8`); modified.
 * Original source: android-shell/src/main/java/com/silentauto/shell/UiNodeUtils.java.
 * Licensed under Apache-2.0; see third_party/shadowauto/LICENSE.
 */
package com.openminis.app.tools.android.vscreen.service.internal

import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.openminis.app.tools.android.UiSensitiveValuePolicy
import org.json.JSONArray
import org.json.JSONObject

internal data class UiNodeCollection(
    val root: JSONObject?,
    val targets: JSONArray,
    val inputs: JSONArray,
    val truncated: Boolean,
)

internal object UiNodeUtils {
    private const val MAX_NODES = 180
    private const val MAX_TEXT = 128

    fun collect(window: AccessibilityWindowInfo, mode: String, windowIndex: Int): UiNodeCollection {
        val root = runCatching { window.root }.getOrNull() ?: return UiNodeCollection(null, JSONArray(), JSONArray(), false)
        val state = State(mode.equals("FULL", ignoreCase = true))
        val json = visit(root, "w$windowIndex", state)
        return UiNodeCollection(json, state.targets, state.inputs, state.truncated)
    }

    fun shiftTargetIndices(root: JSONObject?, offset: Int) {
        if (root == null || offset == 0) return
        val target = root.optInt("targetIndex", 0)
        if (target > 0) root.put("targetIndex", target + offset)
        val children = root.optJSONArray("children") ?: return
        for (index in 0 until children.length()) shiftTargetIndices(children.optJSONObject(index), offset)
    }

    fun clickTarget(root: AccessibilityNodeInfo?, targetIndex: Int): Boolean {
        if (root == null || targetIndex < 1) return false
        val state = TargetSearch(targetIndex)
        findTarget(root, state)
        val node = state.node ?: return false
        return runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }.getOrDefault(false)
    }

    fun setText(root: AccessibilityNodeInfo?, text: String): Boolean {
        val node = findEditable(root, requireFocus = true) ?: findEditable(root, requireFocus = false) ?: return false
        return setTextOnNode(node, text)
    }

    fun setTextTarget(root: AccessibilityNodeInfo?, targetIndex: Int, text: String): Boolean {
        if (root == null || targetIndex < 1) return false
        val state = TargetSearch(targetIndex)
        findTarget(root, state)
        val node = state.node ?: return false
        val editable = safe { node.isEditable } || safeText { node.className }.contains("EditText", true)
        return editable && setTextOnNode(node, text)
    }

    fun focusTarget(root: AccessibilityNodeInfo?, targetIndex: Int): Boolean {
        if (root == null || targetIndex < 1) return false
        val state = TargetSearch(targetIndex)
        findTarget(root, state)
        val node = state.node ?: return false
        val editable = safe { node.isEditable } || safeText { node.className }.contains("EditText", true)
        return editable && runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }.getOrDefault(false)
    }

    private fun setTextOnNode(node: AccessibilityNodeInfo, text: String): Boolean {
        return runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }.getOrDefault(false)
    }

    fun containsPackage(root: AccessibilityNodeInfo?, packageName: String): Boolean {
        if (root == null) return false
        if (runCatching { root.packageName?.toString() == packageName }.getOrDefault(false)) return true
        for (index in 0 until safeInt { root.childCount }.coerceIn(0, MAX_NODES)) {
            if (containsPackage(safeNode { root.getChild(index) }, packageName)) return true
        }
        return false
    }

    private fun visit(node: AccessibilityNodeInfo?, path: String, state: State): JSONObject? {
        if (node == null) return null
        if (state.count >= MAX_NODES) {
            state.truncated = true
            return null
        }
        state.count++
        val password = safe { node.isPassword }
        val text = UiSensitiveValuePolicy.redactAccessibilityValue(password, safeText { node.text })
        val description = UiSensitiveValuePolicy.redactAccessibilityValue(password, safeText { node.contentDescription })
        val hint = UiSensitiveValuePolicy.redactAccessibilityValue(password, safeText { node.hintText })
        val viewId = safeText { node.viewIdResourceName }
        val className = safeText { node.className }
        val packageName = safeText { node.packageName }
        val editable = safe { node.isEditable } || className.contains("EditText", true)
        val visible = safe { node.isVisibleToUser }
        val enabled = safe { node.isEnabled }
        val clickable = safe { node.isClickable } || (safeInt { node.actions } and AccessibilityNodeInfo.ACTION_CLICK) != 0
        val longClickable = safe { node.isLongClickable }
        val useful = text.isNotBlank() || description.isNotBlank() || hint.isNotBlank() || viewId.isNotBlank() ||
            editable || clickable || longClickable || safe { node.isFocused } || safe { node.isSelected } || safe { node.isChecked }
        val targetIndex = if (visible && enabled && (clickable || longClickable || editable)) state.targets.length() + 1 else 0
        val bounds = Rect().also { runCatching { node.getBoundsInScreen(it) } }
        if (targetIndex > 0) {
            state.targets.put(JSONObject().put("index", targetIndex).put("path", path)
                .put("label", (text.ifBlank { description.ifBlank { hint } }).take(MAX_TEXT))
                .put("packageName", packageName.take(MAX_TEXT))
                .put("bounds", boundsValue(bounds)).put("actions", targetActions(clickable, longClickable, editable)))
        }
        if (visible && enabled && editable) {
            state.inputs.put(JSONObject().put("path", path).put("text", text.take(MAX_TEXT))
                .put("hint", hint.take(MAX_TEXT)).put("viewId", viewId.take(MAX_TEXT)).put("bounds", boundsValue(bounds)))
        }

        val children = JSONArray()
        for (index in 0 until safeInt { node.childCount }.coerceIn(0, MAX_NODES)) {
            val child = visit(safeNode { node.getChild(index) }, "$path.$index", state)
            if (child != null) children.put(child)
        }
        if (!state.full && !useful && children.length() == 0) return null
        return JSONObject()
            .put("path", path)
            .put("targetIndex", targetIndex)
            .put("text", text.take(MAX_TEXT))
            .put("contentDescription", description.take(MAX_TEXT))
            .put("hint", hint.take(MAX_TEXT))
            .put("viewId", viewId.take(MAX_TEXT))
            .put("className", className.take(MAX_TEXT))
            .put("bounds", boundsValue(bounds))
            .put("visible", visible)
            .put("enabled", enabled)
            .put("clickable", clickable)
            .put("longClickable", longClickable)
            .put("editable", editable)
            .put("password", password)
            .put("focused", safe { node.isFocused })
            .put("selected", safe { node.isSelected })
            .put("checked", safe { node.isChecked })
            .put("children", children)
    }

    private fun findTarget(node: AccessibilityNodeInfo?, state: TargetSearch) {
        if (node == null || state.node != null) return
        val visible = safe { node.isVisibleToUser }
        val enabled = safe { node.isEnabled }
        val editable = safe { node.isEditable } || safeText { node.className }.contains("EditText", true)
        val clickable = safe { node.isClickable } || (safeInt { node.actions } and AccessibilityNodeInfo.ACTION_CLICK) != 0
        val longClickable = safe { node.isLongClickable }
        if (visible && enabled && (clickable || longClickable || editable)) {
            state.seen++
            if (state.seen == state.wanted) {
                state.node = node
                return
            }
        }
        for (index in 0 until safeInt { node.childCount }.coerceIn(0, MAX_NODES)) {
            findTarget(safeNode { node.getChild(index) }, state)
            if (state.node != null) return
        }
    }

    private fun findEditable(node: AccessibilityNodeInfo?, requireFocus: Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        val editable = safe { node.isEditable } || safeText { node.className }.contains("EditText", true)
        if (editable && (!requireFocus || safe { node.isFocused })) return node
        for (index in 0 until safeInt { node.childCount }.coerceIn(0, MAX_NODES)) {
            val result = findEditable(safeNode { node.getChild(index) }, requireFocus)
            if (result != null) return result
        }
        return null
    }

    private fun targetActions(clickable: Boolean, longClickable: Boolean, editable: Boolean): JSONArray = JSONArray().apply {
        if (clickable) put("click")
        if (longClickable) put("long_click")
        if (editable) put("input")
    }

    private fun boundsValue(rect: Rect): String = "${rect.left},${rect.top},${rect.right},${rect.bottom}"
    private inline fun safe(block: () -> Boolean): Boolean = runCatching(block).getOrDefault(false)
    private inline fun safeInt(block: () -> Int): Int = runCatching(block).getOrDefault(0)
    private inline fun safeText(block: () -> CharSequence?): String = runCatching { block()?.toString().orEmpty() }.getOrDefault("")
    private inline fun safeNode(block: () -> AccessibilityNodeInfo?): AccessibilityNodeInfo? = runCatching(block).getOrNull()

    private class State(val full: Boolean) {
        var count = 0
        var truncated = false
        val targets = JSONArray()
        val inputs = JSONArray()
    }
    private class TargetSearch(val wanted: Int) {
        var seen = 0
        var node: AccessibilityNodeInfo? = null
    }
}
