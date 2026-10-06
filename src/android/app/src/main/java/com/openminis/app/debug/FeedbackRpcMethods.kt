package com.openminis.app.debug

import android.content.Context
import com.openminis.app.tools.MessageFeedbackStore
import org.json.JSONObject

/**
 * `chat.feedback.*` — per-message up/down feedback sidecar for the Web Remote.
 */
internal object FeedbackRpcMethods {

    suspend fun put(context: Context, params: JSONObject): JSONObject {
        val messageId = params.optString("messageId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'messageId' param")
        }
        val kind = params.optString("kind", "up")
        if (kind != "up" && kind != "down") throw RPCException(-32602, "kind must be 'up' or 'down'")
        val sessionId = (context.applicationContext as? com.openminis.app.MinisApp)
            ?.chatRepository?.messageById(messageId)?.sessionId
        val fb = try {
            MessageFeedbackStore.put(context, messageId, kind, params.optString("note", ""), sessionId)
        } catch (e: java.io.IOException) {
            throw RPCException(-32000, "Could not save the feedback: ${e.message}")
        }
        return JSONObject().apply {
            put("ok", true)
            put("kind", fb.kind)
            put("note", fb.note)
            put("at", fb.at)
        }
    }

    fun delete(context: Context, params: JSONObject): JSONObject {
        val messageId = params.optString("messageId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'messageId' param")
        }
        val removed = try {
            MessageFeedbackStore.delete(context, messageId)
        } catch (e: java.io.IOException) {
            throw RPCException(-32000, "Could not save the feedback: ${e.message}")
        }
        return JSONObject().apply {
            put("ok", removed)
        }
    }

    fun listForMessages(context: Context, params: JSONObject): JSONObject {
        val ids = mutableListOf<String>()
        params.optJSONArray("messageIds")?.let { arr ->
            for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotEmpty() }?.let { ids.add(it) }
        }
        val all = MessageFeedbackStore.all(context)
        val filtered = if (ids.isEmpty()) all else all.filterKeys { it in ids }
        return JSONObject().put("feedback", MessageFeedbackStore.toJson(filtered))
    }
}
