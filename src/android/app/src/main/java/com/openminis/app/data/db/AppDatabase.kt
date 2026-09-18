package com.openminis.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

const val APP_DATABASE_VERSION = 20

@Database(
    entities = [
        ChatSessionEntity::class,
        MessageEntity::class,
        CompactMarkerEntity::class,
        WebAppShortcutEntity::class,
        FolderEntity::class,
        SessionEventEntity::class,
        BotEntity::class,
        BotDelegationEntity::class,
        BotTaskEntity::class,
        BotInboxEventEntity::class,
    ],
    version = APP_DATABASE_VERSION,
    // Keep the Room schema history committed so migration and downgrade
    // checks can inspect the real entity shape, including custom tables.
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun webAppShortcutDao(): WebAppShortcutDao
    abstract fun botDao(): BotDao
    abstract fun botDelegationDao(): BotDelegationDao
    abstract fun botTaskDao(): BotTaskDao
    abstract fun botInboxEventDao(): BotInboxEventDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN last_message TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN model_binding TEXT")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN reasoning_content TEXT")
            }
        }

        /**
         * compact_markers: add Phase-A id-first boundary columns. The legacy
         * sort_order columns stay for backfill; when both are present the
         * id-first fields win on lookup (see ChatDao.latestCompactMarker).
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE compact_markers ADD COLUMN first_kept_message_id TEXT")
                db.execSQL("ALTER TABLE compact_markers ADD COLUMN last_compacted_message_id TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_compact_markers_first_kept_message_id ON compact_markers(first_kept_message_id)")
            }
        }

        /**
         * T239: per-session thinking-mode override. Nullable so existing
         * sessions transparently keep "unset" semantics; only sessions where
         * the user explicitly chooses a level start storing a non-null value.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN thinking_override TEXT")
            }
        }

        /**
         * T-pwa-1: pwa_shortcuts table backs the home-screen PWA pinning
         * flow. Pure additive migration — no existing entity is modified
         * and no data is rewritten.
         *
         * Superseded by MIGRATION_8_9 below (Pwa → WebApp rename); kept
         * here so users who already migrated from <=6 land on a
         * consistent state before the rename runs.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS pwa_shortcuts (
                        id TEXT NOT NULL PRIMARY KEY,
                        html_path TEXT NOT NULL,
                        path_scope TEXT NOT NULL,
                        scope_context TEXT,
                        title TEXT NOT NULL,
                        icon_ref TEXT NOT NULL,
                        icon_cache_path TEXT,
                        created_at INTEGER NOT NULL,
                        source_session_id TEXT
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * compact_markers: add `version` column for marker schema versioning.
         * Mirrors iOS Phase v2 — version=1 = legacy multi-field model,
         * version=2 = simplified id-only anchor model. Existing rows default
         * to 1 so legacy resolution code keeps running for them.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE compact_markers ADD COLUMN version INTEGER NOT NULL DEFAULT 1")
            }
        }

        /**
         * Pwa → WebApp rename: copy every row from `pwa_shortcuts` into a
         * new `webapp_shortcuts` table with identical schema, then drop
         * the old table. Row contents (UUIDs, html paths, icon refs) are
         * preserved verbatim — only the table name changes — so existing
         * in-app shortcut lists keep showing the same entries.
         *
         * Note: pinned launcher icons created before this rename still
         * carry the old `ACTION_OPEN_PWA` intent action and will be dead
         * after the upgrade (manifest no longer registers it). The user
         * has to re-pin from inside the app. Per
         * `feedback_no_destructive_git` we do NOT silently delete data —
         * the DB row stays, only the launcher-side icon dies.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS webapp_shortcuts (
                        id TEXT NOT NULL PRIMARY KEY,
                        html_path TEXT NOT NULL,
                        path_scope TEXT NOT NULL,
                        scope_context TEXT,
                        title TEXT NOT NULL,
                        icon_ref TEXT NOT NULL,
                        icon_cache_path TEXT,
                        created_at INTEGER NOT NULL,
                        source_session_id TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO webapp_shortcuts (
                        id, html_path, path_scope, scope_context, title,
                        icon_ref, icon_cache_path, created_at, source_session_id
                    )
                    SELECT
                        id, html_path, path_scope, scope_context, title,
                        icon_ref, icon_cache_path, created_at, source_session_id
                    FROM pwa_shortcuts
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE IF EXISTS pwa_shortcuts")
            }
        }

        /**
         * [T-error-persist-android] messages.error_info — persist the terminal
         * error sticker on an assistant turn so the inline error survives a
         * session reload (mirrors iOS messages.error_info). Pure additive,
         * nullable column; existing rows read back NULL (= no error). No data
         * rewrite.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN error_info TEXT")
            }
        }

        /**
         * [T-android-session-grouping] Session groups. Adds the `folders` table
         * and `sessions.folder_id`.
         *
         * Purely additive: existing sessions read back `folder_id = NULL`
         * (= ungrouped), which is exactly the pre-migration behaviour, so no
         * data is rewritten and a downgrade loses only the grouping.
         *
         * `folder_id` carries NO foreign key on purpose — an id pointing at a
         * group that is not present locally must render as ungrouped rather
         * than fail a constraint (see ChatSessionEntity.folderId). The index is
         * plain and non-unique: many sessions share one group.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS folders (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        icon TEXT,
                        color TEXT,
                        origin TEXT NOT NULL DEFAULT 'manual',
                        sort_index INTEGER NOT NULL DEFAULT 0,
                        pinned_at INTEGER,
                        description TEXT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("ALTER TABLE sessions ADD COLUMN folder_id TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_folder_id ON sessions(folder_id)")
            }
        }

        /**
         * Durable, bounded replay window for the DSH-style session event log.
         * Existing messages remain the initial snapshot; this additive table
         * carries only live state transitions after a snapshot cursor.
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS session_events (
                        session_id TEXT NOT NULL,
                        seq INTEGER NOT NULL,
                        type TEXT NOT NULL,
                        payload_json TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        PRIMARY KEY(session_id, seq),
                        FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_session_events_session_id_seq " +
                        "ON session_events(session_id, seq)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_session_events_created_at " +
                        "ON session_events(created_at)",
                )
            }
        }

        /**
         * GH#32: sparse per-session configuration. Existing rows inherit all
         * globals because the new column is nullable and defaults to NULL.
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN session_overrides TEXT")
            }
        }

        /**
         * [T-token-attribution-snapshot] Record the model/provider that
         * produced each message. All columns are nullable and have no default:
         * existing rows remain intact and are explicitly treated as legacy
         * estimates by the usage screen.
         */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN model_id TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN model_display_name TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN provider_type TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN provider_instance_id TEXT")
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS bots (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        system_prompt TEXT,
                        model_binding TEXT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("ALTER TABLE sessions ADD COLUMN bot_id TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_bot_id ON sessions(bot_id)")
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS bot_delegations (
                        id TEXT NOT NULL PRIMARY KEY,
                        source_bot_id TEXT NOT NULL,
                        source_session_id TEXT NOT NULL,
                        source_run_id TEXT NOT NULL,
                        target_bot_id TEXT NOT NULL,
                        target_session_id TEXT,
                        prompt TEXT NOT NULL,
                        depth INTEGER NOT NULL,
                        attempts INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        outcome_unknown INTEGER NOT NULL,
                        result_text TEXT,
                        error_text TEXT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        started_at INTEGER,
                        finished_at INTEGER,
                        delivered_at INTEGER
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bot_delegations_source_run ON bot_delegations(source_session_id, source_run_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bot_delegations_target_status ON bot_delegations(target_bot_id, status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bot_delegations_status_created ON bot_delegations(status, created_at)")
            }
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // QUEUED rows created before their source Turn settled remain
                // blocked (DEFAULT 0) and therefore cannot revive on startup.
                db.execSQL("ALTER TABLE bot_delegations ADD COLUMN source_turn_settled INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bots ADD COLUMN enabled INTEGER NOT NULL DEFAULT 1")
            }
        }

        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Nullable for legacy rows; new rows carry the originating tool
                // call ID and are protected by a unique source-run index.
                db.execSQL("ALTER TABLE bot_delegations ADD COLUMN source_tool_id TEXT")
                db.execSQL("DROP INDEX IF EXISTS index_bot_delegations_source_tool")
                // The tool call ID remains stable across a replay that receives
                // a newly assigned sourceRunId, so scope uniqueness to the
                // source session and tool call itself.
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_bot_delegations_source_tool ON bot_delegations(source_session_id, source_tool_id)")
            }
        }

        /**
         * Root-task and owner-inbox foundation. Existing delegations remain
         * valid with a NULL root_task_id; new orchestration can opt into a
         * durable root without rewriting historical work.
         */
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bot_delegations ADD COLUMN root_task_id TEXT")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_bot_delegations_root_created " +
                        "ON bot_delegations(root_task_id, created_at)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS bot_tasks (
                        id TEXT NOT NULL PRIMARY KEY,
                        origin_session_id TEXT NOT NULL,
                        origin_message_id TEXT,
                        owner_bot_id TEXT NOT NULL,
                        goal TEXT NOT NULL,
                        acceptance_criteria TEXT,
                        status TEXT NOT NULL,
                        phase TEXT NOT NULL,
                        revision INTEGER NOT NULL,
                        current_owner_session_id TEXT,
                        auto_runs_used INTEGER NOT NULL,
                        delegations_used INTEGER NOT NULL,
                        revision_rounds_used INTEGER NOT NULL,
                        stop_generation INTEGER NOT NULL,
                        deadline_at INTEGER,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        completed_at INTEGER
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bot_tasks_origin ON bot_tasks(origin_session_id, origin_message_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bot_tasks_owner_status ON bot_tasks(owner_bot_id, status, updated_at)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bot_tasks_status_updated ON bot_tasks(status, updated_at)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS bot_inbox_events (
                        id TEXT NOT NULL PRIMARY KEY,
                        dedupe_key TEXT NOT NULL,
                        root_task_id TEXT,
                        recipient_bot_id TEXT NOT NULL,
                        recipient_session_id TEXT,
                        type TEXT NOT NULL,
                        producer_delegation_id TEXT,
                        producer_event_id TEXT,
                        payload_json TEXT NOT NULL,
                        status TEXT NOT NULL,
                        lease_owner TEXT,
                        lease_expires_at INTEGER,
                        wake_batch TEXT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        consumed_at INTEGER
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_bot_inbox_events_dedupe ON bot_inbox_events(dedupe_key)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bot_inbox_events_recipient_status ON bot_inbox_events(recipient_bot_id, status, created_at)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bot_inbox_events_root_created ON bot_inbox_events(root_task_id, created_at)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bot_inbox_events_lease ON bot_inbox_events(status, lease_expires_at)")
            }
        }

        /**
         * [T-android-downgrade-compat] Keep the additive attribution columns
         * during a 14 → 13 downgrade. Older Room entities ignore extra columns,
         * while dropping them would destroy the only exact attribution record.
         */
        val MIGRATION_14_13 = object : Migration(14, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Intentionally empty; see MIGRATION_13_14.
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // sessions: add iOS-parity columns
                db.execSQL("ALTER TABLE sessions ADD COLUMN source TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN memory_enabled INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE sessions ADD COLUMN pinned_at INTEGER")
                db.execSQL("ALTER TABLE sessions ADD COLUMN edit_count INTEGER NOT NULL DEFAULT 0")

                // messages: add iOS-parity columns
                db.execSQL("ALTER TABLE messages ADD COLUMN stream_interrupt_count INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN updated_at INTEGER")

                // compact_markers: new table mirroring iOS
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS compact_markers (
                        id TEXT NOT NULL PRIMARY KEY,
                        session_id TEXT NOT NULL,
                        summary TEXT NOT NULL,
                        first_kept_sort_order INTEGER NOT NULL,
                        compacted_count INTEGER NOT NULL,
                        created_at INTEGER NOT NULL,
                        ui_boundary_sort_order INTEGER,
                        boundary_message_id TEXT,
                        FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_compact_markers_session_id ON compact_markers(session_id)")
            }
        }

        /** Return the already-open singleton used by the app repositories. */
        internal fun getInitializedInstance(): AppDatabase =
            checkNotNull(INSTANCE) { "AppDatabase is not initialized" }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "minis.db"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12,
                        MIGRATION_12_13,
                        MIGRATION_13_14,
                        MIGRATION_14_15,
                        MIGRATION_15_16,
                        MIGRATION_16_17,
                        MIGRATION_17_18,
                        MIGRATION_18_19,
                        MIGRATION_19_20,
                        MIGRATION_14_13,
                    )
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
