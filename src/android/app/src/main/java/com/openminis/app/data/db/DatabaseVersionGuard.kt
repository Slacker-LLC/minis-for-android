package com.openminis.app.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.openminis.app.logging.AppLogger

/**
 * Refuses to let Room open a minis.db written by a newer build when this
 * build has no verified downgrade path. The probe is read-only, so user data
 * remains untouched and reinstalling/upgrading the newer APK recovers it.
 */
object DatabaseVersionGuard {
    private const val TAG = "DbVersionGuard"
    const val CODE_DB_VERSION = 20
    private const val DB_NAME = "minis.db"

    fun readOnDiskVersion(context: Context): Int? {
        val file = context.getDatabasePath(DB_NAME)
        if (!file.exists()) return null
        return runCatching {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
                .use { it.version }
        }.getOrElse {
            AppLogger.warning(TAG, "could not probe db version: ${it.javaClass.simpleName}")
            null
        }
    }

    enum class Decision { PROCEED, SHOW_NEWER_DB_GUIDANCE }

    fun evaluate(context: Context): Decision {
        val onDisk = readOnDiskVersion(context) ?: return Decision.PROCEED
        if (onDisk <= CODE_DB_VERSION) return Decision.PROCEED
        AppLogger.warning(TAG, "database is from a newer build: onDisk=$onDisk code=$CODE_DB_VERSION")
        return Decision.SHOW_NEWER_DB_GUIDANCE
    }
}
