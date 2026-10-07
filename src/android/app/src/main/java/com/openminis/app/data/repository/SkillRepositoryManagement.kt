package com.openminis.app.data.repository

import android.util.Log
import com.openminis.app.data.repository.SkillRepository.AggregateOutcome
import com.openminis.app.data.repository.SkillRepository.BackupSkillFile
import com.openminis.app.data.repository.SkillRepository.BackupSkillRestore
import com.openminis.app.data.repository.SkillRepository.Companion.EXPORT_TTL_MS
import com.openminis.app.data.repository.SkillRepository.Companion.MAX_SKILLS_IN_PROMPT
import com.openminis.app.data.repository.SkillRepository.Companion.MAX_SKILL_DESC_LENGTH
import com.openminis.app.data.repository.SkillRepository.Companion.NORMALIZE_THRESHOLD
import com.openminis.app.data.repository.SkillRepository.Companion.RECENT_SLOTS
import com.openminis.app.data.repository.SkillRepository.Companion.RECENT_WINDOW_MS
import com.openminis.app.data.repository.SkillRepository.Companion.TAG
import com.openminis.app.data.repository.SkillRepository.GitHubInfo
import com.openminis.app.data.repository.SkillRepository.GitHubInspection
import com.openminis.app.data.repository.SkillRepository.ImportSource
import com.openminis.app.data.repository.SkillRepository.SiblingDownloadOutcome
import com.openminis.app.data.repository.SkillRepository.Skill
import com.openminis.app.data.repository.SkillRepository.SkillInstallOutcome
import com.openminis.app.data.repository.SkillRepository.UpdateResult
import com.openminis.app.data.repository.SkillRepository.UsageFrequency
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URLEncoder
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// -- CRUD --

