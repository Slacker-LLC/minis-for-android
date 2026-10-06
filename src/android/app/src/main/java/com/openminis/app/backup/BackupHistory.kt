package com.openminis.app.backup

import android.content.Context
import com.openminis.app.logging.AppLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * [T-android-backup-history] Persistent record of backup runs. The Android peer
 * of `src/ios/Agent/Backup/BackupHistory.swift`.
 *
 * ## Why this exists
 *
 * The screen used to show a transient "Backup ready" card: it reflected only
 * the LAST run, held nothing once the user navigated away, and — the part that
 * actually mattered — never said which destinations the package reached. A
 * delivery that failed to one server out of three surfaced as a single red
 * line of text and then vanished, so "did last night's backup actually land on
 * the NAS?" was unanswerable.
 *
 * A record is written when a run starts and updated as it progresses, so an
 * interrupted run still leaves evidence rather than disappearing.
 *
 * ## Storage
 *
 * One JSON file in filesDir (NOT cacheDir — the system may evict the cache, and
 * a history that silently empties itself is worse than none). Records older
 * than [RETENTION_MS] are pruned on load: a heavy week can't push out a record
 * the user still cares about, and an idle month leaves nothing stale behind.
 * Same 30-day window as iOS.
 */
class BackupHistory private constructor(private val context: Context, private val storeOverride: File? = null) {

    /**
     * Terminal state of a run.
     *
     * [COMPLETED_WITH_ISSUES] is the one that carries its weight: a run that
     * produced a package but couldn't reach a destination, or skipped files at
     * the size cap. Collapsing it into either "succeeded" or "failed" is what
     * made partial delivery invisible before.
     */
    @Serializable
    enum class Status { RUNNING, SUCCEEDED, COMPLETED_WITH_ISSUES, FAILED }

    /** One line of run progress, kept so a finished run can be inspected. */
    @Serializable
    data class LogEntry(
        val at: Long,
        val message: String,
        /** Rendered as a problem (destination failure, skipped file). */
        val isProblem: Boolean = false,
    )

    /** Per-destination delivery outcome — the thing the old UI never showed. */
    @Serializable
    data class DestinationOutcome(
        val name: String,
        val succeeded: Boolean,
        val detail: String? = null,
        /**
         * Backend tag (`smb`, `webdav`, `sftp`, `s3`, …) and the folder the
         * package was written to, so a row can say WHERE the backup went
         * rather than only which saved destination was picked. The saved name
         * alone ("HomeLab") does not tell someone opening a months-old record
         * how to find the file by hand.
         *
         * Both optional: records written before these existed decode with null
         * and render exactly as they did before.
         */
        val kind: String? = null,
        val path: String? = null,
    )

    /** A file left out of the package, with the reason. */
    @Serializable
    data class SkippedEntry(
        val path: String,
        val size: Long,
        val reason: String? = null,
    ) {
        val fileName: String get() = path.substringAfterLast('/')
    }

    @Serializable
    data class Record(
        val id: String = UUID.randomUUID().toString(),
        val backupId: String,
        val startedAt: Long,
        val finishedAt: Long? = null,
        val status: Status,
        val categories: List<String> = emptyList(),
        val encrypted: Boolean = false,
        val totalBytes: Long = 0,
        val skippedFiles: Int = 0,
        val skippedEntries: List<SkippedEntry> = emptyList(),
        val packageName: String? = null,
        val destinations: List<DestinationOutcome> = emptyList(),
        val log: List<LogEntry> = emptyList(),
        val errorMessage: String? = null,
    ) {
        val durationMillis: Long? get() = finishedAt?.let { it - startedAt }
        val hasProblems: Boolean
            get() = destinations.any { !it.succeeded } || skippedFiles > 0
    }

    // MARK: - Storage

    private val storeFile: File
        get() = storeOverride ?: File(context.filesDir, "backup-history").apply { mkdirs() }
            .let { File(it, "records.json") }

    @Volatile
    private var cache: List<Record>? = null

