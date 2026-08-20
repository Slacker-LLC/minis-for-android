package com.openminis.app.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Per-message feedback sidecar (DeepSeek Harness `message-feedback`):
 * up/down thumbs with an optional note, persisted as a small JSON file next
 * to app files. Independent from the immutable message log on purpose.
 */
object MessageFeedbackStore {

    data class Feedback(
        val kind: String, // "up" | "down"
        val note: String = "",
        val at: Long = System.currentTimeMillis(),
    )

    private fun file(context: Context): File =
        File(context.filesDir, "web-message-feedback.json")

    private fun loadAll(context: Context): MutableMap<String, Feedback> {
        val f = file(context)
        if (!f.exists()) return mutableMapOf()
        return runCatching {
            val obj = JSONObject(f.readText())
            val out = mutableMapOf<String, Feedback>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val v = obj.optJSONObject(id) ?: continue
                out[id] = Feedback(
                    kind = v.optString("kind", "up"),
                    note = v.optString("note", ""),
                    at = v.optLong("at", System.currentTimeMillis()),
                )
            }
            out
        }.getOrElse { mutableMapOf() }
    }

    @Synchronized
    private fun saveAll(context: Context, all: Map<String, Feedback>) {
        val obj = JSONObject()
        for ((id, fb) in all) {
            obj.put(id, JSONObject().apply {
                put("kind", fb.kind)
                put("note", fb.note)
                put("at", fb.at)
            })
        }
        runCatching { file(context).writeText(obj.toString()) }
    }

    @Synchronized
    fun put(context: Context, messageId: String, kind: String, note: String = ""): Feedback {
        val all = loadAll(context)
        val fb = Feedback(kind = if (kind == "down") "down" else "up", note = note.trim())
        all[messageId] = fb
        saveAll(context, all)
        return fb
    }

    @Synchronized
    fun delete(context: Context, messageId: String): Boolean {
        val all = loadAll(context)
        val removed = all.remove(messageId) != null
        if (removed) saveAll(context, all)
        return removed
    }

    @Synchronized
    fun listForMessages(context: Context, messageIds: List<String>): Map<String, Feedback> {
        val all = loadAll(context)
        return all.filterKeys { it in messageIds }
    }

    @Synchronized
    fun all(context: Context): Map<String, Feedback> = loadAll(context)

    fun toJson(map: Map<String, Feedback>): JSONArray {
        val arr = JSONArray()
        for ((id, fb) in map) {
            arr.put(JSONObject().apply {
                put("messageId", id)
                put("kind", fb.kind)
                put("note", fb.note)
                put("at", fb.at)
            })
        }
        return arr
    }
}
