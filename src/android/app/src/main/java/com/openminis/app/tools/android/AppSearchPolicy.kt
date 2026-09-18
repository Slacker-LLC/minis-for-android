package com.openminis.app.tools.android

/**
 * [T-eta-app-search] Pure rules for the `android_app search` action, ported from Eta's
 * `search_apps` (`agent/model/AgentDeviceToolCatalog.kt` / `agent/tool/AgentLocalTools.kt`,
 * Mangi-11/Eta @ c15de97 — attribution in THIRD_PARTY_LICENSES.md).
 *
 * The list itself comes from the launcher activities Android lets this app see; this
 * object only decides what matches, in which order, and how many rows come back.
 */
object AppSearchPolicy {

    const val DEFAULT_LIMIT = 20
    const val MAX_LIMIT = 50

    data class Entry(val packageName: String, val label: String, val activity: String)

    fun clampLimit(raw: Int?): Int = (raw ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

    /** A query matches the display label or the package name, case-insensitively. */
    fun matches(entry: Entry, query: String?): Boolean {
        val needle = query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return true
        return entry.label.lowercase().contains(needle) || entry.packageName.lowercase().contains(needle)
    }

    /** Label order, package as the tie-break, capped — the same order every call sees. */
    fun rank(entries: List<Entry>, query: String?, limit: Int): List<Entry> = entries
        .filter { matches(it, query) }
        .sortedWith(compareBy({ it.label.lowercase() }, { it.packageName }, { it.activity }))
        .take(limit)
}
