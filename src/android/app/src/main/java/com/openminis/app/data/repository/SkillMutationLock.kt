package com.openminis.app.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * [T-android-skill-install-transaction] Cross-process mutex for the skills tree
 * and its registry, ported from Eta `agent/skill/SkillMutationLock.kt` (commit
 * c15de97); attribution lives in PROVENANCE.md.
 *
 * Eta's reasoning carries over unchanged: the UI and the Agent runtime can run
 * in different processes, so a JVM monitor alone cannot stop two install
 * transactions from passing the same precondition check. The lock is therefore
 * an advisory file lock on a real file, held for the whole transaction and for
 * every delete, and the first thing done under it is finishing whatever the
 * previous process left behind.
 *
 * Two mechanics differ because Minis is a coroutine codebase:
 *
 * - reentrancy is per coroutine job instead of per thread, because a suspending
 *   call can resume on another thread and Eta's thread-local set would not
 *   survive that;
 * - the blocking lock acquisition runs on the IO dispatcher and stays
 *   cancellable, so waiting for a stuck peer cannot pin the caller.
 */
internal object SkillMutationLock {

    /** One process-wide gate, mirroring Eta's single process-lock monitor. */
    private val processMutex = Mutex()

    @Volatile
    private var holderJob: Job? = null

    @Volatile
    private var holderRoots: Set<String> = emptySet()

    suspend fun <T> withLock(
        lockFile: File,
        skillsRoot: String,
        store: SkillTransactionStore,
        recoveryHandler: ((List<RecoveredSkillOperation>) -> Unit)? = null,
        block: suspend () -> T,
    ): T {
        val key = skillsRoot.trimEnd('/')
        val job = currentCoroutineContext()[Job]
        if (job != null && job === holderJob && key in holderRoots) return block()

        return processMutex.withLock {
            val previousHolder = holderJob
            val previousRoots = holderRoots
            holderJob = job
            holderRoots = holderRoots + key
            try {
                validateLockFile(lockFile)
                RandomAccessFile(lockFile, "rw").use { lockHandle ->
                    if (Files.isSymbolicLink(lockFile.toPath())) {
                        throw IOException("the skill install lock file is unsafe")
                    }
                    val channel = lockHandle.channel
                    var lock: FileLock? = null
                    try {
                        lock = runInterruptible(Dispatchers.IO) { channel.lock() }
                        recoverPendingOperations(skillsRoot, store, recoveryHandler)
                        block()
                    } finally {
                        runCatching { lock?.release() }
                    }
                }
            } finally {
                holderRoots = previousRoots
                holderJob = previousHolder
            }
        }
    }

