package com.openminis.app.tools

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.openminis.app.tools.android.CommandRisk
import com.openminis.app.tools.android.PrivilegedCommandRunner
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-eta-xposed-groups] The alarms and timers the clock app actually holds, read from its own
 * database.
 *
 * Ported from Eta `agent/tool/AgentPrivateDatabaseTools.kt` (Mangi-11/Eta @ c15de97); attribution
 * in THIRD_PARTY_LICENSES.md. Android's alarm API is fire-and-forget, so "what is set right now"
 * exists only inside the clock app; Eta answers it by copying that app's database into its own cache
 * and querying the copy read-only. This project's privileged surface takes arguments, so Eta's one
 * shell script becomes the same steps as separate commands - exists, is not a symlink, is within the
 * size cap, copy - for the database and for each SQLite sidecar, and the snapshot is always deleted
 * afterwards. Path, table and columns are fixed here; no caller input reaches them.
 */
object ClockDatabaseTools {

    private const val DATABASE_PATH = "/data/user_de/{user}/com.coloros.alarmclock/databases/alarms.db"
    private const val MAX_DATABASE_BYTES = 32L * 1024 * 1024
    private const val SNAPSHOT_PREFIX = "minis-clock-"
    private const val SNAPSHOT_TIMEOUT_MS = 15_000L

    private val SIDECAR_SUFFIXES = listOf("-wal", "-shm", "-journal")

