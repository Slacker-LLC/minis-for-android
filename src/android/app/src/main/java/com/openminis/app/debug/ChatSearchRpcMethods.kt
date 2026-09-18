package com.openminis.app.debug

import android.content.Context
import com.openminis.app.MinisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * `chat.search` — cross-session full-text search over message content.
 *
 * Reuses the same battle-tested path as `minis-sessions-cli search`
 * ([com.openminis.app.data.repository.ChatRepository.searchMessages]):
 * parameterised `parts_json LIKE` with Kotlin-side text extraction so
 * tool-call JSON metadata that merely contains the keyword never counts as
 * a hit. Terms are ANDed and treated literally — no wildcard injection.
 */
internal object ChatSearchRpcMethods {

    private const val DEFAULT_LIMIT = 20
    private const val MAX_LIMIT = 50
    private const val MAX_HITS_PER_SESSION = 3

    suspend fun search(context: Context, params: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val query = params.optString("query", "").trim()
        if (query.isEmpty()) throw RPCException(-32602, "Missing 'query' param")

        val sessionId = params.optString("sessionId", "").ifEmpty { null }
        val limit = params.optInt("limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val keywords = query.split(Regex("\\s+")).filter { it.isNotBlank() }

        val app = context.applicationContext as? MinisApp
            ?: throw RPCException(-32000, "MinisApp not initialized")
        val repo = app.chatRepository

        // Over-fetch: searchMessages prunes tool-metadata false positives and
        // trims to the requested cap; ask for more so grouping by session can
        // still yield up to MAX_HITS_PER_SESSION per result row.
        val matches = repo.searchMessages(
            sessionIds = if (sessionId != null) listOf(sessionId) else null,
            keywords = keywords,
            limit = limit * MAX_HITS_PER_SESSION * 2,
            startMs = null,
            endMs = null,
        )

        // Group by session preserving newest-first match order.
        val grouped = LinkedHashMap<String, MutableList<com.openminis.app.data.repository.MessageSearchMatch>>()
        for (m in matches) {
            grouped.getOrPut(m.sessionId) { mutableListOf() }.add(m)
        }

        val results = JSONArray()
        var total = 0
        for ((sid, hits) in grouped) {
            if (results.length() >= limit) break
            val session = repo.getSession(sid)
            val hitsJson = JSONArray()
            for (h in hits.take(MAX_HITS_PER_SESSION)) {
                hitsJson.put(JSONObject().apply {
                    put("messageId", h.messageId)
                    put("role", h.role)
                    put("createdAt", h.createdAt)
                    put("content", h.snippet)
                })
            }
            total += hits.size
            results.put(JSONObject().apply {
                put("sessionId", sid)
                put("title", session?.title ?: "")
                put("matchedCount", hits.size)
                put("snippet", hits.firstOrNull()?.snippet ?: "")
                put("hits", hitsJson)
            })
        }

        JSONObject().apply {
            put("query", query)
            put("count", results.length())
            put("total", total)
            put("results", results)
        }
    }
}
