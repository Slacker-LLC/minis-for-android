package com.openminis.app.tools.history

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.tools.runtime.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-eta-conversation-history] Read the CURRENT session transcript again: search it for a
 * keyword, or page through it from a message index.
 *
 * Ported from Eta `agent/model/AgentConversationToolCatalog.kt` and the runtime history seam
 * that executes it (Mangi-11/Eta @ c15de97); attribution in THIRD_PARTY_LICENSES.md. The
 * contract kept from Eta: the caller cannot name another session — the session id comes from
 * the running turn — and the tool says what the persisted history is not: sensitive tool
 * payloads and transient images were never written to it.
 *
 * Why a model wants it: compaction replaces old turns with a summary, so the exact wording of
 * an earlier instruction or a tool outcome may only exist in the transcript.
 */
object ConversationHistoryTool {
    const val NAME = "conversation.history"

    /** Eta spelling, kept as an alias so a prompt written for either app works. */
    val aliases = listOf("conversation_history")

    /** Messages loaded per page while reading or searching. */
    const val PAGE_SIZE = 200

    /** How far a search will scan before it reports that it stopped looking. */
    const val MAX_SCANNED_MESSAGES = 1_000

    /** Longest tool argument/result excerpt that enters the rendered history. */
    const val TOOL_EXCERPT_CHARS = 160

    internal fun repository(context: Context): ChatRepository? =
        (context.applicationContext as? MinisApp)?.chatRepositoryOrNull

    /** One row rendered as history text: role-visible parts only, tool payloads truncated. */
    internal fun render(message: MessageEntity): String {
        val parts = try {
            JSONArray(message.partsJson)
        } catch (_: Throwable) {
            return message.partsJson
        }
        val lines = mutableListOf<String>()
        for (index in 0 until parts.length()) {
            val part = parts.optJSONObject(index) ?: continue
            val value = part.opt("value")
            when (part.optString("type")) {
                "text" -> (value as? String)?.takeIf { it.isNotBlank() }?.let(lines::add)
                "toolUse" -> {
                    val tool = value as? JSONObject ?: continue
                    val name = tool.optString("name").ifBlank { "tool" }
                    val input = tool.optString("input").replace("\\n", " ")
                    lines += "tool_use " + name + ": " + input.take(TOOL_EXCERPT_CHARS)
                }
                "toolResult" -> {
                    val result = value as? JSONObject ?: continue
                    val output = result.optString("output").replace("\\n", " ")
                    lines += "tool_result ok=" + result.optBoolean("success", true) + ": " +
                        output.take(TOOL_EXCERPT_CHARS)
                }
                "mediaRef" -> lines += "[image]"
            }
        }
        return lines.joinToString(" | ")
    }

    internal fun entries(messages: List<MessageEntity>, firstIndex: Int): List<ConversationHistoryPolicy.Entry> =
        messages.mapIndexed { position, message ->
            val role = if (message.role == "user") "user" else "assistant"
            ConversationHistoryPolicy.Entry(
                index = firstIndex + position,
                role = role,
                text = ConversationHistoryPolicy.boundedEntry(render(message)),
            )
        }

    internal suspend fun execute(
        argsJson: String,
        sessionId: String,
        context: Context,
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        val repository = repository(context)
            ?: return@withContext ToolExecutionResult("Error: HISTORY_UNAVAILABLE: the chat store is not ready yet", false)
        if (sessionId.isBlank()) {
            return@withContext ToolExecutionResult("Error: HISTORY_UNAVAILABLE: this turn has no session to read", false)
        }
        val args = JSONObject(argsJson)
        val query = args.optString("query", "").trim()
        val maxChars = ConversationHistoryPolicy.clampMaxChars(
            if (args.has("max_chars")) args.optInt("max_chars") else null,
        )
        if (query.isNotEmpty()) {
            search(repository, sessionId, query, maxChars)
        } else {
            read(repository, sessionId, args, maxChars)
        }
    }

