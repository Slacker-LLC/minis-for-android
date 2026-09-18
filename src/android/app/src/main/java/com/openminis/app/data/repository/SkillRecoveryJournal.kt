package com.openminis.app.data.repository

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/**
 * [T-android-skill-install-transaction] Crash-recovery journal for skill
 * installs.
 *
 * Ported from Eta `agent/skill/SkillRecoveryJournal.kt` (commit c15de97);
 * attribution lives in PROVENANCE.md. The record shape, the commit-phase flags,
 * the journal document, its bounds and the recovery decisions are Eta's. The
 * file calls are the ported part: Eta reads and writes a `java.io.File` in an
 * app-private work directory, Minis goes through [SkillTransactionStore] so the
 * same work directory can live inside the guest skills root.
 *
 * The journal is the only reason a half-finished install is safe. It is written
 * before the first real move and deleted only after the files and the registry
 * agree again, so a process death at any point leaves either a complete old
 * version, a complete new version, or a record that forces the next caller to
 * finish the recovery first (fail-closed).
 */

/** Names the ported transaction reserves below the skills root. */
internal object SkillInstallLayout {
    /**
     * Eta puts its work root next to the skills root
     * (`File(skillsRoot.parentFile, ".eta-skill-installer")`). Minis keeps it
     * inside the skills root: that is the only directory the guest file API can
     * address, it is the same filesystem the final rename needs, and the loader
     * skips the reserved name while scanning for skills.
     */
    const val WORK_DIRECTORY = ".minis-skill-installer"

    const val BACKUP_DIRECTORY = "backup"
    const val RECOVERY_DIRECTORY = "recovery"
    const val STAGING_DIRECTORY = "new"
    const val OPERATION_PREFIX = "operation-"
}

internal const val JOURNAL_FILE_NAME = "pending-install.json"

/** Eta `SkillRecoveryRecord`: one skill inside one install transaction. */
internal data class SkillRecoveryRecord(
    val id: String,
    val originalTargetExisted: Boolean,
    val backupCompleted: Boolean = false,
    val newTargetCommitted: Boolean = false,
    val registrySnapshot: SkillRegistryRecoverySnapshot = SkillRegistryRecoverySnapshot(
        skillId = id,
        entryExisted = false,
    ),
)

/**
 * Eta `SkillRegistryRecoverySnapshot`.
 *
 * Eta describes the previous registry entry as `enabled`/`source`/
 * `installState` because its registry is a JSON document with those fields.
 * Minis keeps the registry in SQLite, so the snapshot carries the repository's
 * own encoding of the row instead: the journal validates that it is well formed
 * and hands it back untouched on restore.
 */
internal data class SkillRegistryRecoverySnapshot(
    val skillId: String,
    val entryExisted: Boolean,
    val payload: String? = null,
)

/** Eta `RecoveredSkillOperation` — its operationDirectory is a guest path here. */
internal data class RecoveredSkillOperation(
    val operationDirectory: String,
    val records: List<SkillRecoveryRecord>,
)

/**
 * Eta `PendingSkillRecoveryJournal`: the transaction's live view of its own
 * journal. Every phase is written to disk before it is attempted.
 */
internal class PendingSkillRecoveryJournal private constructor(
    private val store: SkillTransactionStore,
    private val operationDirectory: String,
    records: List<SkillRecoveryRecord>,
) {
    private var records = records

    suspend fun markBackupCompleted(skillId: String) {
        update(skillId) { it.copy(backupCompleted = true) }
    }

    suspend fun markNewTargetCommitted(skillId: String) {
        update(skillId) { it.copy(newTargetCommitted = true) }
    }

    suspend fun clear() {
        store.delete(journalPath(operationDirectory))
    }

    private suspend fun update(skillId: String, transform: (SkillRecoveryRecord) -> SkillRecoveryRecord) {
        var found = false
        records = records.map { record ->
            if (record.id == skillId) {
                found = true
                transform(record)
            } else {
                record
            }
        }
        check(found) { "the skill recovery journal has no entry for " + skillId }
        writeJournalAtomically(store, operationDirectory, records)
    }

    companion object {
        suspend fun begin(
            store: SkillTransactionStore,
            root: String,
            operationDirectory: String,
            records: List<SkillRecoveryRecord>,
        ): PendingSkillRecoveryJournal {
            validateOperationDirectory(root, operationDirectory, store)
            require(records.isNotEmpty()) { "the skill recovery journal needs at least one skill" }
            validateRecords(records)
            writeJournalAtomically(store, operationDirectory, records)
            return PendingSkillRecoveryJournal(store, operationDirectory, records)
        }
    }
}

