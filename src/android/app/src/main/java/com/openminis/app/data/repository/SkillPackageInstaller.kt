package com.openminis.app.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

/**
 * [T-android-skill-install-transaction] Transactional skill install.
 *
 * Ported from Eta `agent/skill/SkillPackageInstaller.kt` (commit c15de97);
 * attribution lives in PROVENANCE.md.
 *
 * Only the transactional half of that file is ported. Eta's installer also
 * reads the ZIP, discovers candidates, decides conflicts against a builtin
 * registry and supports picking directories out of a repository archive; Minis
 * already owns all of that in [SkillArchiveReader] and the Skills screen, and
 * `importFromArchive` keeps its existing outward behaviour (one skill, replace
 * if present, null on failure). What is ported is the part Minis was missing:
 *
 * 1. content is staged first and validated before anything touches the final
 *    directory;
 * 2. the commit sequence is journal, backup, atomic move, registry, clear;
 * 3. a failure rolls back to the version the user already had;
 * 4. a failure that cannot be rolled back keeps the journal and refuses to
 *    continue until the next recovery finishes.
 *
 * Every file call goes through [SkillTransactionStore] (see that file for why
 * the port cannot use `java.io.File` the way Eta does) and every commit runs
 * under the cross-process [SkillMutationLock].
 */
internal sealed class SkillInstallResult {
    data class Success(val installed: List<InstalledSkill>) : SkillInstallResult()
    data class Failure(
        val error: SkillInstallError,
        val recoveryRequired: Boolean = false,
    ) : SkillInstallResult()
}

/**
 * What the staged tree of [SkillPackageInstaller.install] contains.
 *
 * Eta only ever installs a whole skill, because every payload it accepts is an
 * extracted archive, so its installer has no such switch. Minis also writes single
 * files into an already installed skill (the file editor, a URL refresh, the bundled
 * seed, a re-downloaded sibling), and those writes have to keep the sibling files the
 * skill already ships.
 */
internal enum class SkillInstallBase {
    /** The staged tree is exactly the payload; an installed skill is replaced. */
    REPLACE,

    /** The staged tree starts from the installed tree with the payload applied on top. */
    PRESERVE,
}

internal data class InstalledSkill(
    val id: String,
    val name: String,
    val description: String,
)

internal data class SkillInstallError(
    val code: SkillInstallErrorCode,
    val message: String,
)

/**
 * The subset of Eta's error codes the ported transaction can raise. Eta's
 * archive-shaped codes (empty archive, too many entries, nested candidates)
 * belong to the reader Minis already has.
 */
internal enum class SkillInstallErrorCode {
    IO_ERROR,
    INVALID_SKILL,
    UNSAFE_TARGET,
    COMMIT_FAILED,
    RECOVERY_REQUIRED,
}

/** One skill to install: the id it lands under plus the registry row it needs. */
internal data class SkillInstallCandidate(
    val id: String,
    val registration: SkillRegistration,
)

