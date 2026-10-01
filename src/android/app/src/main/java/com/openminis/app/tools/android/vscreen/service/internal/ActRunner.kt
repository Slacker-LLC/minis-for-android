package com.openminis.app.tools.android.vscreen.service.internal

import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.openminis.app.accessibility.AccessibilityNodeIdentity
import com.openminis.app.tools.android.vscreen.SettlePolicy
import com.openminis.app.tools.android.vscreen.SettleTracker
import com.openminis.app.tools.android.vscreen.SettledBy
import org.json.JSONObject

/**
 * One Binder call = locate the target, act on it, wait for the screen to react, and report the new
 * screen. This replaces the app-side sequence dump -> verify ref -> click -> fixed sleep ->
 * package check -> dump, which cost five Binder calls and several tree walks per action and left a
 * window between "verified" and "acted" in which the screen could change.
 *
 * Everything happens under the service lock held by the caller, so the node that was verified is the
 * node that is acted on.
 */
internal class ActRunner(
    private val ui: UiBridge,
    private val input: () -> InputBridge,
    private val settle: SettleTracker,
) {
    fun run(session: VirtualDisplaySession, request: JSONObject): JSONObject {
        val displayId = session.displayId
        val action = request.optString("action")
        val started = SystemClock.uptimeMillis()
        val result = JSONObject().put("action", action)
        val timing = JSONObject()
        ui.beginObserving(displayId)

        // ---- locate ----
        var node: AccessibilityNodeInfo? = null
        val locator = request.optJSONObject("locator")
        if (locator != null) {
            val spec = parseLocator(locator) ?: return finish(session, result, timing, started, request, "invalid_locator", "locator is malformed")
            when (val located = UiNodeUtils.locate(ui.windows(displayId), spec, request.optBoolean("scanTruncated", false))) {
                is Located.Hit -> {
                    node = located.node
                    result.put("resolvedBy", located.by)
                }
                is Located.Miss -> return finish(
                    session, result, timing, started, request, located.code,
                    "the target is no longer on the display; a fresh observation is attached",
                    resolveMs = SystemClock.uptimeMillis() - started,
                )
            }
        } else if (action in NEEDS_LOCATOR) {
            return finish(session, result, timing, started, request, "locator_required", "$action needs a ref")
        }
        val resolveMs = SystemClock.uptimeMillis() - started

        // ---- act ----
        val actionStart = SystemClock.uptimeMillis()
        settle.arm(actionStart)
        val outcome = try {
            perform(session, action, node, request)
        } catch (error: Throwable) {
            Outcome(false, "action_exception", (error.message ?: error.javaClass.simpleName).take(200))
        }
        val actionMs = SystemClock.uptimeMillis() - actionStart
        if (!outcome.ok) {
            return finish(session, result, timing, started, request, outcome.error ?: "action_rejected", outcome.detail ?: "the action was rejected", resolveMs, actionMs)
        }

        // ---- settle ----
        val settledBy = awaitSettle(SettlePolicy.paramsFor(action))
        val seen = settle.snapshot()
        val settleMs = SystemClock.uptimeMillis() - actionStart - actionMs
        result.put("ok", true).put("settledBy", settledBy.wire)
            .put("semanticSignal", seen.firstSemantic >= 0)
            .put("eventsEver", settle.semanticEventsEver())
        if (seen.firstSemantic >= 0) timing.put("firstChangeMs", seen.firstSemantic - seen.armedAt)
        return finish(session, result, timing, started, request, null, null, resolveMs, actionMs, settleMs)
    }

    private fun awaitSettle(params: com.openminis.app.tools.android.vscreen.SettleParams): SettledBy {
        while (true) {
            SettlePolicy.evaluate(SystemClock.uptimeMillis(), settle.snapshot(), params)?.let { return it }
            SystemClock.sleep(POLL_MS)
        }
    }

    private class Outcome(val ok: Boolean, val error: String? = null, val detail: String? = null)

    private fun perform(session: VirtualDisplaySession, action: String, node: AccessibilityNodeInfo?, request: JSONObject): Outcome {
        val displayId = session.displayId
        return when (action) {
            "click" -> {
                val target = node ?: return Outcome(false, "locator_required")
                if (!actionable(target)) return Outcome(false, "target_not_actionable", "the target is disabled or not visible")
                Outcome(UiNodeUtils.click(target))
            }
            "long_press" -> {
                val duration = request.optInt("durationMs", 700).coerceIn(300, 5_000)
                if (node != null) {
                    if (!actionable(node)) return Outcome(false, "target_not_actionable", "the target is disabled or not visible")
                    // Use the node's CURRENT centre, never the bounds seen in an older observation.
                    if (node.isLongClickable && UiNodeUtils.longClick(node)) return Outcome(true)
                    val centre = UiNodeUtils.centerOf(node) ?: return Outcome(false, "target_not_actionable", "the target has no on-screen bounds")
                    Outcome(input().longPress(displayId, centre.first, centre.second, duration))
                } else {
                    val (x, y) = point(session, request) ?: return Outcome(false, "coordinates_out_of_bounds")
                    Outcome(input().longPress(displayId, x, y, duration))
                }
            }
            "set_text" -> {
                val target = node ?: return Outcome(false, "locator_required")
                if (!UiNodeUtils.isEditable(target)) return Outcome(false, "target_not_editable")
                val text = request.optString("text")
                if (text.length > MAX_TEXT) return Outcome(false, "text_too_long")
                Outcome(UiNodeUtils.setTextOnNode(target, text))
            }
            "input_text" -> {
                val target = node ?: return Outcome(false, "locator_required")
                if (!UiNodeUtils.isEditable(target)) return Outcome(false, "target_not_editable")
                val text = request.optString("text")
                if (text.isEmpty() || text.length > MAX_TEXT) return Outcome(false, "invalid_input_text")
                if (!UiNodeUtils.focus(target)) return Outcome(false, "target_focus_failed")
                val bridge = input()
                if (bridge.canType(text)) {
                    Outcome(bridge.text(displayId, text))
                } else {
                    // CJK and other characters with no key mapping: KeyCharacterMap.getEvents returns null for
                    // them, so the key-event path could never type them. Write through the node instead.
                    val inserted = UiNodeUtils.insertAtSelection(target, text)
                    if (inserted) Outcome(true)
                    else Outcome(
                        false, "text_unavailable",
                        "this field does not expose its text/selection, so non-ASCII input cannot be inserted; use set_text with the full value",
                    )
                }
            }
            "ime_enter" -> {
                val target = node ?: ui.findInputFocus(displayId)
                // The field's own IME action when the platform offers it (API 30+); otherwise a real Enter,
                // which a single-line field turns into its IME action.
                if (target != null && UiNodeUtils.imeEnter(target)) Outcome(true)
                else Outcome(input().key(displayId, KeyEvent.KEYCODE_ENTER))
            }
            "tap" -> {
                val (x, y) = point(session, request) ?: return Outcome(false, "coordinates_out_of_bounds")
                Outcome(input().tap(displayId, x, y))
            }
            "swipe" -> {
                val start = Pair(request.optInt("startX", -1), request.optInt("startY", -1))
                val end = Pair(request.optInt("endX", -1), request.optInt("endY", -1))
                if (!inside(session, start.first, start.second) || !inside(session, end.first, end.second)) {
                    return Outcome(false, "coordinates_out_of_bounds")
                }
                Outcome(input().swipe(displayId, start.first, start.second, end.first, end.second, request.optInt("durationMs", 300)))
            }
            "key" -> {
                val code = request.optInt("keyCode", -1)
                if (code !in 0..KeyEvent.getMaxKeyCode()) return Outcome(false, "invalid_key_code")
                Outcome(input().key(displayId, code))
            }
            "back" -> Outcome(input().key(displayId, KeyEvent.KEYCODE_BACK))
            "home" -> Outcome(input().key(displayId, KeyEvent.KEYCODE_HOME))
            else -> Outcome(false, "unknown_act_action", action.take(32))
        }
    }

    private fun actionable(node: AccessibilityNodeInfo): Boolean =
        runCatching { node.isEnabled && node.isVisibleToUser }.getOrDefault(false)

    private fun point(session: VirtualDisplaySession, request: JSONObject): Pair<Int, Int>? {
        val x = request.optInt("x", -1)
        val y = request.optInt("y", -1)
        return if (inside(session, x, y)) x to y else null
    }

    private fun inside(session: VirtualDisplaySession, x: Int, y: Int) = x in 0 until session.width && y in 0 until session.height

    private fun parseLocator(json: JSONObject): LocatorSpec? {
        val path = json.optString("path")
        if (path.isEmpty() || path.length > 256) return null
        val identity = AccessibilityNodeIdentity(
            uniqueId = json.optString("uid"),
            windowId = json.optInt("windowId", -1),
            packageName = json.optString("packageName"),
            className = json.optString("className"),
            viewId = json.optString("viewId"),
            text = json.optString("text"),
            description = json.optString("desc"),
            password = json.optBoolean("password", false),
        )
        return LocatorSpec(path, identity, json.optString("bounds"))
    }

    /** Adds the fresh observation and timing, whether the action worked or not. */
    private fun finish(
        session: VirtualDisplaySession,
        result: JSONObject,
        timing: JSONObject,
        started: Long,
        request: JSONObject,
        error: String?,
        detail: String?,
        resolveMs: Long = 0,
        actionMs: Long = 0,
        settleMs: Long = 0,
    ): JSONObject {
        if (error != null) result.put("ok", false).put("error", error).put("detail", detail.orEmpty())
        val dumpStart = SystemClock.uptimeMillis()
        val observation = runCatching {
            ui.dumpObject(session.displayId, "SIMPLE", session.width, session.height, session.rotation())
        }.getOrNull()
        val now = SystemClock.uptimeMillis()
        if (observation != null) {
            result.put("observation", observation)
            val wanted = request.optString("package")
            if (wanted.isNotBlank()) {
                val windows = observation.optJSONArray("windows")
                val present = windows != null && (0 until windows.length()).any { windows.optJSONObject(it)?.optString("package") == wanted }
                result.put("packagePresent", present)
            }
        }
        timing.put("resolveMs", resolveMs).put("actionMs", actionMs).put("settleMs", settleMs)
            .put("dumpMs", now - dumpStart).put("totalMs", now - started)
        return result.put("timing", timing)
    }

    companion object {
        private val NEEDS_LOCATOR = setOf("click", "set_text", "input_text")
        private const val POLL_MS = 10L
        private const val MAX_TEXT = 4096
    }
}
