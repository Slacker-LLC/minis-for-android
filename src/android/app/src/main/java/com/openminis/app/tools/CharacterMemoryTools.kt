package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.roleplay.CharacterCardException
import com.openminis.app.roleplay.CharacterMemoryDocument
import com.openminis.app.roleplay.CharacterMemoryMutation
import com.openminis.app.roleplay.CharacterMemoryRepository
import com.openminis.app.roleplay.CharacterMemoryWrite
import com.openminis.app.roleplay.CharacterRepository
import com.openminis.app.tools.runtime.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * [T-eta-character-cards] The character's story memory as two tools: read it, change it.
 *
 * Ported from Eta `agent/roleplay/CharacterMemoryTools.kt` (Mangi-11/Eta @ c15de97); attribution in
 * THIRD_PARTY_LICENSES.md. The boundaries Eta draws are kept: the character is whatever the session is
 * bound to — the model cannot name another one — the memory is fiction and lives apart from the real
 * MEMORY.md, and every write carries the revision it was based on, so a stale write is refused instead
 * of overwriting a scene somebody else already moved on from.
 */
object CharacterMemoryTools {
    const val READ = "roleplay.memory_read"
    const val WRITE = "roleplay.memory_write"

    val aliases: Map<String, List<String>> = mapOf(
        READ to listOf("character_memory_get"),
        WRITE to listOf("character_memory_write"),
    )

    fun handlers(): List<ToolHandler> = listOf(CharacterMemoryReadHandler(), CharacterMemoryWriteHandler())

    /** The character this session speaks as, or null when it is an ordinary chat. */
    private suspend fun boundCharacter(sessionId: String): String? =
        if (sessionId.isBlank()) null else CharacterRepository.bindingFor(sessionId)?.characterId?.takeIf { it.isNotBlank() }

    internal suspend fun read(argsJson: String, sessionId: String, context: Context): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            val characterId = boundCharacter(sessionId)
                ?: return@withContext ToolExecutionResult(error("NO_CHARACTER_BOUND", "这段会话还没有绑定角色").toString(), false)
            val args = JSONObject(argsJson)
            val result = CharacterMemoryRepository.read(
                context = context,
                characterId = characterId,
                query = args.optString("query").takeIf { it.isNotBlank() },
                startLine = args.optInt("start_line", 1),
                maxChars = if (args.has("max_chars")) args.optInt("max_chars") else CharacterMemoryDocument.DEFAULT_MAX_READ_CHARS,
            )
            ToolExecutionResult(
                JSONObject()
                    .put("ok", true)
                    .put("character_id", characterId)
                    .put("revision", result.snapshot.revision)
                    .put("bytes", result.snapshot.byteSize)
                    .put("line_count", result.snapshot.lineCount)
                    .put("start_line", result.startLine ?: JSONObject.NULL)
                    .put("end_line", result.endLine ?: JSONObject.NULL)
                    .put("matched_lines", result.matchedLines)
                    .put("has_more", result.hasMore)
                    .put("content", result.content)
                    .put(
                        "note",
                        "Story memory only: it holds this character's fictional events and relationships, " +
                            "not the user's real memory notes or any record of what the device did.",
                    )
                    .toString(2),
                true,
            )
        }

    internal suspend fun write(argsJson: String, sessionId: String, context: Context): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            val characterId = boundCharacter(sessionId)
                ?: return@withContext ToolExecutionResult(error("NO_CHARACTER_BOUND", "这段会话还没有绑定角色").toString(), false)
            val args = JSONObject(argsJson)
            val revision = args.optString("revision")
            if (revision.isBlank()) {
                return@withContext ToolExecutionResult(
                    error("REVISION_REQUIRED", "写入剧情记忆必须带上 character_memory_get 返回的 revision").toString(),
                    false,
                )
            }
            val mutation = when (val mode = args.optString("mode")) {
                "append" -> CharacterMemoryMutation.Append(revision, args.optString("content"))
                "replace_range" -> CharacterMemoryMutation.ReplaceRange(
                    revision = revision,
                    startLine = args.optInt("start_line", 1),
                    endLine = args.optInt("end_line", 1),
                    content = args.optString("content"),
                )
                "clear" -> CharacterMemoryMutation.Clear(revision)
                else -> return@withContext ToolExecutionResult(
                    error("INVALID_MEMORY_MODE", "mode 必须是 append、replace_range 或 clear（收到 '$mode'）").toString(),
                    false,
                )
            }
            try {
                when (val written = CharacterMemoryRepository.mutate(context, characterId, mutation)) {
                    is CharacterMemoryWrite.Success -> ToolExecutionResult(
                        JSONObject()
                            .put("ok", true)
                            .put("character_id", characterId)
                            .put("revision", written.snapshot.revision)
                            .put("bytes", written.snapshot.byteSize)
                            .put("line_count", written.snapshot.lineCount)
                            .toString(2),
                        true,
                    )
                    is CharacterMemoryWrite.Conflict -> ToolExecutionResult(
                        error(
                            "MEMORY_CONFLICT",
                            "剧情记忆已经变了，请先调用 ${READ} 取回最新内容再写入",
                        ).put("revision", written.snapshot.revision).toString(),
                        false,
                    )
                }
            } catch (failure: CharacterCardException) {
                ToolExecutionResult(error(failure.code, failure.message ?: "剧情记忆写入失败").toString(), false)
            } catch (failure: IllegalArgumentException) {
                ToolExecutionResult(error("MEMORY_WRITE_REJECTED", failure.message ?: "剧情记忆写入被拒绝").toString(), false)
            }
        }

    private fun error(code: String, message: String): JSONObject =
        JSONObject().put("ok", false).put("code", code).put("message", message)
}

class CharacterMemoryReadHandler : ToolHandler {
    override val definition = AgentToolDefinition(
        name = CharacterMemoryTools.READ,
        description = "Read this character's persistent story memory: fictional events, relationships and " +
            "scene continuity shared by every conversation with that character. Use query, or page with " +
            "start_line. This never reads the user's real memory notes.",
        parameters = mapOf(
            "query" to AgentToolParam("string", "Optional keyword; matching lines are returned with their line numbers"),
            "start_line" to AgentToolParam("integer", "1-based line to start from when there is no query"),
            "max_chars" to AgentToolParam("integer", "Max characters returned, default 12000"),
        ),
        required = emptyList(),
    )

    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String) =
        CharacterMemoryTools.read(argsJson, sessionId, context)
}

class CharacterMemoryWriteHandler : ToolHandler {
    override val definition = AgentToolDefinition(
        name = CharacterMemoryTools.WRITE,
        description = "Update this character's story memory with what just happened in the story: fictional " +
            "events, relationships, scene continuity. Pass the revision from " + CharacterMemoryTools.READ + " " +
            "(a stale one is refused rather than merged), keep it concise, and correct stale facts instead of " +
            "duplicating them. Never record real device actions here.",
        parameters = mapOf(
            "revision" to AgentToolParam("string", "Exact revision returned by the last read"),
            "mode" to AgentToolParam("string", "append, replace_range or clear", listOf("append", "replace_range", "clear")),
            "content" to AgentToolParam("string", "Markdown to append, or the replacement for the line range"),
            "start_line" to AgentToolParam("integer", "replace_range: first line to replace (1-based)"),
            "end_line" to AgentToolParam("integer", "replace_range: last line to replace (inclusive)"),
        ),
        required = listOf("revision", "mode"),
    )

    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String) =
        CharacterMemoryTools.write(argsJson, sessionId, context)
}