/** Eta `SkillRecoveryRequiredException`: the journal prevents any new install. */
internal class SkillRecoveryRequiredException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Eta `recoverPendingSkillOperations`.
 *
 * Must be called with the root-wide mutation lock held. Every `operation-*`
 * directory that still carries a journal is unwound to the state the user had
 * before the transaction; the caller then restores the registry and only then
 * lets the lock be released.
 */
internal suspend fun recoverPendingSkillOperations(
    root: String,
    store: SkillTransactionStore,
): List<RecoveredSkillOperation> {
    val workRoot = skillInstallerWorkRoot(root)
    val workEntry = store.stat(workRoot) ?: return emptyList()
    if (workEntry.kind != SkillEntryKind.DIRECTORY) {
        recoveryFailure("the skill install work directory is not a plain directory")
    }
    val names = store.children(workRoot)
        ?: recoveryFailure("cannot read the skill install work directory")
    val operations = names
        .filter { it.startsWith(SkillInstallLayout.OPERATION_PREFIX) }
        .sorted()
    val recovered = operations.mapNotNull { name ->
        val operation = skillTransactionChildOrNull(workRoot, name)
            ?: recoveryFailure("the skill install work directory holds an unusable name")
        val journal = journalPath(operation)
        if (store.stat(journal) == null) return@mapNotNull null
        recoverOperation(root, operation, store)
    }
    val duplicateIds = recovered
        .flatMap { it.records }
        .groupBy { it.id }
        .filterValues { it.size > 1 }
        .keys
    if (duplicateIds.isNotEmpty()) {
        recoveryFailure("several pending transactions carry the same skill")
    }
    return recovered
}

private suspend fun recoverOperation(
    root: String,
    operation: String,
    store: SkillTransactionStore,
): RecoveredSkillOperation {
    try {
        validateOperationDirectory(root, operation, store)
        val journalFile = journalPath(operation)
        val journalEntry = store.stat(journalFile)
        if (journalEntry == null || journalEntry.kind != SkillEntryKind.FILE) {
            recoveryFailure("the skill recovery journal is not a plain file")
        }
        val bytes = store.readFile(journalFile, MAX_JOURNAL_BYTES)
            ?: recoveryFailure("the skill recovery journal cannot be read")
        if (bytes.size !in 1..MAX_JOURNAL_BYTES) {
            recoveryFailure("the skill recovery journal size is invalid")
        }
        val records = parseJournal(bytes)
        val backupRoot = skillTransactionChildOrNull(operation, SkillInstallLayout.BACKUP_DIRECTORY)
            ?: recoveryFailure("the skill backup directory name is unusable")
        records.forEach { record ->
            val target = skillTransactionChildOrNull(root, record.id)
                ?: recoveryFailure("the skill recovery journal names an unusable skill id")
            val backup = skillTransactionChildOrNull(backupRoot, record.id)
                ?: recoveryFailure("the skill backup path is unusable")
            val backupEntry = store.stat(backup)
            if (record.originalTargetExisted) {
                when {
                    backupEntry != null -> restoreBackup(root, operation, backupRoot, record.id, store)
                    record.backupCompleted -> recoveryFailure(
                        "the previous version of " + record.id + " has no backup",
                    )
                    !isSafeExistingTarget(target, store) -> recoveryFailure(
                        "the target of " + record.id + " does not match the recovery journal",
                    )
                }
            } else {
                if (backupEntry != null) {
                    recoveryFailure("a brand-new skill must not have a backup: " + record.id)
                }
                if (!deleteSkillPathWithoutFollowingLinks(root, target, store)) {
                    recoveryFailure("cannot remove the unfinished skill " + record.id)
                }
            }
        }
        return RecoveredSkillOperation(operationDirectory = operation, records = records)
    } catch (error: SkillRecoveryRequiredException) {
        throw error
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        throw SkillRecoveryRequiredException("the skill recovery failed", error)
    }
}

/**
 * Eta `completeRecoveredSkillOperations`.
 *
 * Deleting the journal is what marks files and registry as jointly recovered.
 * Residue without a journal can no longer confuse the index, so a cleanup that
 * fails must not put an already finished recovery back into the pending state.
 */