    /**
     * The lock file must be a regular file inside an existing directory, with no
     * symbolic link on either path. Eta performs the same two checks around the
     * open; the port keeps both, so a link planted between them still fails.
     */
    private fun validateLockFile(lockFile: File) {
        val parent = lockFile.parentFile
            ?: throw IOException("the skill install lock has no parent directory")
        if (!Files.isDirectory(parent.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("the skill install lock directory is unavailable")
        }
        if (Files.isSymbolicLink(parent.toPath())) {
            throw IOException("the skill install lock directory is unsafe")
        }
        val path = lockFile.toPath()
        if (
            Files.exists(path, LinkOption.NOFOLLOW_LINKS) &&
            (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
        ) {
            throw IOException("the skill install lock file is unsafe")
        }
    }

    private suspend fun recoverPendingOperations(
        skillsRoot: String,
        store: SkillTransactionStore,
        recoveryHandler: ((List<RecoveredSkillOperation>) -> Unit)?,
    ) {
        val recovered = recoverPendingSkillOperations(skillsRoot, store)
        if (recovered.isEmpty()) return
        val handler = recoveryHandler
            ?: throw SkillRecoveryRequiredException(
                "skill files were recovered, the registry still needs restoring",
            )
        try {
            handler(recovered)
            completeRecoveredSkillOperations(skillsRoot, store, recovered)
        } catch (error: SkillRecoveryRequiredException) {
            throw error
        } catch (error: Exception) {
            throw SkillRecoveryRequiredException(
                "restoring the skill registry after recovery failed",
                error,
            )
        }
    }

}

/**
 * Eta prepareSkillInstallerWorkRoot: create or verify the private install work
 * root and refuse symbolic links or special files.
 */
internal suspend fun prepareSkillInstallerWorkRoot(
    root: String,
    store: SkillTransactionStore,
): String {
    val workRoot = skillInstallerWorkRoot(root)
    if (store.stat(workRoot) == null) {
        store.makeDirectory(workRoot)
    }
    if (store.stat(workRoot)?.kind != SkillEntryKind.DIRECTORY) {
        throw IOException("the skill install work directory is unsafe")
    }
    return workRoot
}

/**
 * Eta isRecoverableSkillDirectoryTree: a replace may only proceed when the old
 * directory can be copied back in full, so the tree must be ordinary files and
 * directories without symbolic links. Eta walks it without a ceiling; Minis
 * bounds the walk by the same entry and depth limits the recovery copy uses and
 * refuses anything larger (fail-closed).
 */
internal suspend fun isRecoverableSkillDirectoryTree(
    root: String,
    skillId: String,
    store: SkillTransactionStore,
): Boolean {
    if (!isSafeSkillTransactionId(skillId)) return false
    val target = skillTransactionChildOrNull(root, skillId) ?: return false
    val budget = WalkBudget()
    return isRegularDirectoryTreeWithoutLinks(target, store, budget, depth = 0)
}

private suspend fun isRegularDirectoryTreeWithoutLinks(
    path: String,
    store: SkillTransactionStore,
    budget: WalkBudget,
    depth: Int,
): Boolean {
    if (depth > SkillTransactionLimits.MAX_PATH_DEPTH) return false
    return when (store.stat(path)?.kind) {
        SkillEntryKind.FILE -> budget.consume(1)
        SkillEntryKind.DIRECTORY -> {
            if (!budget.consume(1)) return false
            val children = store.children(path) ?: return false
            children.all { name ->
                val child = skillTransactionChildOrNull(path, name) ?: return false
                isRegularDirectoryTreeWithoutLinks(child, store, budget, depth + 1)
            }
        }
        else -> false
    }
}

/**
 * Eta moveSkillDirectoryAtomically. Eta renames with the ATOMIC_MOVE option and
 * falls back to a plain move when the platform refuses the flag; the guest
 * adapter performs the same pair, trying a directory-handle rename first and
 * falling back to copy plus delete only when the rename is impossible.
 */
internal suspend fun moveSkillDirectoryAtomically(
    store: SkillTransactionStore,
    source: String,
    target: String,
) {
    store.move(source, target)
}

/**
 * Eta deleteSkillPathWithoutFollowingLinks: delete inside the skills root, but
 * never follow a link. A symbolic link is removed itself and its target is left
 * alone, and a missing path is already deleted. The guest adapter resolves the
 * path through directory handles, which is the containment check Eta performs
 * with canonical paths.
 */
internal suspend fun deleteSkillPathWithoutFollowingLinks(
    root: String,
    target: String,
    store: SkillTransactionStore,
): Boolean {
    val trimmedRoot = root.trimEnd('/')
    val trimmedTarget = target.trimEnd('/')
    if (trimmedTarget == trimmedRoot) return false
    if (!trimmedTarget.startsWith(trimmedRoot + "/")) return false
    return runCatching {
        store.delete(trimmedTarget)
        true
    }.getOrDefault(false)
}

private class WalkBudget {
    private var used = 0

    fun consume(count: Int): Boolean {
        used += count
        return used <= SkillTransactionLimits.MAX_TREE_ENTRIES
    }
}
