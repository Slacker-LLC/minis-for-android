package com.openminis.app.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Verifies the additive v13 → v14 message-attribution migration against real
 * SQLite. Existing rows must survive, new columns must be writable, and the
 * registered downgrade must not drop the captured identity.
 */
@RunWith(AndroidJUnit4::class)
class MessageAttributionMigrationTest {

    private lateinit var dbFile: File
    private lateinit var rawDb: SQLiteDatabase
    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var supportDb: SupportSQLiteDatabase

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        dbFile = File(context.cacheDir, "message-attribution-migration.db")
        dbFile.delete()
        rawDb = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        rawDb.execSQL(
            """
            CREATE TABLE messages (
                id TEXT NOT NULL PRIMARY KEY,
                session_id TEXT NOT NULL,
                role TEXT NOT NULL,
                parts_json TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                token_usage TEXT,
                sort_order INTEGER NOT NULL,
                reasoning_content TEXT,
                stream_interrupt_count INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER,
                error_info TEXT
            )
            """.trimIndent(),
        )
        rawDb.version = 13
        rawDb.close()

        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbFile.absolutePath)
            .callback(object : SupportSQLiteOpenHelper.Callback(13) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        supportDb = helper.writableDatabase
        rawDb = SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        )
    }

    @After
    fun tearDown() {
        rawDb.close()
        helper.close()
        dbFile.delete()
    }

    private fun columns(): Set<String> = rawDb.rawQuery("PRAGMA table_info(messages)", null).use { cursor ->
        buildSet {
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) add(cursor.getString(nameIndex))
        }
    }

    @Test
    fun upgrade13To14_preservesExistingUsageAndAddsNullableColumns() {
        rawDb.execSQL(
            "INSERT INTO messages (id, session_id, role, parts_json, created_at, token_usage, sort_order) " +
                "VALUES ('old','s1','assistant','[]',100,'{\"inputTokens\":7}',0)",
        )

        AppDatabase.MIGRATION_13_14.migrate(supportDb)

        assertTrue(
            columns().containsAll(
                listOf("model_id", "model_display_name", "provider_type", "provider_instance_id"),
            ),
        )
        rawDb.rawQuery("SELECT token_usage, model_id FROM messages WHERE id = 'old'", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("{\"inputTokens\":7}", cursor.getString(0))
            assertTrue(cursor.isNull(1))
        }
    }

    @Test
    fun downgrade14To13_keepsSnapshotAndAllowsOldInsert() {
        AppDatabase.MIGRATION_13_14.migrate(supportDb)
        rawDb.execSQL(
            "INSERT INTO messages (id, session_id, role, parts_json, created_at, sort_order, " +
                "model_id, model_display_name, provider_type, provider_instance_id) VALUES " +
                "('new','s1','assistant','[]',100,0,'gpt-5.6-sol','GPT-5.6 Sol','openAI','inst-1')",
        )

        AppDatabase.MIGRATION_14_13.migrate(supportDb)

        rawDb.execSQL(
            "INSERT INTO messages (id, session_id, role, parts_json, created_at, sort_order) " +
                "VALUES ('old-style','s1','user','[]',101,1)",
        )
        rawDb.rawQuery(
            "SELECT model_id, model_display_name, provider_type, provider_instance_id " +
                "FROM messages WHERE id = 'new'",
            null,
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("gpt-5.6-sol", cursor.getString(0))
            assertEquals("GPT-5.6 Sol", cursor.getString(1))
            assertEquals("openAI", cursor.getString(2))
            assertEquals("inst-1", cursor.getString(3))
        }
        rawDb.rawQuery("SELECT model_id FROM messages WHERE id = 'old-style'", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
        }
    }
}