internal suspend fun completeRecoveredSkillOperations(
    root: String,
    store: SkillTransactionStore,
    recovered: List<RecoveredSkillOperation>,
) {
    val workRoot = skillInstallerWorkRoot(root)
    recovered.forEach { recovery ->
        val operation = recovery.operationDirectory
        validateOperationDirectory(root, operation, store)
        store.delete(journalPath(operation))
        deleteSkillPathWithoutFollowingLinks(workRoot, operation, store)
    }
}

/**
 * Eta `createSkillRecoveryOperationDirectory`: a fresh per-transaction
 * directory below the work root. Eta retries the four-name collision case twice
 * as often as the installer does; the retry budget is kept.
 */
internal suspend fun createSkillRecoveryOperationDirectory(
    root: String,
    store: SkillTransactionStore,
    newOperationId: () -> String = { UUID.randomUUID().toString() },
): String {
    val workRoot = prepareSkillInstallerWorkRoot(root, store)
    repeat(8) {
        val candidate = skillTransactionChildOrNull(
            workRoot,
            SkillInstallLayout.OPERATION_PREFIX + newOperationId(),
        ) ?: throw IOException("the generated skill install operation name is unusable")
        if (store.stat(candidate) == null) {
            store.makeDirectory(candidate)
            return candidate
        }
    }
    throw IOException("cannot create a skill install transaction directory")
}

/**
 * Eta `restoreBackup`.
 *
 * The previous version is copied into a recovery staging directory below the
 * operation, and only that copy is moved into place. The backup itself is never
 * consumed, so a failure halfway through the restore still leaves the only
 * complete copy of the old skill on disk.
 */
private suspend fun restoreBackup(
    root: String,
    operation: String,
    backupRoot: String,
    skillId: String,
    store: SkillTransactionStore,
) {
    val backupRootEntry = store.stat(backupRoot)
    if (backupRootEntry?.kind != SkillEntryKind.DIRECTORY) {
        recoveryFailure("the skill backup root is unsafe")
    }
    val backup = skillTransactionChildOrNull(backupRoot, skillId)
        ?: recoveryFailure("the skill backup path is unusable")
    if (store.stat(backup)?.kind != SkillEntryKind.DIRECTORY) {
        recoveryFailure("the backup of " + skillId + " is unsafe")
    }
    val recoveryRoot = skillTransactionChildOrNull(operation, SkillInstallLayout.RECOVERY_DIRECTORY)
        ?: recoveryFailure("the skill recovery staging path is unusable")
    store.makeDirectory(recoveryRoot)
    if (store.stat(recoveryRoot)?.kind != SkillEntryKind.DIRECTORY) {
        recoveryFailure("cannot create the skill recovery staging directory")
    }
    val staging = skillTransactionChildOrNull(recoveryRoot, skillId)
        ?: recoveryFailure("the skill recovery staging path is unusable")
    if (!deleteSkillPathWithoutFollowingLinks(operation, staging, store)) {
        recoveryFailure("cannot clean the skill recovery staging directory")
    }
    copyDirectoryWithoutFollowingLinks(backup, staging, store, TreeBudget())

    val target = skillTransactionChildOrNull(root, skillId)
        ?: recoveryFailure("the skill target path is unusable")
    if (!deleteSkillPathWithoutFollowingLinks(root, target, store)) {
        recoveryFailure("cannot remove the unfinished skill " + skillId)
    }
    moveSkillDirectoryAtomically(store, staging, target)
}

/**
 * Eta `copyDirectoryWithoutFollowingLinks`, with the entry budget the Minis
 * contracts require before any recursion happens. A backup that is deeper or
 * larger than a skill may be, or that holds a symbolic link, refuses the
 * restore instead of producing a partial copy.
 */
private suspend fun copyDirectoryWithoutFollowingLinks(
    source: String,
    target: String,
    store: SkillTransactionStore,
    budget: TreeBudget,
) {
    if (store.stat(source)?.kind != SkillEntryKind.DIRECTORY) {
        recoveryFailure("the skill backup directory is unsafe")
    }
    budget.consume(1)
    store.makeDirectory(target)
    val children = store.children(source)
        ?: recoveryFailure("cannot read the skill backup directory")
    children.forEach { name ->
        val child = skillTransactionChildOrNull(source, name)
            ?: recoveryFailure("the skill backup holds an unusable name")
        val destination = skillTransactionChildOrNull(target, name)
            ?: recoveryFailure("the skill backup holds an unusable name")
        when (store.stat(child)?.kind) {
            SkillEntryKind.DIRECTORY -> copyDirectoryWithoutFollowingLinks(child, destination, store, budget)
            SkillEntryKind.FILE -> {
                budget.consume(1)
                store.copy(child, destination)
            }
            else -> recoveryFailure("the skill backup holds a symbolic link or special file")
        }
    }
}

