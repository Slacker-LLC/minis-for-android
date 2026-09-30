package com.openminis.app.ui.browser

import com.openminis.app.browser.BrowserTabPool

/**
 * Hand-off for the browser page. The tab pool belongs to whichever screen opened it (a chat, the
 * session list, the assistant home), so the caller parks it here and asks the navigation host to
 * push the browser route; the route reads it back. Same pattern as the file preview's holder.
 */
object BrowserHolder {
    @Volatile var tabPool: BrowserTabPool? = null

    /** Set by the navigation host; pushes the browser page, or its settings page when `true`. */
    @Volatile var opener: ((settings: Boolean) -> Unit)? = null

    /** Open the browser page (or its settings page) on [pool]. False when no navigation host is installed. */
    fun open(pool: BrowserTabPool, settings: Boolean = false): Boolean {
        val open = opener ?: return false
        tabPool = pool
        open(settings)
        return true
    }
}
