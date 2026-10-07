package com.openminis.app.data.repository

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Manages skill metadata (SQLite) and SKILL.md files on disk.
 * Mirrors iOS SkillStore architecture:
 *   - Metadata in `skills.db` (name, description, version, source, enabled)
 *   - SKILL.md in `minis-global/skills/<id>/SKILL.md`
 *   - Session overrides in `session_skill_overrides` table
 *   - Prompt fragment generation for system prompt injection
 */
class SkillRepository(internal val context: Context) {

    companion object {
        internal const val TAG = "SkillRepository"

        /**
         * Whether a DB row whose skill files are missing should be deleted: never for a bundled skill,
         * never when the tree could not be listed, and never before the legacy migration has copied the
         * user's skills in.
         */
        internal inline fun shouldPruneOrphan(
            bundled: Boolean,
            legacyMigrated: Boolean,
            onDisk: Collection<String>?,
            id: String,
            hasSkillMd: () -> Boolean,
        ): Boolean = !bundled && legacyMigrated && onDisk != null && (id !in onDisk || !hasSkillMd())
        internal const val DB_NAME = "skills.db"
        internal const val DB_VERSION = 3
        internal const val MAX_SKILLS_IN_PROMPT = 20
        internal const val MAX_SKILL_DESC_LENGTH = 200
        internal const val RECENT_WINDOW_MS = 7L * 24 * 3600 * 1000
        internal const val RECENT_SLOTS = 10
        internal const val NORMALIZE_THRESHOLD = 1000.0

        /** [T-android-skill-export] How long an exported zip stays on disk
         *  before the next export sweeps it. 24h matches iOS — long enough that
         *  any Save-to-Files / AirDrop / upload consumer has finished. */
        internal const val EXPORT_TTL_MS = 24L * 3600 * 1000
        internal const val SKILLS_ROOT = "/var/minis/skills"

        /**
         * [T-android-skill-install-transaction] The cross-process install lock
         * lives in the app's private directory, not under the skills root: the
         * agent inside the guest can unlink anything below /var/minis/skills,
         * and a lock file that can be replaced stops excluding anybody.
         */
        internal const val INSTALL_LOCK_FILE_NAME = ".skill-install.lock"
        /**
         * Skill metadata is useful but never allowed to hold up application
         * startup. A transient guest-file/runtime failure gets one bounded
         * background load attempt and can be retried when the skills screen is opened.
         */
        internal const val SKILL_LOAD_TIMEOUT_MS = 15_000L
    }

    internal val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Repository-owned scope for fire-and-forget background work that must
     * outlive the calling Composable / ViewModel — notably the sibling-file
     * download that runs after a SKILL.md import. SupervisorJob so a single
     * download failure doesn't poison the next one.
     */
    internal val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Serialize initial loading and explicit disk rescans. */
    internal val loadMutex = Mutex()

    /**
     * [T-android-skill-install-transaction] Registry side of the install
     * transaction. Ported from Eta `SkillRuntime.captureRegistryRecoverySnapshots`
     * / `registerInstalledUserSkills` / `restoreRecoveredRegistry` (commit
     * c15de97), which is the JSON registry store; Minis' registry is the
     * `skills` table plus the in-memory list, so the snapshot is the row.
     */
    internal val skillInstallIndex = object : SkillInstallIndex {
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

    internal val skillPackageInstaller = SkillPackageInstaller(
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

    internal val _skills = MutableStateFlow<List<Skill>>(emptyList())
    val skills: StateFlow<List<Skill>> = _skills.asStateFlow()

    internal val db: SQLiteDatabase by lazy {
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


    // -- Import from GitHub URL --

    data class GitHubInfo(
        val user: String,
        val repo: String,
        val branch: String,
        /** Directory path (without SKILL.md leaf) */
        val dirPath: String,
    )


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


    /** Result of [inspectGitHub]: the pinned commit plus the directories that hold a SKILL.md. */
    data class GitHubInspection(
        val commitSha: String?,
        val paths: List<String>,
        val truncated: Boolean,
        val error: String?,
    )

    /** Result of [installFromGitHub]. */
    sealed class SkillInstallOutcome {
        data class Installed(
            val skill: Skill,
            val commitSha: String,
            val filesWritten: Int,
            /** Non-null when the SKILL.md landed but the sibling walk did not finish. */
            val partialReason: String?,
        ) : SkillInstallOutcome()

        data class Conflict(val existingId: String, val bundled: Boolean) : SkillInstallOutcome()

        data class Failure(val reason: String) : SkillInstallOutcome()
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
     * Mutable accumulator threaded through the recursion. Tracks a
     * machine-friendly count plus the FIRST human-readable reason so the UI
     * can show one specific cause (e.g. "GitHub API 403 (rate limited)")
     * rather than a generic "some files failed".
     */
    internal class AggregateOutcome(private val files: MutableList<SkillInstallFile>) {
        var filesWritten = 0
        var filesFailed = 0
        var firstReason: String? = null

        internal var stagedBytes = 0L

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






































    data class ParsedSkill(
        val name: String,
        val description: String,
        val version: String = "1.0.0",
        val body: String,
    )





    // -- Database Helper --

    internal class SkillDbHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
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
internal val SKILL_CREATOR_CONTENT = """
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
