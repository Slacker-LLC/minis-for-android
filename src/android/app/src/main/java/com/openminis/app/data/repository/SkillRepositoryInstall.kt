package com.openminis.app.data.repository

import android.net.Uri
import android.util.Log
import com.openminis.app.data.repository.SkillRepository.AggregateOutcome
import com.openminis.app.data.repository.SkillRepository.Companion.SKILLS_ROOT
import com.openminis.app.data.repository.SkillRepository.Companion.SKILL_LOAD_TIMEOUT_MS
import com.openminis.app.data.repository.SkillRepository.Companion.TAG
import com.openminis.app.data.repository.SkillRepository.Companion.shouldPruneOrphan
import com.openminis.app.data.repository.SkillRepository.GitHubInfo
import com.openminis.app.data.repository.SkillRepository.ImportSource
import com.openminis.app.data.repository.SkillRepository.ParsedSkill
import com.openminis.app.data.repository.SkillRepository.Skill
import com.openminis.app.runtime.files.WorkspaceFileClient
import java.io.File
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.json.JSONObject

/**
 * Fetch a GitHub Contents-API URL, retrying once on transient failure
 * (HTTP 403/429/5xx, IOException). Returns the response body string on
 * success, or null after both attempts fail — in which case the failure
 * reason is recorded into [outcome] so the UI can surface it.
 */
internal suspend fun SkillRepository.fetchContentsWithRetry(apiURL: String, outcome: AggregateOutcome): String? {
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
internal suspend fun SkillRepository.fetchBytesWithRetry(url: String): ByteArray? {
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
internal fun SkillRepository.parseGitHubURL(urlString: String): GitHubInfo? {
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
internal fun SkillRepository.githubToRawURL(urlString: String): String? {
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

internal fun SkillRepository.normalizeURL(urlString: String): String {
    val trimmed = urlString.trim()
    return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
    else "https://$trimmed"
}

internal fun SkillRepository.httpGetString(url: String): String? = try {
    val req = Request.Builder().url(url).get().build()
    httpClient.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) null else resp.body?.string()
    }
} catch (e: Exception) {
    Log.w(TAG, "httpGetString failed $url: ${e.message}")
    null
}

internal fun SkillRepository.httpGetBytes(url: String): ByteArray? = try {
    val req = Request.Builder().url(url).get().build()
    httpClient.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) null else resp.body?.bytes()
    }
} catch (e: Exception) {
    Log.w(TAG, "httpGetBytes failed $url: ${e.message}")
    null
}

// -- Bundled Skills --

