package com.openminis.app.tools

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-eta-xposed-groups] The alarms and timers the clock app actually holds, read from its own
 * database.
 *
 * Ported from Eta `agent/tool/AgentPrivateDatabaseTools.kt` (Mangi-11/Eta @ c15de97); attribution
 * in THIRD_PARTY_LICENSES.md. Android's alarm API is fire-and-forget, so "what is set right now"
 * exists only inside the clock app; the snapshot in [PrivateDatabaseSnapshot] is what makes that
 * readable. Path, table and columns are fixed here, and a schema that does not carry the columns the
 * query needs is refused instead of guessed.
 */
object ClockDatabaseTools {

    private const val DATABASE_PATH =
        "/data/user_de/{user}/com.coloros.alarmclock/databases/alarms.db"
    private const val MAX_DATABASE_BYTES = 32L * 1024 * 1024

    /** Eta's answer when the clock app's data cannot be reached at all. */
    private const val UNAVAILABLE_CODE = "CLOCK_DATA_UNAVAILABLE"
    private const val UNAVAILABLE_MESSAGE = "the clock database is not reachable right now"

    /** …and when it can be reached but is not the schema this tool knows. */
    private const val SCHEMA_CODE = "CLOCK_SCHEMA_UNSUPPORTED"

    suspend fun listAlarms(
        context: Context,
        sessionId: String,
        argsJson: String,
    ): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrElse { JSONObject() }
        val limit = PrivateDatabaseRules.clampLimit(
            if (args.has("limit")) args.optInt("limit") else null,
        )
        val enabledOnly = if (args.has("enabled_only")) args.optBoolean("enabled_only") else true
        return read(context, sessionId) { database ->
            if (!database.hasColumns("alarms", setOf("_id", "hour", "minutes", "enabled"))) {
                databaseError(SCHEMA_CODE, "the clock database schema is not one this tool knows")
            } else {
                databaseOk(
                    tool = "list_alarms",
                    items = database.databaseRows(
                        table = "alarms",
                        columns = listOf(
                            "_id", "hour", "minutes", "daysofweek", "alarmtime", "enabled",
                            "message", "vibrate", "deleteAfterUse", "workdaySwitch",
                            "holidaySwitch", "snoozeTime",
                        ),
                        selection = if (enabledOnly) "enabled=1" else null,
                        order = "enabled DESC, alarmtime ASC",
                        limit = limit,
                    ),
                    limit = limit,
                )
            }
        }
    }

    suspend fun listTimers(
        context: Context,
        sessionId: String,
        argsJson: String,
    ): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrElse { JSONObject() }
        val limit = PrivateDatabaseRules.clampLimit(
            if (args.has("limit")) args.optInt("limit") else null,
        )
        return read(context, sessionId) { database ->
            if (!database.hasColumns("timer_schedule", setOf("_id", "duration", "state"))) {
                databaseError(SCHEMA_CODE, "the clock database schema is not one this tool knows")
            } else {
                databaseOk(
                    tool = "list_active_timers",
                    items = database.databaseRows(
                        table = "timer_schedule",
                        columns = listOf(
                            "_id", "description", "duration", "state", "first_start_time",
                            "start_time", "remain_time", "pause_remain_time", "alert_time",
                        ),
                        // A timer row with state 0 is one that was finished or cleared.
                        selection = "state<>0",
                        order = "alert_time ASC",
                        limit = limit,
                    ),
                    limit = limit,
                )
            }
        }
    }

    private suspend fun read(
        context: Context,
        sessionId: String,
        block: (SQLiteDatabase) -> String,
    ): ToolExecutionResult = PrivateDatabaseSnapshot.read(
        context = context,
        sessionId = sessionId,
        sourceTemplate = DATABASE_PATH,
        maxBytes = MAX_DATABASE_BYTES,
        unavailableCode = UNAVAILABLE_CODE,
        unavailableMessage = UNAVAILABLE_MESSAGE,
        block = block,
    )

}