internal class SkillPackageInstaller(
    private val root: String,
    private val store: SkillTransactionStore,
    private val index: SkillInstallIndex,
    /**
     * Eta keeps the lock file inside a work directory that is a sibling of the
     * skills root. Minis' skills root is writable by the agent inside the
     * guest, so the lock file lives in the app's private directory instead: a
     * lock file that the guest could unlink would silently stop excluding
     * anybody. The transaction workspace itself stays under the skills root.
     */
    private val lockFile: File,
    private val newOperationId: () -> String = { UUID.randomUUID().toString() },
) {

    /** Eta `ArchiveOperation`: one transaction's directory and its fate. */
    private class InstallOperation(val directory: String) {
        var preserveForRecovery: Boolean = false
    }

    /**
     * Eta `installCandidates` after staging: everything below runs under the
     * mutation lock, and the lock first finishes any transaction an earlier
     * process left behind.
     *
     * [base] decides what the staged tree contains. Eta only replaces whole
     * skills, because every payload it installs is an extracted archive. Minis
     * also writes single files into an installed skill (the editor save, a URL
     * refresh, the bundled seed), and those have to keep the sibling files the
     * skill already shipped. Such a write therefore stages the installed tree
     * with the new files applied on top, which means it has to read that tree
     * under the mutation lock — the payload is staged inside the lock for
     * [SkillInstallBase.PRESERVE] instead of before it.
     */
    suspend fun install(
        candidate: SkillInstallCandidate,
        files: List<SkillInstallFile>,
        base: SkillInstallBase = SkillInstallBase.REPLACE,
    ): SkillInstallResult {
        checkStagedRequest(candidate, files, requireSkillFile = base == SkillInstallBase.REPLACE)?.let { return it }
        val operation = try {
            InstallOperation(createSkillRecoveryOperationDirectory(root, store, newOperationId))
        } catch (error: Exception) {
            return failure(SkillInstallErrorCode.IO_ERROR, "cannot create the skill install staging directory", error)
        }
        return try {
            if (base == SkillInstallBase.REPLACE) {
                stageFiles(operation.directory, candidate, files)
            }
            installCandidates(operation, listOf(candidate), base, files)
        } catch (error: SkillRecoveryRequiredException) {
            // Eta stops the install and leaves the staging directory to the
            // cleanup below: the unfinished transaction belongs to a different
            // operation directory and is not touched here.
            SkillInstallResult.Failure(
                error = SkillInstallError(
                    code = SkillInstallErrorCode.COMMIT_FAILED,
                    message = "an unfinished skill recovery was found, the install stopped",
                ),
                recoveryRequired = true,
            )
        } catch (error: Exception) {
            failure(SkillInstallErrorCode.IO_ERROR, "staging the skill failed", error)
        } finally {
            if (!operation.preserveForRecovery) {
                deleteSkillPathWithoutFollowingLinks(
                    skillInstallerWorkRoot(root),
                    operation.directory,
                    store,
                )
            }
        }
    }

    /**
     * Eta `SkillIndexService.deleteSkill`: removal is the same transaction as an
     * install, not a bare directory delete. The previous version is moved into
     * the transaction's backup, the journal records that move, the registry
     * entry is dropped, and only then is the journal cleared and the backup
     * discarded. A failure or a process death therefore restores the skill the
     * user still had instead of leaving a directory that is gone while its
     * registry row survived.
     */
    suspend fun delete(skillId: String): SkillInstallResult {
        if (!isSafeSkillTransactionId(skillId)) {
            return SkillInstallResult.Failure(
                SkillInstallError(SkillInstallErrorCode.INVALID_SKILL, "the skill id is not a safe directory name"),
            )
        }
        val operation = try {
            InstallOperation(createSkillRecoveryOperationDirectory(root, store, newOperationId))
        } catch (error: Exception) {
            return failure(SkillInstallErrorCode.IO_ERROR, "cannot create the skill delete staging directory", error)
        }
        return try {
            removeCandidates(operation, listOf(skillId))
        } catch (error: SkillRecoveryRequiredException) {
            SkillInstallResult.Failure(
                error = SkillInstallError(
                    code = SkillInstallErrorCode.COMMIT_FAILED,
                    message = "an unfinished skill recovery was found, the delete stopped",
                ),
                recoveryRequired = true,
            )
        } catch (error: Exception) {
            failure(SkillInstallErrorCode.IO_ERROR, "deleting the skill failed", error)
        } finally {
            if (!operation.preserveForRecovery) {
                deleteSkillPathWithoutFollowingLinks(
                    skillInstallerWorkRoot(root),
                    operation.directory,
                    store,
                )
            }
        }
    }

    /**
     * Eta `SkillIndexService.withMutationLock`: the root-wide lock every write and
     * every read of the tree shares. Acquiring it first finishes a pending
     * filesystem transaction and restores its registry snapshot, so an
     * interrupted install can never be read as if it were installed. Registry-only
     * mutations (a skill toggle, a metadata refresh, a scan) take it too, so they
     * can never land while a transaction is pending, and a recovery that cannot
     * finish refuses them instead of letting them guess (fail-closed).
     */
    suspend fun <T> withSkillMutationLock(block: suspend () -> T): T = SkillMutationLock.withLock(
        lockFile = lockFile,
        skillsRoot = root,
        store = store,
        recoveryHandler = index::restoreRecoveredRegistry,
        block = block,
    )

    private suspend fun <T : SkillInstallResult> withMutationLock(
        block: suspend () -> T,
    ): SkillInstallResult = try {
        withSkillMutationLock(block)
    } catch (error: CancellationException) {
        throw error
    } catch (error: SkillRecoveryRequiredException) {
        SkillInstallResult.Failure(
            error = SkillInstallError(
                code = SkillInstallErrorCode.RECOVERY_REQUIRED,
                message = "an unfinished skill recovery blocks this operation",
            ),
            recoveryRequired = true,
        )
    } catch (error: Exception) {
        failure(SkillInstallErrorCode.IO_ERROR, "the skill install lock or the guest file API failed", error)
    }

    private suspend fun installCandidates(
        operation: InstallOperation,
        candidates: List<SkillInstallCandidate>,
        base: SkillInstallBase,
        files: List<SkillInstallFile>,
    ): SkillInstallResult = withMutationLock {
        val precondition = checkCommitPreconditions(candidates)
        if (precondition != null) {
            precondition
        } else {
            // Eta: cancellation is accepted only before any real file change.
            withContext(NonCancellable) { commitCandidates(operation, candidates, base, files) }
        }
    }

    /**
     * Eta's conflict stage, reduced to the part Minis has: a target that cannot
     * be restored from a backup (a symbolic link, a special file, or a tree the
     * bounded recovery copy refuses) must not be replaced at all.
     */
    private suspend fun checkCommitPreconditions(
        candidates: List<SkillInstallCandidate>,
    ): SkillInstallResult? {
        val duplicateIds = candidates.groupBy { it.id }.filterValues { it.size > 1 }.keys
        if (duplicateIds.isNotEmpty()) {
            return SkillInstallResult.Failure(
                SkillInstallError(SkillInstallErrorCode.INVALID_SKILL, "the selection repeats a skill id"),
            )
        }
        candidates.forEach { candidate ->
            val target = skillTransactionChildOrNull(root, candidate.id)
                ?: return SkillInstallResult.Failure(
                    SkillInstallError(SkillInstallErrorCode.INVALID_SKILL, "the skill id is not usable"),
                )
            if (store.stat(target) == null) return@forEach
            if (!isRecoverableSkillDirectoryTree(root, candidate.id, store)) {
                return SkillInstallResult.Failure(
                    SkillInstallError(
                        code = SkillInstallErrorCode.UNSAFE_TARGET,
                        message = "the existing skill " + candidate.id + " cannot be restored from a backup",
                    ),
                )
            }
        }
        return null
    }

    /**
     * Eta `installCandidatesLocked`'s commit sequence:
     * journal, backup, atomic move, registry, clear — with the rollback that
     * Eta performs when any of those steps throws.
     */
    private suspend fun commitCandidates(
        operation: InstallOperation,
        candidates: List<SkillInstallCandidate>,
        base: SkillInstallBase,
        files: List<SkillInstallFile>,
    ): SkillInstallResult {
        if (store.stat(root)?.kind != SkillEntryKind.DIRECTORY) {
            store.makeDirectory(root)
            if (store.stat(root)?.kind != SkillEntryKind.DIRECTORY) {
                return SkillInstallResult.Failure(
                    SkillInstallError(SkillInstallErrorCode.IO_ERROR, "the skills directory is unavailable"),
                )
            }
        }
        // Eta stages an archive before the lock; a merge has to read the installed
        // tree, which is exactly what the lock protects, so it is staged here.
        if (base == SkillInstallBase.PRESERVE) {
            stageMergedPayload(operation, candidates, files)?.let { return it }
        }
        val backupRoot = skillTransactionChildOrNull(operation.directory, SkillInstallLayout.BACKUP_DIRECTORY)
            ?: return SkillInstallResult.Failure(
                SkillInstallError(SkillInstallErrorCode.IO_ERROR, "the backup directory name is unusable"),
            )
        return try {
            store.makeDirectory(backupRoot)

            // The previous registry state is captured before anything moves, so
            // the journal can restore it even if the process dies here.
            val snapshots = index
                .captureRegistryRecoverySnapshots(candidates.map { it.id })
                .associateBy { it.skillId }
            try {
                val journal = PendingSkillRecoveryJournal.begin(
                    store = store,
                    root = root,
                    operationDirectory = operation.directory,
                    records = candidates.map { candidate ->
                        SkillRecoveryRecord(
                            id = candidate.id,
                            originalTargetExisted = store.stat(targetOf(candidate.id)) != null,
                            registrySnapshot = snapshots[candidate.id] ?: throw IOException(
                                "the registry snapshot of " + candidate.id + " is missing",
                            ),
                        )
                    },
                )
                candidates.forEach { candidate ->
                    val target = targetOf(candidate.id)
                    if (store.stat(target) != null) {
                        moveSkillDirectoryAtomically(store, target, backupOf(operation, candidate.id))
                        journal.markBackupCompleted(candidate.id)
                    }
                }
                candidates.forEach { candidate ->
                    moveSkillDirectoryAtomically(
                        store,
                        stagedOf(operation, candidate.id),
                        targetOf(candidate.id),
                    )
                    journal.markNewTargetCommitted(candidate.id)
                }
                index.registerInstalledSkills(
                    candidates.map { InstalledSkillRegistration(it.id, it.registration) },
                )
                journal.clear()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                return rollback(operation, "committing the skill install failed", error)
            }
            SkillInstallResult.Success(
                candidates.map {
                    InstalledSkill(it.id, it.registration.name, it.registration.description)
                },
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            failure(SkillInstallErrorCode.IO_ERROR, "preparing the skill install transaction failed", error)
        }
    }

    /**
     * Eta `deleteSkill`'s transaction: journal, move the previous version aside,
     * drop the registry entry, clear. No new target is ever committed, so the
     * journal record keeps `newTargetCommitted = false` and a recovery puts the
     * backup back together with the registry snapshot.
     */
    private suspend fun removeCandidates(
        operation: InstallOperation,
        skillIds: List<String>,
    ): SkillInstallResult = withMutationLock {
        withContext(NonCancellable) { commitDeletes(operation, skillIds) }
    }

    private suspend fun commitDeletes(
        operation: InstallOperation,
        skillIds: List<String>,
    ): SkillInstallResult {
        if (store.stat(root)?.kind != SkillEntryKind.DIRECTORY) {
            return SkillInstallResult.Failure(
                SkillInstallError(SkillInstallErrorCode.IO_ERROR, "the skills directory is unavailable"),
            )
        }
        val backupRoot = skillTransactionChildOrNull(operation.directory, SkillInstallLayout.BACKUP_DIRECTORY)
            ?: return SkillInstallResult.Failure(
                SkillInstallError(SkillInstallErrorCode.IO_ERROR, "the backup directory name is unusable"),
            )
        return try {
            store.makeDirectory(backupRoot)
            val snapshots = index
                .captureRegistryRecoverySnapshots(skillIds)
                .associateBy { it.skillId }
            try {
                val journal = PendingSkillRecoveryJournal.begin(
                    store = store,
                    root = root,
                    operationDirectory = operation.directory,
                    records = skillIds.map { skillId ->
                        SkillRecoveryRecord(
                            id = skillId,
                            originalTargetExisted = store.stat(targetOf(skillId)) != null,
                            registrySnapshot = snapshots[skillId] ?: throw IOException(
                                "the registry snapshot of " + skillId + " is missing",
                            ),
                        )
                    },
                )
                skillIds.forEach { skillId ->
                    val target = targetOf(skillId)
                    // A skill whose directory is already gone still loses its registry
                    // row: the journal records that there was nothing to move aside, so a
                    // recovery leaves the tree alone.
                    if (store.stat(target) != null) {
                        moveSkillDirectoryAtomically(store, target, backupOf(operation, skillId))
                        journal.markBackupCompleted(skillId)
                    }
                }
                index.removeInstalledSkills(skillIds)
                journal.clear()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                return rollback(operation, "committing the skill delete failed", error)
            }
            SkillInstallResult.Success(emptyList())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            failure(SkillInstallErrorCode.IO_ERROR, "preparing the skill delete transaction failed", error)
        }
    }

    /**
     * Eta's rollback: unwind the pending transaction, restore the registry from
     * the snapshots the journal holds, and only then clear the journal. A
     * rollback that cannot finish keeps the journal and the operation directory,
     * and every following entry point refuses until a recovery completes.
     */
    private suspend fun rollback(
        operation: InstallOperation,
        what: String,
        error: Throwable,
    ): SkillInstallResult {
        val journalExists = store.stat(journalPath(operation.directory)) != null
        val rollbackComplete = !journalExists || runCatching {
            val recovered = recoverPendingSkillOperations(root, store)
            index.restoreRecoveredRegistry(recovered)
            completeRecoveredSkillOperations(root, store, recovered)
        }.isSuccess
        if (!rollbackComplete) operation.preserveForRecovery = true
        val suffix = if (rollbackComplete) {
            ", the previous version was restored"
        } else {
            ", and the automatic recovery did not finish"
        }
        return SkillInstallResult.Failure(
            error = SkillInstallError(
                code = SkillInstallErrorCode.COMMIT_FAILED,
                message = what + suffix + ": " + describe(error),
            ),
            recoveryRequired = !rollbackComplete,
        )
    }

    /**
     * Eta extracts the archive into `<operation>/extracted` before the commit
     * starts; Minis stages the validated payload into
     * `<operation>/new/<id>`. Nothing outside the operation directory is
     * written, and a staging failure leaves the installed skill untouched.
     */
    private suspend fun stageFiles(
        operationDirectory: String,
        candidate: SkillInstallCandidate,
        files: List<SkillInstallFile>,
    ) {
        val staging = skillTransactionChildOrNull(operationDirectory, SkillInstallLayout.STAGING_DIRECTORY)
            ?: throw IOException("the skill staging path is unusable")
        store.makeDirectory(staging)
        val target = skillTransactionChildOrNull(staging, candidate.id)
            ?: throw IOException("the skill staging path is unusable")
        store.makeDirectory(target)
        files.forEach { file ->
            var path = target
            file.relativePath.split('/').forEach { segment ->
                path = skillTransactionChildOrNull(path, segment)
                    ?: throw IOException("the staged path is unusable")
            }
            store.writeFile(path, file.bytes)
        }
    }

    /**
     * The merge half of [SkillInstallBase.PRESERVE]: read the installed tree
     * through the store, drop the entries the payload replaces, and stage the
     * union. The read runs under the mutation lock, so a payload can never be
     * merged into a tree another process is installing.
     */
    private suspend fun stageMergedPayload(
        operation: InstallOperation,
        candidates: List<SkillInstallCandidate>,
        files: List<SkillInstallFile>,
    ): SkillInstallResult? {
        val candidate = candidates.singleOrNull()
            ?: return SkillInstallResult.Failure(
                SkillInstallError(
                    code = SkillInstallErrorCode.INVALID_SKILL,
                    message = "a merged skill write can only carry one skill",
                ),
            )
        val installed = try {
            readInstalledSkillFiles(candidate.id)
        } catch (error: Exception) {
            return failure(SkillInstallErrorCode.IO_ERROR, "reading the installed skill failed", error)
        }
        val replaced = files.mapTo(HashSet(files.size)) { it.relativePath }
        val merged = installed.orEmpty().filter { it.relativePath !in replaced } + files
        checkStagedRequest(candidate, merged, requireSkillFile = true)?.let { return it }
        return try {
            stageFiles(operation.directory, candidate, merged)
            null
        } catch (error: Exception) {
            failure(SkillInstallErrorCode.IO_ERROR, "staging the skill failed", error)
        }
    }

    /**
     * Eta re-reads the installed entry under the lock before it replaces it; the
     * merge needs that read too. The walk is bounded by the same entry, depth and
     * byte budgets the transaction stages, and a tree that holds a symbolic link
     * or a special file is refused instead of being copied partially.
     */
    private suspend fun readInstalledSkillFiles(skillId: String): List<SkillInstallFile>? {
        val target = skillTransactionChildOrNull(root, skillId)
            ?: throw IOException("the skill target path is unusable")
        if (store.stat(target) == null) return null
        val files = mutableListOf<SkillInstallFile>()
        val budget = InstalledTreeBudget()
        collectInstalledFiles(target, "", files, budget)
        return files
    }

    private suspend fun collectInstalledFiles(
        directory: String,
        relativeDirectory: String,
        files: MutableList<SkillInstallFile>,
        budget: InstalledTreeBudget,
    ) {
        budget.enter(relativeDirectory)
        val children = store.children(directory)
            ?: throw IOException("the installed skill directory cannot be listed")
        children.forEach { name ->
            val child = skillTransactionChildOrNull(directory, name)
                ?: throw IOException("the installed skill holds an unusable name")
            val relativePath = if (relativeDirectory.isEmpty()) name else relativeDirectory + "/" + name
            when (store.stat(child)?.kind) {
                SkillEntryKind.DIRECTORY -> collectInstalledFiles(child, relativePath, files, budget)
                SkillEntryKind.FILE -> {
                    budget.enter(relativePath)
                    val bytes = store.readFile(child, SkillArchiveReader.MAX_ENTRY_BYTES.toInt())
                        ?: throw IOException("the installed skill file " + relativePath + " cannot be read")
                    budget.consumeBytes(bytes.size)
                    files += SkillInstallFile(relativePath, bytes)
                }
                else -> throw IOException(
                    "the installed skill holds a symbolic link or a special file: " + relativePath,
                )
            }
        }
    }

    /**
     * Eta's write-side path rules (`validateRelativePath` and the collision key
     * from `extractArchive`): every staged entry must be a relative path with no
     * control character, no dot segment and no oversized component, and two
     * entries may not collide after Unicode normalisation and case folding. The
     * reader already applies a similar rule; this is the boundary that
     * guarantees it for the staging directory itself.
     */
    private fun checkStagedRequest(
        candidate: SkillInstallCandidate,
        files: List<SkillInstallFile>,
        requireSkillFile: Boolean,
    ): SkillInstallResult? {
        if (!isSafeSkillTransactionId(candidate.id)) {
            return refusal("the skill id is not a safe directory name")
        }
        if (files.isEmpty() || files.size > SkillArchiveReader.MAX_ENTRIES) {
            return refusal("the staged set has an unusable number of entries")
        }
        if (requireSkillFile && files.none { it.relativePath == SKILL_FILE_NAME }) {
            return refusal("the staged set has no SKILL.md")
        }
        var totalBytes = 0L
        val pathKinds = linkedMapOf<String, Boolean>()
        files.forEach { file ->
            val segments = validateRelativePath(file.relativePath)
                ?: return refusal("the staged set holds an unsafe path")
            if (segments.size > SkillTransactionLimits.MAX_PATH_DEPTH) {
                return refusal("the staged set nests deeper than the allowed " + SkillTransactionLimits.MAX_PATH_DEPTH + " levels")
            }
            val relativePath = segments.joinToString("/")
            val collisionKey = collisionKey(relativePath)
            if (pathKinds.containsKey(collisionKey)) {
                return refusal("the staged set repeats the entry " + relativePath)
            }
            val ancestorKeys = segments.indices.drop(1).map { index ->
                collisionKey(segments.take(index).joinToString("/"))
            }
            if (ancestorKeys.any { pathKinds[it] == false }) {
                return refusal("the staged set mixes a file and a directory at " + relativePath)
            }
            if (pathKinds.keys.any { it.startsWith(collisionKey + "/") }) {
                return refusal("the staged set mixes a file and a directory at " + relativePath)
            }
            pathKinds[collisionKey] = false
            totalBytes += file.bytes.size.toLong()
            if (totalBytes > SkillArchiveReader.MAX_TOTAL_BYTES) {
                return refusal("the staged set is larger than " + SkillArchiveReader.MAX_TOTAL_BYTES + " bytes")
            }
        }
        return null
    }

    private fun validateRelativePath(raw: String): List<String>? {
        if (
            raw.isBlank() || raw.startsWith('/') || raw.startsWith('\\') ||
            WINDOWS_DRIVE_PREFIX.containsMatchIn(raw) || raw.contains('\\') ||
            raw.contains('\u0000') || raw.any { it.isISOControl() }
        ) {
            return null
        }
        val segments = raw.split('/')
        if (
            segments.any { segment ->
                segment.isBlank() || segment == "." || segment == ".." ||
                    segment.length > SkillTransactionLimits.MAX_PATH_SEGMENT_CHARS
            }
        ) {
            return null
        }
        return segments
    }

    private fun collisionKey(path: String): String = Normalizer
        .normalize(path, Normalizer.Form.NFC)
        .lowercase(Locale.ROOT)

    private fun targetOf(skillId: String): String =
        skillTransactionChildOrNull(root, skillId)
            ?: throw IOException("the skill target path is unusable")

    private fun backupOf(operation: InstallOperation, skillId: String): String {
        val backupRoot = skillTransactionChildOrNull(operation.directory, SkillInstallLayout.BACKUP_DIRECTORY)
            ?: throw IOException("the skill backup path is unusable")
        return skillTransactionChildOrNull(backupRoot, skillId)
            ?: throw IOException("the skill backup path is unusable")
    }

    private fun stagedOf(operation: InstallOperation, skillId: String): String {
        val staging = skillTransactionChildOrNull(operation.directory, SkillInstallLayout.STAGING_DIRECTORY)
            ?: throw IOException("the skill staging path is unusable")
        return skillTransactionChildOrNull(staging, skillId)
            ?: throw IOException("the skill staging path is unusable")
    }

    private fun refusal(message: String): SkillInstallResult = SkillInstallResult.Failure(
        SkillInstallError(SkillInstallErrorCode.INVALID_SKILL, message),
    )

    private fun failure(
        code: SkillInstallErrorCode,
        message: String,
        error: Throwable,
    ): SkillInstallResult = SkillInstallResult.Failure(
        SkillInstallError(code, message + ": " + describe(error)),
    )

    private fun describe(error: Throwable): String =
        error.message ?: error.javaClass.simpleName

    private companion object {
        const val SKILL_FILE_NAME = "SKILL.md"
        val WINDOWS_DRIVE_PREFIX = Regex("^[A-Za-z]:")
    }
}

/**
 * Ceilings for the installed-tree read a merged write performs. The resulting
 * set is validated again by the staging rules before anything is written, so this
 * budget only has to stop an oversized tree from being buffered at all.
 */
private class InstalledTreeBudget {
    private var entries = 0
    private var bytes = 0L

    fun enter(relativePath: String) {
        val depth = if (relativePath.isEmpty()) 0 else relativePath.count { it == '/' } + 1
        if (depth > SkillTransactionLimits.MAX_PATH_DEPTH) {
            throw IOException(
                "the installed skill nests deeper than " + SkillTransactionLimits.MAX_PATH_DEPTH + " levels",
            )
        }
        entries += 1
        if (entries > SkillArchiveReader.MAX_ENTRIES) {
            throw IOException(
                "the installed skill holds more than " + SkillArchiveReader.MAX_ENTRIES + " entries",
            )
        }
    }

    fun consumeBytes(count: Int) {
        bytes += count.toLong()
        if (bytes > SkillArchiveReader.MAX_TOTAL_BYTES) {
            throw IOException(
                "the installed skill is larger than " + SkillArchiveReader.MAX_TOTAL_BYTES + " bytes",
            )
        }
    }
}