internal suspend fun SkillRepository.installBundledSkills() {
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
fun SkillRepository.reloadFromDisk() {
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

internal suspend fun SkillRepository.loadAllSafelyLocked(reason: String) {
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

internal suspend fun SkillRepository.loadAll() {
    // A guest-file failure must not look like an empty tree. Keep the
    // nullable result so a transient runtime outage cannot prune valid DB
    // rows before the guest runtime becomes ready again.
    val onDisk = listSkillDirectoriesForLoad()
    // Until the legacy data migration has run, the App-owned skills tree can be empty only because
    // the user's skills are still in the legacy location: pruning then would delete their switches,
    // usage counts and session overrides before the files arrive.
    val legacyMigrated = com.openminis.app.runtime.ubuntu.LegacyDataMigration.isComplete(context)

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
         if (shouldPruneOrphan(
                 bundled = importSource == ImportSource.BUNDLED,
                 legacyMigrated = legacyMigrated,
                 onDisk = onDisk,
                 id = id,
             ) { readSkillFileForLoad(id, "SKILL.md") != null }
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

internal fun SkillRepository.insertDb(skill: Skill) {
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
internal fun SkillRepository.readSkillRowSnapshot(skillId: String): String? = db.rawQuery(
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
internal fun SkillRepository.writeRegisteredSkill(skillId: String, registration: SkillRegistration) {
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
internal fun SkillRepository.restoreSkillRow(skillId: String, payload: String) {
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
internal fun SkillRepository.removeSkillRow(skillId: String) {
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
internal suspend fun SkillRepository.commitSkillFiles(
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
internal fun SkillRepository.commitSkillFilesBlocking(
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
internal fun <T> SkillRepository.readUnderSkillMutationLock(operation: String, block: suspend () -> T): T? = try {
    runBlocking(Dispatchers.IO) { skillPackageInstaller.withSkillMutationLock(block) }
} catch (error: Exception) {
    Log.w(
        TAG,
        "Refused skill read (" + operation + "): " + (error.message ?: error.javaClass.simpleName),
    )
    null
}

internal fun SkillRepository.registrationOf(skill: Skill): SkillRegistration = SkillRegistration(
    name = skill.name,
    description = skill.description,
    version = skill.version,
    importSource = skill.importSource.value,
    sourceUrl = skill.sourceURL,
    body = skill.body,
)

internal fun SkillRepository.skillMarkdownFile(markdown: String): SkillInstallFile =
    SkillInstallFile("SKILL.md", markdown.toByteArray(Charsets.UTF_8))

/** Suspending twin of [readSkillBytes], for callers inside the transaction lock. */
internal suspend fun SkillRepository.readSkillBytesSuspending(skillId: String, relativePath: String): ByteArray? {
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
internal fun SkillRepository.applySkillRegistryRow(skill: Skill) {
    db.execSQL(
        "UPDATE skills SET name=?, description=?, version=?, updated_at=? WHERE id=?",
        arrayOf<Any>(skill.name, skill.description, skill.version, skill.updatedAt, skill.id),
    )
    _skills.value = _skills.value.map { if (it.id == skill.id) skill else it }
}

internal fun SkillRepository.logRefusedTransaction(
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

internal fun SkillRepository.readSkillBodyBlocking(skillId: String): String {
    val bytes = readSkillBytes(skillId, "SKILL.md") ?: return ""
    return parseSkillMd(String(bytes, Charsets.UTF_8))?.body ?: ""
}

/**
 * [T-android-skill-install-transaction] Canonical SKILL.md text. Shared by
 * the direct write and by the staged archive import, so both produce the
 * same bytes and the transaction does not change what lands on disk.
 */
internal fun SkillRepository.skillMdContent(
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

internal suspend fun SkillRepository.readSkillMdBodyForLoad(id: String): String {
    val parsed = readSkillFileForLoad(id, "SKILL.md")?.let(::parseSkillMd)
    return parsed?.body ?: ""
}

/**
 * Suspending variant used exclusively by [loadAll]. Keeping the startup
 * path free of `runBlocking` is what makes an unavailable guest-file backend unable to
 * trigger an Android application-start ANR.
 */
internal suspend fun SkillRepository.listSkillDirectoriesForLoad(): List<String>? = try {
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

internal suspend fun SkillRepository.readSkillFileForLoad(skillId: String, relativePath: String): String? {
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
internal suspend fun SkillRepository.listSkillGuestFilesLocked(skillId: String): List<String> = runCatching {
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

internal fun SkillRepository.readSkillBytes(skillId: String, relativePath: String): ByteArray? {
    if (!isSafeSkillId(skillId) || !isSafeRelativePath(relativePath)) return null
    return runCatching {
        WorkspaceFileClient.readAllBlocking("", skillGuestPath(skillId, relativePath))
    }.getOrNull()
}

internal fun SkillRepository.childGuestPath(directory: String, name: String): String {
    require(isSafeRelativePath(name)) { "invalid skill directory entry" }
    return "${directory.trimEnd('/')}/$name"
}

internal fun SkillRepository.skillGuestPath(skillId: String, relativePath: String = ""): String {
    require(isSafeSkillId(skillId)) { "invalid skill id" }
    require(relativePath.isEmpty() || isSafeRelativePath(relativePath)) {
        "invalid skill file path"
    }
    return if (relativePath.isEmpty()) "$SKILLS_ROOT/$skillId"
    else "$SKILLS_ROOT/$skillId/$relativePath"
}

internal fun SkillRepository.isSafeSkillId(id: String): Boolean =
    id.isNotEmpty() && id != "." && id != ".." && id.all {
        it.code < 128 && (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.')
    }

internal fun SkillRepository.isSafeRelativePath(path: String): Boolean =
    path.isNotEmpty() && path.split('/').all { component ->
        component.isNotEmpty() && component != "." && component != ".." &&
            !component.contains('\\') && !component.contains('\u0000')
    }

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
fun SkillRepository.parseSkillMdPublic(content: String): ParsedSkill? = parseSkillMd(content)

internal fun SkillRepository.parseSkillMd(content: String): ParsedSkill? {
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

internal fun SkillRepository.slugify(name: String): String =
    name.lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')

internal fun SkillRepository.escapeXml(text: String): String =
    text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