    /** Eta's answer when the clock app's data cannot be reached at all. */
    private const val UNAVAILABLE_CODE = "CLOCK_DATA_UNAVAILABLE"

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
                error(SCHEMA_CODE, "the clock database schema is not one this tool knows")
            } else {
                ok(
                    tool = "list_alarms",
                    items = database.rows(
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
                error(SCHEMA_CODE, "the clock database schema is not one this tool knows")
            } else {
                ok(
                    tool = "list_active_timers",
                    items = database.rows(
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
    ): ToolExecutionResult {
        val userId = context.dataDir.parentFile?.name?.toIntOrNull()
            ?: return ToolExecutionResult(
                error(UNAVAILABLE_CODE, "the current Android user could not be determined"),
                false,
            )
        val source = DATABASE_PATH.replace("{user}", userId.toString())
        val snapshot = createSnapshot(context, sessionId, source)
            ?: return ToolExecutionResult(
                error(UNAVAILABLE_CODE, "the clock database is not reachable right now"),
                false,
            )
        return try {
            val content = runCatching {
                SQLiteDatabase.openDatabase(
                    snapshot.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                ).use(block)
            }.getOrElse {
                error(UNAVAILABLE_CODE, "the clock database snapshot could not be read")
            }
            ToolExecutionResult(content, JSONObject(content).optBoolean("ok"))
        } finally {
            deleteSnapshot(snapshot)
        }
    }

    /** The database, and then each sidecar; any refusal fails the whole snapshot, as upstream. */
    private suspend fun createSnapshot(
        context: Context,
        sessionId: String,
        source: String,
    ): File? {
        cleanupStaleSnapshots(context)
        val snapshot = runCatching {
            File.createTempFile(SNAPSHOT_PREFIX, ".db", context.cacheDir)
        }.getOrNull() ?: return null
        if (copyIfCopyable(context, sessionId, source, snapshot) != true) {
            deleteSnapshot(snapshot)
            return null
        }
        SIDECAR_SUFFIXES.forEach { suffix ->
            val sidecar = File(snapshot.absolutePath + suffix)
            when (copyIfCopyable(context, sessionId, source + suffix, sidecar)) {
                true -> Unit
                // A missing sidecar is normal: the database may never have had a WAL.
                false -> sidecar.delete()
                null -> {
                    deleteSnapshot(snapshot)
                    return null
                }
            }
        }
        return snapshot
    }

    /**
     * True when copied, false when the source is not there, null when it was refused (a link, or a
     * file past the cap) or the copy failed.
     */
    private suspend fun copyIfCopyable(
        context: Context,
        sessionId: String,
        source: String,
        target: File,
    ): Boolean? {
        val size = fileSize(context, sessionId, source) ?: return false
        if (PrivateDatabaseRules.exceedsSizeCap(size, MAX_DATABASE_BYTES)) return null
        // A link would make the copy read something other than the file that was measured.
        if (isSymlink(context, sessionId, source)) return null
        val result = privileged(
            context = context,
            sessionId = sessionId,
            argv = listOf("cp", source, target.absolutePath),
            operation = "snapshot $source",
        )
        return if (result.success) true else null
    }

    private suspend fun fileSize(context: Context, sessionId: String, path: String): Long? {
        val result = privileged(
            context = context,
            sessionId = sessionId,
            argv = listOf("stat", "-c", "%s", path),
            operation = "stat $path",
        )
        if (!result.success) return null
        return result.stdout.trim().lineSequence().firstOrNull()?.toLongOrNull()
    }

    private suspend fun isSymlink(context: Context, sessionId: String, path: String): Boolean {
        val result = privileged(
            context = context,
            sessionId = sessionId,
            argv = listOf("readlink", path),
            operation = "readlink $path",
        )
        // readlink exits non-zero for a path that is not a link, which is the normal case here.
        return result.success && PrivateDatabaseRules.isSymlink(result.stdout)
    }

    private suspend fun privileged(
        context: Context,
        sessionId: String,
        argv: List<String>,
        operation: String,
    ) = PrivilegedCommandRunner.run(
        context = context,
        sessionId = sessionId.ifBlank { "global" },
        argv = argv,
        operation = operation,
        risk = CommandRisk.READ_ONLY,
        timeoutMs = SNAPSHOT_TIMEOUT_MS,
    )

    private fun SQLiteDatabase.hasColumns(table: String, required: Set<String>): Boolean =
        PrivateDatabaseRules.hasColumns(tableColumns(table), required)

    private fun SQLiteDatabase.tableColumns(table: String): Set<String> = runCatching {
        rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val name = cursor.getColumnIndex("name")
            buildSet { while (cursor.moveToNext()) add(cursor.getString(name)) }
        }
    }.getOrDefault(emptySet())

    private fun SQLiteDatabase.rows(
        table: String,
        columns: List<String>,
        selection: String?,
        selectionArgs: Array<String>? = null,
        order: String,
        limit: Int,
    ): JSONArray {
        // Only the columns the table really has are asked for, so an extra column on another build
        // narrows the answer instead of failing the query.
        val available = tableColumns(table)
        val projection = columns.filter(available::contains)
        return JSONArray().also { rows ->
            query(
                table,
                projection.toTypedArray(),
                selection,
                selectionArgs,
                null,
                null,
                order,
                limit.toString(),
            ).use { cursor ->
                while (cursor.moveToNext()) rows.put(cursor.toJson())
            }
        }
    }

    private fun Cursor.toJson(): JSONObject = JSONObject().also { row ->
        for (index in 0 until columnCount) {
            if (isNull(index)) continue
            val value: Any = when (getType(index)) {
                Cursor.FIELD_TYPE_INTEGER -> getLong(index)
                Cursor.FIELD_TYPE_FLOAT -> getDouble(index)
                Cursor.FIELD_TYPE_STRING ->
                    getString(index).take(PrivateDatabaseRules.MAX_FIELD_CHARS)
                else -> continue
            }
            row.put(getColumnName(index), value)
        }
    }

    private fun ok(tool: String, items: JSONArray, limit: Int): String = JSONObject()
        .put("ok", true)
        .put("tool", tool)
        .put("items", items)
        .put("count", items.length())
        .put("truncated", items.length() == limit)
        .toString()

    private fun error(code: String, message: String): String = JSONObject()
        .put("ok", false)
        .put("code", code)
        .put("message", message)
        .toString()

    private fun cleanupStaleSnapshots(context: Context) {
        context.cacheDir.listFiles()
            ?.filter { it.name.startsWith(SNAPSHOT_PREFIX) }
            ?.forEach(File::delete)
    }

    private fun deleteSnapshot(snapshot: File) {
        listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
            File(snapshot.absolutePath + suffix).delete()
        }
    }
}
