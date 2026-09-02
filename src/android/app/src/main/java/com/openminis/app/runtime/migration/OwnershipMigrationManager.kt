package com.openminis.app.runtime.migration

import org.json.JSONObject

/**
 * Six-stage transactional FD-based ownership migration protocol.
 * Conforms to V4 audit specifications for migrating canonical user storage
 * from legacy package identity (dev.openminispet.android) to canonical identity (llc.slacker.minis).
 *
 * Sequence:
 *   1. PREPARE: Dynamic UID & GID discovery via kernel stat (not pm list packages alone).
 *   2. FREEZE: Kill old instances and acquire exclusive .migration_in_progress lock.
 *   3. FD_MIGRATION: Recursive openat/fchown traversal with append-only WAL logging.
 *   4. FSYNC: Force metadata sync to backing persistent storage.
 *   5. COMMIT: Write and fsync .migration_committed marker.
 *   6. CLEANUP: Release lock and journal; boot new package.
 *
 * Crash self-healing:
 *   If .migration_in_progress exists without .migration_committed, the system reads
 *   the WAL journal and issues a reverse rollback to restore every mutated file to oldUid:oldGid.
 */
object OwnershipMigrationManager {

    const val LEGACY_PACKAGE = "dev.openminispet.android"
    const val CANONICAL_PACKAGE = "llc.slacker.minis"

    const val CANONICAL_BASE_DIR = "/data/adb/minis"
    const val LOCK_FILE_NAME = ".migration_in_progress"
    const val JOURNAL_FILE_NAME = ".migration_journal.log"
    const val COMMIT_MARKER_NAME = ".migration_committed"

    enum class Phase {
        PREPARE,
        FREEZE,
        FD_MIGRATION,
        FSYNC,
        COMMIT,
        CLEANUP,
    }

    enum class EntryStatus {
        PENDING,
        MIGRATED,
        REVERTED,
    }

    data class AppIdentity(
        val packageName: String,
        val uid: Int,
        val gid: Int,
    )

    data class JournalEntry(
        val relativePath: String,
        val oldUid: Int,
        val oldGid: Int,
        val newUid: Int,
        val newGid: Int,
        val status: EntryStatus,
    ) {
        fun serialize(): String =
            "$relativePath\t$oldUid\t$oldGid\t$newUid\t$newGid\t${status.name}"

        companion object {
            fun parse(line: String): JournalEntry? {
                val parts = line.trim().split('\t')
                if (parts.size != 6) return null
                val oldU = parts[1].toIntOrNull() ?: return null
                val oldG = parts[2].toIntOrNull() ?: return null
                val newU = parts[3].toIntOrNull() ?: return null
                val newG = parts[4].toIntOrNull() ?: return null
                val st = runCatching { EntryStatus.valueOf(parts[5]) }.getOrNull() ?: return null
                return JournalEntry(parts[0], oldU, oldG, newU, newG, st)
            }
        }
    }

    /**
     * Resolves real UID and GID by statting the package's private application directory.
     * Android pm list packages -U only outputs UID; kernel stat guarantees true GID verification.
     */
    fun resolveIdentityCommand(packageName: String): String =
        "stat -c '%u %g' /data/user/0/$packageName 2>/dev/null || stat -c '%u %g' /data/data/$packageName 2>/dev/null"

    fun parseIdentityOutput(packageName: String, output: String): AppIdentity? {
        val tokens = output.trim().split(Regex("\\s+"))
        if (tokens.size != 2) return null
        val uid = tokens[0].toIntOrNull() ?: return null
        val gid = tokens[1].toIntOrNull() ?: return null
        if (uid < 10000 || gid < 10000) return null
        return AppIdentity(packageName, uid, gid)
    }

    fun buildLockPayload(
        source: AppIdentity,
        target: AppIdentity,
        timestampMs: Long = System.currentTimeMillis(),
    ): String = JSONObject()
        .put("version", 1)
        .put("sourcePackage", source.packageName)
        .put("sourceUid", source.uid)
        .put("sourceGid", source.gid)
        .put("targetPackage", target.packageName)
        .put("targetUid", target.uid)
        .put("targetGid", target.gid)
        .put("timestampMs", timestampMs)
        .toString(2)

    /**
     * Computes the exact reverse rollback sequence from an uncommitted journal log.
     * Only entries with status MIGRATED need to be reverted.
     * Reversal runs in reverse order (bottom-up) to preserve directory semantics.
     */
    fun computeRollbackPlan(journalLines: List<String>): List<JournalEntry> {
        val entries = journalLines.mapNotNull { JournalEntry.parse(it) }
        return entries
            .filter { it.status == EntryStatus.MIGRATED }
            .reversed()
    }

    /**
     * Rejection boundary: paths must stay relative and strictly beneath root.
     */
    fun isSafeRelativePath(path: String): Boolean {
        if (path.startsWith("/") || path.startsWith("\\") || path.contains("\u0000")) return false
        val segments = path.split('/', '\\')
        return !segments.contains("..") && !segments.contains(".")
    }
}
