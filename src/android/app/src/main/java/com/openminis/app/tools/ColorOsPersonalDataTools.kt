package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.tools.android.CommandRisk
import com.openminis.app.tools.android.PrivilegedCommandRunner
import com.openminis.app.tools.runtime.ToolHandler
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-eta-xposed-groups] ColorOS notes, recordings and recording summaries, read through the
 * providers those apps already publish.
 *
 * Ported from Eta `agent/tool/AgentPersonalDataTools.kt` and the matching entries in
 * `agent/model/AgentDeviceToolCatalog.kt` (Mangi-11/Eta @ c15de97); attribution in
 * THIRD_PARTY_LICENSES.md. The read is a `content query` against the vendor's own provider, run
 * through this project's structured privileged path - one argv, no shell text - because the note and
 * recorder providers are not readable by this app otherwise. The answer is parsed by the column list
 * that was requested, a provider exception is reported as a failure rather than as an empty list,
 * and the row count is bounded before anything is returned.
 */
object ColorOsPersonalDataTools {
    const val NOTES = "android.coloros.notes"
    const val RECORDINGS = "android.coloros.recordings"
    const val SUMMARIES = "android.coloros.recording_summaries"

    val aliases: Map<String, List<String>> = mapOf(
        NOTES to listOf("search_coloros_notes"),
        RECORDINGS to listOf("search_coloros_recordings"),
        SUMMARIES to listOf("search_recording_summaries"),
    )

    fun handlers(): List<ToolHandler> = listOf(
        ColorOsNotesHandler(),
        ColorOsRecordingsHandler(),
        ColorOsRecordingSummariesHandler(),
    )



    private fun definition(name: String, description: String) = AgentToolDefinition(
        name = name,
        description = description,
        parameters = mapOf(
            "query" to AgentToolParam(
                "string",
                "Optional keyword, up to ${PersonalDataQueryPolicy.MAX_KEYWORD_CHARS} characters",
            ),
            "limit" to AgentToolParam(
                "integer",
                "Max rows (default ${PersonalDataQueryPolicy.DEFAULT_LIMIT}, " +
                    "max ${PersonalDataQueryPolicy.MAX_LIMIT})",
            ),
        ),
    )

    class ColorOsNotesHandler : AndroidSystemHandler() {
        override val definition = definition(
            NOTES,
            "Search ColorOS notes and to-dos by title or body. Only available where the ColorOS " +
                "notes app is installed and its provider answers.",
        )

        override suspend fun execute(
            argsJson: String,
            sessionId: String,
            context: Context,
            toolId: String,
        ): ToolExecutionResult = PersonalDataQueryTools.query(
            tool = NOTES,
            uri = "content://com.nearme.note/rich_notes",
            columns = listOf(
                "local_id",
                "raw_title",
                "raw_text",
                "update_time",
                "create_time",
                "folder_id",
                "deleted",
                "recycle_time",
            ),
            sort = "update_time DESC",
            fixedWhere = "deleted=0 AND recycle_time=0",
            argsJson = argsJson,
            sessionId = sessionId,
            context = context,
        )
    }

    class ColorOsRecordingsHandler : AndroidSystemHandler() {
        override val definition = definition(
            RECORDINGS,
            "Search ColorOS recorder files (ordinary and call recordings) by name or path, " +
                "returning name, duration, type and file path.",
        )

        override suspend fun execute(
            argsJson: String,
            sessionId: String,
            context: Context,
            toolId: String,
        ): ToolExecutionResult = PersonalDataQueryTools.query(
            tool = RECORDINGS,
            uri = "content://com.coloros.soundrecorder.provider/records",
            columns = listOf(
                "_id",
                "display_name",
                "_data",
                "duration",
                "date_modified",
                "record_type",
                "relative_path",
            ),
            sort = "date_modified DESC",
            fixedWhere = "deleted=0 AND is_recycle=0",
            argsJson = argsJson,
            sessionId = sessionId,
            context = context,
        )
    }

    class ColorOsRecordingSummariesHandler : AndroidSystemHandler() {
        override val definition = definition(
            SUMMARIES,
            "Search the transcripts and notes the ColorOS recorder attached to recordings. Only " +
                "available where the recorder app produced summaries.",
        )

        override suspend fun execute(
            argsJson: String,
            sessionId: String,
            context: Context,
            toolId: String,
        ): ToolExecutionResult = PersonalDataQueryTools.query(
            tool = SUMMARIES,
            uri = "content://com.coloros.soundrecorder.provider/summary",
            columns = listOf(
                "_id",
                "record_uuid",
                "record_type",
                "note_content",
                "note_state",
                "media_id",
                "media_path",
                "note_id",
            ),
            sort = "_id DESC",
            fixedWhere = null,
            argsJson = argsJson,
            sessionId = sessionId,
            context = context,
        )
    }
}

/**
 * [T-eta-xposed-groups] How a keyword becomes a `--where` clause, and how much may be asked for.
 *
 * Ported from Eta `agent/tool/AgentPersonalDataTools.kt` (Mangi-11/Eta @ c15de97). The escaping is
 * the part worth keeping in one place: a keyword goes into another app's SQL through `content
 * query`, so its backslash, %, _ and quote have to be neutralised, and the comparison is done
 * case-insensitively across the columns the tool actually returns.
 */
object PersonalDataQueryPolicy {
    const val DEFAULT_LIMIT = 10
    const val MAX_LIMIT = 30
    const val MAX_KEYWORD_CHARS = 200

    fun clampLimit(requested: Int?): Int = (requested ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

    /** The error envelope every personal-data tool answers with. */
    fun failure(code: String, message: String, exitCode: Int? = null): String = JSONObject()
        .put("ok", false)
        .put("code", code)
        .put("message", message)
        .apply { exitCode?.let { put("exit_code", it) } }
        .toString(2)

    /** A longer keyword is a caller error, not something to silently cut. */
    fun isKeywordTooLong(keyword: String?): Boolean =
        (keyword?.length ?: 0) > MAX_KEYWORD_CHARS

    fun combineWhere(first: String?, second: String?): String? = when {
        first == null -> second
        second == null -> first
        else -> "($first) AND ($second)"
    }

    fun likeClause(keyword: String, columns: List<String>): String {
        val escaped = keyword
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
            .replace("'", "''")
        val value = "'%$escaped%'"
        return columns.joinToString(" OR ", prefix = "(", postfix = ")") { column ->
            "LOWER($column) LIKE LOWER($value) ESCAPE '\\'"
        }
    }
}
