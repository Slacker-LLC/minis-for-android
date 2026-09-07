package com.openminis.app.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.BotRepository
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Verifies the Bot schema's additive 14 → 20 upgrade path on real SQLite.
 * In particular, `enabled` is introduced only by 18 → 19; 14 → 15 must not
 * create it early or the final migration would attempt to add a duplicate
 * column on existing installs.
 */
@RunWith(AndroidJUnit4::class)
class BotMigrationTest {

    private lateinit var dbFile: File
    private lateinit var rawDb: SQLiteDatabase
    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var supportDb: SupportSQLiteDatabase

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        dbFile = File(context.cacheDir, "bot-migration.db")
        dbFile.delete()
        rawDb = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        rawDb.execSQL(
            """
            CREATE TABLE sessions (
                id TEXT NOT NULL PRIMARY KEY,
                title TEXT,
                model_id TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        rawDb.version = 14
        rawDb.close()

        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbFile.absolutePath)
            .callback(object : SupportSQLiteOpenHelper.Callback(14) {
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

    private fun columns(table: String): Set<String> =
        rawDb.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            buildSet {
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }

    @Test
    fun upgrade14To20_addsBotTablesAndEnabledOnce() {
        AppDatabase.MIGRATION_14_15.migrate(supportDb)
        assertTrue("enabled must be deferred to 18 → 19", "enabled" !in columns("bots"))

        AppDatabase.MIGRATION_15_16.migrate(supportDb)
        AppDatabase.MIGRATION_16_17.migrate(supportDb)
        AppDatabase.MIGRATION_17_18.migrate(supportDb)
        AppDatabase.MIGRATION_18_19.migrate(supportDb)
        AppDatabase.MIGRATION_19_20.migrate(supportDb)

        assertTrue(columns("bots").contains("enabled"))
        assertTrue(columns("bot_delegations").containsAll(listOf("source_turn_settled", "source_tool_id")))
        assertTrue(columns("bot_delegations").contains("root_task_id"))
        assertTrue(columns("bot_tasks").containsAll(listOf("goal", "phase", "stop_generation")))
        assertTrue(columns("bot_inbox_events").containsAll(listOf("dedupe_key", "lease_expires_at", "status")))
        rawDb.rawQuery(
            "SELECT sql FROM sqlite_master WHERE type = 'index' AND name = 'index_bot_delegations_source_tool'",
            null,
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.getString(0).contains("UNIQUE"))
        }
    }

    @Test
    fun directConversation_keepsBindingAndSkipsInternalExecution() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repository = ChatRepository(db.chatDao())
            val binding = "{\"type\":\"entry\",\"entryId\":\"chosen-entry\"}"
            val direct = repository.createSession("model", botId = "reviewer", modelBinding = binding)
            db.chatDao().insertSession(direct.copy(updatedAt = 10, createdAt = 10))
            listOf("subagent", "bot_delegation", "bot-delegation").forEachIndexed { index, source ->
                db.chatDao().insertSession(direct.copy(id = "internal-$index", source = source, updatedAt = 20))
            }
            assertEquals(direct.id, repository.latestBotConversation("reviewer")?.id)
            assertEquals(binding, repository.getSession(direct.id)?.modelBinding)
            assertEquals(null, repository.latestBotConversation("another-bot"))
        } finally {
            db.close()
        }
    }

    @Test
    fun deletingMember_keepsConversationAndClearsIdentity() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val bots = BotRepository(db.botDao())
            val chats = ChatRepository(db.chatDao())
            val role = "Review changes.\n\n- Read the diff.\n- Check failures."
            val bot = bots.createBot("Reviewer", role)
            val session = chats.createSession("model", botId = bot.id)
            assertEquals(role, bots.getBot(bot.id)?.systemPrompt)
            assertTrue(bots.deleteBot(bot.id))
            assertEquals(null, bots.getBot(bot.id))
            assertEquals(session.copy(botId = null), chats.getSession(session.id))
        } finally {
            db.close()
        }
    }

    @Test
    fun claimingTaskPersistsTargetAndRecoveryCancelsOnlyUnsettledWork() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val tasks = com.openminis.app.data.repository.BotDelegationRepository(db.botDelegationDao())
            suspend fun enqueue(runId: String) = tasks.enqueue("owner", "source-session", runId,
                rootTaskId = "root-task",
                targetBotId = "worker", prompt = "Review this change")
            val running = enqueue("settled-run")
            val unsettled = enqueue("abandoned-run")
            assertEquals(false, tasks.claim(unsettled.id, "must-not-start"))
            tasks.markSourceRunSettled("source-session", "settled-run")
            assertTrue(tasks.claim(running.id, "target-session"))
            assertEquals(false, tasks.claim(running.id, "duplicate-session"))
            assertEquals("target-session", tasks.get(running.id)?.targetSessionId)
            assertEquals("root-task", tasks.get(running.id)?.rootTaskId)
            assertEquals(1, tasks.cancelUnsettledAfterProcessStart())
            assertEquals(BotDelegationEntity.STATUS_CANCELLED, tasks.get(unsettled.id)?.status)
            assertEquals(BotDelegationEntity.STATUS_RUNNING, tasks.get(running.id)?.status)
            tasks.fail(running.id, "process stopped", outcomeUnknown = true)
            assertEquals("target-session", tasks.get(running.id)?.targetSessionId)
            assertEquals(1, tasks.get(running.id)?.outcomeUnknown)
            assertEquals(0, tasks.listDispatchable().size)
        } finally {
            db.close()
        }
    }

    @Test
    fun rootTaskAndInbox_areIdempotentAndOwnerScoped() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val tasks = com.openminis.app.data.repository.BotTaskRepository(db.botTaskDao())
            val first = tasks.ensureRootTask(
                originSessionId = "source-session",
                originMessageId = "message-1",
                ownerBotId = "owner",
                goal = "  Review the login recovery path  ",
            )
            val replay = tasks.ensureRootTask(
                originSessionId = "source-session",
                originMessageId = "message-1",
                ownerBotId = "owner",
                goal = "A replay must not create a second root",
            )
            assertEquals(first.id, replay.id)
            assertTrue(tasks.requestRevision(first.id))
            assertEquals(BotTaskEntity.PHASE_REVISING, tasks.get(first.id)?.phase)

            val inbox = com.openminis.app.data.repository.BotInboxRepository(db.botInboxEventDao())
            val event = inbox.enqueue(
                dedupeKey = "delegation:${first.id}:attempt-1",
                rootTaskId = first.id,
                recipientBotId = "owner",
                type = BotInboxEventEntity.TYPE_DELEGATION_SUBMITTED,
                payloadJson = "{\"summary\":\"ready\"}",
            )
            val duplicate = inbox.enqueue(
                dedupeKey = "delegation:${first.id}:attempt-1",
                rootTaskId = first.id,
                recipientBotId = "owner",
                type = BotInboxEventEntity.TYPE_DELEGATION_SUBMITTED,
                payloadJson = "{\"summary\":\"replayed\"}",
            )
            assertEquals(event.id, duplicate.id)
            val claimed = inbox.claimPending("owner", "wake-1")
            assertEquals(listOf(event.id), claimed.map { it.id })
            assertEquals(false, inbox.consume(event.id, "wrong-owner"))
            assertEquals(true, inbox.consume(event.id, "wake-1"))
            assertEquals(emptyList<BotInboxEventEntity>(), inbox.claimPending("owner", "wake-2"))
        } finally {
            db.close()
        }
    }
}
