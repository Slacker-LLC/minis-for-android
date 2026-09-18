package com.openminis.app.data.repository

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.util.Log
import com.openminis.app.runtime.files.WorkspaceFileClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URLEncoder
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Manages skill metadata (SQLite) and SKILL.md files on disk.
 * Mirrors iOS SkillStore architecture:
 *   - Metadata in `skills.db` (name, description, version, source, enabled)
 *   - SKILL.md in `minis-global/skills/<id>/SKILL.md`
 *   - Session overrides in `session_skill_overrides` table
 *   - Prompt fragment generation for system prompt injection
 */
class SkillRepository(private val context: Context) {

    companion object {
        private const val TAG = "SkillRepository"
        private const val DB_NAME = "skills.db"
        private const val DB_VERSION = 3
        private const val MAX_SKILLS_IN_PROMPT = 20
        private const val MAX_SKILL_DESC_LENGTH = 200
        private const val RECENT_WINDOW_MS = 7L * 24 * 3600 * 1000
        private const val RECENT_SLOTS = 10
        private const val NORMALIZE_THRESHOLD = 1000.0

        /** [T-android-skill-export] How long an exported zip stays on disk
         *  before the next export sweeps it. 24h matches iOS — long enough that
         *  any Save-to-Files / AirDrop / upload consumer has finished. */
        private const val EXPORT_TTL_MS = 24L * 3600 * 1000
        private const val SKILLS_ROOT = "/var/minis/skills"

        /**
         * [T-android-skill-install-transaction] The cross-process install lock
         * lives in the app's private directory, not under the skills root: the
         * agent inside the guest can unlink anything below /var/minis/skills,
         * and a lock file that can be replaced stops excluding anybody.
         */
        private const val INSTALL_LOCK_FILE_NAME = ".skill-install.lock"
        /**
         * Skill metadata is useful but never allowed to hold up application
         * startup. A transient guest-file/runtime failure gets one bounded
         * background load attempt and can be retried when the skills screen is opened.
         */
        private const val SKILL_LOAD_TIMEOUT_MS = 15_000L
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Repository-owned scope for fire-and-forget background work that must
     * outlive the calling Composable / ViewModel — notably the sibling-file
     * download that runs after a SKILL.md import. SupervisorJob so a single
     * download failure doesn't poison the next one.
     */
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Serialize initial loading and explicit disk rescans. */
    private val loadMutex = Mutex()

    /**
     * [T-android-skill-install-transaction] Registry side of the install
     * transaction. Ported from Eta `SkillRuntime.captureRegistryRecoverySnapshots`
     * / `registerInstalledUserSkills` / `restoreRecoveredRegistry` (commit
     * c15de97), which is the JSON registry store; Minis' registry is the
     * `skills` table plus the in-memory list, so the snapshot is the row.
     */
    private val skillInstallIndex = object : SkillInstallIndex {
        override fun captureRegistryRecoverySnapshots(
            skillIds: List<String>,
        ): List<SkillRegistryRecoverySnapshot> = skillIds.distinct().map { skillId ->
            val payload = readSkillRowSnapshot(skillId)
            SkillRegistryRecoverySnapshot(
                skillId = skillId,
                entryExisted = payload != null,
                payload = payload,
            )
        }

        override fun registerInstalledSkills(registrations: List<InstalledSkillRegistration>) {
            registrations.forEach { installed ->
                writeRegisteredSkill(installed.id, installed.registration)
            }
        }

        /**
         * Eta `SkillIndexService.deleteSkill` drops the registry entry inside the
         * delete transaction, after the previous version has moved into the backup
         * and before the journal is cleared.
         */
        override fun removeInstalledSkills(skillIds: List<String>) {
            skillIds.forEach { removeSkillRow(it) }
        }

        override fun restoreRecoveredRegistry(recovered: List<RecoveredSkillOperation>) {
            if (recovered.isEmpty()) return
            recovered.flatMap { it.records }.forEach { record ->
                val snapshot = record.registrySnapshot
                if (snapshot.entryExisted) {
                    val payload = snapshot.payload
                        ?: throw IllegalStateException("the recovery journal lost the registry snapshot")
                    restoreSkillRow(record.id, payload)
                } else {
                    removeSkillRow(record.id)
                }
            }
        }
    }

    private val skillPackageInstaller = SkillPackageInstaller(
        root = SKILLS_ROOT,
        store = WorkspaceSkillTransactionStore,
        index = skillInstallIndex,
        lockFile = File(context.filesDir, INSTALL_LOCK_FILE_NAME),
    )

    // -- Data Types --

    enum class ImportSource(val value: String) {
        URL("url"),
        FILE("file"),
        BUNDLED("bundled"),
        SESSION("session");

        companion object {
            fun from(value: String): ImportSource =
                entries.find { it.value == value } ?: FILE
        }
    }

    data class Skill(
        val id: String = UUID.randomUUID().toString(),
        val name: String,
        val description: String = "",
        val version: String = "1.0.0",
        val importSource: ImportSource = ImportSource.FILE,
        val isEnabled: Boolean = true,
        val installedAt: Long = System.currentTimeMillis(),
        val updatedAt: Long = System.currentTimeMillis(),
        val body: String = "",
        /** Original GitHub URL when importSource is URL (null otherwise). */
        val sourceURL: String? = null,
        /** Cumulative read count of this skill's SKILL.md; normalized to 0–100 after exceeding 1000. */
        val useCount: Double = 0.0,
    )

    enum class UsageFrequency { NEVER, LOW, REGULAR, HIGH }

    // -- State --

    private val _skills = MutableStateFlow<List<Skill>>(emptyList())
    val skills: StateFlow<List<Skill>> = _skills.asStateFlow()

    private val db: SQLiteDatabase by lazy {
        SkillDbHelper(context).writableDatabase
    }

    init {
        // [T-android-safemode-lateinit-crash-147] and GH#129: this constructor
        // runs inline in MinisApp.onCreate. Disk-backed skill loading performs
        // guest-file reads may encounter a transient runtime failure, so
        // it must never run on the application/main thread. Keep the complete
        // initialization sequence in the repository-owned IO scope; the app
        // can finish creating all other subsystems and render its UI while
        // skills are loaded (or while the runtime failure is logged).
        backgroundScope.launch {
            loadMutex.withLock {
                loadAllSafelyLocked("initialization")
                runCatching { withTimeout(SKILL_LOAD_TIMEOUT_MS) { installBundledSkills() } }
                    .onFailure {
                        Log.e(TAG, "installBundledSkills failed — continuing: ${it.message}", it)
                    }
            }
        }
    }

    // -- CRUD --

    fun add(name: String, description: String, body: String, version: String = "1.0.0", source: ImportSource = ImportSource.FILE, sourceURL: String? = null): Skill? {
        val id = slugify(name)
        if (id.isBlank()) return null
        if (_skills.value.any { it.id == id }) return null

        // [T-android-skill-install-transaction] Creating a skill is the same transaction
        // as installing an archive: SKILL.md and the registry row commit together. The
        // previous order (row first, file second) could leave a row advertising a file
        // that was never written. PRESERVE keeps the sibling files of a directory that
        // already exists on disk without a row.
        val registration = SkillRegistration(
            name = name,
            description = description,
            version = version,
            importSource = source.value,
            sourceUrl = sourceURL,
            body = body,
        )
        val result = commitSkillFilesBlocking(
            skillId = id,
            registration = registration,
            files = listOf(skillMarkdownFile(skillMdContent(name, description, version, body))),
            base = SkillInstallBase.PRESERVE,
        )
        return when (result) {
            is SkillInstallResult.Success -> _skills.value.find { skill -> skill.id == id }
                ?.also { Log.i(TAG, "Added skill: " + it.id) }
            is SkillInstallResult.Failure -> {
                logRefusedTransaction("add", id, result)
                null
            }
        }
    }

    fun update(id: String, name: String? = null, description: String? = null, body: String? = null): Boolean {
        val current = _skills.value.find { it.id == id } ?: return false
        val updated = current.copy(
            name = name ?: current.name,
            description = description ?: current.description,
            body = body ?: current.body,
            updatedAt = System.currentTimeMillis(),
        )

        // [T-android-skill-install-transaction] The row and SKILL.md are one transaction:
        // a failure leaves the version the user already had on disk and in the registry
        // instead of a row that has already moved on. The sibling files are read inside
        // the lock and staged with the new SKILL.md, so an interrupted update cannot
        // drop scripts/ either.
        val result = commitSkillFilesBlocking(
            skillId = id,
            registration = registrationOf(updated),
            files = listOf(
                skillMarkdownFile(
                    skillMdContent(updated.name, updated.description, updated.version, updated.body),
                ),
            ),
            base = SkillInstallBase.PRESERVE,
        )
        return when (result) {
            is SkillInstallResult.Success -> true
            is SkillInstallResult.Failure -> {
                logRefusedTransaction("update", id, result)
                false
            }
        }
    }

    fun delete(id: String) {
        // [T-android-skill-install-transaction] Removal is the same transaction as an
        // install, ported from Eta `SkillIndexService.deleteSkill`: the previous version
        // is moved into the transaction's backup, the journal records that move, and the
        // registry row is dropped inside the transaction, so a failure or a process
        // death restores both. The previous order (row first) resurrected the skill from
        // disk on the next load whenever the guest delete failed.
        val removal = runCatching {
            runBlocking(Dispatchers.IO) { skillPackageInstaller.delete(id) }
        }.getOrElse { error ->
            SkillInstallResult.Failure(
                SkillInstallError(
                    SkillInstallErrorCode.IO_ERROR,
                    error.message ?: "the skill delete failed",
                ),
            )
        }
        if (removal is SkillInstallResult.Failure) {
            logRefusedTransaction("delete", id, removal)
            return
        }
        // The skills row is restored by the journal; the session overrides are not part
        // of the install registry, so they are dropped once the delete has committed.
        db.execSQL("DELETE FROM session_skill_overrides WHERE skill_id=?", arrayOf(id))
        Log.i(TAG, "Deleted skill: $id")
    }

    fun setEnabled(id: String, enabled: Boolean) {
        // [T-android-skill-install-transaction] Eta `SkillIndexService.setSkillEnabled`
        // writes the registry under the same root lock as an install: a toggle can never
        // land while a transaction is pending, and an unfinished recovery refuses it
        // instead of letting it guess (fail-closed).
        val applied = runCatching {
            runBlocking(Dispatchers.IO) {
                skillPackageInstaller.withSkillMutationLock {
                    db.execSQL(
                        "UPDATE skills SET is_enabled=? WHERE id=?",
                        arrayOf<Any>(if (enabled) 1 else 0, id),
                    )
                    _skills.value = _skills.value.map {
                        if (it.id == id) it.copy(isEnabled = enabled) else it
                    }
                }
            }
        }
        applied.onFailure { error ->
            Log.w(TAG, "Refused skill toggle for " + id + ": " + error.message)
        }
    }

    // -- Session Overrides --

    fun isEnabledForSession(skillId: String, sessionId: String): Boolean {
        val cursor = db.rawQuery(
            "SELECT is_enabled FROM session_skill_overrides WHERE session_id=? AND skill_id=?",
            arrayOf(sessionId, skillId)
        )
        val override = if (cursor.moveToFirst()) cursor.getInt(0) == 1 else null
        cursor.close()
        if (override != null) return override
        return _skills.value.find { it.id == skillId }?.isEnabled ?: false
    }

    fun setSessionOverride(sessionId: String, skillId: String, enabled: Boolean) {
        db.execSQL(
            "INSERT OR REPLACE INTO session_skill_overrides (session_id, skill_id, is_enabled) VALUES (?, ?, ?)",
            arrayOf<Any>(sessionId, skillId, if (enabled) 1 else 0)
        )
    }

    fun clearSessionOverrides(sessionId: String) {
        db.execSQL("DELETE FROM session_skill_overrides WHERE session_id=?", arrayOf(sessionId))
    }

    /**
     * [T-android-session-skill-override-init-timing] Re-point every
     * `session_skill_overrides` row that was written against the draft
     * session id ([fromDraft], e.g. `__new__<uuid>`) onto the persisted
     * session id ([toReal]) once `ensureSession()` creates the real DB row.
     * Without this hop, a pre-first-message skill toggle stays bound to the
     * draft key and becomes invisible the next time the chat is opened
     * under its real id — exactly the symptom XIN reported. Paired with
     * [MCPRepository.renameSessionOverrides].
     */
    fun renameSessionOverrides(fromDraft: String, toReal: String) {
        if (fromDraft == toReal) return
        db.execSQL(
            "UPDATE OR REPLACE session_skill_overrides SET session_id=? WHERE session_id=?",
            arrayOf<Any>(toReal, fromDraft),
        )
    }

    // -- Prompt Fragment --

    /**
     * Build the system-prompt fragment that makes skills discoverable.
     * Discloses up to [MAX_SKILLS_IN_PROMPT] skills with 3-tier priority
     * (bundled > 7-day recent > most-used), matching iOS SkillStore.
     * Returns null when the session has no enabled skills.
     */
    fun skillPromptFragment(sessionId: String): String? {
        val enabled = _skills.value.filter { isEnabledForSession(it.id, sessionId) }
        if (enabled.isEmpty()) return null

        val total = enabled.size
        val selected: List<Skill>
        val hasMore: Boolean

        if (total <= MAX_SKILLS_IN_PROMPT) {
            selected = enabled.sortedByDescending { it.updatedAt }
            hasMore = false
        } else {
            val picked = linkedMapOf<String, Skill>() // preserves insertion order + id dedupe
            // Priority 1: bundled
            enabled.filter { it.importSource == ImportSource.BUNDLED }
                .forEach { picked.putIfAbsent(it.id, it) }
            // Priority 2: recently updated (within 7 days), up to RECENT_SLOTS more
            val cutoff = System.currentTimeMillis() - RECENT_WINDOW_MS
            val recentLimit = minOf(RECENT_SLOTS, MAX_SKILLS_IN_PROMPT - picked.size).coerceAtLeast(0)
            enabled.asSequence()
                .filter { it.updatedAt > cutoff && it.id !in picked }
                .sortedByDescending { it.updatedAt }
                .take(recentLimit)
                .forEach { picked.putIfAbsent(it.id, it) }
            // Priority 3: fill remaining slots by useCount (desc)
            if (picked.size < MAX_SKILLS_IN_PROMPT) {
                val remaining = MAX_SKILLS_IN_PROMPT - picked.size
                enabled.asSequence()
                    .filter { it.id !in picked }
                    .sortedByDescending { it.useCount }
                    .take(remaining)
                    .forEach { picked.putIfAbsent(it.id, it) }
            }
            selected = picked.values.toList()
            hasMore = total > selected.size
        }

        val xml = buildString {
            append("<available_skills>\n")
            for (skill in selected) {
                var desc = skill.description
                if (desc.length > MAX_SKILL_DESC_LENGTH) {
                    desc = desc.substring(0, MAX_SKILL_DESC_LENGTH) + "…"
                }
                append("  <skill>\n")
                append("    <name>").append(escapeXml(skill.name)).append("</name>\n")
                append("    <description>").append(escapeXml(desc)).append("</description>\n")
                append("    <path>/var/minis/skills/").append(skill.id).append("/SKILL.md</path>\n")
                append("  </skill>\n")
            }
            append("</available_skills>")
        }

        return buildString {
            append("Skills:\n")
            append("Reusable instruction sets stored at /var/minis/skills/<name>/SKILL.md. Read the SKILL.md file to load full instructions before using a skill.\n\n")
            append(xml)
            if (hasMore) {
                val selectedIds = selected.mapTo(HashSet(selected.size)) { it.id }
                val omitted = enabled.filter { it.id !in selectedIds }
                val maxUndisclosed = (100 - selected.size).coerceAtLeast(0)
                val names = omitted.take(maxUndisclosed).joinToString(", ") { it.name }
                append("\n\n")
                append(omitted.size).append(" more skills not shown above: ").append(names)
                append(". List /var/minis/skills/ or grep to search all.")
            }
        }
    }

    /**
     * Record that a skill's SKILL.md was read. Matches iOS `SkillStore.recordSkillUse`:
     * bumps `useCount` by 1 and normalizes all counts to 0–100 when any exceeds 1000,
     * so long-lived installs don't drift into multi-thousand-read territory.
     */
    fun recordSkillUse(skillId: String) {
        val current = _skills.value.find { it.id == skillId } ?: return
        val bumped = current.copy(useCount = current.useCount + 1.0)
        db.execSQL(
            "UPDATE skills SET use_count = use_count + 1 WHERE id=?",
            arrayOf<Any>(skillId)
        )
        var next = _skills.value.map { if (it.id == skillId) bumped else it }
        if (bumped.useCount > NORMALIZE_THRESHOLD) {
            next = normalizeUseCounts(next)
        }
        _skills.value = next
    }

    private fun normalizeUseCounts(list: List<Skill>): List<Skill> {
        val maxCount = list.maxOfOrNull { it.useCount } ?: 0.0
        if (maxCount <= 0.0) return list
        val scaled = list.map { it.copy(useCount = it.useCount / maxCount * 100.0) }
        db.beginTransaction()
        try {
            for (s in scaled) {
                db.execSQL(
                    "UPDATE skills SET use_count=? WHERE id=?",
                    arrayOf<Any>(s.useCount, s.id)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return scaled
    }

    /** Coarse-grained usage band for UI surfacing (matches iOS UsageFrequency). */
    fun usageFrequency(skillId: String): UsageFrequency {
        val skill = _skills.value.find { it.id == skillId } ?: return UsageFrequency.NEVER
        if (skill.useCount == 0.0) return UsageFrequency.NEVER
        val maxCount = _skills.value.maxOfOrNull { it.useCount } ?: 0.0
        if (maxCount <= 0.0) return UsageFrequency.NEVER
        val normalized = skill.useCount / maxCount * 100.0
        return when {
            normalized < 20.0 -> UsageFrequency.LOW
            normalized < 60.0 -> UsageFrequency.REGULAR
            else -> UsageFrequency.HIGH
        }
    }

    /**
     * Extract skill id from `/var/minis/skills/<id>/SKILL.md`. Returns null unless
     * the path is exactly a SKILL.md read of a known skill — sub-resource reads
     * under `scripts/` etc. don't count toward usage.
     */
    fun skillIdFromPath(path: String): String? {
        val prefix = "/var/minis/skills/"
        if (!path.startsWith(prefix)) return null
        if (!path.endsWith("/SKILL.md")) return null
        val rest = path.substring(prefix.length)
        val slash = rest.indexOf('/')
        if (slash <= 0) return null
        val candidate = rest.substring(0, slash)
        return if (_skills.value.any { it.id == candidate }) candidate else null
    }

    // -- Import from SKILL.md Content --

    /**
     * Parse SKILL.md content (YAML frontmatter + markdown body) and import.
     * Returns the created Skill or null on failure.
     */
    fun importFromContent(content: String, source: ImportSource = ImportSource.FILE, sourceURL: String? = null): Skill? {
        val parsed = parseSkillMd(content) ?: return null
        val id = slugify(parsed.name)
        if (id.isBlank()) return null
        // [T-android-skill-install-transaction] Every content import (editor save, URL and
        // GitHub import, session fork) writes SKILL.md and the registry row through the
        // same transaction as an archive install. The old in-place write could leave a
        // file whose frontmatter no longer matched the row after an interruption.
        // PRESERVE keeps the sibling files of a skill that is already installed, which is
        // what the in-place write did.
        val current = _skills.value.find { it.id == id }
        val registration = SkillRegistration(
            name = parsed.name,
            description = parsed.description,
            version = parsed.version,
            importSource = source.value,
            sourceUrl = sourceURL ?: current?.sourceURL,
            body = parsed.body,
        )
        val result = commitSkillFilesBlocking(
            skillId = id,
            registration = registration,
            files = listOf(
                skillMarkdownFile(
                    skillMdContent(parsed.name, parsed.description, parsed.version, parsed.body),
                ),
            ),
            base = SkillInstallBase.PRESERVE,
        )
        return when (result) {
            is SkillInstallResult.Success -> _skills.value.find { skill -> skill.id == id }
            is SkillInstallResult.Failure -> {
                logRefusedTransaction("import", id, result)
                null
            }
        }
    }

    /**
     * Returns the SKILL.md file path for a given skill (for display in UI).
     */
    fun skillMdPath(id: String): String = "/var/minis/skills/$id/SKILL.md"

    // -- Import from Zip Archive --

    /**
     * Import a skill from a .zip archive (mirrors iOS `SkillStore.importFromArchive`).
     * The archive must contain a `SKILL.md` at the root or one directory deep;
     * bundled sibling files (scripts/, references/, assets/, etc.) are extracted
     * alongside it into `/var/minis/skills/<id>/`.
     */
    fun importFromArchive(input: InputStream): Skill? {
        // [T-android-skill-archive-bounds] Read the archive under hard budgets
        // and refuse the whole file when it violates them, instead of pulling
        // every entry into memory and skipping what does not fit.
        val archive = try {
            SkillArchiveReader.read(input)
        } catch (e: SkillArchiveException) {
            Log.w(TAG, "Rejected skill archive: ${e.message}")
            return null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read zip archive: ${e.message}")
            return null
        }
        if (archive.skippedEntries > 0) {
            Log.i(TAG, "Skipped ${archive.skippedEntries} hidden archive entries")
        }

        val parsed = parseSkillMd(archive.skillMarkdown) ?: return null
        val skillId = slugify(parsed.name)
        if (skillId.isBlank()) return null

        // [T-android-skill-install-transaction] The archive is staged below the
        // skills root and only then committed. Writing entry by entry straight
        // into /var/minis/skills/<id>/ (what this used to do) left a directory
        // that looked installed once a write failed, and could not be repaired
        // after a process death at all. The staged SKILL.md is the same
        // canonical text the direct write produced before, so the layout, the
        // file contents and the DB row are unchanged. An archive defines the
        // whole skill, so it stages with SkillInstallBase.REPLACE — a file the
        // archive does not carry is gone, exactly as before.
        val files = buildList {
            add(
                SkillInstallFile(
                    relativePath = "SKILL.md",
                    bytes = skillMdContent(parsed.name, parsed.description, parsed.version, parsed.body)
                        .toByteArray(Charsets.UTF_8),
                ),
            )
            archive.files.forEach { add(SkillInstallFile(it.relativePath, it.bytes)) }
        }
        val registration = SkillRegistration(
            name = parsed.name,
            description = parsed.description,
            version = parsed.version,
            importSource = ImportSource.FILE.value,
            sourceUrl = null,
            body = parsed.body,
        )
        val result = runBlocking(Dispatchers.IO) {
            skillPackageInstaller.install(SkillInstallCandidate(skillId, registration), files)
        }
        return when (result) {
            is SkillInstallResult.Success -> _skills.value.find { it.id == skillId }
            is SkillInstallResult.Failure -> {
                Log.w(
                    TAG,
                    "Refused skill archive import for " + skillId + ": " + result.error.code + " " +
                        result.error.message + " (recoveryRequired=" + result.recoveryRequired + ")",
                )
                null
            }
        }
    }

    // -- Restore from a backup package --

    /**
     * [T-android-skill-backup-restore-transaction] One skill as a backup package
     * carries it: a path relative to the skill directory plus its bytes.
     */
    data class BackupSkillFile(val relativePath: String, val bytes: ByteArray)

    /**
     * Outcome of [restoreFromBackup].
     *
     * [Refused.recoveryRequired] is the fail-closed signal: an unfinished install
     * transaction left by an earlier process has to be recovered before any skill
     * write is accepted, so the caller stops instead of retrying skill after skill.
     */
    sealed class BackupSkillRestore {
        /** The whole skill is installed; [created] is false when it replaced a local one. */
        data class Restored(val skill: Skill, val created: Boolean) : BackupSkillRestore()

        /** Nothing was written. [reason] is safe to log and to show. */
        data class Refused(val reason: String, val recoveryRequired: Boolean) : BackupSkillRestore()
    }

    /**
     * [T-android-skill-backup-restore-transaction] Restore one skill from a backup
     * package through the same install transaction every other skill write uses
     * ([T-android-skill-install-transaction]).
     *
     * The backup importer used to write the package's `skills/<id>/…` tree straight
     * into the skills directory and let the next load invent the registry row: no
     * lock (a restore could interleave with an install on the same tree), no journal
     * (an interrupted restore left a half-written skill nothing could name, let
     * alone repair) and no budget. Committing through [commitSkillFiles] stages the
     * payload below the skills root, moves the previous version into the
     * transaction's backup, writes the registry row last and rolls back to the
     * installed version when anything fails.
     *
     * [SkillInstallBase.PRESERVE] is what keeps a restore from deleting data the
     * package does not carry: the staged tree starts from the installed skill, so a
     * §3.4 size tombstone or an unreadable file keeps the local copy instead of
     * being replaced by a hole. The path rules, depth, entry count and byte budgets
     * are the archive import's, enforced by the transaction itself — absolute
     * paths, `..`, backslashes, control characters, repeated entries and
     * file/directory collisions are refused.
     *
     * The registry row: name/description/version/body come from the restored
     * SKILL.md, the source stays what the local skill had, and a skill this device
     * did not have is registered as [ImportSource.SESSION] — the source the
     * auto-discovery pass used to give it after the direct write.
     */
    suspend fun restoreFromBackup(skillId: String, files: List<BackupSkillFile>): BackupSkillRestore {
        if (!isSafeSkillTransactionId(skillId)) {
            return BackupSkillRestore.Refused(
                reason = "the skill id is not a safe directory name",
                recoveryRequired = false,
            )
        }
        val skillMarkdown = files.firstOrNull { it.relativePath == "SKILL.md" }?.bytes
            ?: return BackupSkillRestore.Refused(
                reason = "the restored skill carries no SKILL.md",
                recoveryRequired = false,
            )
        val parsed = parseSkillMd(String(skillMarkdown, Charsets.UTF_8))
            ?: return BackupSkillRestore.Refused(
                reason = "the restored SKILL.md is not a readable skill",
                recoveryRequired = false,
            )
        val current = _skills.value.find { it.id == skillId }
        val registration = SkillRegistration(
            name = parsed.name,
            description = parsed.description,
            version = parsed.version,
            importSource = (current?.importSource ?: ImportSource.SESSION).value,
            sourceUrl = current?.sourceURL,
            body = parsed.body,
        )
        val result = commitSkillFiles(
            skillId = skillId,
            registration = registration,
            files = files.map { SkillInstallFile(it.relativePath, it.bytes) },
            base = SkillInstallBase.PRESERVE,
        )
        return when (result) {
            is SkillInstallResult.Success -> {
                val skill = _skills.value.find { it.id == skillId }
                    ?: return BackupSkillRestore.Refused(
                        reason = "the restored skill was not registered",
                        recoveryRequired = false,
                    )
                BackupSkillRestore.Restored(skill, created = current == null)
            }
            is SkillInstallResult.Failure -> BackupSkillRestore.Refused(
                reason = result.error.code.name + ": " + result.error.message,
                recoveryRequired = result.recoveryRequired,
            )
        }
    }

    // -- Import from GitHub URL --

    data class GitHubInfo(
        val user: String,
        val repo: String,
        val branch: String,
        /** Directory path (without SKILL.md leaf) */
        val dirPath: String,
    )

    /**
     * Download SKILL.md from a GitHub URL and recursively download sibling files
     * from the same directory. Mirrors iOS SkillStore.importFromGitHub.
     */
    suspend fun importFromGitHub(urlString: String): Skill? = withContext(Dispatchers.IO) {
        val rawURL = githubToRawURL(urlString) ?: return@withContext null
        val content = httpGetString(rawURL) ?: return@withContext null
        val skill = importFromContent(content, ImportSource.URL, sourceURL = urlString) ?: return@withContext null

        val ghInfo = parseGitHubURL(urlString) ?: return@withContext skill
        // Detach the sibling-file download from the caller's coroutine scope.
        // The previous design ran the recursion inline, so any navigation
        // away from the import screen — or a ViewModel finishing its initial
        // composition — would cancel the recursion mid-flight and leave the
        // skill with only SKILL.md (T152 root cause).
        backgroundScope.launch {
            // [T-android-skill-install-transaction] The recursion now collects the
            // sibling files and commits them in one transaction on the same lock the
            // SKILL.md import used; a recursion that dies halfway leaves the previous
            // version of every file it had not committed yet. The download stays detached
            // from the caller's scope on purpose: navigation must not cancel it (T152).
            val files = mutableListOf<SkillInstallFile>()
            val outcome = downloadSiblingFiles(ghInfo, files)
            val current = _skills.value.find { it.id == skill.id }
            if (current != null && files.isNotEmpty()) {
                val result = commitSkillFiles(
                    skillId = skill.id,
                    registration = registrationOf(current),
                    files = files,
                    base = SkillInstallBase.PRESERVE,
                )
                if (result is SkillInstallResult.Failure) {
                    logRefusedTransaction("sibling import", skill.id, result)
                }
            }
            if (!outcome.isComplete) {
                Log.w(TAG, "importFromGitHub partial for ${skill.id}: ${outcome.reason ?: "${outcome.filesFailed} file(s) failed"}")
            }
        }
        skill
    }

    /** Outcome of [updateFromURL]: either an updated skill or a human-readable reason. */
    sealed class UpdateResult {
        data class Success(val skill: Skill) : UpdateResult()
        /**
         * SKILL.md fetched + persisted, but the sibling-file recursion
         * (scripts/, references/, …) ran into trouble. The skill is usable
         * but its bundled resources may be incomplete. T152 fix: this state
         * used to be silently squashed into a plain Success.
         */
        data class PartialSuccess(val skill: Skill, val reason: String) : UpdateResult()
        data class Failure(val reason: String) : UpdateResult()
    }

    /**
     * Aggregate outcome of a sibling-file download walk — surfaced through
     * [UpdateResult.PartialSuccess] when SKILL.md imported but sibling files
     * (scripts/, references/, …) could not all be retrieved. `reason` is
     * non-null only when [filesFailed] > 0 or the recursion bailed before
     * listing the directory at all.
     */
    data class SiblingDownloadOutcome(
        val filesWritten: Int,
        val filesFailed: Int,
        val reason: String?,
    ) {
        val isComplete: Boolean get() = filesFailed == 0 && reason == null
    }

    /**
     * Re-fetch the SKILL.md for an existing URL-sourced skill and update its
     * record in place — does NOT create a new skill. Also re-downloads sibling
     * files from the same GitHub directory so bundled scripts/references stay
     * in sync with upstream. Returns a typed result with an explicit reason on
     * failure so the UI can surface *why* the update failed.
     */
    suspend fun updateFromURL(skillId: String): UpdateResult = withContext(Dispatchers.IO) {
        val existing = _skills.value.find { it.id == skillId }
            ?: return@withContext UpdateResult.Failure("Skill not found")
        if (existing.importSource != ImportSource.URL) {
            return@withContext UpdateResult.Failure("Skill was not imported from a URL")
        }
        val urlString = existing.sourceURL
        if (urlString.isNullOrBlank()) {
            // Older imports (pre-fix) never persisted the source URL. Users need
            // to re-import the skill from Minis Skills so the URL gets saved.
            return@withContext UpdateResult.Failure(
                "No source URL on file. Re-import this skill from Minis Skills to enable updates."
            )
        }

        val rawURL = githubToRawURL(urlString)
            ?: return@withContext UpdateResult.Failure("Could not build a raw download URL from $urlString")
        val content = try { httpGetString(rawURL) } catch (e: Exception) {
            return@withContext UpdateResult.Failure("Network error: ${e.message ?: "unknown"}")
        }
        if (content == null) {
            return@withContext UpdateResult.Failure("Download failed (HTTP error) for $rawURL")
        }
        val parsed = parseSkillMd(content)
            ?: return@withContext UpdateResult.Failure("Downloaded SKILL.md is not valid (missing YAML frontmatter or 'name:' field)")

        // [T-android-skill-install-transaction] The refreshed SKILL.md and every
        // re-downloaded sibling file go into one transaction: an update that fails or is
        // interrupted leaves the version the user already had, instead of the previous
        // behaviour of writing each file into the installed directory as it arrived.
        //
        // The sibling recursion still runs here, not detached: the user invoked this
        // synchronously and is looking at a spinner, and its outcome has to be reported
        // as PartialSuccess (e.g. "GitHub API 403 (likely rate limited)") rather than
        // claiming success while scripts/ stays empty.
        val ghInfo = parseGitHubURL(urlString)
        val siblingFiles = mutableListOf<SkillInstallFile>()
        var siblingOutcome: SiblingDownloadOutcome? = null
        if (ghInfo != null) {
            siblingOutcome = downloadSiblingFiles(ghInfo, siblingFiles)
        }

        val current = _skills.value.find { it.id == skillId }
            ?: return@withContext UpdateResult.Failure("Skill disappeared during update")
        val registration = SkillRegistration(
            name = parsed.name,
            description = parsed.description,
            version = current.version,
            importSource = ImportSource.URL.value,
            sourceUrl = urlString,
            body = parsed.body,
        )
        val files = listOf(
            skillMarkdownFile(
                skillMdContent(parsed.name, parsed.description, current.version, parsed.body),
            ),
        ) + siblingFiles
        val written = commitSkillFiles(
            skillId = skillId,
            registration = registration,
            files = files,
            base = SkillInstallBase.PRESERVE,
        )
        if (written is SkillInstallResult.Failure) {
            logRefusedTransaction("URL update", skillId, written)
            return@withContext UpdateResult.Failure("Failed to write updated skill")
        }

        val fresh = _skills.value.find { it.id == skillId }
            ?: return@withContext UpdateResult.Failure("Skill disappeared during update")
        if (siblingOutcome != null && !siblingOutcome.isComplete) {
            val reason = siblingOutcome.reason
                ?: "${siblingOutcome.filesFailed} sibling file(s) failed to download"
            return@withContext UpdateResult.PartialSuccess(fresh, reason)
        }
        UpdateResult.Success(fresh)
    }

    // -- Export to Zip Archive --

    /**
     * [T-android-skill-export] Zip the whole `/var/minis/skills/<id>/` directory for
     * sharing. Android port of iOS `SkillDetailView.shareSkill`; the round-trip
     * partner of [importFromArchive], which already accepts exactly this shape
     * (SKILL.md at the archive root plus any sibling files).
     *
     * Three safeguards carried over from iOS, each of which fixed a real bug
     * there — do not simplify them away:
     *
     *  1. **Unique per-export directory** (`share/skill-export-<uuid>/`).
     *     Consecutive shares of the same skill must not overwrite or delete a
     *     zip that an earlier share's consumer (Save to Files / Drive / a chat
     *     app) may still be reading.
     *  2. **Non-empty verification** before returning. iOS shipped an "exported
     *     an empty file" bug precisely because a failed copy was swallowed;
     *     here any failure throws or returns null rather than vending a zip
     *     nobody can import.
     *  3. **Stale sweep** of exports older than [EXPORT_TTL_MS], run before each
     *     new export instead of deleting on share-sheet dismissal — by then any
     *     pending consumer of an older export has long finished.
     *
     * Written under `cacheDir/share/` because that is a root already declared
     * in `file_provider_paths.xml`; a file outside a declared root makes
     * `FileProvider.getUriForFile` throw and the user just sees a share failure.
     *
     * @return the zip file, or null when the skill has no directory / no files.
     */
    fun exportSkillToZip(skillId: String): File? {
        val skill = _skills.value.find { it.id == skillId }
        val relPaths = listSkillFiles(skillId)
        if (relPaths.isEmpty()) {
            Log.w(TAG, "exportSkillToZip: $skillId has no files to export")
            return null
        }

        sweepStaleExports()

        val exportDir = File(File(context.cacheDir, "share"), "skill-export-${UUID.randomUUID()}")
        if (!exportDir.mkdirs() && !exportDir.isDirectory) {
            Log.w(TAG, "exportSkillToZip: could not create $exportDir")
            return null
        }
        // Filesystem-safe, single-component zip name (mirrors iOS's sanitizing).
        val safeName = (skill?.name ?: skillId)
            .replace('/', '-')
            .replace(':', '-')
            .replace('\\', '-')
            .trim()
            .ifEmpty { "skill" }
        val zipFile = File(exportDir, "$safeName.zip")

        return try {
            // [T-android-skill-install-transaction] The payload is read under the root
            // lock, so an export cannot be interleaved with the install that is replacing
            // the tree it is zipping; an unfinished recovery refuses the read instead of
            // vending a guessed archive.
            val entries = readUnderSkillMutationLock("export " + skillId) {
                ZipOutputStream(FileOutputStream(zipFile).buffered()).use { zos ->
                    var written = 0
                    for (rel in relPaths) {
                        val bytes = readSkillBytesSuspending(skillId, rel) ?: continue
                        // Store entries with forward slashes — the portable
                        // separator every unzip tool (and importFromArchive) expects.
                        zos.putNextEntry(ZipEntry(rel.replace(File.separatorChar, '/')))
                        zos.write(bytes)
                        zos.closeEntry()
                        written += 1
                    }
                    written
                }
            }
            if (entries == null) {
                Log.w(TAG, "exportSkillToZip: reading $skillId was refused")
                runCatching { zipFile.delete() }
                return null
            }
            // Safeguard 2: never vend an empty/missing archive.
            if (entries == 0 || !zipFile.isFile || zipFile.length() <= 0L) {
                Log.w(TAG, "exportSkillToZip: produced an empty zip for $skillId")
                zipFile.delete()
                return null
            }
            Log.i(TAG, "exportSkillToZip: $skillId -> ${zipFile.name} (${zipFile.length()} bytes, ${relPaths.size} files)")
            zipFile
        } catch (e: Exception) {
            Log.w(TAG, "exportSkillToZip failed for $skillId: ${e.message}")
            runCatching { zipFile.delete() }
            null
        }
    }

    /**
     * Safeguard 3: delete `skill-export-*` directories older than
     * [EXPORT_TTL_MS]. Called before each new export rather than when the share
     * sheet closes, so a consumer still reading a previous export is never
     * pulled out from under.
     */
    private fun sweepStaleExports() {
        val root = File(context.cacheDir, "share")
        val now = System.currentTimeMillis()
        val entries = root.listFiles() ?: return
        for (entry in entries) {
            if (!entry.isDirectory || !entry.name.startsWith("skill-export-")) continue
            if (now - entry.lastModified() < EXPORT_TTL_MS) continue
            runCatching { entry.deleteRecursively() }
                .onFailure { Log.w(TAG, "sweepStaleExports: could not delete ${entry.name}") }
        }
    }

    /**
     * List every file inside `/var/minis/skills/<id>/` (recursive). Returns relative
     * paths from the skill root, with SKILL.md first and the rest sorted by
     * name. Used by the Skill detail UI to enumerate bundled scripts.
     */
    fun listSkillFiles(skillId: String): List<String> {
        if (!isSafeSkillId(skillId)) return emptyList()
        // [T-android-skill-install-transaction] Eta's loader reads the tree under the same
        // lock as the writes, so a listing can never run against a tree whose transaction
        // is still pending (and an unfinished recovery refuses it instead of guessing).
        val files = readUnderSkillMutationLock("list " + skillId) { listSkillGuestFilesLocked(skillId) }
            ?: return emptyList()
        return files.sortedWith(compareBy(
            { if (it.equals("SKILL.md", ignoreCase = true)) 0 else 1 },
            { it },
        ))
    }

    /** Guest path for a file inside a skill's directory. */
    fun skillFileHostPath(skillId: String, relativePath: String): String =
        skillGuestPath(skillId, relativePath)

    /**
     * Re-read SKILL.md from disk and push any frontmatter changes back into the
     * DB + in-memory state. Call after the agent (or user) edits SKILL.md on
     * disk so the store reflects the new name/description/version/body.
     * Returns the refreshed skill or null if the file is gone or malformed.
     */
    fun rescanFromDisk(skillId: String): Skill? {
        return runCatching {
            runBlocking(Dispatchers.IO) { rescanFromDiskLocked(skillId) }
        }.getOrElse { error ->
            Log.w(TAG, "Rescan of $skillId failed: " + error.message)
            null
        }
    }

    /**
     * [T-android-skill-install-transaction] The read and the registry write of a rescan
     * run under the root mutation lock, so a rescan can neither observe a pending
     * transaction nor race one. The orphan branch calls the repository's own delete
     * transaction from inside the lock: the lock is reentrant for this coroutine, so both
     * steps still happen without a gap another writer could interleave.
     */
    private suspend fun rescanFromDiskLocked(skillId: String): Skill? =
        skillPackageInstaller.withSkillMutationLock {
            val current = _skills.value.find { it.id == skillId }
                ?: return@withSkillMutationLock null
            val content = readSkillBytesSuspending(skillId, "SKILL.md")?.toString(Charsets.UTF_8)
            if (content == null) {
                // [T-android-skill-orphan-db-row] A rescan that finds nothing on
                // disk used to only paint "SKILL.md missing or invalid" and leave
                // the record in place, so the one action explicitly meant to
                // reconcile with disk detected the orphan and still refused to act
                // on it. Drop it here too — same BUNDLED exemption as loadAll(),
                // since a bundled skill's file is restored by installBundledSkills.
                if (current.importSource != ImportSource.BUNDLED) {
                    val removal = skillPackageInstaller.delete(skillId)
                    if (removal is SkillInstallResult.Failure) {
                        logRefusedTransaction("orphan rescan delete", skillId, removal)
                    } else {
                        db.execSQL("DELETE FROM session_skill_overrides WHERE skill_id=?", arrayOf(skillId))
                        Log.i(TAG, "Rescan found no SKILL.md — removed orphan skill: $skillId")
                    }
                }
                return@withSkillMutationLock null
            }
            val parsed = parseSkillMd(content) ?: return@withSkillMutationLock null
            val refreshed = current.copy(
                name = parsed.name,
                description = parsed.description,
                version = parsed.version,
                body = parsed.body,
                updatedAt = System.currentTimeMillis(),
            )
            // Eta's registry-only mutations take the same lock and are not journaled:
            // there is no filesystem change for a journal to pair with the row.
            applySkillRegistryRow(refreshed)
            refreshed
        }

    /**
     * Rename a skill in place — updates the `name:` line in frontmatter and DB
     * but keeps the slugged id (and therefore the path) stable so references
     * from existing sessions don't break.
     */
    fun renameSkill(skillId: String, newName: String): Boolean {
        val current = _skills.value.find { it.id == skillId } ?: return false
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return false
        val original = readSkillFile(skillId, "SKILL.md")
        if (original == null) {
            // Nothing to rewrite on disk. The previous code skipped the file write and
            // still refreshed the row; that stays a registry-only mutation under the
            // same lock.
            return runCatching {
                runBlocking(Dispatchers.IO) {
                    skillPackageInstaller.withSkillMutationLock {
                        applySkillRegistryRow(
                            current.copy(name = trimmed, updatedAt = System.currentTimeMillis()),
                        )
                    }
                }
            }.isSuccess
        }
        val updated = original.replaceFirst(Regex("(?m)^name:\\s*.*$"), "name: " + trimmed)
        // [T-android-skill-install-transaction] The renamed frontmatter and the registry
        // row commit together; writing the file in place and updating the row afterwards
        // could leave the two disagreeing whenever either step failed.
        val result = commitSkillFilesBlocking(
            skillId = skillId,
            registration = registrationOf(current).copy(name = trimmed),
            files = listOf(SkillInstallFile("SKILL.md", updated.toByteArray(Charsets.UTF_8))),
            base = SkillInstallBase.PRESERVE,
        )
        return when (result) {
            is SkillInstallResult.Success -> true
            is SkillInstallResult.Failure -> {
                logRefusedTransaction("rename", skillId, result)
                false
            }
        }
    }

    /** Read an arbitrary file inside a skill's directory (e.g. scripts/foo.py). */
    fun readSkillFile(skillId: String, relativePath: String): String? {
        if (!isSafeSkillId(skillId) || !isSafeRelativePath(relativePath)) return null
        // [T-android-skill-install-transaction] Reads take the same root lock as writes:
        // a read never runs against a tree whose transaction is still pending, and an
        // unfinished recovery refuses it instead of showing a guessed state.
        val bytes = readUnderSkillMutationLock("read " + skillId + "/" + relativePath) {
            readSkillBytesSuspending(skillId, relativePath)
        }
        return bytes?.toString(Charsets.UTF_8)
    }

    /** Write an arbitrary file inside a skill's directory. Creates parents. */
    fun writeSkillFile(skillId: String, relativePath: String, content: String): Boolean {
        if (!isSafeSkillId(skillId) || !isSafeRelativePath(relativePath)) return false
        val current = _skills.value.find { it.id == skillId } ?: return false
        // [T-android-skill-install-transaction] A file write into an installed skill is a
        // transaction of its own: the payload is staged below the skills root and
        // committed with an atomic move, so a failed write leaves the previous file. For
        // SKILL.md the parsed frontmatter is registered in the same transaction, which
        // replaces the old write-then-rescan pair.
        val isSkillMd = relativePath.equals("SKILL.md", ignoreCase = true)
        val registration = if (isSkillMd) {
            parseSkillMd(content)
                ?.let { parsed ->
                    registrationOf(current).copy(
                        name = parsed.name,
                        description = parsed.description,
                        version = parsed.version,
                        body = parsed.body,
                    )
                }
                ?: registrationOf(current)
        } else {
            registrationOf(current)
        }
        val result = commitSkillFilesBlocking(
            skillId = skillId,
            registration = registration,
            files = listOf(SkillInstallFile(relativePath, content.toByteArray(Charsets.UTF_8))),
            base = SkillInstallBase.PRESERVE,
        )
        return when (result) {
            is SkillInstallResult.Success -> true
            is SkillInstallResult.Failure -> {
                logRefusedTransaction("write " + relativePath, skillId, result)
                false
            }
        }
    }

    /**
     * Walk the GitHub directory of an imported skill and collect every sibling file.
     *
     * [T-android-skill-install-transaction] The collected files are handed back to the
     * caller, which commits them in one transaction. The download no longer writes into
     * the installed skill as it goes: a recursion that is interrupted now leaves the
     * version the user already had instead of a half-refreshed scripts/ tree.
     */
    private suspend fun downloadSiblingFiles(
        ghInfo: GitHubInfo,
        files: MutableList<SkillInstallFile>,
    ): SiblingDownloadOutcome {
        Log.i(TAG, "[siblings] start dir=${ghInfo.dirPath} repo=${ghInfo.user}/${ghInfo.repo}@${ghInfo.branch}")
        val agg = AggregateOutcome(files)
        downloadGitHubDirectory(
            user = ghInfo.user,
            repo = ghInfo.repo,
            branch = ghInfo.branch,
            remotePath = ghInfo.dirPath,
            relativeTo = "",
            depth = 0,
            outcome = agg,
        )
        val finalReason = agg.firstReason
        Log.i(TAG, "[siblings] done staged=${agg.filesWritten} failed=${agg.filesFailed} reason=${finalReason ?: "(none)"}")
        return SiblingDownloadOutcome(agg.filesWritten, agg.filesFailed, finalReason)
    }

    /**
     * Mutable accumulator threaded through the recursion. Tracks a
     * machine-friendly count plus the FIRST human-readable reason so the UI
     * can show one specific cause (e.g. "GitHub API 403 (rate limited)")
     * rather than a generic "some files failed".
     */
    private class AggregateOutcome(private val files: MutableList<SkillInstallFile>) {
        var filesWritten = 0
        var filesFailed = 0
        var firstReason: String? = null

        private var stagedBytes = 0L

        fun recordFailure(reason: String) {
            filesFailed += 1
            if (firstReason == null) firstReason = reason
        }
        fun recordReason(reason: String) {
            if (firstReason == null) firstReason = reason
        }

        /**
         * Collect one downloaded file for the transaction that follows.
         *
         * The transaction refuses a set above its budgets anyway, but the download has
         * to stop at the same ceilings so it can never buffer an unbounded amount of
         * data first. Returns whether the file was collected.
         */
        fun stage(relativePath: String, bytes: ByteArray): Boolean {
            when {
                files.size >= SkillArchiveReader.MAX_ENTRIES -> recordFailure(
                    "More than ${SkillArchiveReader.MAX_ENTRIES} sibling files, stopping at $relativePath",
                )
                bytes.size.toLong() > SkillArchiveReader.MAX_ENTRY_BYTES -> recordFailure(
                    "$relativePath is larger than ${SkillArchiveReader.MAX_ENTRY_BYTES} bytes",
                )
                stagedBytes + bytes.size > SkillArchiveReader.MAX_TOTAL_BYTES -> recordFailure(
                    "Sibling files exceed ${SkillArchiveReader.MAX_TOTAL_BYTES} bytes in total",
                )
                else -> {
                    stagedBytes += bytes.size
                    files += SkillInstallFile(relativePath, bytes)
                    filesWritten += 1
                    return true
                }
            }
            return false
        }
    }

    private suspend fun downloadGitHubDirectory(
        user: String,
        repo: String,
        branch: String,
        remotePath: String,
        relativeTo: String,
        depth: Int,
        outcome: AggregateOutcome,
    ) {
        if (depth > 5) {
            val msg = "Max recursion depth (5) at $remotePath — stopping"
            Log.w(TAG, "[siblings] $msg")
            outcome.recordReason(msg)
            return
        }
        val encodedPath = URLEncoder.encode(remotePath, "UTF-8").replace("+", "%20").replace("%2F", "/")
        val apiURL = "https://api.github.com/repos/$user/$repo/contents/$encodedPath?ref=$branch"

        // 1-shot retry on transient failure (HTTP 403 rate-limited, network
        // exception). The retry waits 1.5s — enough to clear short-lived
        // OkHttp connection-pool issues, not enough to fix a real rate-limit
        // window (which is per-hour) but cheap enough that it's worth trying.
        val body = fetchContentsWithRetry(apiURL, outcome) ?: return

        val items = try {
            JSONArray(body)
        } catch (e: Exception) {
            // Some endpoints return a JSON object on error (e.g. {"message":...})
            // even with HTTP 200. Capture the first-line snippet for triage.
            val snippet = body.lineSequence().firstOrNull()?.take(160) ?: "(empty)"
            val msg = "GitHub contents API returned non-array JSON for $remotePath: $snippet"
            Log.w(TAG, "[siblings] $msg (${e.javaClass.simpleName}: ${e.message})")
            outcome.recordReason(msg)
            return
        }

        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val name = item.optString("name")
            if (name.isEmpty()) continue
            val type = item.optString("type")
            val relPath = if (relativeTo.isEmpty()) name else "$relativeTo/$name"

            if (type == "dir") {
                val subRemote = if (remotePath.isEmpty()) name else "$remotePath/$name"
                downloadGitHubDirectory(
                    user = user, repo = repo, branch = branch,
                    remotePath = subRemote, relativeTo = relPath,
                    depth = depth + 1, outcome = outcome,
                )
                continue
            }

            if (type != "file") continue
            // Skip SKILL.md at root (already imported)
            if (relativeTo.isEmpty() && name.equals("SKILL.md", ignoreCase = true)) continue
            val downloadURL = item.optString("download_url")
            if (downloadURL.isEmpty()) {
                Log.w(TAG, "[siblings] missing download_url for $relPath — skipping")
                outcome.recordFailure("Missing download_url for $relPath")
                continue
            }

            val fileData = fetchBytesWithRetry(downloadURL)
            if (fileData == null) {
                val msg = "Failed to download $relPath from $downloadURL"
                Log.w(TAG, "[siblings] $msg")
                outcome.recordFailure(msg)
                continue
            }
            if (!isSafeRelativePath(relPath)) {
                outcome.recordFailure("Unsafe skill file path: $relPath")
                continue
            }
            if (outcome.stage(relPath, fileData)) {
                Log.i(TAG, "[siblings] staged $relPath (${fileData.size}B)")
            }
        }
    }

    /**
     * Fetch a GitHub Contents-API URL, retrying once on transient failure
     * (HTTP 403/429/5xx, IOException). Returns the response body string on
     * success, or null after both attempts fail — in which case the failure
     * reason is recorded into [outcome] so the UI can surface it.
     */
    private suspend fun fetchContentsWithRetry(apiURL: String, outcome: AggregateOutcome): String? {
        var lastReason: String? = null
        repeat(2) { attempt ->
            try {
                val req = Request.Builder()
                    .url(apiURL)
                    .header("Accept", "application/vnd.github.v3+json")
                    .get()
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string()
                        if (body != null) return body
                        lastReason = "GitHub contents API returned empty body"
                    } else {
                        val isTransient = resp.code == 403 || resp.code == 429 || resp.code in 500..599
                        // 403 from api.github.com is almost always rate-limit;
                        // call it out specifically so users know waiting helps.
                        val hint = if (resp.code == 403) " (likely anonymous rate limit — wait an hour or sign in)" else ""
                        lastReason = "GitHub contents API HTTP ${resp.code}$hint for $apiURL"
                        if (!isTransient) {
                            Log.w(TAG, "[siblings] non-retryable ${lastReason}")
                            outcome.recordReason(lastReason!!)
                            return null
                        }
                    }
                }
            } catch (e: Exception) {
                lastReason = "GitHub contents API ${e.javaClass.simpleName}: ${e.message ?: "unknown"} for $apiURL"
            }
            if (attempt == 0) {
                Log.w(TAG, "[siblings] retrying after transient failure: $lastReason")
                try { delay(1500) } catch (_: Exception) {}
            }
        }
        Log.w(TAG, "[siblings] both attempts failed: $lastReason")
        outcome.recordReason(lastReason ?: "GitHub contents API unavailable")
        return null
    }

    /** Same retry policy as [fetchContentsWithRetry], for raw file blobs. */
    private suspend fun fetchBytesWithRetry(url: String): ByteArray? {
        repeat(2) { attempt ->
            try {
                val req = Request.Builder().url(url).get().build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) return resp.body?.bytes()
                    val isTransient = resp.code == 403 || resp.code == 429 || resp.code in 500..599
                    if (!isTransient) {
                        Log.w(TAG, "[siblings] HTTP ${resp.code} non-retryable for $url")
                        return null
                    }
                    Log.w(TAG, "[siblings] HTTP ${resp.code} on $url (attempt ${attempt + 1})")
                }
            } catch (e: Exception) {
                Log.w(TAG, "[siblings] ${e.javaClass.simpleName}: ${e.message} for $url (attempt ${attempt + 1})")
            }
            if (attempt == 0) {
                try { delay(1500) } catch (_: Exception) {}
            }
        }
        return null
    }

    /**
     * Parse a GitHub URL into components. Returns null for non-GitHub URLs.
     * Supports:
     *   - github.com/user/repo/blob|tree/branch/path/SKILL.md
     *   - raw.githubusercontent.com/user/repo/branch/path/SKILL.md
     */
    private fun parseGitHubURL(urlString: String): GitHubInfo? {
        val normalized = normalizeURL(urlString)
        val uri = try { Uri.parse(normalized) } catch (_: Exception) { return null }
        val host = uri.host ?: return null
        val parts = uri.pathSegments.filter { it.isNotEmpty() }

        if (host == "raw.githubusercontent.com") {
            // /user/repo/branch/path/to/SKILL.md
            if (parts.size < 3) return null
            val user = parts[0]; val repo = parts[1]; val branch = parts[2]
            val dirParts = parts.drop(3).toMutableList()
            if (dirParts.isNotEmpty() && dirParts.last().equals("SKILL.md", ignoreCase = true)) {
                dirParts.removeAt(dirParts.size - 1)
            }
            return GitHubInfo(user, repo, branch, dirParts.joinToString("/"))
        }

        if (host != "github.com") return null
        // /user/repo/blob|tree/branch/path/...
        if (parts.size < 4) return null
        val user = parts[0]; val repo = parts[1]; val branch = parts[3]
        val dirParts = parts.drop(4).toMutableList()
        if (dirParts.isNotEmpty() && dirParts.last().equals("SKILL.md", ignoreCase = true)) {
            dirParts.removeAt(dirParts.size - 1)
        }
        return GitHubInfo(user, repo, branch, dirParts.joinToString("/"))
    }

    /**
     * Convert a GitHub URL to a raw.githubusercontent.com SKILL.md URL.
     * Returns null if the URL cannot be parsed.
     */
    private fun githubToRawURL(urlString: String): String? {
        val normalized = normalizeURL(urlString)
        val uri = try { Uri.parse(normalized) } catch (_: Exception) { return null }
        val host = uri.host ?: return null
        val parts = uri.pathSegments.filter { it.isNotEmpty() }

        if (host == "raw.githubusercontent.com") {
            return if (parts.lastOrNull()?.equals("SKILL.md", ignoreCase = true) == true) {
                normalized
            } else {
                val base = normalized.trimEnd('/')
                "$base/SKILL.md"
            }
        }

        if (host != "github.com") return null
        if (parts.size < 4) return null
        val user = parts[0]; val repo = parts[1]; val branch = parts[3]
        val pathParts = parts.drop(4).toMutableList()
        var path = pathParts.joinToString("/")
        if (!path.endsWith("SKILL.md", ignoreCase = true)) {
            path = if (path.isEmpty()) "SKILL.md" else "$path/SKILL.md"
        }
        return "https://raw.githubusercontent.com/$user/$repo/$branch/$path"
    }

    private fun normalizeURL(urlString: String): String {
        val trimmed = urlString.trim()
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
        else "https://$trimmed"
    }

    private fun httpGetString(url: String): String? = try {
        val req = Request.Builder().url(url).get().build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()
        }
    } catch (e: Exception) {
        Log.w(TAG, "httpGetString failed $url: ${e.message}")
        null
    }

    private fun httpGetBytes(url: String): ByteArray? = try {
        val req = Request.Builder().url(url).get().build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.bytes()
        }
    } catch (e: Exception) {
        Log.w(TAG, "httpGetBytes failed $url: ${e.message}")
        null
    }


    // -- Bundled Skills --

    private suspend fun installBundledSkills() {
        val bundledId = "skill-creator"
        val bundledVersion = "2.0.0"
        val existing = _skills.value.find { it.id == bundledId }

        // Skip if local version is same or newer
        if (existing != null && existing.version >= bundledVersion) return

        val parsed = parseSkillMd(SKILL_CREATOR_CONTENT) ?: return
        if (existing != null) {
            // [T-android-skill-install-transaction] Upgrading the bundled skill is the
            // same transaction as a user import: the seed's SKILL.md and its registry row
            // commit together, so a failure during the first launch leaves the previous
            // version of both instead of a row that was bumped before its file was
            // written.
            val registration = SkillRegistration(
                name = existing.name,
                description = parsed.description,
                version = bundledVersion,
                importSource = ImportSource.BUNDLED.value,
                sourceUrl = existing.sourceURL,
                body = parsed.body,
            )
            val result = commitSkillFiles(
                skillId = bundledId,
                registration = registration,
                files = listOf(
                    skillMarkdownFile(
                        skillMdContent(existing.name, parsed.description, bundledVersion, parsed.body),
                    ),
                ),
                base = SkillInstallBase.PRESERVE,
            )
            if (result is SkillInstallResult.Failure) {
                logRefusedTransaction("bundled upgrade", bundledId, result)
            } else {
                Log.i(TAG, "Upgraded bundled skill: $bundledId → v$bundledVersion")
            }
        } else {
            // Fresh install
            add(
                name = parsed.name,
                description = parsed.description,
                body = parsed.body,
                source = ImportSource.BUNDLED,
            )
            Log.i(TAG, "Installed bundled skill: $bundledId (v$bundledVersion)")
        }
    }

    // -- Reload --

    /**
     * Re-scan `/var/minis/skills` and the SQLite registry, re-publishing the
     * resulting list via the [skills] StateFlow. Mirrors iOS
     * `SkillStore.reload()` (Agent/Session/SkillStore.swift). Use this after
     * an out-of-band install (agent shell `git clone`, agent file_write of a
     * SKILL.md, on Skills screen entry) so newly dropped directories are
     * promoted from disk into the registry without requiring an app restart.
     *
     * Compat: does not delete or reset enabled state — DB rows are the
     * source of truth for `is_enabled`, and disk-only directories are
     * auto-discovered as ImportSource.SESSION (matching the existing init
     * path), not overwriting any preserved DB toggle.
     */
    fun reloadFromDisk() {
        // Keep the public fire-and-forget API used by Compose and ChatViewModel,
        // but move guest-file I/O off their caller threads. The mutex also
        // prevents a screen-entry rescan from racing the initial load.
        backgroundScope.launch {
            loadMutex.withLock {
                loadAllSafelyLocked("reloadFromDisk")
            }
        }
    }

    // -- Internal --

    private suspend fun loadAllSafelyLocked(reason: String) {
        try {
            withTimeout(SKILL_LOAD_TIMEOUT_MS) {
                // [T-android-skill-install-transaction] Ported from Eta
                // `SkillIndexService.listSkillsForManagement` / `SkillRuntime.withMutationLock`:
                // the scan itself runs under the root mutation lock, so acquiring the lock
                // is what finishes a pending install transaction and restores its registry
                // snapshot BEFORE a single directory is read, pruned or auto-discovered.
                // Previously the recovery held the lock and the scan that followed did not,
                // which left exactly the window this port exists to close. A recovery that
                // cannot finish refuses the scan instead of guessing: the previously loaded
                // list stays visible and the next reload retries.
                try {
                    skillPackageInstaller.withSkillMutationLock { loadAll() }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(
                        TAG,
                        "Skill recovery before $reason did not finish: " + error.message,
                    )
                }
            }
        } catch (error: TimeoutCancellationException) {
            Log.w(
                TAG,
                "loadAll timed out after ${SKILL_LOAD_TIMEOUT_MS}ms during $reason; " +
                    "keeping ${_skills.value.size} currently loaded skill(s)",
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.e(
                TAG,
                "loadAll failed during $reason — continuing with ${_skills.value.size} skill(s): ${error.message}",
                error,
            )
        }
    }

    private suspend fun loadAll() {
        // A guest-file failure must not look like an empty tree. Keep the
        // nullable result so a transient runtime outage cannot prune valid DB
        // rows before the guest runtime becomes ready again.
        val onDisk = listSkillDirectoriesForLoad()

        // Load from DB
        val dbSkills = mutableListOf<Skill>()
        // [T-android-safemode-lateinit-crash-147] `use` so the cursor is closed
        // even when a row throws — the old code only reached close() on the
        // success path and leaked on any failure.
        db.rawQuery("SELECT * FROM skills ORDER BY installed_at DESC", null).use { cursor ->
        while (cursor.moveToNext()) {
            // [T-android-safemode-lateinit-crash-147] Per-row isolation: one
            // malformed skill (a third-party import with a missing column or
            // an unparseable SKILL.md) must not discard every OTHER skill, and
            // must not escape into MinisApp.onCreate. Skip the bad row, keep
            // the rest.
            try {
            val id = cursor.getString(cursor.getColumnIndexOrThrow("id"))
            val importSource = ImportSource.from(cursor.getString(cursor.getColumnIndexOrThrow("import_source")))
            // [T-android-skill-orphan-db-row] Prune rows whose skill directory
            // is gone. The agent deletes skills with `rm -rf` from the shell,
            // which never reaches delete() — so the DB row survived and this
            // merge, which only ever ADDED disk-only skills (the auto-discover
            // pass below), read the orphan straight back into _skills on every
            // load. The entry stayed in the settings list through screen
            // re-entry AND cold start, was still navigable, and — worse than
            // cosmetic — skillPromptFragment() kept advertising it to the model
            // with a dead /var/minis/skills/<id>/SKILL.md path. Mirrors iOS
            // SkillStore.loadSkills(), which does dbDeleteSkill(id:)+continue
            // when SKILL.md is missing.
            //
            // BUNDLED is exempt on purpose: init{} runs loadAll() BEFORE
            // installBundledSkills(), so on the first load after a fresh
            // install the skill-creator row legitimately exists with nothing on
            // disk yet. Pruning it here would discard its use_count moments
            // before the installer re-materializes the file.
            //
             // Only one location to check, unlike iOS's Library+rootfs pair:
             // the App-owned guest file API owns the canonical `/var/minis/skills` view.
             if (importSource != ImportSource.BUNDLED &&
                 onDisk != null &&
                 (id !in onDisk || readSkillFileForLoad(id, "SKILL.md") == null)
             ) {
                db.execSQL("DELETE FROM skills WHERE id=?", arrayOf(id))
                db.execSQL("DELETE FROM session_skill_overrides WHERE skill_id=?", arrayOf(id))
                Log.i(TAG, "Pruned orphan skill row (no SKILL.md on disk): $id")
                continue
            }
            // When guest file access is unavailable, keep the DB metadata instead of
            // issuing one more blocking-looking read per row. The next bounded
            // rescan will fill the body once the runtime is healthy again.
            val body = if (onDisk == null) "" else readSkillMdBodyForLoad(id)
            val sourceUrlIdx = cursor.getColumnIndex("source_url")
            val useCountIdx = cursor.getColumnIndex("use_count")
            var description = cursor.getString(cursor.getColumnIndexOrThrow("description"))
            var name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
            var version = cursor.getString(cursor.getColumnIndexOrThrow("version"))
            // Self-heal rows persisted by an earlier parser that treated YAML
            // block scalars (`|` and `>`) as literal one-character description
            // values. Re-parse the on-disk SKILL.md whenever the stored
            // description is stale and update the row in place so the skills
            // list shows the real frontmatter description.
            //
            // [T-android-skill-empty-description-never-refreshes] GH#215
            // (iOS 7d0cc49a7): an EMPTY description is stale too, not just the
            // literal "|" / ">" leftovers the original check knew about.
            //
            // How a skill gets stuck: the agent writes SKILL.md from the shell,
            // and the skill can be registered while that file is still being
            // written and has no `description:` line yet. "" is persisted, and
            // because the stale test only matched "|" / ">", every later load
            // trusted that "" forever. Since the DB row is keyed by directory
            // name it survives delete-and-recreate, so touching the file,
            // rewriting the frontmatter and even `rm -rf` + recreate all fail
            // to heal it — only renaming the skill works, because that mints a
            // new row id.
            if (description == ">" || description == "|" || description.isBlank()) {
                val skillMd = if (onDisk == null) null else readSkillFileForLoad(id, "SKILL.md")
                if (skillMd != null) {
                    val reparsed = parseSkillMd(skillMd)
                    // Only ever REPLACE an empty/placeholder value with a real
                    // one, never the reverse. A mid-edit or malformed SKILL.md
                    // parses to null or yields an empty description, and
                    // writing that back would wipe good metadata — the exact
                    // hazard that kept this check narrow in the first place.
                    // Combined with the guard above (the stored value must be
                    // blank or a block-scalar leftover to get here), a
                    // description the user deliberately set can never be
                    // overwritten.
                    if (reparsed != null && reparsed.description.isNotBlank()) {
                        description = reparsed.description
                        if (name.isBlank()) name = reparsed.name
                        if (version.isBlank()) version = reparsed.version
                        db.execSQL(
                            "UPDATE skills SET name=?, description=?, version=?, updated_at=? WHERE id=?",
                            arrayOf<Any>(name, description, version, System.currentTimeMillis(), id),
                        )
                        Log.i(TAG, "Self-healed skill description for $id")
                    }
                }
            }
            dbSkills.add(Skill(
                id = id,
                name = name,
                description = description,
                version = version,
                importSource = importSource,
                isEnabled = cursor.getInt(cursor.getColumnIndexOrThrow("is_enabled")) == 1,
                installedAt = cursor.getLong(cursor.getColumnIndexOrThrow("installed_at")),
                updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
                body = body,
                sourceURL = if (sourceUrlIdx >= 0 && !cursor.isNull(sourceUrlIdx)) cursor.getString(sourceUrlIdx) else null,
                useCount = if (useCountIdx >= 0) cursor.getDouble(useCountIdx) else 0.0,
            ))
            } catch (t: Throwable) {
                Log.e(TAG, "skipping unreadable skill row: ${t.message}", t)
            }
        }
        }

        // Auto-discover skills in the canonical guest tree without DB entries.
        for (id in onDisk.orEmpty()) {
            val skillMd = readSkillFileForLoad(id, "SKILL.md")
            if (skillMd != null && dbSkills.none { it.id == id }) {
                val parsed = parseSkillMd(skillMd)
                if (parsed != null) {
                    val skill = Skill(
                        id = id,
                        name = parsed.name,
                        description = parsed.description,
                        importSource = ImportSource.SESSION,
                        body = parsed.body,
                    )
                    insertDb(skill)
                    dbSkills.add(skill)
                    Log.i(TAG, "Auto-discovered skill: $id")
                }
            }
        }

        _skills.value = dbSkills
    }

    private fun insertDb(skill: Skill) {
        db.execSQL(
            """INSERT OR REPLACE INTO skills (id, name, description, version, import_source, source_url, is_enabled, installed_at, updated_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            arrayOf<Any?>(
                skill.id, skill.name, skill.description, skill.version,
                skill.importSource.value, skill.sourceURL,
                if (skill.isEnabled) 1 else 0,
                skill.installedAt, skill.updatedAt,
            )
        )
    }

    /**
     * [T-android-skill-install-transaction] The registry row as the recovery
     * journal stores it. Ported from Eta `SkillRegistryRecoverySnapshot`:
     * captured before the first move, restored verbatim when the transaction has
     * to roll back, and never re-derived from the tree (the tree is exactly what
     * is in doubt while a transaction is unfinished).
     */
    private fun readSkillRowSnapshot(skillId: String): String? = db.rawQuery(
        "SELECT name, description, version, import_source, source_url, is_enabled, " +
            "installed_at, updated_at, use_count FROM skills WHERE id=?",
        arrayOf(skillId),
    ).use { cursor ->
        if (!cursor.moveToFirst()) return null
        JSONObject()
            .put("name", cursor.getString(0))
            .put("description", cursor.getString(1))
            .put("version", cursor.getString(2))
            .put("import_source", cursor.getString(3))
            .put("source_url", if (cursor.isNull(4)) JSONObject.NULL else cursor.getString(4))
            .put("is_enabled", cursor.getInt(5))
            .put("installed_at", cursor.getLong(6))
            .put("updated_at", cursor.getLong(7))
            .put("use_count", cursor.getDouble(8))
            .toString()
    }

    /**
     * Eta `registerInstalledUserSkills`: runs once the files are committed.
     * The id is a new row for a fresh install and an updated row for a replace,
     * which keeps the toggles, counters and install time the user already had.
     */
    private fun writeRegisteredSkill(skillId: String, registration: SkillRegistration) {
        val current = _skills.value.find { it.id == skillId }
        val now = System.currentTimeMillis()
        val updated = Skill(
            id = skillId,
            name = registration.name,
            description = registration.description,
            version = registration.version,
            importSource = ImportSource.from(registration.importSource),
            isEnabled = current?.isEnabled ?: true,
            installedAt = current?.installedAt ?: now,
            updatedAt = now,
            body = registration.body,
            sourceURL = registration.sourceUrl ?: current?.sourceURL,
            useCount = current?.useCount ?: 0.0,
        )
        db.execSQL(
            """INSERT OR REPLACE INTO skills (id, name, description, version, import_source, source_url, is_enabled, installed_at, updated_at, use_count)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            arrayOf<Any?>(
                updated.id, updated.name, updated.description, updated.version,
                updated.importSource.value, updated.sourceURL,
                if (updated.isEnabled) 1 else 0,
                updated.installedAt, updated.updatedAt, updated.useCount,
            )
        )
        val loaded = _skills.value
        _skills.value = if (loaded.any { it.id == skillId }) {
            loaded.map { if (it.id == skillId) updated else it }
        } else {
            loaded + updated
        }
    }

    /**
     * Eta `restoreRecoveredRegistry` for one record: put the captured row back.
     * The body is re-read from the SKILL.md that recovery just moved into place,
     * which keeps the journal small and keeps the in-memory entry usable without
     * waiting for the next full load.
     */
    private fun restoreSkillRow(skillId: String, payload: String) {
        val json = JSONObject(payload)
        val restored = Skill(
            id = skillId,
            name = json.getString("name"),
            description = json.getString("description"),
            version = json.getString("version"),
            importSource = ImportSource.from(json.getString("import_source")),
            isEnabled = json.optInt("is_enabled", 1) == 1,
            installedAt = json.optLong("installed_at", System.currentTimeMillis()),
            updatedAt = json.optLong("updated_at", System.currentTimeMillis()),
            body = readSkillBodyBlocking(skillId),
            sourceURL = if (json.isNull("source_url")) null else json.getString("source_url"),
            useCount = json.optDouble("use_count", 0.0),
        )
        insertDb(restored)
        val loaded = _skills.value
        _skills.value = if (loaded.any { it.id == skillId }) {
            loaded.map { if (it.id == skillId) restored else it }
        } else {
            loaded + restored
        }
    }

    /** Eta removes the registry entry when the interrupted install was new. */
    private fun removeSkillRow(skillId: String) {
        db.execSQL("DELETE FROM skills WHERE id=?", arrayOf(skillId))
        _skills.value = _skills.value.filter { it.id != skillId }
    }

    // -- Skill write transactions --

    /**
     * [T-android-skill-install-transaction] One transactional write of a skill payload
     * plus its registry row, ported from Eta `SkillPackageInstaller.installCandidates`
     * (commit c15de97). The payload is staged below the skills root, the previous version
     * is moved into the transaction backup, and the registry row is written last, so an
     * interrupted write leaves either the complete old version or the complete new one.
     */
    private suspend fun commitSkillFiles(
        skillId: String,
        registration: SkillRegistration,
        files: List<SkillInstallFile>,
        base: SkillInstallBase,
    ): SkillInstallResult = try {
        skillPackageInstaller.install(
            candidate = SkillInstallCandidate(skillId, registration),
            files = files,
            base = base,
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        SkillInstallResult.Failure(
            SkillInstallError(
                SkillInstallErrorCode.IO_ERROR,
                error.message ?: "the skill transaction failed",
            ),
        )
    }

    /** Blocking entry for the synchronous repository API (UI and RPC callers). */
    private fun commitSkillFilesBlocking(
        skillId: String,
        registration: SkillRegistration,
        files: List<SkillInstallFile>,
        base: SkillInstallBase,
    ): SkillInstallResult = runBlocking(Dispatchers.IO) {
        commitSkillFiles(skillId, registration, files, base)
    }

    /**
     * [T-android-skill-install-transaction] Reads that walk the skills tree take the
     * same root lock the writes take, so they cannot run against a tree whose
     * transaction is still pending. A refused read returns null and the caller keeps its
     * previous state instead of showing a guessed one.
     */
    private fun <T> readUnderSkillMutationLock(operation: String, block: suspend () -> T): T? = try {
        runBlocking(Dispatchers.IO) { skillPackageInstaller.withSkillMutationLock(block) }
    } catch (error: Exception) {
        Log.w(
            TAG,
            "Refused skill read (" + operation + "): " + (error.message ?: error.javaClass.simpleName),
        )
        null
    }

    private fun registrationOf(skill: Skill): SkillRegistration = SkillRegistration(
        name = skill.name,
        description = skill.description,
        version = skill.version,
        importSource = skill.importSource.value,
        sourceUrl = skill.sourceURL,
        body = skill.body,
    )

    private fun skillMarkdownFile(markdown: String): SkillInstallFile =
        SkillInstallFile("SKILL.md", markdown.toByteArray(Charsets.UTF_8))

    /** Suspending twin of [readSkillBytes], for callers inside the transaction lock. */
    private suspend fun readSkillBytesSuspending(skillId: String, relativePath: String): ByteArray? {
        if (!isSafeSkillId(skillId) || !isSafeRelativePath(relativePath)) return null
        return try {
            WorkspaceFileClient.readAll("", skillGuestPath(skillId, relativePath))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to read guest skill file $skillId/$relativePath: ${error.message}")
            null
        }
    }

    /**
     * Eta's registry-only mutations are not journaled — there is no filesystem change for
     * a journal to pair with the row — but they run under the same root lock.
     */
    private fun applySkillRegistryRow(skill: Skill) {
        db.execSQL(
            "UPDATE skills SET name=?, description=?, version=?, updated_at=? WHERE id=?",
            arrayOf<Any>(skill.name, skill.description, skill.version, skill.updatedAt, skill.id),
        )
        _skills.value = _skills.value.map { if (it.id == skill.id) skill else it }
    }

    private fun logRefusedTransaction(
        operation: String,
        skillId: String,
        failure: SkillInstallResult.Failure,
    ) {
        Log.w(
            TAG,
            "Refused skill " + operation + " for " + skillId + ": " + failure.error.code + " " +
                failure.error.message + " (recoveryRequired=" + failure.recoveryRequired + ")",
        )
    }

    private fun readSkillBodyBlocking(skillId: String): String {
        val bytes = readSkillBytes(skillId, "SKILL.md") ?: return ""
        return parseSkillMd(String(bytes, Charsets.UTF_8))?.body ?: ""
    }

    /**
     * [T-android-skill-install-transaction] Canonical SKILL.md text. Shared by
     * the direct write and by the staged archive import, so both produce the
     * same bytes and the transaction does not change what lands on disk.
     */
    private fun skillMdContent(
        name: String,
        description: String,
        version: String,
        body: String,
    ): String = buildString {
        appendLine("---")
        appendLine("name: " + name)
        appendLine("description: " + description)
        appendLine("version: " + version)
        appendLine("---")
        append(body)
    }

    private suspend fun readSkillMdBodyForLoad(id: String): String {
        val parsed = readSkillFileForLoad(id, "SKILL.md")?.let(::parseSkillMd)
        return parsed?.body ?: ""
    }

    /**
     * Suspending variant used exclusively by [loadAll]. Keeping the startup
     * path free of `runBlocking` is what makes an unavailable guest-file backend unable to
     * trigger an Android application-start ANR.
     */
    private suspend fun listSkillDirectoriesForLoad(): List<String>? = try {
        WorkspaceFileClient.listAll("", SKILLS_ROOT)
            .filter { it.optString("type") == "dir" }
            .mapNotNull { entry ->
                val id = entry.optString("name")
                // [T-android-skill-install-transaction] The reserved install
                // work directory lives inside the skills root, so it must never
                // be loaded or reported as a skill.
                id.takeIf { it != SkillInstallLayout.WORK_DIRECTORY && isSafeSkillId(it) }
            }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Log.w(TAG, "Failed to list guest skill directories: ${error.message}")
        null
    }

    private suspend fun readSkillFileForLoad(skillId: String, relativePath: String): String? {
        if (!isSafeSkillId(skillId) || !isSafeRelativePath(relativePath)) return null
        return try {
            WorkspaceFileClient.readAll("", skillGuestPath(skillId, relativePath))
                .toString(Charsets.UTF_8)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to read guest skill file $skillId/$relativePath: ${error.message}")
            null
        }
    }

    /**
     * Recursive listing of one installed skill. Callers hold the root mutation lock;
     * this is the unlocked half of [listSkillFiles].
     */
    private suspend fun listSkillGuestFilesLocked(skillId: String): List<String> = runCatching {
        val found = mutableListOf<String>()
        val queue = ArrayDeque<Pair<String, String>>()
        queue.add(skillGuestPath(skillId) to "")
        while (queue.isNotEmpty()) {
            val (directory, relativeDirectory) = queue.removeFirst()
            for (entry in WorkspaceFileClient.listAll("", directory)) {
                val name = entry.optString("name")
                if (!isSafeRelativePath(name) || name.startsWith(".")) continue
                val relative = if (relativeDirectory.isEmpty()) name else "$relativeDirectory/$name"
                when (entry.optString("type")) {
                    "file" -> found += relative
                    "dir" -> queue.add(childGuestPath(directory, name) to relative)
                }
            }
        }
        found
    }.getOrElse { error ->
        Log.w(TAG, "Failed to list guest files for skill $skillId: ${error.message}")
        emptyList()
    }

    private fun readSkillBytes(skillId: String, relativePath: String): ByteArray? {
        if (!isSafeSkillId(skillId) || !isSafeRelativePath(relativePath)) return null
        return runCatching {
            WorkspaceFileClient.readAllBlocking("", skillGuestPath(skillId, relativePath))
        }.getOrNull()
    }

    private fun childGuestPath(directory: String, name: String): String {
        require(isSafeRelativePath(name)) { "invalid skill directory entry" }
        return "${directory.trimEnd('/')}/$name"
    }

    private fun skillGuestPath(skillId: String, relativePath: String = ""): String {
        require(isSafeSkillId(skillId)) { "invalid skill id" }
        require(relativePath.isEmpty() || isSafeRelativePath(relativePath)) {
            "invalid skill file path"
        }
        return if (relativePath.isEmpty()) "$SKILLS_ROOT/$skillId"
        else "$SKILLS_ROOT/$skillId/$relativePath"
    }

    private fun isSafeSkillId(id: String): Boolean =
        id.isNotEmpty() && id != "." && id != ".." && id.all {
            it.code < 128 && (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.')
        }

    private fun isSafeRelativePath(path: String): Boolean =
        path.isNotEmpty() && path.split('/').all { component ->
            component.isNotEmpty() && component != "." && component != ".." &&
                !component.contains('\\') && !component.contains('\u0000')
        }

    data class ParsedSkill(
        val name: String,
        val description: String,
        val version: String = "1.0.0",
        val body: String,
    )

    /**
     * Parse a SKILL.md file into [ParsedSkill]. Mirrors iOS SkillStore.parse(skillMD:).
     *
     * Recognized YAML frontmatter forms (delimited by `---` … `---`):
     *
     *   description: short text
     *   description: |
     *     line one
     *     line two           ← preserved with newlines
     *   description: >
     *     line one
     *     line two           ← folded into a single space-joined line
     *
     * The block-scalar variants (`|` and `>`) matter in practice — many
     * authored SKILL.md files (e.g. hyperframes, bilibili-hub) use
     * `description: >` to either fold a multi-line description or as a
     * placeholder with an empty body. The previous parser stored the
     * literal string `">"` for those skills, which is what surfaced in the
     * skills list as a confusing single-character description.
     *
     * Returns null only if the document doesn't start with `---` or if
     * the closing `---` is missing — caller treats null as "not a SKILL.md".
     * If frontmatter parses but `name` is blank we also return null so
     * import paths can fall back (e.g. derive name from the GitHub URL).
     */
    /**
     * Public re-exposure for the "Update from File" UI. Same parser, same
     * `null = invalid SKILL.md` contract. Lives on the instance for symmetry
     * with the rest of the API surface, even though the parse itself is
     * stateless.
     */
    fun parseSkillMdPublic(content: String): ParsedSkill? = parseSkillMd(content)

    private fun parseSkillMd(content: String): ParsedSkill? {
        val trimmed = content.trimStart()
        if (!trimmed.startsWith("---")) return null

        // Find the closing `---` on a line by itself (matches iOS — and avoids
        // false matches against horizontal rules later in the document).
        val lines = trimmed.lines()
        var frontmatterEndLine = -1
        for (i in 1 until lines.size) {
            if (lines[i].trim() == "---") {
                frontmatterEndLine = i
                break
            }
        }
        if (frontmatterEndLine < 0) return null

        var name = ""
        var description = ""
        var version = "1.0.0"

        var i = 1
        while (i < frontmatterEndLine) {
            val line = lines[i]
            val colonIdx = line.indexOf(':')
            if (colonIdx < 0) { i++; continue }
            val key = line.substring(0, colonIdx).trim().lowercase()
            val rawValue = line.substring(colonIdx + 1).trim()

            // YAML block scalar: `|` keeps newlines, `>` folds them into spaces.
            // Body lines continue while they are indented (or empty); a
            // dedented line ends the block.
            //
            // T151: also accept the YAML chomping indicators `>-`, `>+`, `|-`,
            // `|+` (and any explicit indentation digit suffix). Real-world
            // skill frontmatter — qbt-hub, for one — opens its description
            // with `description: >-` so trailing newlines are stripped, but
            // the previous strict `==` match treated that as a plain string
            // literal, leaving the renderer to display the raw `>-` marker.
            val isBlockScalar = rawValue.startsWith("|") || rawValue.startsWith(">")
            val resolved: String
            if (isBlockScalar && i + 1 < frontmatterEndLine) {
                val fold = rawValue.startsWith(">")
                val blockLines = mutableListOf<String>()
                var j = i + 1
                while (j < frontmatterEndLine) {
                    val next = lines[j]
                    if (next.isEmpty() || next[0].isWhitespace()) {
                        blockLines.add(next.trim())
                    } else break
                    j++
                }
                resolved = if (fold) {
                    blockLines.joinToString(" ").trim()
                } else {
                    blockLines.joinToString("\n").trim('\n')
                }
                i = j
            } else {
                resolved = rawValue
                i++
            }

            when (key) {
                "name" -> name = resolved
                "description" -> description = resolved
                "version" -> version = resolved
            }
        }

        if (name.isBlank()) return null

        val bodyStartLine = frontmatterEndLine + 1
        val body = if (bodyStartLine < lines.size) {
            lines.subList(bodyStartLine, lines.size).joinToString("\n").trim('\n')
        } else ""

        return ParsedSkill(name, description, version, body)
    }

    private fun slugify(name: String): String =
        name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')

    private fun escapeXml(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    // -- Database Helper --

    private class SkillDbHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE skills (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    description TEXT NOT NULL DEFAULT '',
                    version TEXT NOT NULL DEFAULT '1.0.0',
                    import_source TEXT NOT NULL DEFAULT 'file',
                    source_url TEXT,
                    is_enabled INTEGER NOT NULL DEFAULT 1,
                    installed_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    use_count REAL NOT NULL DEFAULT 0
                )
            """)
            db.execSQL("""
                CREATE TABLE session_skill_overrides (
                    session_id TEXT NOT NULL,
                    skill_id TEXT NOT NULL,
                    is_enabled INTEGER NOT NULL,
                    PRIMARY KEY (session_id, skill_id)
                )
            """)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                try {
                    db.execSQL("ALTER TABLE skills ADD COLUMN source_url TEXT")
                } catch (_: Exception) { /* column may already exist */ }
            }
            if (oldVersion < 3) {
                try {
                    db.execSQL("ALTER TABLE skills ADD COLUMN use_count REAL NOT NULL DEFAULT 0")
                } catch (_: Exception) { /* column may already exist */ }
            }
        }
    }
}

// Bundled skill-creator content (matches iOS SkillStore.skillCreatorContent)
private val SKILL_CREATOR_CONTENT = """
---
name: skill-creator
version: 2.0.0
description: Guide for creating effective skills. This skill should be used when users want to create a new skill (or update an existing skill) that extends Claude's capabilities with specialized knowledge, workflows, or tool integrations.
---

# Skill Creator

This skill provides guidance for creating effective skills.

## About Skills

Skills are modular, self-contained packages that extend Claude's capabilities by providing
specialized knowledge, workflows, and tools. Think of them as "onboarding guides" for specific
domains or tasks—they transform Claude from a general-purpose agent into a specialized agent
equipped with procedural knowledge that no model can fully possess.

### What Skills Provide

1. Specialized workflows - Multi-step procedures for specific domains
2. Tool integrations - Instructions for working with specific file formats or APIs
3. Domain expertise - Company-specific knowledge, schemas, business logic
4. Bundled resources - Scripts, references, and assets for complex and repetitive tasks

## Core Principles

### Concise is Key

The context window is a public good. Skills share the context window with everything else Claude needs: system prompt, conversation history, other Skills' metadata, and the actual user request.

**Default assumption: Claude is already very smart.** Only add context Claude doesn't already have. Challenge each piece of information: "Does Claude really need this explanation?" and "Does this paragraph justify its token cost?"

Prefer concise examples over verbose explanations.

### Set Appropriate Degrees of Freedom

Match the level of specificity to the task's fragility and variability:

- **High freedom (text-based instructions)**: Use when multiple approaches are valid.
- **Medium freedom (pseudocode or scripts with parameters)**: Use when a preferred pattern exists.
- **Low freedom (specific scripts, few parameters)**: Use when operations are fragile, consistency is critical, or a specific sequence must be followed.

### Anatomy of a Skill

Every skill consists of a required SKILL.md file and optional bundled resources:

```
skill-name/
├── SKILL.md (required)
│   ├── YAML frontmatter (name + description required)
│   └── Markdown instructions
└── Bundled Resources (optional)
    ├── scripts/       - Executable code
    ├── references/    - Documentation loaded as needed
    └── assets/        - Files used in output (templates, icons, etc.)
```

#### SKILL.md Frontmatter

- `name` (required): The skill name
- `description` (required): What the skill does and when to trigger it. Be comprehensive—this is the primary triggering mechanism.

#### SKILL.md Body

Instructions and guidance, loaded after the skill triggers. Keep under 500 lines; split into reference files when approaching this limit.

### Progressive Disclosure

Skills use three loading levels:
1. **Metadata** - Always in context (~100 words)
2. **SKILL.md body** - When skill triggers (<5k words)
3. **Bundled resources** - As needed (unlimited)

## Skill Creation Process

1. **Understand** the skill with concrete examples from the user
2. **Plan** reusable contents (scripts, references, assets)
3. **Create** the SKILL.md with proper frontmatter and instructions
4. **Test** by using the skill on real tasks
5. **Iterate** based on actual usage

### Writing the SKILL.md

- Use imperative/infinitive form
- `description` field should include all "when to use" triggers (body is loaded after triggering)
- Only add context Claude doesn't already have
- Prefer concise examples over verbose explanations
- Keep essential workflow in SKILL.md; move detailed reference material to separate files

### What NOT to Include

Do not create extraneous files: README.md, INSTALLATION_GUIDE.md, CHANGELOG.md, etc. The skill should only contain what an AI agent needs to do the job.
""".trimIndent()
