package com.openminis.app.provider

import org.json.JSONObject

/**
 * [T-eta-hosted-web-search] Reading the events of the tools a provider runs itself.
 *
 * Ported from Eta `agent/model/OpenAiResponsesProvider.kt` (Mangi-11/Eta @ c15de97); attribution in
 * THIRD_PARTY_LICENSES.md. Upstream names the same events in a `when` and keeps a map so each call
 * reports itself started once and finished once; both are kept here as values, because a duplicate
 * row or a missing one is exactly the kind of thing that only shows up on a real stream.
 */
object HostedCallEventPolicy {

    private const val PREFIX = "response."
    private const val CALL_MARKER = "_call."

    enum class Phase { STARTED, FINISHED, FAILED }

    data class Activity(val kind: String, val phase: Phase) {
        val finished: Boolean get() = phase != Phase.STARTED
        val success: Boolean get() = phase != Phase.FAILED
    }

    /**
     * Parses `response.<kind>_call.<phase>`; null for anything else, including a phase this version
     * does not know - an unknown phase is not a reason to render a wrong row.
     */
    fun parse(eventType: String): Activity? {
        if (!eventType.startsWith(PREFIX)) return null
        val separator = eventType.indexOf(CALL_MARKER)
        if (separator <= PREFIX.length) return null
        val kind = eventType.substring(PREFIX.length, separator)
        if (kind.isEmpty()) return null
        val phase = when (eventType.substring(separator + CALL_MARKER.length)) {
            "in_progress", "searching" -> Phase.STARTED
            "completed" -> Phase.FINISHED
            "failed" -> Phase.FAILED
            else -> return null
        }
        return Activity(kind, phase)
    }

    /**
     * The call's id, in upstream's own order of preference: what the event names, then the item it
     * carries, then a deterministic fallback that keeps two calls apart.
     */
    fun itemId(event: JSONObject, kind: String): String =
        event.optString("item_id")
            .ifBlank { event.optString("id") }
            .ifBlank { event.optJSONObject("item")?.optString("id").orEmpty() }
            .ifBlank { kind + "_" + event.optInt("output_index", 0) }

    /**
     * Upstream's bookkeeping: a call reports itself started once and finished once, and a finish
     * that arrives without a start still produces both rows.
     */
    class Ledger {
        private val started = mutableSetOf<String>()
        private val finished = mutableSetOf<String>()

        @Synchronized
        fun accept(id: String, activity: Activity): List<Activity> {
            val rows = mutableListOf<Activity>()
            if (started.add(id)) rows += Activity(activity.kind, Phase.STARTED)
            if (activity.finished && finished.add(id)) rows += activity
            return rows
        }
    }
}
