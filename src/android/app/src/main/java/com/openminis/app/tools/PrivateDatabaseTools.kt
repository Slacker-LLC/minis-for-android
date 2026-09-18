package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.tools.runtime.ToolHandler
import org.json.JSONObject

/**
 * [T-eta-xposed-groups] The input method's clipboard history and the system's health summary, read
 * from the databases that hold them.
 *
 * Ported from Eta `agent/tool/AgentPrivateDatabaseTools.kt` (Mangi-11/Eta @ c15de97); attribution
 * in THIRD_PARTY_LICENSES.md. Both are read-only summaries of somebody else's store: the clipboard
 * history is the one the current input method saved, the health summary is Health Connect's, and the
 * snapshot in [PrivateDatabaseSnapshot] is what makes either reachable. Neither tool returns raw
 * measurement series, and a database that is not the schema this port knows answers with a code
 * rather than a guess.
 */
object PrivateDatabaseTools {

    const val CLIPBOARD = "android.clipboard.history"
    const val HEALTH = "android.health.summary"

    val aliases: Map<String, List<String>> = mapOf(
        CLIPBOARD to listOf("search_clipboard_history"),
        HEALTH to listOf("get_health_summary"),
    )

    private const val CLIPBOARD_PATH =
        "/data/user/{user}/com.sohu.inputmethod.sogouoem/databases/clipboard_db"
    private const val CLIPBOARD_MAX_BYTES = 32L * 1024 * 1024

    private const val HEALTH_PATH = "/data/system_ce/{user}/healthconnect/healthconnect.db"
    private const val HEALTH_MAX_BYTES = 256L * 1024 * 1024