    /** Newest first. Expired records are dropped (and rewritten) on first read. */
    @Synchronized
    fun records(): List<Record> {
        cache?.let { return it }
        val loaded = runCatching {
            storeFile.takeIf { it.exists() }?.readText()
                ?.let { JSON.decodeFromString(RECORD_LIST, it) }
        }.getOrElse {
            // Unreadable is not the same as empty: setAside keeps the file, so the next write
            // does not destroy what might still be recovered.
            setAsideUnreadable()
            null
        } ?: emptyList()
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        val kept = loaded.filter { it.startedAt >= cutoff }.sortedByDescending { it.startedAt }
        if (kept.size != loaded.size) persist(kept)
        cache = cache ?: kept
        return cache!!
    }

    /** Moves an unparseable records file to `records.json.corrupt` (replacing an older one). */
    private fun setAsideUnreadable() {
        val f = storeFile
        if (!f.exists()) return
        val aside = File(f.parentFile, f.name + ".corrupt")
        val moved = runCatching { aside.delete(); f.renameTo(aside) }.getOrDefault(false)
        AppLogger.error(TAG, "[Backup] history file unreadable; ${if (moved) "kept as ${aside.name}" else "could not be set aside"}")
    }

    /**
     * Commits [list] to disk and only then publishes it, so what the screen shows is what a restart
     * will find. False (and nothing changes) when the write failed.
     */
    @Synchronized
    private fun persist(list: List<Record>): Boolean {
        val saved = com.openminis.app.util.atomicWriteText(storeFile, JSON.encodeToString(RECORD_LIST, list))
        if (saved) cache = list else AppLogger.error(TAG, "[Backup] history write failed; keeping the previous list")
        return saved
    }

    // MARK: - Mutation

    /** Insert a record, or replace the one carrying the same id. */
    @Synchronized
    fun upsert(record: Record): Boolean {
        val cur = records().toMutableList()
        val i = cur.indexOfFirst { it.id == record.id }
        if (i >= 0) cur[i] = record else cur.add(0, record)
        return persist(cur.sortedByDescending { it.startedAt })
    }

    @Synchronized
    fun remove(id: String): Boolean = persist(records().filterNot { it.id == id })

    @Synchronized
    fun clear(): Boolean = persist(emptyList())

    /**
     * Mark any record still flagged RUNNING as failed.
     *
     * A run that was killed — process death, a crash, the user swiping the app
     * away — leaves its record RUNNING forever, which would render as a
     * perpetual spinner in the list. Called at construction so the state is
     * reconciled before the UI ever sees it.
     */
    @Synchronized
    private fun reconcileInterrupted() {
        val cur = records()
        if (cur.none { it.status == Status.RUNNING }) return
        persist(
            cur.map {
                if (it.status != Status.RUNNING) it
                else it.copy(
                    status = Status.FAILED,
                    finishedAt = it.finishedAt ?: System.currentTimeMillis(),
                    errorMessage = it.errorMessage ?: INTERRUPTED_MARKER,
                )
            },
        )
    }

    companion object {
        private const val TAG = "Backup"

        /**
         * Whether a configured server is the place [outcome] was delivered to. A saved name can be
         * reused after its server is removed, so the record's backend and folder must match too; a
         * record that kept neither (written before they were stored) cannot be verified.
         */
        fun sameDestination(outcome: DestinationOutcome, backend: String, path: String): Boolean =
            outcome.kind != null && outcome.path != null && outcome.kind == backend && outcome.path == path

        /** 30 days, matching iOS `BackupHistory.retention`. */
        const val RETENTION_MS: Long = 30L * 24 * 60 * 60 * 1000

        /**
         * Recorded as the error for a run that never reported a terminal state.
         * Not localized on purpose: it is a stored marker, and the UI maps it to
         * a translated string at render time.
         */
        const val INTERRUPTED_MARKER = "interrupted"

        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val RECORD_LIST = ListSerializer(Record.serializer())

        @Volatile
        private var instance: BackupHistory? = null

        /** A store backed by [file], for JVM tests (no Android files directory needed). */
        internal fun forFile(context: Context, file: File): BackupHistory = BackupHistory(context, file)

        fun get(context: Context): BackupHistory =
            instance ?: synchronized(this) {
                instance ?: BackupHistory(context.applicationContext)
                    .also { it.reconcileInterrupted(); instance = it }
            }
    }
}
