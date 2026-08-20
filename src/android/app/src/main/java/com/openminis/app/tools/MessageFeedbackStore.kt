package com.openminis.app.tools

import android.content.Context
import android.util.Log
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

    /** Set when [loadAll] hit a parse error: the on-disk file is corrupt and
     *  must be backed up as .corrupt before the next write overwrites it. */
    private var corruptFile = false

    private fun loadAll(context: Context): MutableMap<String, Feedback> {
        val f = file(context)
        if (!f.exists()) return mutableMapOf()
        return try {
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
            corruptFile = false
            out
        } catch (e: Exception) {
            // Never silently clear a corrupt sidecar: keep the original file so
            // the damage is not compounded, and let the next save back it up.
            Log.w("MessageFeedbackStore", "failed to parse feedback file: ${e.message}", e)
            corruptFile = true
            mutableMapOf()
        }
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
        runCatching {
            val f = file(context)
            if (corruptFile && f.exists()) {
                // Preserve the damaged file before overwriting it.
                val backup = File(f.parentFile, f.name + ".corrupt")
                if (!backup.exists() || backup.delete()) f.renameTo(backup)
            }
            writeAtomic(f, obj.toString())
        }
        corruptFile = false
    }

    /** Write [content] to [f] via a temp file + rename (atomic replace). */
    private fun writeAtomic(f: File, content: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(f)) {
            // renameTo can fail when the target already exists on some
            // filesystems; fall back to a plain write.
            f.writeText(content)
            tmp.delete()
        }
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