/** Eta `parseJournal`. */
private fun parseJournal(bytes: ByteArray): List<SkillRecoveryRecord> {
    val json = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }
        .getOrElse { recoveryFailure("the skill recovery journal is not valid JSON") }
    if (json.optInt("version", -1) != JOURNAL_VERSION) {
        recoveryFailure("the skill recovery journal version is not supported")
    }
    val entries = json.optJSONArray("skills")
        ?: recoveryFailure("the skill recovery journal has no skills array")
    if (entries.length() !in 1..MAX_JOURNAL_SKILLS) {
        recoveryFailure("the skill recovery journal entry count is invalid")
    }
    val records = (0 until entries.length()).map { index ->
        val entry = entries.optJSONObject(index)
            ?: recoveryFailure("the skill recovery journal entry is invalid")
        val id = entry.optString("id")
        if (!entry.has("originalTargetExisted") || entry.opt("originalTargetExisted") !is Boolean) {
            recoveryFailure("the skill recovery journal entry has no original target state")
        }
        if (!entry.has("backupCompleted") || entry.opt("backupCompleted") !is Boolean) {
            recoveryFailure("the skill recovery journal entry has no backup state")
        }
        if (!entry.has("newTargetCommitted") || entry.opt("newTargetCommitted") !is Boolean) {
            recoveryFailure("the skill recovery journal entry has no commit state")
        }
        SkillRecoveryRecord(
            id = id,
            originalTargetExisted = entry.getBoolean("originalTargetExisted"),
            backupCompleted = entry.getBoolean("backupCompleted"),
            newTargetCommitted = entry.getBoolean("newTargetCommitted"),
            registrySnapshot = parseRegistrySnapshot(entry, id),
        )
    }
    validateRecords(records)
    return records
}

/**
 * Eta `writeJournalAtomically`.
 *
 * Eta writes a temporary file, `fsync`s it and renames it over the journal
 * with `ATOMIC_MOVE` (falling back to a plain rename). The guest file API's
 * `writeBytes` is already that operation — it creates a temporary entry in the
 * same directory and renames it into place — so the port calls it once and
 * keeps the write-side size ceiling Eta enforces when reading.
 */
private suspend fun writeJournalAtomically(
    store: SkillTransactionStore,
    operationDirectory: String,
    records: List<SkillRecoveryRecord>,
) {
    val json = JSONObject()
        .put("version", JOURNAL_VERSION)
        .put(
            "skills",
            JSONArray().apply {
                records.forEach { record ->
                    put(
                        JSONObject()
                            .put("id", record.id)
                            .put("originalTargetExisted", record.originalTargetExisted)
                            .put("backupCompleted", record.backupCompleted)
                            .put("newTargetCommitted", record.newTargetCommitted)
                            .put(
                                "registry",
                                JSONObject()
                                    .put("entryExisted", record.registrySnapshot.entryExisted)
                                    .put("payload", record.registrySnapshot.payload ?: JSONObject.NULL),
                            )
                    )
                }
            }
        )
    val payload = json.toString().toByteArray(Charsets.UTF_8)
    if (payload.size > MAX_JOURNAL_BYTES) {
        throw IOException("the skill recovery journal would exceed " + MAX_JOURNAL_BYTES + " bytes")
    }
    store.writeFile(journalPath(operationDirectory), payload)
}

/**
 * Eta `validateOperationDirectory`. The lexical part (name and parent) is
 * pure, the type check goes through the store, and both must hold: a symbolic
 * link that happens to be named like an operation is refused.
 */
