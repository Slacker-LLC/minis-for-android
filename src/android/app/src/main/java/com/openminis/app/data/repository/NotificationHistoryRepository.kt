package com.openminis.app.data.repository

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.openminis.app.notifications.NotificationHistoryPolicy
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-eta-notification-history] The bounded, self-expiring notification history: seven days,
 * at most one thousand records, four thousand characters per field.
 *
 * Ported from Eta `data/repository/NotificationHistoryRepository.kt` (Mangi-11/Eta @
 * c15de97); attribution in THIRD_PARTY_LICENSES.md. Eta's comment states the contract this
 * port keeps: notifications are stored only while the owner has granted Notification
 * Access, and the raw content never enters the persisted agent conversation — the tools that
 * read this store are classified sensitive, so the transcript keeps a placeholder.
 */
class NotificationHistoryRepository(context: Context) {

    private val database = Database(context.applicationContext)

    fun record(
        key: String,
        packageName: String,
        title: String?,
        text: String?,
        subText: String?,
        postedAt: Long,
    ) {
        if (!NotificationHistoryPolicy.isRecordable(title, text, subText)) return
        val values = ContentValues().apply {
            put("notification_key", key.take(NotificationHistoryPolicy.MAX_KEY_CHARS))
            put("package_name", packageName.take(NotificationHistoryPolicy.MAX_PACKAGE_CHARS))
            put("title", NotificationHistoryPolicy.bounded(title))
            put("text", NotificationHistoryPolicy.bounded(text))
            put("sub_text", NotificationHistoryPolicy.bounded(subText))
            put("posted_at", postedAt)
        }
        database.writableDatabase.transaction {
            insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            // Retention first: an expired row must not survive because the cap was not hit.
            delete(TABLE, "posted_at<?", arrayOf((System.currentTimeMillis() - NotificationHistoryPolicy.RETENTION_MS).toString()))
            execSQL(
                "DELETE FROM $TABLE WHERE notification_key NOT IN " +
                    "(SELECT notification_key FROM $TABLE ORDER BY posted_at DESC LIMIT " +
                    "${NotificationHistoryPolicy.MAX_RECORDS})",
            )
        }
    }

    /** Newest first, filtered by keyword and package, bounded by the caller's limit. */
    fun search(query: String, packageName: String, maxAgeHours: Int, limit: Int): List<JSONObject> {
        val clauses = mutableListOf("posted_at>=?")
        val args = mutableListOf(
            (System.currentTimeMillis() - maxAgeHours * NotificationHistoryPolicy.HOUR_MS).toString(),
        )
        if (packageName.isNotBlank()) {
            clauses += "package_name=?"
            args += packageName
        }
        if (query.isNotBlank()) {
            clauses += "(title LIKE ? ESCAPE '\\' OR text LIKE ? ESCAPE '\\' OR sub_text LIKE ? ESCAPE '\\')"
            val pattern = "%${NotificationHistoryPolicy.escapeLike(query)}%"
            repeat(3) { args += pattern }
        }
        val items = mutableListOf<JSONObject>()
        database.readableDatabase.query(
            TABLE,
            arrayOf("package_name", "title", "text", "sub_text", "posted_at"),
            clauses.joinToString(" AND "),
            args.toTypedArray(),
            null,
            null,
            "posted_at DESC",
            limit.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                items += JSONObject()
                    .put("package_name", cursor.getString(0))
                    .put("title", cursor.getString(1) ?: JSONObject.NULL)
                    .put("text", cursor.getString(2) ?: JSONObject.NULL)
                    .put("sub_text", cursor.getString(3) ?: JSONObject.NULL)
                    .put("posted_at", cursor.getLong(4))
            }
        }
        return items
    }

    /** How many records the store currently holds (diagnostics and tool summaries). */
    fun count(): Int = database.readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { cursor ->
        if (cursor.moveToFirst()) cursor.getInt(0) else 0
    }

    private fun SQLiteDatabase.transaction(block: SQLiteDatabase.() -> Unit) {
        beginTransaction()
        try {
            block()
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }

    private class Database(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE (" +
                    "notification_key TEXT PRIMARY KEY NOT NULL," +
                    "package_name TEXT NOT NULL," +
                    "title TEXT,text TEXT,sub_text TEXT,posted_at INTEGER NOT NULL)",
            )
            db.execSQL("CREATE INDEX notification_history_posted_at ON $TABLE(posted_at DESC)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private companion object {
        const val DATABASE_NAME = "minis_notification_history.db"
        const val TABLE = "notification_history"
    }
}