fun SkillRepository.add(name: String, description: String, body: String, version: String = "1.0.0", source: ImportSource = ImportSource.FILE, sourceURL: String? = null): Skill? {
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

fun SkillRepository.update(id: String, name: String? = null, description: String? = null, body: String? = null): Boolean {
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

fun SkillRepository.delete(id: String) {
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

fun SkillRepository.setEnabled(id: String, enabled: Boolean) {
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

fun SkillRepository.isEnabledForSession(skillId: String, sessionId: String): Boolean {
    val cursor = db.rawQuery(
        "SELECT is_enabled FROM session_skill_overrides WHERE session_id=? AND skill_id=?",
        arrayOf(sessionId, skillId)
    )
    val override = if (cursor.moveToFirst()) cursor.getInt(0) == 1 else null
    cursor.close()
    if (override != null) return override
    return _skills.value.find { it.id == skillId }?.isEnabled ?: false
}

fun SkillRepository.setSessionOverride(sessionId: String, skillId: String, enabled: Boolean) {
    db.execSQL(
        "INSERT OR REPLACE INTO session_skill_overrides (session_id, skill_id, is_enabled) VALUES (?, ?, ?)",
        arrayOf<Any>(sessionId, skillId, if (enabled) 1 else 0)
    )
}

fun SkillRepository.clearSessionOverrides(sessionId: String) {
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
fun SkillRepository.renameSessionOverrides(fromDraft: String, toReal: String) {
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
fun SkillRepository.skillPromptFragment(sessionId: String): String? {
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
fun SkillRepository.recordSkillUse(skillId: String) {
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

internal fun SkillRepository.normalizeUseCounts(list: List<Skill>): List<Skill> {
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
fun SkillRepository.usageFrequency(skillId: String): UsageFrequency {
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
fun SkillRepository.skillIdFromPath(path: String): String? {
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
fun SkillRepository.importFromContent(content: String, source: ImportSource = ImportSource.FILE, sourceURL: String? = null): Skill? {
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
fun SkillRepository.skillMdPath(id: String): String = "/var/minis/skills/$id/SKILL.md"

// -- Import from Zip Archive --

/**
 * Import a skill from a .zip archive (mirrors iOS `SkillStore.importFromArchive`).
 * The archive must contain a `SKILL.md` at the root or one directory deep;
 * bundled sibling files (scripts/, references/, assets/, etc.) are extracted
 * alongside it into `/var/minis/skills/<id>/`.
 */
fun SkillRepository.importFromArchive(input: InputStream): Skill? {
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
suspend fun SkillRepository.restoreFromBackup(skillId: String, files: List<BackupSkillFile>): BackupSkillRestore {
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

/**
 * Download SKILL.md from a GitHub URL and recursively download sibling files
 * from the same directory. Mirrors iOS SkillStore.importFromGitHub.
 */
suspend fun SkillRepository.importFromGitHub(urlString: String): Skill? = withContext(Dispatchers.IO) {
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

// -- Public GitHub skill sources [T-eta-skill-tools] -------------------------

/**
 * The skill id a SKILL.md would install as, without writing anything — used to
 * detect a conflict before an install starts.
 */
fun SkillRepository.candidateSkillId(content: String): String? =
    parseSkillMd(content)?.name?.let { slugify(it) }?.takeIf { it.isNotBlank() }

/**
 * List the directories that contain a SKILL.md in a public repository, pinned to the
 * commit the ref resolved to. Read-only: nothing is downloaded into the skills tree,
 * and the returned sha is what an install should be pinned to.
 */
suspend fun SkillRepository.inspectGitHub(repository: String, ref: String?, path: String?): GitHubInspection =
    withContext(Dispatchers.IO) {
        val parsed = com.openminis.app.tools.skills.SkillSourcePolicy.parseRepository(repository)
            ?: return@withContext GitHubInspection(
                null, emptyList(), false,
                "repository must be owner/repo or a github.com URL",
            )
        val targetRef = ref?.trim()?.takeIf { it.isNotEmpty() } ?: parsed.ref ?: "HEAD"
        val commitSha = resolveCommitSha(parsed.owner, parsed.repo, targetRef)
            ?: return@withContext GitHubInspection(
                null, emptyList(), false,
                "GitHub returned no commit for ${parsed.owner}/${parsed.repo}@$targetRef " +
                    "(private repository, unknown ref, or API rate limit)",
            )
        val treeJson = httpGetString(
            com.openminis.app.tools.skills.SkillSourcePolicy.treeApiUrl(parsed.owner, parsed.repo, commitSha),
        ) ?: return@withContext GitHubInspection(
            commitSha, emptyList(), false,
            "GitHub returned no file tree for ${parsed.owner}/${parsed.repo}@$commitSha",
        )
        val candidates = com.openminis.app.tools.skills.SkillSourcePolicy.skillDirectories(
            treeJson,
            path ?: parsed.path,
        )
        GitHubInspection(commitSha, candidates.paths, candidates.truncated, null)
    }

/**
 * Install one skill directory from a public repository. The SKILL.md and its sibling
 * files are fetched at the SAME commit, so a branch that moves between inspection and
 * install cannot mix two versions. An existing id is never overwritten: the caller
 * gets the conflicting id back and updates it through the Skills screen instead.
 */
suspend fun SkillRepository.installFromGitHub(
    repository: String,
    ref: String?,
    path: String?,
): SkillInstallOutcome = withContext(Dispatchers.IO) {
    val policy = com.openminis.app.tools.skills.SkillSourcePolicy
    val parsed = policy.parseRepository(repository)
        ?: return@withContext SkillInstallOutcome.Failure(
            "repository must be owner/repo or a github.com URL",
        )
    val directory = path?.trim('/')?.takeIf { it.isNotEmpty() } ?: parsed.path
    if (directory != null && policy.safeRelativeDirectory(directory) == null) {
        return@withContext SkillInstallOutcome.Failure(
            "path must be a repository-relative directory without '..' or backslashes",
        )
    }
    val targetRef = ref?.trim()?.takeIf { it.isNotEmpty() } ?: parsed.ref ?: "HEAD"
    val commitSha = resolveCommitSha(parsed.owner, parsed.repo, targetRef)
        ?: return@withContext SkillInstallOutcome.Failure(
            "GitHub returned no commit for ${parsed.owner}/${parsed.repo}@$targetRef",
        )
    val markdown = httpGetString(policy.rawSkillMdUrl(parsed.owner, parsed.repo, commitSha, directory))
        ?: return@withContext SkillInstallOutcome.Failure(
            "SKILL.md was not found at ${directory ?: "(repository root)"} in " +
                "${parsed.owner}/${parsed.repo}@$commitSha",
        )
    val candidateId = candidateSkillId(markdown)
        ?: return@withContext SkillInstallOutcome.Failure("the fetched SKILL.md has no usable frontmatter name")
    _skills.value.find { it.id == candidateId }?.let { existing ->
        return@withContext SkillInstallOutcome.Conflict(
            existingId = existing.id,
            bundled = existing.importSource == ImportSource.BUNDLED,
        )
    }

    val sourceURL = policy.htmlSkillUrl(parsed.owner, parsed.repo, commitSha, directory)
    val installed = importFromContent(markdown, ImportSource.URL, sourceURL = sourceURL)
        ?: return@withContext SkillInstallOutcome.Failure("the skill transaction refused $candidateId")

    val files = mutableListOf<SkillInstallFile>()
    val agg = AggregateOutcome(files)
    downloadGitHubDirectory(
        user = parsed.owner,
        repo = parsed.repo,
        branch = commitSha,
        remotePath = directory.orEmpty(),
        relativeTo = "",
        depth = 0,
        outcome = agg,
    )
    var partialReason = agg.firstReason
    if (files.isNotEmpty()) {
        val result = commitSkillFiles(
            skillId = installed.id,
            registration = registrationOf(installed),
            files = files,
            base = SkillInstallBase.PRESERVE,
        )
        if (result is SkillInstallResult.Failure) {
            logRefusedTransaction("GitHub install siblings", installed.id, result)
            partialReason = "sibling files were refused: ${result.error.message}"
        }
    }
    SkillInstallOutcome.Installed(
        skill = _skills.value.find { it.id == installed.id } ?: installed,
        commitSha = commitSha,
        filesWritten = agg.filesWritten,
        partialReason = partialReason,
    )
}

internal fun SkillRepository.resolveCommitSha(owner: String, repo: String, ref: String): String? {
    val body = httpGetString(
        com.openminis.app.tools.skills.SkillSourcePolicy.commitApiUrl(owner, repo, ref),
    ) ?: return null
    return try {
        JSONObject(body).optString("sha").takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }
}

/**
 * Re-fetch the SKILL.md for an existing URL-sourced skill and update its
 * record in place — does NOT create a new skill. Also re-downloads sibling
 * files from the same GitHub directory so bundled scripts/references stay
 * in sync with upstream. Returns a typed result with an explicit reason on
 * failure so the UI can surface *why* the update failed.
 */
suspend fun SkillRepository.updateFromURL(skillId: String): UpdateResult = withContext(Dispatchers.IO) {
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
fun SkillRepository.exportSkillToZip(skillId: String): File? {
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
internal fun SkillRepository.sweepStaleExports() {
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
fun SkillRepository.listSkillFiles(skillId: String): List<String> {
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
fun SkillRepository.skillFileHostPath(skillId: String, relativePath: String): String =
    skillGuestPath(skillId, relativePath)

/**
 * Re-read SKILL.md from disk and push any frontmatter changes back into the
 * DB + in-memory state. Call after the agent (or user) edits SKILL.md on
 * disk so the store reflects the new name/description/version/body.
 * Returns the refreshed skill or null if the file is gone or malformed.
 */
fun SkillRepository.rescanFromDisk(skillId: String): Skill? {
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
internal suspend fun SkillRepository.rescanFromDiskLocked(skillId: String): Skill? =
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
fun SkillRepository.renameSkill(skillId: String, newName: String): Boolean {
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
fun SkillRepository.readSkillFile(skillId: String, relativePath: String): String? {
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
fun SkillRepository.writeSkillFile(skillId: String, relativePath: String, content: String): Boolean {
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
internal suspend fun SkillRepository.downloadSiblingFiles(
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

internal suspend fun SkillRepository.downloadGitHubDirectory(
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
