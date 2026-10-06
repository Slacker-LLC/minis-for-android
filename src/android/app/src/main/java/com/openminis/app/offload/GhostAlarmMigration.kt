package com.openminis.app.offload

import org.json.JSONArray
import org.json.JSONObject

/**
 * The one-shot move of pre-T266 internal alarms into the system Clock, as a decision over the stored list.
 * Each entry ends up in one of three states: handed over (done), expired (nothing to recover), or failed
 * (keep, so a later foreground launch can retry just that one). Only the last kind stays in the stored
 * list, so an expired entry never keeps the batch alive and a handed-over one is never submitted twice.
 */
internal object GhostAlarmMigration {
    data class Outcome(val remaining: JSONArray, val migrated: Int, val expired: Int, val failed: Int)

    fun isExpired(entry: JSONObject, nowMs: Long): Boolean {
        val triggerAt = entry.optLong("triggerAtMs", 0L)
        if (triggerAt !in 1L..nowMs) return false
        return entry.optString("type") == "timer" || entry.optString("repeatMode", "ONCE") == "ONCE"
    }

    /** [submit] hands one live entry to the Clock and returns whether that worked. */
    fun run(entries: JSONArray, nowMs: Long, submit: (JSONObject) -> Boolean): Outcome {
        val remaining = JSONArray()
        var migrated = 0
        var expired = 0
        var failed = 0
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            when {
                isExpired(entry, nowMs) -> expired++
                runCatching { submit(entry) }.getOrDefault(false) -> migrated++
                else -> { failed++; remaining.put(entry) }
            }
        }
        return Outcome(remaining, migrated, expired, failed)
    }
}
