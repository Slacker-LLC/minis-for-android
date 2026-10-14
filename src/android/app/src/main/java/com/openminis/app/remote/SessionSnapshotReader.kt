package com.openminis.app.remote

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.ui.chat.SessionEventCapture
import com.openminis.app.ui.chat.SessionEventHub
import com.openminis.app.ui.chat.SessionEventTail
import com.openminis.app.ui.chat.SessionEventTailTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Durable messages plus the materialized event tail at one exact waterline. */
internal object SessionSnapshotReader {
    private fun app(context: Context): MinisApp =
        context.applicationContext as? MinisApp ?: throw RPCException(-32000, "MinisApp not initialized")

    /**
     * Atomically combine the durable message base with the SessionEventHub's
     * materialized live tail and capture its exact journal waterline. Browser
     * clients hydrate [SessionEventCapture.value] once, then request only
     * events after [SessionEventCapture.lastSeq]. No throttled Compose state is
     * consulted for this fence.
     */
    suspend fun capture(
        context: Context,
        sessionId: String,
        includeReasoning: Boolean = true,
    ): SessionEventCapture<JSONObject> {
        val application = app(context)
        // Database work stays off Main. If this session becomes live while
        // this fallback is loading, the Main-side capture below detects it
        // and selects the canonical VM projection instead.
        withContext(Dispatchers.IO) {
            SessionEventHub.activateSession(application.database.chatDao(), sessionId)
        }
        val durableMessages = withContext(Dispatchers.IO) {
            ChatReadMethods.messagesList(
                context,
                JSONObject()
                    .put("sessionId", sessionId)
                    .put("limit", 2_000)
                    .put("includeTools", true)
                    .put("includeReasoning", includeReasoning),
            )
        }
        val durableStatus = withContext(Dispatchers.IO) {
            val status = ChatReadMethods.status(
                context,
                JSONObject().put("sessionId", sessionId),
            )
            val session = application.database.chatDao().getSession(sessionId)
            status.put("thinkingLevel", session?.thinkingOverride?.lowercase() ?: "off")
            status
        }
        // The reducer is advanced inside appendHotLocked before the sequence
        // is published. Therefore this snapshot never reads mutable Compose
        // state outside the event lock: it is durable message base + the
        // materialized tail at the exact returned waterline.
        return SessionEventHub.captureWithWatermark(sessionId) { _, tail, _ ->
            buildEventSourcedSnapshot(sessionId, durableMessages, durableStatus, tail, includeReasoning)
        }
    }

