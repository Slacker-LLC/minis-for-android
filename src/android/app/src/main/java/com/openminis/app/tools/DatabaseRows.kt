package com.openminis.app.tools

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-eta-xposed-groups] Reading rows out of a database whose schema belongs to somebody else.
 *
 * Ported from Eta `agent/tool/AgentPrivateDatabaseTools.kt` (Mangi-11/Eta @ c15de97); attribution
 * in THIRD_PARTY_LICENSES.md. The file comes from another app and its schema moves between releases,
 * so every query first asks the table what it has, projects only those columns, bounds each text
 * field, and answers with one envelope: ok, tool, items, count, truncated.
 */

internal fun SQLiteDatabase.tableColumns(table: String): Set<String> = runCatching {
    rawQuery("PRAGMA table_info($table)", null).use { cursor ->
        val name = cursor.getColumnIndex("name")
        buildSet { while (cursor.moveToNext()) add(cursor.getString(name)) }
    }
}.getOrDefault(emptySet())

internal fun SQLiteDatabase.hasColumns(table: String, required: Set<String>): Boolean =
    PrivateDatabaseRules.hasColumns(tableColumns(table), required)

internal fun SQLiteDatabase.databaseRows(
    table: String,
    columns: List<String>,
    selection: String?,
    order: String,
    limit: Int,
    selectionArgs: Array<String>? = null,
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
            while (cursor.moveToNext()) rows.put(cursor.toJsonRow())
        }
    }
}

internal fun Cursor.toJsonRow(): JSONObject = JSONObject().also { row ->
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

internal fun databaseOk(tool: String, items: JSONArray, limit: Int): String = JSONObject()
    .put("ok", true)
    .put("tool", tool)
    .put("items", items)
    .put("count", items.length())
    .put("truncated", items.length() == limit)
    .toString()

internal fun databaseError(code: String, message: String): String = JSONObject()
    .put("ok", false)
    .put("code", code)
    .put("message", message)
    .toString()

/** A two-number aggregate (rows, total) over a table, or null when the table is not there. */
internal fun SQLiteDatabase.aggregatePair(table: String, query: String, cutoff: Long): LongArray? {
    if (tableColumns(table).isEmpty()) return null
    return runCatching {
        rawQuery(query, arrayOf(cutoff.toString())).use { cursor ->
            if (!cursor.moveToFirst()) null else longArrayOf(cursor.getLong(0), cursor.getLong(1))
        }
    }.getOrNull()
}

/** The newest value of one column since [cutoff], or null when the table lacks either column. */
internal fun SQLiteDatabase.latestMeasurement(
    table: String,
    timeColumn: String,
    valueColumn: String,
    cutoff: Long,
): Double? {
    if (!hasColumns(table, setOf(timeColumn, valueColumn))) return null
    return runCatching {
        rawQuery(
            "SELECT $valueColumn FROM $table WHERE $timeColumn>=? ORDER BY $timeColumn DESC LIMIT 1",
            arrayOf(cutoff.toString()),
        ).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getDouble(0) else null
        }
    }.getOrNull()
}
