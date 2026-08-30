package io.github.slackerllc.minis.debug

import android.content.Context
import io.github.slackerllc.minis.tools.QuestionCenter
import org.json.JSONArray
import org.json.JSONObject

/**
 * `chat.question.*` RPC handlers for the Web Remote question cards.
 *
 * The agent tool `ask_user_question` registers a [io.github.slackerllc.minis.tools.PendingQuestion]
 * in [QuestionCenter] and suspends; the web UI polls `chat.question.pending`,
 * renders a card, and resumes the turn via `chat.question.answer`.
 */
internal object QuestionRpcMethods {

    fun pending(context: Context, params: JSONObject): JSONObject {
        val sessionId = params.optString("sessionId", "").ifEmpty { null }
        val arr = JSONArray()
        for (q in QuestionCenter.pendingFor(sessionId)) {
            val options = JSONArray()
            for (o in q.options) {
                options.put(JSONObject().apply {
                    put("value", o.value)
                    put("label", o.label)
                    if (o.recommended) put("recommended", true)
                })
            }
            arr.put(JSONObject().apply {
                put("id", q.id)
                put("sessionId", q.sessionId)
                put("prompt", q.prompt)
                put("options", options)
                put("multiple", q.multiple)
                put("allowCustom", q.allowCustom)
                put("createdAt", q.createdAt)
            })
        }
        return JSONObject().put("questions", arr)
    }

    fun answer(context: Context, params: JSONObject): JSONObject {
        val questionId = params.optString("questionId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'questionId' param")
        }
        val selected = mutableListOf<String>()
        params.optJSONArray("selected")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotEmpty() }?.let { selected.add(it) }
            }
        }
        val custom = params.optString("custom", "").trim().ifEmpty { null }
        val skipped = params.optBoolean("skipped", false)
        if (!skipped && selected.isEmpty() && custom == null) {
            throw RPCException(-32602, "Provide 'selected', 'custom' or 'skipped'")
        }
        val ok = QuestionCenter.answer(
            questionId,
            io.github.slackerllc.minis.tools.QuestionAnswer(
                selected = selected,
                custom = custom,
                skipped = skipped,
            ),
        )
        if (!ok) throw RPCException(-32001, "No pending question with id $questionId")
        return JSONObject().apply {
            put("ok", true)
            put("answered", true)
        }
    }
}
