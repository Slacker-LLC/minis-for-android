package com.openminis.app.browser

/**
 * Ported from Eta `AgentBrowserSession` (Mangi-11/Eta @ c15de97), which exposes
 * `go_back` / `go_forward` / `reload` as agent actions through
 * `historyNavigation` / `reload`. This app's schema had none of them, so an agent
 * that followed a link either re-navigated to the URL it remembered or lost its
 * place; the tab's own back/forward state was reachable only from the UI.
 *
 * The refusals matter as much as the moves: a tab with nothing to go back to used
 * to be a silent no-op in our code (`if (canGoBack()) goBack()`), which reads to the
 * model as "the page changed". The headline lines are this repository's; upstream
 * answers with a JSON envelope.
 */
internal object BrowserHistoryPolicy {

    fun moved(backwards: Boolean): String =
        if (backwards) "Went back to the previous page." else "Went forward to the next page."

    fun reloaded(): String = "Reloaded the page."

    fun unavailable(backwards: Boolean): String =
        if (backwards) {
            "Cannot go back: this tab has no earlier page in its history."
        } else {
            "Cannot go forward: this tab has no later page in its history."
        }
}