    suspend fun clipboardHistory(
        context: Context,
        sessionId: String,
        argsJson: String,
    ): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrElse { JSONObject() }
        val limit = PrivateDatabaseRules.clampLimit(
            if (args.has("limit")) args.optInt("limit") else null,
        )
        val keyword = args.optString("query").trim().takeIf { it.isNotEmpty() }
        return PrivateDatabaseSnapshot.read(
            context = context,
            sessionId = sessionId,
            sourceTemplate = CLIPBOARD_PATH,
            maxBytes = CLIPBOARD_MAX_BYTES,
            unavailableCode = "CLIPBOARD_HISTORY_UNAVAILABLE",
            unavailableMessage = "the current input method has no clipboard history this tool can reach",
        ) { database ->
            if (!database.hasColumns("CLIPBOARD_ITEM", setOf("TIME", "CONTENT"))) {
                databaseError(
                    "CLIPBOARD_SCHEMA_UNSUPPORTED",
                    "the clipboard history schema is not one this tool knows",
                )
            } else {
                databaseOk(
                    tool = "search_clipboard_history",
                    items = database.databaseRows(
                        table = "CLIPBOARD_ITEM",
                        columns = listOf("TIME", "CONTENT"),
                        selection = keyword?.let { "CONTENT LIKE ? ESCAPE '\\' COLLATE NOCASE" },
                        order = "TIME DESC",
                        limit = limit,
                        selectionArgs = keyword?.let {
                            arrayOf("%" + PrivateDatabaseRules.escapeLike(it) + "%")
                        },
                    ),
                    limit = limit,
                )
            }
        }
    }

    suspend fun healthSummary(
        context: Context,
        sessionId: String,
        argsJson: String,
    ): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrElse { JSONObject() }
        val days = HealthSummaryPolicy.clampDays(
            if (args.has("days")) args.optInt("days") else null,
        )
        return PrivateDatabaseSnapshot.read(
            context = context,
            sessionId = sessionId,
            sourceTemplate = HEALTH_PATH,
            maxBytes = HEALTH_MAX_BYTES,
            unavailableCode = "HEALTH_DATA_UNAVAILABLE",
            unavailableMessage = "the system health data is not reachable right now",
        ) { database ->
            val cutoff = HealthSummaryPolicy.cutoffFor(System.currentTimeMillis(), days)
            val summary = JSONObject()
            database.aggregatePair(
                "steps_record_table",
                "SELECT COUNT(*), COALESCE(SUM(count),0) FROM steps_record_table WHERE end_time>=?",
                cutoff,
            )?.let { summary.put("steps", JSONObject().put("records", it[0]).put("count", it[1])) }
            database.aggregatePair(
                "sleep_session_record_table",
                "SELECT COUNT(*), COALESCE(SUM(end_time-start_time),0) FROM " +
                    "sleep_session_record_table WHERE end_time>=?",
                cutoff,
            )?.let {
                summary.put("sleep", JSONObject().put("sessions", it[0]).put("duration_ms", it[1]))
            }
            database.aggregatePair(
                "exercise_session_record_table",
                "SELECT COUNT(*), COALESCE(SUM(end_time-start_time),0) FROM " +
                    "exercise_session_record_table WHERE end_time>=?",
                cutoff,
            )?.let {
                summary.put("exercise", JSONObject().put("sessions", it[0]).put("duration_ms", it[1]))
            }
            if (database.hasColumns("heart_rate_record_table", setOf("row_id", "end_time")) &&
                database.hasColumns(
                    "heart_rate_record_series_table",
                    setOf("parent_key", "beats_per_minute"),
                )
            ) {
                database.rawQuery(
                    "SELECT COUNT(*), MIN(beats_per_minute), MAX(beats_per_minute), " +
                        "AVG(beats_per_minute) FROM heart_rate_record_series_table " +
                        "WHERE parent_key IN (SELECT row_id FROM heart_rate_record_table " +
                        "WHERE end_time>=?)",
                    arrayOf(cutoff.toString()),
                ).use { cursor ->
                    if (cursor.moveToFirst() && cursor.getLong(0) > 0) {
                        summary.put(
                            "heart_rate",
                            JSONObject()
                                .put("samples", cursor.getLong(0))
                                .put("min_bpm", cursor.getLong(1))
                                .put("max_bpm", cursor.getLong(2))
                                .put("avg_bpm", cursor.getDouble(3)),
                        )
                    }
                }
            }
            database.latestMeasurement("weight_record_table", "time", "weight", cutoff)
                ?.let {
                    summary.put(
                        "latest_weight_kg",
                        HealthSummaryPolicy.weightKgFromRecord(it),
                    )
                }
            database.latestMeasurement(
                "oxygen_saturation_record_table",
                "time",
                "percentage",
                cutoff,
            )?.let { summary.put("latest_oxygen_saturation", it) }
            JSONObject()
                .put("ok", true)
                .put("tool", "get_health_summary")
                .put("window_days", days)
                .put("summary", summary)
                .toString()
        }
    }

    fun handlers(): List<ToolHandler> = listOf(ClipboardHistoryHandler(), HealthSummaryHandler())
}

class ClipboardHistoryHandler : ToolHandler {
    override val definition = AgentToolDefinition(
        name = PrivateDatabaseTools.CLIPBOARD,
        description = "Search the clipboard history the current input method saved, newest first. " +
            "Only available where that input method keeps one; the entries are the user's own " +
            "clipboard content.",
        parameters = mapOf(
            "query" to AgentToolParam("string", "Optional keyword matched against the content"),
            "limit" to AgentToolParam(
                "integer",
                "Max rows (default " + PrivateDatabaseRules.DEFAULT_LIMIT + ", max " +
                    PrivateDatabaseRules.MAX_LIMIT + ")",
            ),
        ),
        required = emptyList(),
    )

    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String) =
        PrivateDatabaseTools.clipboardHistory(context, sessionId, argsJson)
}

class HealthSummaryHandler : ToolHandler {
    override val definition = AgentToolDefinition(
        name = PrivateDatabaseTools.HEALTH,
        description = "Summarise the system health data: steps, sleep, exercise, heart rate, weight " +
            "and oxygen saturation over a window. Raw measurement series are never returned.",
        parameters = mapOf(
            "days" to AgentToolParam(
                "integer",
                "How many days back to summarise (default " + HealthSummaryPolicy.DEFAULT_DAYS +
                    ", max " + HealthSummaryPolicy.MAX_DAYS + ")",
            ),
        ),
        required = emptyList(),
    )

    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String) =
        PrivateDatabaseTools.healthSummary(context, sessionId, argsJson)
}
