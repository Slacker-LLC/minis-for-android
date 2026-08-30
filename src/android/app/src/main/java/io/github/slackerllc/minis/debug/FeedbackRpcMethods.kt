package io.github.slackerllc.minis.debug

import android.content.Context
import io.github.slackerllc.minis.tools.MessageFeedbackStore
import org.json.JSONObject

/**
 * `chat.feedback.*` — per-message up/down feedback sidecar for the Web Remote.
 */
internal object FeedbackRpcMethods {

    fun put(context: Context, params: JSONObject): JSONObject {
        val messageId = params.optString("messageId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'messageId' param")
        }
        val kind = params.optString("kind", "up")
        if (kind != "up" && kind != "down") throw RPCException(-32602, "kind must be 'up' or 'down'")
        val fb = MessageFeedbackStore.put(context, messageId, kind, params.optString("note", ""))
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
        return JSONObject().apply {
            put("ok", MessageFeedbackStore.delete(context, messageId))
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
