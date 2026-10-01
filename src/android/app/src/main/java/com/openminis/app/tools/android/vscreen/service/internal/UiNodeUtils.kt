/*
 * Adapted for Minis from ShadowAuto (`android-notes/ShadowAuto`, commit `611e5eb0e1c94befda3c17c97438912dfd30e8a8`); modified.
 * Original source: android-shell/src/main/java/com/silentauto/shell/UiNodeUtils.java.
 * Licensed under Apache-2.0; see third_party/shadowauto/LICENSE.
 */
package com.openminis.app.tools.android.vscreen.service.internal

import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.openminis.app.accessibility.AccessibilityNodeIdentity
import com.openminis.app.tools.android.UiRefResolutionPolicy
import com.openminis.app.tools.android.UiGenerationFence
import com.openminis.app.tools.android.UiSensitiveValuePolicy
import org.json.JSONArray
import org.json.JSONObject

/**
 * One window's worth of observation.
 *
 * Scanning and output are separate budgets on purpose: the scan decides how much of the tree was
 * READ (and therefore whether a ref can be trusted), the output caps decide how much is REPORTED
 * (and only mean "the model was not told about every candidate"). The previous single 180-node
 * limit conflated the two and disabled ref actions on any moderately busy screen.
 */
internal data class UiNodeCollection(
    /** Full node tree; only built in FULL mode. */
    val root: JSONObject?,
    val targets: JSONArray,
    val inputs: JSONArray,
    val texts: JSONArray,
    val scanned: Int,
    val scanTruncated: Boolean,
    val outputTruncated: Boolean,
    /** Package of the window's root node; lets a caller test "is this app still on the display" without another walk. */
    val rootPackage: String = "",
)

/** What the app remembers about a target, sent back down so the service can find it again. */
internal data class LocatorSpec(
    val path: String,
    val identity: AccessibilityNodeIdentity,
    val bounds: String,
)

internal sealed interface Located {
    data class Hit(val node: AccessibilityNodeInfo, val by: String) : Located
    data class Miss(val code: String) : Located
}

internal object UiNodeUtils {
    /** Hard cap on nodes READ per dump, across all windows. */
    const val SCAN_LIMIT = 2_000
    private const val MAX_TEXT = 128
    private const val MAX_TARGETS_PER_WINDOW = 400
    private const val MAX_INPUTS_PER_WINDOW = 60
    private const val MAX_TEXTS_PER_WINDOW = 300

    fun collect(window: AccessibilityWindowInfo, full: Boolean, windowIndex: Int, scanBudget: Int): UiNodeCollection {
        val root = runCatching { window.root }.getOrNull()
            ?: return UiNodeCollection(null, JSONArray(), JSONArray(), JSONArray(), 0, false, false)
        val state = State(full, scanBudget)
        val rootPackage = safeText { root.packageName }.take(MAX_TEXT)
        val json = visit(root, "w$windowIndex", state, depth = 0)
        return UiNodeCollection(
            json, state.targets, state.inputs, state.texts, state.count,
            state.scanTruncated, state.outputTruncated, rootPackage,
        )
    }

    /** FULL-mode trees carry the target index; with several windows it must be made globally unique. */
    fun shiftTargetIndices(root: JSONObject?, offset: Int) {
        if (root == null || offset == 0) return
        val target = root.optInt("targetIndex", 0)
        if (target > 0) root.put("targetIndex", target + offset)
        val children = root.optJSONArray("children") ?: return
        for (index in 0 until children.length()) shiftTargetIndices(children.optJSONObject(index), offset)
    }

    // ---- locating a node again from what an earlier observation reported ----

