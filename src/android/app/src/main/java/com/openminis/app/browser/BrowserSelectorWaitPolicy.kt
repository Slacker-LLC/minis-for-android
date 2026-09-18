package com.openminis.app.browser

/**
 * Ported from Eta `AgentBrowserSession.waitForSelector` (Mangi-11/Eta @ c15de97): the
 * wait budget (default 5 s, clamped to 0.5–30 s), the 250 ms poll interval, and the
 * rule that the wait ends on a **visible** match — the page-side `selectorState`
 * skips matches the browser cannot render, so "found" never means "in the DOM but
 * unclickable".
 *
 * The outcome lines are this repository's: upstream answers with a JSON envelope,
 * our results are prose. Pure logic so the clamps and the wording are unit-tested.
 */
internal object BrowserSelectorWaitPolicy {
    const val DEFAULT_TIMEOUT_MS = 5_000
    const val MIN_TIMEOUT_MS = 500
    const val MAX_TIMEOUT_MS = 30_000
    const val POLL_INTERVAL_MS = 250L

    fun timeout(requested: Int?): Long =
        (requested?.toLong() ?: DEFAULT_TIMEOUT_MS.toLong())
            .coerceIn(MIN_TIMEOUT_MS.toLong(), MAX_TIMEOUT_MS.toLong())

    /**
     * [enabled] is the page-side accessibility check; a disabled control that the
     * caller is about to click is worth saying out loud, because the click will
     * land and do nothing.
     */
    fun found(selector: String, elapsedMs: Long, enabled: Boolean): String =
        "Selector $selector appeared after ${elapsedMs}ms (visible, " +
            (if (enabled) "enabled)." else "disabled).")

    fun notFound(selector: String, timeoutMs: Long): String =
        "Selector $selector did not appear within ${timeoutMs}ms (polled every " +
            "${POLL_INTERVAL_MS}ms)."
}
