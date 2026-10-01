package com.openminis.app.tools.android.vscreen

/** How long the service waits for the screen to react to an action, in uptime milliseconds. */
internal data class SettleParams(val graceMs: Long, val quietMs: Long, val deadlineMs: Long)

internal enum class SettledBy(val wire: String) {
    /** A semantic change was seen and then nothing moved for [SettleParams.quietMs]. */
    QUIET("quiet"),

    /** No semantic change arrived within the grace window: the action probably had no visible effect. */
    GRACE_EXPIRED("grace_expired"),

    /** The screen kept changing until the hard limit. */
    DEADLINE("deadline"),
}

/** What the tracker has seen since [armedAt]; all times are uptime ms, -1 = never. */
internal data class SettleSnapshot(
    val armedAt: Long,
    val firstSemantic: Long,
    val lastSemantic: Long,
    val lastFrame: Long,
)

/**
 * Event-driven replacement for the fixed 250 ms sleep after every action.
 *
 * Two signals, with different jobs:
 *  - a *semantic* accessibility event on the virtual display's windows STARTS the "something
 *    changed" clock (a press ripple or a focus ring must not: those fire on a click that did
 *    nothing, and would end the wait before the real page transition lands);
 *  - frames from the virtual display only EXTEND the quiet window (animations).
 *
 * No semantic event within the action's grace window means "no visible effect yet", reported as
 * [SettledBy.GRACE_EXPIRED] so the caller can tell the model to wait rather than pretend.
 */
internal object SettlePolicy {
    // Literal values of AccessibilityEvent.TYPE_*; kept as literals so this file stays JVM-testable.
    private const val TYPE_VIEW_TEXT_CHANGED = 0x10
    private const val TYPE_WINDOW_STATE_CHANGED = 0x20
    private const val TYPE_WINDOW_CONTENT_CHANGED = 0x800
    private const val TYPE_VIEW_SCROLLED = 0x1000
    private const val TYPE_WINDOWS_CHANGED = 0x400000

    /** Click / focus / hover feedback is deliberately NOT semantic. */
    fun isSemantic(eventType: Int): Boolean = when (eventType) {
        TYPE_VIEW_TEXT_CHANGED, TYPE_WINDOW_STATE_CHANGED, TYPE_WINDOW_CONTENT_CHANGED,
        TYPE_VIEW_SCROLLED, TYPE_WINDOWS_CHANGED -> true
        else -> false
    }

    /** Window-set events can come from a window that did not exist when the display was last listed. */
    fun mayIntroduceUnknownWindow(eventType: Int): Boolean =
        eventType == TYPE_WINDOW_STATE_CHANGED || eventType == TYPE_WINDOWS_CHANGED

    const val HARD_LIMIT_MS = 1_500L

    fun paramsFor(action: String): SettleParams = when (action) {
        "set_text", "input_text" -> SettleParams(graceMs = 150, quietMs = 80, deadlineMs = 800)
        "scroll", "swipe" -> SettleParams(graceMs = 250, quietMs = 100, deadlineMs = 1_000)
        // Anything that can navigate: give a cold Activity time to produce its first event.
        "back", "home", "key", "ime_enter", "launch" -> SettleParams(graceMs = 700, quietMs = 120, deadlineMs = HARD_LIMIT_MS)
        else -> SettleParams(graceMs = 400, quietMs = 100, deadlineMs = HARD_LIMIT_MS)
    }

    /** Null means "keep waiting". */
    fun evaluate(now: Long, seen: SettleSnapshot, params: SettleParams): SettledBy? {
        val elapsed = now - seen.armedAt
        if (elapsed >= params.deadlineMs) return SettledBy.DEADLINE
        if (seen.firstSemantic < 0) {
            return if (elapsed >= params.graceMs) SettledBy.GRACE_EXPIRED else null
        }
        val lastActivity = maxOf(seen.lastSemantic, seen.lastFrame)
        return if (now - lastActivity >= params.quietMs) SettledBy.QUIET else null
    }
}

/** Thread-safe collector of the two signals; [arm] discards everything older than the action. */
internal class SettleTracker {
    private val lock = Any()
    private var armedAt = 0L
    private var firstSemantic = -1L
    private var lastSemantic = -1L
    private var lastFrame = -1L
    private var semanticTotal = 0L

    fun arm(now: Long) = synchronized(lock) {
        armedAt = now
        firstSemantic = -1L
        lastSemantic = -1L
        lastFrame = -1L
    }

    fun onSemantic(now: Long) = synchronized(lock) {
        semanticTotal++
        if (now < armedAt) return@synchronized
        if (firstSemantic < 0) firstSemantic = now
        lastSemantic = now
    }

    fun onFrame(now: Long) = synchronized(lock) {
        if (now >= armedAt) lastFrame = now
    }

    fun snapshot(): SettleSnapshot = synchronized(lock) { SettleSnapshot(armedAt, firstSemantic, lastSemantic, lastFrame) }

    /** Lifetime count; 0 after several actions means the event stream never reached us. */
    fun semanticEventsEver(): Long = synchronized(lock) { semanticTotal }
}
