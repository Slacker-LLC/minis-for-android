package com.openminis.app.offload

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lightweight SharedPreferences-backed registry of currently-scheduled
 * `android-notification schedule` notifications.
 *
 * Used so that:
 *   - `android-notification pending` can list what's still queued
 *   - `android-notification cancel --id` can remove a specific entry
 *   - the receiver can drop entries on fire so they don't linger
 *
 * The app already maintains a similar prefs registry for alarms in
 * AlarmOffloadManager; we deliberately keep this separate so the two
 * surfaces don't collide on key names or fire semantics.
 */
internal class ScheduledNotificationStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Adds [entry], replacing any entry with the same id: the system alarm for an id is replaced by
     * the next schedule call, so two entries for one id would leave a phantom pending item after the
     * single alarm fired.
     */
    fun add(entry: JSONObject) = synchronized(LOCK) {
        val id = entry.optString("id")
        val list = loadAll()
        val next = JSONArray()
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            if (o.optString("id") != id) next.put(o)
        }
        next.put(entry)
        prefs.edit().putString(KEY, next.toString()).apply()
    }

    fun remove(id: String): Boolean = synchronized(LOCK) {
        val list = loadAll()
        val next = JSONArray()
        var removed = false
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            if (o.optString("id") == id) removed = true else next.put(o)
        }
        if (removed) prefs.edit().putString(KEY, next.toString()).apply()
        removed
    }

    fun clear() = synchronized(LOCK) {
        prefs.edit().remove(KEY).apply()
    }

    fun get(id: String): JSONObject? {
        val list = loadAll()
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            if (o.optString("id") == id) return o
        }
        return null
    }

    fun loadAll(): JSONArray {
        val raw = prefs.getString(KEY, null) ?: return JSONArray()
        return try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
    }

    /**
     * Drop entries that are long past due. A time that has passed is not a delivery: with Doze the
     * system may deliver the alarm many minutes late, and until then the entry is still pending (and
     * cancellable). Only entries more than [EXPIRY_GRACE_MS] overdue are treated as lost.
     */
    fun sweepExpired(now: Long = System.currentTimeMillis()) = synchronized(LOCK) {
        val list = loadAll()
        val kept = JSONArray()
        var dropped = false
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            if (!isLost(o, now)) kept.put(o) else dropped = true
        }
        if (dropped) prefs.edit().putString(KEY, kept.toString()).apply()
    }

    companion object {
        private const val PREFS = "minis_scheduled_notifications"
        private const val KEY = "scheduled"

        /** One lock for every instance: the handler, the receiver and boot recovery each make their own. */
        private val LOCK = Any()

        internal const val EXPIRY_GRACE_MS = 60 * 60 * 1000L

        internal fun isLost(entry: JSONObject, now: Long): Boolean =
            entry.optLong("trigger_at_ms", Long.MAX_VALUE) + EXPIRY_GRACE_MS < now

        /** What to do with a stored entry after a reboot cleared every alarm. */
        internal enum class RestoreAction { RESCHEDULE, DELIVER_NOW, DROP }

        internal fun restoreAction(entry: JSONObject, now: Long): RestoreAction {
            val at = entry.optLong("trigger_at_ms", 0L)
            return when {
                at > now -> RestoreAction.RESCHEDULE
                isLost(entry, now) -> RestoreAction.DROP
                else -> RestoreAction.DELIVER_NOW
            }
        }
    }
}