    /**
     * Finds the node again without any whole-screen comparison: walk the observed path, accept it
     * only if the node still carries the observed identity, otherwise accept a single provably
     * unique identity match elsewhere. Decided by [UiRefResolutionPolicy], shared with the
     * physical-screen path so the two cannot drift apart.
     */
    fun locate(windows: List<AccessibilityWindowInfo>, spec: LocatorSpec, snapshotTruncated: Boolean): Located {
        val parsed = parsePath(spec.path) ?: return Located.Miss("invalid_locator")
        val (windowIndex, childPath) = parsed
        val window = windows.firstOrNull { safeInt { it.id } == spec.identity.windowId } ?: windows.getOrNull(windowIndex)
        var node: AccessibilityNodeInfo? = window?.let { runCatching { it.root }.getOrNull() }
        for (index in childPath) node = node?.let { safeNode { it.getChild(index) } }
        val wanted = spec.identity
        val pathMatches = node != null && wanted.matches(identityOf(node))
        val samePosition = pathMatches && boundsValue(node!!) == spec.bounds
        var candidates: List<AccessibilityNodeInfo> = emptyList()
        val decision = UiRefResolutionPolicy.decide(
            // This service never sees the observed screen, so it can never claim "unchanged":
            // a node must prove itself by identity (or identity-free: exact position).
            freshness = UiGenerationFence.Freshness.CONTENT_CHANGED,
            pathIdentityMatches = pathMatches,
            identityStrong = wanted.strong,
            hasUniqueId = wanted.uniqueId.isNotBlank(),
            snapshotTruncated = snapshotTruncated,
            trustPathUnderTruncation = true,
            pathBackedByPosition = samePosition,
            candidateCount = {
                candidates = matchAll(windows, wanted)
                candidates.size
            },
        )
        return when (decision) {
            is UiRefResolutionPolicy.Decision.Use ->
                if (decision.basis == UiRefResolutionPolicy.Basis.PATH) Located.Hit(node!!, "path")
                else Located.Hit(candidates.single(), "unique_identity")
            is UiRefResolutionPolicy.Decision.Refuse -> Located.Miss("stale_target")
        }
    }

    private fun matchAll(windows: List<AccessibilityWindowInfo>, wanted: AccessibilityNodeIdentity): List<AccessibilityNodeInfo> {
        val found = ArrayList<AccessibilityNodeInfo>(2)
        var visited = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        for (window in windows) runCatching { window.root }.getOrNull()?.let(queue::addLast)
        while (queue.isNotEmpty() && visited < SCAN_LIMIT) {
            val node = queue.removeFirst()
            visited++
            // Same pruning as the scan: nothing under an invisible node can be a target.
            if (!safe { node.isVisibleToUser } && visited > windows.size) continue
            if (wanted.matches(identityOf(node))) {
                found.add(node)
                if (found.size > 1) return found
            }
            for (index in 0 until safeInt { node.childCount }) safeNode { node.getChild(index) }?.let(queue::addLast)
        }
        return found
    }

    /** The identity exactly as the dump reports it (redacted, length-capped), so both sides compare like with like. */
    fun identityOf(node: AccessibilityNodeInfo): AccessibilityNodeIdentity {
        val password = safe { node.isPassword }
        return AccessibilityNodeIdentity(
            uniqueId = uniqueIdOf(node),
            windowId = safeInt { node.windowId },
            packageName = safeText { node.packageName }.take(MAX_TEXT),
            className = safeText { node.className }.take(MAX_TEXT),
            viewId = safeText { node.viewIdResourceName }.take(MAX_TEXT),
            text = UiSensitiveValuePolicy.redactAccessibilityValue(password, safeText { node.text }).take(MAX_TEXT),
            description = UiSensitiveValuePolicy.redactAccessibilityValue(password, safeText { node.contentDescription }).take(MAX_TEXT),
            password = password,
        )
    }

