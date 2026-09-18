package com.openminis.app.data.repository

import com.openminis.app.runtime.files.WorkspaceFileClient

/**
 * [T-android-skill-install-transaction] The file surface the ported install
 * transaction needs, bound to Minis' guest filesystem.
 *
 * Ported from Eta `agent/skill/SkillPackageInstaller.kt`,
 * `agent/skill/SkillRecoveryJournal.kt` and `agent/skill/SkillMutationLock.kt`
 * (commit c15de97); attribution lives in PROVENANCE.md.
 *
 * Those modules call `java.io.File` and `java.nio.file` directly, because in
 * Eta the skills tree is an ordinary app-private directory. In Minis the same
 * tree is guest storage: every path is resolved by the App-owned file API
 * ([WorkspaceFileClient]) into a trusted root plus components and is opened
 * through directory handles with `O_NOFOLLOW`, so no operation can be
 * redirected by a symbolic link or escape `/var/minis/skills`.
 *
 * The port therefore keeps Eta's sequence and its fail-closed decisions and
 * swaps only the file calls: [WorkspaceSkillTransactionStore] is what runs on
 * device, and unit tests substitute an in-memory fake.
 */
internal enum class SkillEntryKind { DIRECTORY, FILE, OTHER }

/** Kind of one path; symbolic links and special files land in [SkillEntryKind.OTHER]. */
internal data class SkillEntry(val kind: SkillEntryKind)

internal interface SkillTransactionStore {
    /** Null when the path does not exist. */
    suspend fun stat(path: String): SkillEntry?

    /** Names inside a directory, or null when the directory cannot be listed. */
    suspend fun children(path: String): List<String>?

    suspend fun makeDirectory(path: String)

    suspend fun writeFile(path: String, bytes: ByteArray)

    /** Bounded read; null when the path is missing, unreadable or larger than [maxBytes]. */
    suspend fun readFile(path: String, maxBytes: Int): ByteArray?

    suspend fun copy(source: String, destination: String)

    /** Rename when the platform allows it, copy+delete otherwise. */
    suspend fun move(source: String, destination: String)

    /** Deletes a tree without following symbolic links; a missing path is not an error. */
    suspend fun delete(path: String)
}

/**
 * Guest-backed implementation. Every call is a directory-handle operation:
 * the root comes from [com.openminis.app.runtime.ubuntu.UbuntuPaths], so a path
 * that leaves the guest namespace fails instead of resolving somewhere else,
 * and `type == "other"` (symbolic link, socket, fifo) is refused by the
 * transaction rather than silently traversed.
 */
internal object WorkspaceSkillTransactionStore : SkillTransactionStore {
    override suspend fun stat(path: String): SkillEntry? {
        val info = WorkspaceFileClient.info("", path)
        if (!info.optBoolean("exists", false)) return null
        return SkillEntry(kindOf(info.optString("type")))
    }

    override suspend fun children(path: String): List<String>? = runCatching {
        WorkspaceFileClient.listAll("", path).mapNotNull { entry ->
            entry.optString("name").takeIf { it.isNotEmpty() }
        }
    }.getOrNull()

    override suspend fun makeDirectory(path: String) {
        WorkspaceFileClient.mkdir("", path)
    }

    override suspend fun writeFile(path: String, bytes: ByteArray) {
        WorkspaceFileClient.writeBytes("", path, bytes)
    }

    override suspend fun readFile(path: String, maxBytes: Int): ByteArray? = runCatching {
        WorkspaceFileClient.readAll("", path, maxBytes.toLong())
    }.getOrNull()

    override suspend fun copy(source: String, destination: String) {
        WorkspaceFileClient.copy("", source, destination)
    }

    override suspend fun move(source: String, destination: String) {
        WorkspaceFileClient.move("", source, destination)
    }

    override suspend fun delete(path: String) {
        WorkspaceFileClient.delete("", path)
    }

    private fun kindOf(type: String): SkillEntryKind = when (type) {
        "dir" -> SkillEntryKind.DIRECTORY
        "file" -> SkillEntryKind.FILE
        else -> SkillEntryKind.OTHER
    }
}

/** One staged file: a validated relative path plus its bytes. */
internal data class SkillInstallFile(val relativePath: String, val bytes: ByteArray)

/** What the registry has to hold once the files are committed. */
internal data class SkillRegistration(
    val name: String,
    val description: String,
    val version: String,
    val importSource: String,
    val sourceUrl: String?,
    val body: String,
)

internal data class InstalledSkillRegistration(val id: String, val registration: SkillRegistration)

/**
 * The registry subset the transaction touches. It is the Minis projection of
 * Eta's `SkillIndexService`: Eta keeps skill registry entries in a JSON store
 * (enabled/source/installState), Minis keeps a `skills` row plus the in-memory
 * [SkillRepository.skills] list, so the snapshot is an opaque encoding handed
 * back unchanged on restore.
 */
internal interface SkillInstallIndex {
    /** Eta `captureRegistryRecoverySnapshots`: the state before any move. */
    fun captureRegistryRecoverySnapshots(skillIds: List<String>): List<SkillRegistryRecoverySnapshot>

    /** Eta `registerInstalledUserSkills`, plus the metadata the row needs. */
    fun registerInstalledSkills(registrations: List<InstalledSkillRegistration>)

    /**
     * Eta's delete path drops the registry entry inside the delete transaction, so a
     * rollback can put the captured snapshot back.
     */
    fun removeInstalledSkills(skillIds: List<String>)

    /** Eta `restoreRecoveredRegistry`: one restore for every recovered record. */
    fun restoreRecoveredRegistry(recovered: List<RecoveredSkillOperation>)
}

/**
 * Skill ids the transaction accepts as one directory name below the skills root.
 *
 * Eta mints its ids from the SKILL.md `name` and therefore validates them against a
 * lowercase-hyphen pattern. Minis keeps the on-disk directory name as the id, and its
 * loader already trusts every ASCII name without a path separator, so the transaction
 * has to accept that same set: a skill the app lists must stay editable and removable.
 * Containment is not decided here — every path is built through
 * [skillTransactionChildOrNull], which refuses separators, dot segments, control
 * characters and the reserved install work directory.
 */
internal fun isSafeSkillTransactionId(id: String): Boolean =
    id.length in 1..SkillTransactionLimits.MAX_SKILL_ID_CHARS &&
        id != "." && id != ".." &&
        id.all { character ->
            character.code < 128 &&
                (character.isLetterOrDigit() || character == '-' || character == '_' || character == '.')
        }

/** Bounds shared by the journal, the installer and the recovery walk. */
internal object SkillTransactionLimits {
    const val MAX_SKILL_ID_CHARS = 64
    const val MAX_PATH_SEGMENT_CHARS = 255
    const val MAX_PATH_DEPTH = 16
    const val MAX_TREE_ENTRIES = 2_048
}

/**
 * One validated child name below [parent], or null when the name could escape
 * the parent. Names come back from directory listings, so they are untrusted
 * input even though the parent is not.
 */
internal fun skillTransactionChildOrNull(parent: String, name: String): String? {
    if (name.isEmpty() || name == "." || name == "..") return null
    if (name == SkillInstallLayout.WORK_DIRECTORY) return null
    if (name.length > SkillTransactionLimits.MAX_PATH_SEGMENT_CHARS) return null
    val legal = name.all { character ->
        val code = character.code
        code >= 0x20 && code != 0x7F && character != '/' && character != '\\' && character != '\u0000'
    }
    if (!legal) return null
    return parent.trimEnd('/') + "/" + name
}