    private suspend fun read(
        repository: ChatRepository,
        sessionId: String,
        args: JSONObject,
        maxChars: Int,
    ): ToolExecutionResult {
        val startIndex = args.optInt("message_index", 0).coerceAtLeast(0)
        val startOffset = args.optInt("offset", 0).coerceAtLeast(0)
        val page = repository.loadMessagePageRaw(sessionId, startIndex, PAGE_SIZE)
        val total = repository.messageCount(sessionId)
        val entries = entries(page, startIndex)
        val window = ConversationHistoryPolicy.window(entries, startIndex, startOffset, maxChars)
        // A full page means the transcript continues beyond it; hand the caller the next
        // index instead of an end-of-history signal it would trust.
        val pageEnded = page.size == PAGE_SIZE
        val nextIndex = window.nextMessageIndex ?: if (pageEnded) startIndex + page.size else null
        val nextOffset = window.nextOffset ?: if (pageEnded) 0 else null
        return ToolExecutionResult(
            JSONObject()
                .put("ok", true)
                .put("mode", "read")
                .put("session", sessionId)
                .put("total_messages", total)
                .put("message_index", startIndex)
                .put("offset", startOffset)
                .put("max_chars", maxChars)
                .put("next_message_index", nextIndex ?: JSONObject.NULL)
                .put("next_offset", nextOffset ?: JSONObject.NULL)
                .put("truncated", window.truncated || pageEnded)
                .put("text", window.text)
                .put(
                    "note",
                    "Persisted history only: sensitive tool payloads and transient images were never " +
                        "written to it, and following the continuation pointers reads the same transcript.",
                )
                .toString(2),
            true,
        )
    }

    private suspend fun search(
        repository: ChatRepository,
        sessionId: String,
        query: String,
        maxChars: Int,
    ): ToolExecutionResult {
        val total = repository.messageCount(sessionId)
        val scanned = mutableListOf<MessageEntity>()
        var offset = 0
        while (scanned.size < MAX_SCANNED_MESSAGES) {
            val page = repository.loadMessagePageRaw(sessionId, offset, PAGE_SIZE)
            if (page.isEmpty()) break
            scanned += page
            offset += page.size
            if (page.size < PAGE_SIZE) break
        }
        val limited = scanned.take(MAX_SCANNED_MESSAGES)
        val matches = ConversationHistoryPolicy.search(entries(limited, 0), query)
        val matchArray = JSONArray().also { array ->
            matches.forEach { match ->
                array.put(
                    JSONObject()
                        .put("message_index", match.index)
                        .put("role", match.role)
                        .put("snippet", match.snippet),
                )
            }
        }
        return ToolExecutionResult(
            JSONObject()
                .put("ok", true)
                .put("mode", "search")
                .put("session", sessionId)
                .put("query", query)
                .put("total_messages", total)
                .put("scanned_messages", limited.size)
                .put("scan_truncated", total > limited.size)
                .put("count", matchArray.length())
                .put("truncated", matchArray.length() >= ConversationHistoryPolicy.MAX_SEARCH_MATCHES)
                .put("matches", matchArray)
                .put("max_chars", maxChars)
                .put(
                    "note",
                    "Matches come from the persisted transcript; read one with " + NAME +
                        " and message_index, or without query to page from the start.",
                )
                .toString(2),
            true,
        )
    }
}

class ConversationHistoryHandler : ToolHandler {
    override val definition = AgentToolDefinition(
        name = ConversationHistoryTool.NAME,
        description = "Read the CURRENT session transcript: search it with a keyword, or page through it " +
            "from a message_index/offset. Use it to check earlier instructions and tool outcomes after " +
            "context was compacted. The persisted history holds no sensitive tool payloads and no transient " +
            "images, and following next_message_index/next_offset reads the same transcript rather than a " +
            "rewritten one.",
        parameters = mapOf(
            "query" to AgentToolParam("string", "Optional keyword to search for; omit it to page through the transcript"),
            "message_index" to AgentToolParam("integer", "0-based message index to start from (default 0, the oldest)"),
            "offset" to AgentToolParam("integer", "Character offset inside that message (default 0)"),
            "max_chars" to AgentToolParam("integer", "Max characters returned, 256-8000, default 4000"),
        ),
        required = emptyList(),
    )

    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String) =
        ConversationHistoryTool.execute(argsJson, sessionId, context)
}