    private fun uniqueIdOf(node: AccessibilityNodeInfo): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) safeText { node.uniqueId } else ""

    /** "w2.0.5.1" -> window index 2, child path [0, 5, 1]. */
    fun parsePath(path: String): Pair<Int, List<Int>>? {
        val parts = path.split('.')
        if (parts.isEmpty() || parts.size > 64) return null
        val head = parts[0]
        if (head.length < 2 || head[0] != 'w') return null
        val windowIndex = head.substring(1).toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val children = parts.drop(1).map { it.toIntOrNull()?.takeIf { value -> value >= 0 } ?: return null }
        return windowIndex to children
    }

    // ---- node actions (every one of them works on a node that [locate] already vetted) ----

    fun click(node: AccessibilityNodeInfo): Boolean = runCatching {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }.getOrDefault(false)

    fun longClick(node: AccessibilityNodeInfo): Boolean =
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) }.getOrDefault(false)

    fun focus(node: AccessibilityNodeInfo): Boolean =
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }.getOrDefault(false)

    fun isEditable(node: AccessibilityNodeInfo): Boolean =
        safe { node.isEditable } || safeText { node.className }.contains("EditText", true)

    fun setTextOnNode(node: AccessibilityNodeInfo, text: String): Boolean = runCatching {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }.getOrDefault(false)

    /**
     * Inserts [text] at the field's own selection through the node, for characters that cannot be
     * typed as key events (CJK and most non-ASCII). A password field never hands over its value,
     * so it cannot be reconstructed and is refused rather than overwritten.
     */
    fun insertAtSelection(node: AccessibilityNodeInfo, text: String): Boolean {
        if (safe { node.isPassword }) return false
        return runCatching {
            node.refresh()
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val showingHint = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && safe { node.isShowingHintText }
            val current = if (showingHint) "" else safeText { node.text }
            var start = safeInt { node.textSelectionStart }
            var end = safeInt { node.textSelectionEnd }
            if (start < 0 || end < 0 || start > current.length || end > current.length) {
                start = current.length
                end = current.length
            }
            val low = minOf(start, end)
            val high = maxOf(start, end)
            val updated = current.substring(0, low) + text + current.substring(high)
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated) }
            if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return@runCatching false
            val caret = low + text.length
            val selection = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, caret)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, caret)
            }
            runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection) }
            true
        }.getOrDefault(false)
    }

    /** Presses the field's own IME action (search / done / send). API 30+. */
    fun imeEnter(node: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return runCatching { node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) }.getOrDefault(false)
    }

    fun centerOf(node: AccessibilityNodeInfo): Pair<Int, Int>? {
        val rect = Rect().also { runCatching { node.getBoundsInScreen(it) } }
        if (rect.width() <= 0 || rect.height() <= 0) return null
        return rect.centerX() to rect.centerY()
    }

    fun setText(root: AccessibilityNodeInfo?, text: String): Boolean {
        val node = findEditable(root, requireFocus = true) ?: findEditable(root, requireFocus = false) ?: return false
        return setTextOnNode(node, text)
    }

    fun containsPackage(root: AccessibilityNodeInfo?, packageName: String): Boolean {
        if (root == null) return false
        if (runCatching { root.packageName?.toString() == packageName }.getOrDefault(false)) return true
        for (index in 0 until safeInt { root.childCount }.coerceIn(0, 180)) {
            if (containsPackage(safeNode { root.getChild(index) }, packageName)) return true
        }
        return false
    }

    /** First node holding input focus under [root], if any. */
    fun findInputFocus(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? =
        runCatching { root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()

    // ---- observation ----

    private fun visit(node: AccessibilityNodeInfo?, path: String, state: State, depth: Int): JSONObject? {
        if (node == null) return null
        if (state.count >= state.scanBudget) {
            state.scanTruncated = true
            return null
        }
        state.count++
        val password = safe { node.isPassword }
        val text = UiSensitiveValuePolicy.redactAccessibilityValue(password, safeText { node.text }).take(MAX_TEXT)
        val description = UiSensitiveValuePolicy.redactAccessibilityValue(password, safeText { node.contentDescription }).take(MAX_TEXT)
        val hint = UiSensitiveValuePolicy.redactAccessibilityValue(password, safeText { node.hintText }).take(MAX_TEXT)
        val viewId = safeText { node.viewIdResourceName }.take(MAX_TEXT)
        val className = safeText { node.className }.take(MAX_TEXT)
        val packageName = safeText { node.packageName }.take(MAX_TEXT)
        val editable = safe { node.isEditable } || className.contains("EditText", true)
        val visible = safe { node.isVisibleToUser }
        val enabled = safe { node.isEnabled }
        val clickable = safe { node.isClickable } || (safeInt { node.actions } and AccessibilityNodeInfo.ACTION_CLICK) != 0
        val longClickable = safe { node.isLongClickable }
        val isTarget = visible && enabled && (clickable || longClickable || editable)
        val bounds = Rect().also { runCatching { node.getBoundsInScreen(it) } }

        var targetIndex = 0
        if (isTarget) {
            if (state.targets.length() >= MAX_TARGETS_PER_WINDOW) {
                state.outputTruncated = true
            } else {
                targetIndex = state.targets.length() + 1
                val row = JSONObject().put("index", targetIndex).put("path", path)
                    .put("windowId", safeInt { node.windowId })
                    .put("label", text.ifBlank { description.ifBlank { hint } })
                    .put("text", text).put("desc", description)
                    .put("packageName", packageName).put("className", className).put("viewId", viewId)
                    .put("bounds", boundsValue(bounds)).put("actions", targetActions(clickable, longClickable, editable))
                val uniqueId = uniqueIdOf(node)
                if (uniqueId.isNotEmpty()) row.put("uid", uniqueId)
                if (password) row.put("password", true)
                state.targets.put(row)
            }
        } else if (visible && (text.isNotBlank() || description.isNotBlank())) {
            // Context the model needs to choose a target (headings, prices, unread counts) that is
            // not itself actionable. Reported flat, so no tree has to be sent.
            if (state.texts.length() >= MAX_TEXTS_PER_WINDOW) {
                state.outputTruncated = true
            } else {
                state.texts.put(JSONObject().put("text", text.ifBlank { description }).put("bounds", boundsValue(bounds)))
            }
        }
        if (visible && enabled && editable) {
            if (state.inputs.length() >= MAX_INPUTS_PER_WINDOW) state.outputTruncated = true else
                state.inputs.put(JSONObject().put("path", path).put("text", text).put("hint", hint)
                    .put("viewId", viewId).put("bounds", boundsValue(bounds)))
        }

        // Nothing under an invisible node can be a visible target, and every child of an off-screen
        // list item is one more Binder round trip. FULL mode keeps the exhaustive walk for debugging;
        // the window root is never pruned because some ROMs report it as not visible.
        val descend = state.full || depth == 0 || visible
        val children = if (state.full) JSONArray() else null
        if (descend) {
            for (index in 0 until safeInt { node.childCount }) {
                val child = visit(safeNode { node.getChild(index) }, "$path.$index", state, depth + 1)
                if (child != null) children?.put(child)
                if (state.scanTruncated) break
            }
        }
        if (!state.full) return null
        val useful = text.isNotBlank() || description.isNotBlank() || hint.isNotBlank() || viewId.isNotBlank() ||
            editable || clickable || longClickable || safe { node.isFocused } || safe { node.isSelected } || safe { node.isChecked }
        if (!useful && (children?.length() ?: 0) == 0) return null
        return JSONObject()
            .put("path", path)
            .put("targetIndex", targetIndex)
            .put("text", text).put("contentDescription", description).put("hint", hint)
            .put("viewId", viewId).put("className", className)
            .put("bounds", boundsValue(bounds))
            .put("visible", visible).put("enabled", enabled)
            .put("clickable", clickable).put("longClickable", longClickable).put("editable", editable)
            .put("password", password)
            .put("focused", safe { node.isFocused }).put("selected", safe { node.isSelected }).put("checked", safe { node.isChecked })
            .put("children", children)
    }

    private fun findEditable(node: AccessibilityNodeInfo?, requireFocus: Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (isEditable(node) && (!requireFocus || safe { node.isFocused })) return node
        for (index in 0 until safeInt { node.childCount }.coerceIn(0, 180)) {
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

    fun boundsValue(node: AccessibilityNodeInfo): String =
        boundsValue(Rect().also { runCatching { node.getBoundsInScreen(it) } })

    private fun boundsValue(rect: Rect): String = "${rect.left},${rect.top},${rect.right},${rect.bottom}"
    private inline fun safe(block: () -> Boolean): Boolean = runCatching(block).getOrDefault(false)
    private inline fun safeInt(block: () -> Int): Int = runCatching(block).getOrDefault(0)
    private inline fun safeText(block: () -> CharSequence?): String = runCatching { block()?.toString().orEmpty() }.getOrDefault("")
    private inline fun safeNode(block: () -> AccessibilityNodeInfo?): AccessibilityNodeInfo? = runCatching(block).getOrNull()

    private class State(val full: Boolean, val scanBudget: Int) {
        var count = 0
        var scanTruncated = false
        var outputTruncated = false
        val targets = JSONArray()
        val inputs = JSONArray()
        val texts = JSONArray()
    }
}