    /** Apply the bounded, materialized event tail to the durable message base. */
    private fun buildEventSourcedSnapshot(
        sessionId: String,
        durableMessages: JSONObject,
        durableStatus: JSONObject,
        tail: SessionEventTail,
        includeReasoning: Boolean,
    ): JSONObject {
        val ordered = ArrayList<JSONObject>()
        val byId = LinkedHashMap<String, JSONObject>()
        val base = durableMessages.optJSONArray("messages") ?: JSONArray()
        for (index in 0 until base.length()) {
            val message = base.optJSONObject(index) ?: continue
            val copy = JSONObject(message.toString())
            val id = copy.optString("id", "")
            if (id.isNotBlank()) byId[id] = copy
            ordered += copy
        }
        tail.messages.values.forEach { messageTail ->
            val message = byId[messageTail.id] ?: JSONObject().also {
                byId[messageTail.id] = it
                ordered += it
            }
            message.put("id", messageTail.id)
            message.put("role", messageTail.role)
            if (messageTail.contentAuthoritative || !message.has("content")) {
                message.put("content", messageTail.content)
            }
            if (messageTail.role == "assistant") {
                message.put("isStreaming", messageTail.isStreaming)
                message.put("isAwaitingModelResponse", messageTail.isAwaitingModelResponse)
            }
            if (messageTail.attachments.isNotEmpty() && !message.has("attachments")) {
                message.put("attachments", JSONArray(messageTail.attachments))
            }
            if (includeReasoning && messageTail.reasoningByIndex.isNotEmpty()) {
                message.put("reasoningContent", messageTail.reasoningByIndex.values.joinToString(""))
            }
            applyTailTools(message, messageTail.tools.values)
        }
        val messages = JSONArray().apply { ordered.forEach(::put) }
        val running = tail.isRunning ?: durableStatus.optBoolean("isRunning", false)
        val model = tail.modelName?.takeIf { it.isNotBlank() }
            ?: durableStatus.optString("modelName", "")
        val thinking = tail.thinkingLevel?.takeIf { it.isNotBlank() }
            ?: durableStatus.optString("thinkingLevel", "off")
        val title = ChatTitleNormalizer.normalize(durableStatus.opt("title"))
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("isRunning", running)
            put("modelName", model)
            put("thinkingLevel", thinking)
            if (title.isNotEmpty()) put("title", title)
            put("live", running || tail.messages.values.any { it.isStreaming })
            put("messages", messages)
            put("totalCount", messages.length())
            put("status", JSONObject().apply {
                put("isRunning", running)
                put("modelName", model)
                put("thinkingLevel", thinking)
                if (title.isNotEmpty()) put("title", title)
                tail.turn?.let { put("turn", it) }
            })
        }
    }

    private fun applyTailTools(message: JSONObject, tools: Collection<SessionEventTailTool>) {
        if (tools.isEmpty()) return
        val liveBlocks = message.optJSONArray("liveBlocks")?.let { existing ->
            JSONArray(existing.toString())
        } ?: JSONArray()
        val blockIndex = HashMap<String, Int>()
        for (index in 0 until liveBlocks.length()) {
            liveBlocks.optJSONObject(index)?.optString("id", "")?.takeIf { it.isNotBlank() }?.let {
                blockIndex[it] = index
            }
        }
        val calls = message.optJSONArray("toolCalls")?.let { JSONArray(it.toString()) } ?: JSONArray()
        val results = message.optJSONArray("toolResults")?.let { JSONArray(it.toString()) } ?: JSONArray()
        tools.forEach { tool ->
            val block = JSONObject().apply {
                put("id", tool.id)
                put("kind", "tool_use")
                put("content", tool.output)
                put("toolStatus", tool.status ?: JSONObject.NULL)
                put("toolTitle", tool.title.ifBlank { tool.name })
                put("toolName", tool.name)
                put("toolArgs", tool.args)
                put("durationMs", tool.durationMs)
                put("startTimeMs", tool.startTimeMs)
            }
            val blockAt = blockIndex[tool.id]
            if (blockAt == null) {
                blockIndex[tool.id] = liveBlocks.length()
                liveBlocks.put(block)
            } else {
                liveBlocks.put(blockAt, block)
            }
            val call = JSONObject().apply {
                put("id", tool.id)
                put("toolUseId", tool.id)
                put("name", tool.name)
                put("toolName", tool.name)
                put("title", tool.title)
                put("toolStatus", tool.status ?: JSONObject.NULL)
                put("toolArgs", tool.args)
                put("startTimeMs", tool.startTimeMs)
                put("durationMs", tool.durationMs)
            }
            upsertJsonById(calls, tool.id, call)
            if (tool.status in setOf("SUCCESS", "FAILED", "CANCELLED", "TIMEOUT")) {
                upsertJsonById(results, tool.id, JSONObject().apply {
                    put("id", tool.id)
                    put("toolUseId", tool.id)
                    put("name", tool.name)
                    put("toolName", tool.name)
                    put("output", tool.output)
                    put("success", tool.status == "SUCCESS")
                    put("toolStatus", tool.status)
                    put("durationMs", tool.durationMs)
                })
            }
        }
        message.put("liveBlocks", liveBlocks)
        if (calls.length() > 0) message.put("toolCalls", calls)
        if (results.length() > 0) message.put("toolResults", results)
    }

    private fun upsertJsonById(target: JSONArray, id: String, value: JSONObject) {
        for (index in 0 until target.length()) {
            val old = target.optJSONObject(index) ?: continue
            val oldId = old.optString("id", old.optString("toolUseId", ""))
            if (oldId == id) {
                target.put(index, value)
                return
            }
        }
        target.put(value)
    }

}