internal suspend fun validateOperationDirectory(
    root: String,
    operationDirectory: String,
    store: SkillTransactionStore,
) {
    val workRoot = skillInstallerWorkRoot(root)
    val name = operationDirectory.substringAfterLast('/')
    if (operationDirectory.trimEnd('/') != workRoot + "/" + name) {
        recoveryFailure("the skill operation directory is outside the work directory")
    }
    if (!OPERATION_NAME_REGEX.matches(name)) {
        recoveryFailure("the skill operation directory name is unsafe")
    }
    if (store.stat(operationDirectory)?.kind != SkillEntryKind.DIRECTORY) {
        recoveryFailure("the skill operation directory is not a plain directory")
    }
}

/** Eta `validateRecords`. */
private fun validateRecords(records: List<SkillRecoveryRecord>) {
    if (records.map { it.id }.distinct().size != records.size) {
        recoveryFailure("the skill recovery journal repeats a skill id")
    }
    records.forEach { record ->
        if (!isSafeSkillTransactionId(record.id)) {
            recoveryFailure("the skill recovery journal holds an illegal skill id")
        }
        if (!record.originalTargetExisted && record.backupCompleted) {
            recoveryFailure("a brand-new skill carries an illegal backup state")
        }
        if (record.originalTargetExisted && record.newTargetCommitted && !record.backupCompleted) {
            recoveryFailure("a replace transaction carries inconsistent states")
        }
        val registry = record.registrySnapshot
        if (registry.skillId != record.id) {
            recoveryFailure("the skill recovery journal registry id does not match")
        }
        if (registry.entryExisted) {
            val payload = registry.payload
            if (payload.isNullOrEmpty() || payload.length > MAX_REGISTRY_SNAPSHOT_CHARS) {
                recoveryFailure("the skill recovery journal registry snapshot is invalid")
            }
            if (runCatching { JSONObject(payload) }.isFailure) {
                recoveryFailure("the skill recovery journal registry snapshot is not a JSON object")
            }
        } else if (!registry.payload.isNullOrEmpty()) {
            recoveryFailure("a missing registry entry carries extra state")
        }
    }
}

/** Eta `parseRegistrySnapshot`. */
private fun parseRegistrySnapshot(
    entry: JSONObject,
    skillId: String,
): SkillRegistryRecoverySnapshot {
    val registry = entry.optJSONObject("registry")
        ?: recoveryFailure("the skill recovery journal has no registry snapshot")
    if (!registry.has("entryExisted") || registry.opt("entryExisted") !is Boolean) {
        recoveryFailure("the skill recovery journal registry snapshot is invalid")
    }
    val existed = registry.getBoolean("entryExisted")
    if (existed && (!registry.has("payload") || registry.opt("payload") !is String)) {
        recoveryFailure("the skill recovery journal registry payload is invalid")
    }
    if (!existed && registry.has("payload") && !registry.isNull("payload")) {
        recoveryFailure("a missing registry entry carries a payload")
    }
    return SkillRegistryRecoverySnapshot(
        skillId = skillId,
        entryExisted = existed,
        payload = if (existed) registry.getString("payload") else null,
    )
}

private suspend fun isSafeExistingTarget(target: String, store: SkillTransactionStore): Boolean =
    store.stat(target)?.kind == SkillEntryKind.DIRECTORY

/**
 * Eta `skillInstallerWorkRoot`: Eta derives
 * `File(skillsRoot.parentFile, ".eta-skill-installer")`; Minis puts the same
 * reserved directory inside the skills root (see [SkillInstallLayout]).
 */
internal fun skillInstallerWorkRoot(root: String): String =
    root.trimEnd('/') + "/" + SkillInstallLayout.WORK_DIRECTORY

/** Guest path of the journal inside one operation directory. */
internal fun journalPath(operationDirectory: String): String =
    operationDirectory.trimEnd('/') + "/" + JOURNAL_FILE_NAME

private fun recoveryFailure(message: String): Nothing =
    throw SkillRecoveryRequiredException(message)

/** Entry ceiling for the recursive backup copy. */
private class TreeBudget {
    private var used = 0

    fun consume(count: Int) {
        used += count
        if (used > SkillTransactionLimits.MAX_TREE_ENTRIES) {
            recoveryFailure("the skill backup is larger than " + SkillTransactionLimits.MAX_TREE_ENTRIES + " entries")
        }
    }
}

private const val JOURNAL_VERSION = 1
private const val MAX_JOURNAL_BYTES = 256 * 1024
private const val MAX_JOURNAL_SKILLS = 2_048
private const val MAX_REGISTRY_SNAPSHOT_CHARS = 8 * 1024
private val OPERATION_NAME_REGEX =
    Regex("^operation-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
