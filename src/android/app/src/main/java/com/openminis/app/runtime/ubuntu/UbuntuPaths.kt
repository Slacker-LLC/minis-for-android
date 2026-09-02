package com.openminis.app.runtime.ubuntu

import android.content.Context
import android.util.Base64
import com.openminis.app.runtime.minisd.WorkspaceFileClient
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Persistent Linux guest data is contracted at `/data/adb/minis`.
 * App file tools and minisd bind mounts must use these host paths.
 */
object UbuntuPaths {
    const val HOST_MINIS = "/data/adb/minis"
    const val HOST_ROOTFS = "$HOST_MINIS/rootfs"
    const val MIGRATION_MARKER = "$HOST_MINIS/run/legacy-filesdir-migrated"

    var hostWorkspace: String = "$HOST_MINIS/workspace"
        private set
    var hostMemory: String = "$HOST_MINIS/memory"
        private set
    var hostSkills: String = "$HOST_MINIS/skills"
        private set
    var hostShared: String = "$HOST_MINIS/shared"
        private set
    var hostSessions: String = "$HOST_MINIS/sessions"
        private set
    var hostHome: String = "$HOST_MINIS/home"
        private set

    val bindMounts: MutableMap<String, String> = linkedMapOf()

    private val aliases = listOf(
        "/workspace" to { hostWorkspace },
        "/var/minis/workspace" to { hostWorkspace },
        "/var/minis/attachments" to { "$hostWorkspace/attachments" },
        "/var/minis/offloads" to { "$hostWorkspace/offloads" },
        "/var/minis/browser" to { "$hostWorkspace/browser" },
        "/memory" to { hostMemory },
        "/var/minis/memory" to { hostMemory },
        "/skills" to { hostSkills },
        "/var/minis/skills" to { hostSkills },
        "/shared" to { hostShared },
        "/var/minis/shared" to { hostShared },
        "/home/minis" to { hostHome },
    )

    private val sessionAliases = listOf(
        "/var/minis/workspace/attachments" to "attachments",
        "/var/minis/workspace/offloads" to "offloads",
        "/var/minis/workspace/browser" to "browser",
        "/workspace/attachments" to "attachments",
        "/workspace/offloads" to "offloads",
        "/workspace/browser" to "browser",
        "/workspace" to "workspace",
        "/var/minis/workspace" to "workspace",
        "/var/minis/attachments" to "attachments",
        "/var/minis/offloads" to "offloads",
        "/var/minis/browser" to "browser",
    )

    data class LegacyMigrationResult(
        val skipped: Boolean,
        val copied: Boolean,
        val error: String? = null,
    )

    internal fun useLayoutForTest(root: File) {
        hostWorkspace = File(root, "workspace").absolutePath
        hostMemory = File(root, "memory").absolutePath
        hostSkills = File(root, "skills").absolutePath
        hostShared = File(root, "shared").absolutePath
        hostSessions = File(root, "sessions").absolutePath
        hostHome = File(root, "home").absolutePath
    }

    internal fun resetLayoutForTest() {
        hostWorkspace = "$HOST_MINIS/workspace"
        hostMemory = "$HOST_MINIS/memory"
        hostSkills = "$HOST_MINIS/skills"
        hostShared = "$HOST_MINIS/shared"
        hostSessions = "$HOST_MINIS/sessions"
        hostHome = "$HOST_MINIS/home"
    }

    fun sessionDir(sessionId: String): File? = ensureSessionDirsAt(File(hostSessions), sessionId)

    fun ensureSessionDirs(sessionId: String): File? = sessionDir(sessionId)

    fun resolveGuest(linuxPath: String): File? {
        if (unsafePath(linuxPath)) return null
        val match = aliases
            .filter { linuxPath == it.first || linuxPath.startsWith(it.first + "/") }
            .maxByOrNull { it.first.length }
            ?: return null
        return childOf(match.second(), linuxPath.removePrefix(match.first).removePrefix("/"))
    }

    fun resolveHostPath(linuxPath: String): File? {
        if (unsafePath(linuxPath)) return null
        // External SAF mounts are owned by minisd. Returning a host File here
        // would bypass the persisted-grant re-attestation and kernel mount
        // policy, so callers must use the broker-backed file client instead.
        if (linuxPath == "/var/minis/mounts" || linuxPath.startsWith("/var/minis/mounts/")) {
            return null
        }
        resolveGuest(linuxPath)?.let { return it }
        val sorted = bindMounts.keys.sortedByDescending { it.length }
        for (mount in sorted) {
            if (linuxPath == mount || linuxPath.startsWith("$mount/")) {
                val hostBase = bindMounts[mount] ?: continue
                return childOf(hostBase, linuxPath.removePrefix(mount).removePrefix("/"))
            }
        }
        if (!linuxPath.startsWith("/")) return resolveGuest("/workspace/$linuxPath")
        return null
    }

    internal fun isSafeSessionId(sessionId: String): Boolean =
        sessionId.isNotEmpty() &&
            sessionId.length <= 128 &&
            sessionId != "." &&
            sessionId != ".." &&
            sessionId.all {
                it.code < 128 && (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.')
            }

    internal fun ensureSessionDirsAt(sessionsRoot: File, sessionId: String): File? {
        if (!isSafeSessionId(sessionId)) return null
        if (!sessionsRoot.isDirectory && !sessionsRoot.mkdirs()) return null
        val session = childOf(sessionsRoot.absolutePath, sessionId) ?: return null
        listOf("workspace", "attachments", "offloads", "browser").forEach { subdir ->
            val dir = File(session, subdir)
            if (!dir.isDirectory && !dir.mkdirs()) return null
        }
        return session
    }

    internal fun resolveSessionPath(sessionsRoot: File, sessionId: String, linuxPath: String): File? {
        if (unsafePath(linuxPath)) return null
        val session = ensureSessionDirsAt(sessionsRoot, sessionId) ?: return null
        val normalized = if (linuxPath.startsWith('/')) linuxPath else "/workspace/$linuxPath"
        val match = sessionAliases
            .filter { normalized == it.first || normalized.startsWith(it.first + "/") }
            .maxByOrNull { it.first.length }
            ?: return null
        val base = File(session, match.second)
        val rest = normalized.removePrefix(match.first).removePrefix("/")
        return childOf(base.absolutePath, rest)
    }

    fun resolveSessionHostPath(sessionId: String, linuxPath: String, context: Context): File? =
        resolveSessionHostPath(sessionId, linuxPath)

    fun resolveSessionHostPath(sessionId: String, linuxPath: String): File? =
        if (isSessionScopedPath(linuxPath)) {
            resolveSessionPath(File(hostSessions), sessionId, linuxPath)
        } else {
            resolveHostPath(linuxPath)
        }

    @Suppress("UNUSED_PARAMETER")
    fun deleteSession(context: Context, sessionId: String): Boolean =
        runCatching {
            WorkspaceFileClient.deleteSessionBlocking(sessionId)
            true
        }.getOrDefault(false)

    internal data class MigrationRoot(
        val source: File,
        val target: String,
        val sessionId: String? = null,
    )

    internal fun legacyMigrationRoots(filesDir: File): List<MigrationRoot> = listOf(
        MigrationRoot(File(filesDir, "minis/workspace"), "workspace"),
        MigrationRoot(File(filesDir, "minis-global/memory"), "memory"),
        MigrationRoot(File(filesDir, "minis-global/skills"), "skills"),
        MigrationRoot(File(filesDir, "minis-global/shared"), "shared"),
        MigrationRoot(File(filesDir, "minis/home"), "home"),
    )

    suspend fun migrateLegacyFilesDir(filesDir: File): LegacyMigrationResult =
        migrateLegacyFilesDir(filesDir, brokerReady = false)

    internal suspend fun migrateLegacyFilesDirAfterBrokerReady(filesDir: File): LegacyMigrationResult =
        migrateLegacyFilesDir(filesDir, brokerReady = true)

    private suspend fun migrateLegacyFilesDir(filesDir: File, brokerReady: Boolean): LegacyMigrationResult {
        val ensureBroker = !brokerReady
        return try {
            if (WorkspaceFileClient.migrationStatus(ensureBroker = ensureBroker).optBoolean("complete")) {
                return LegacyMigrationResult(skipped = true, copied = false)
            }
            var present = false
            for (root in legacyMigrationRoots(filesDir)) {
                if (!Files.exists(root.source.toPath(), LinkOption.NOFOLLOW_LINKS)) continue
                if (!root.source.isDirectory || Files.isSymbolicLink(root.source.toPath())) {
                    return LegacyMigrationResult(
                        skipped = false,
                        copied = false,
                        error = "legacy migration source is not a real directory: ${root.source}",
                    )
                }
                present = true
                migrateDirectory(root.source, root.target, ensureBroker = ensureBroker)
            }
            val sessions = File(filesDir, "minis-sessions")
            if (Files.exists(sessions.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                if (!sessions.isDirectory || Files.isSymbolicLink(sessions.toPath())) {
                    return LegacyMigrationResult(
                        skipped = false,
                        copied = false,
                        error = "legacy session source is not a real directory: $sessions",
                    )
                }
                for (session in sessions.listFiles()?.sortedBy { it.name }
                    ?: throw IllegalStateException("cannot list legacy session source: $sessions")) {
                    if (!isSafeSessionId(session.name)) {
                        return LegacyMigrationResult(
                            skipped = false,
                            copied = false,
                            error = "invalid legacy session id: ${session.name}",
                        )
                    }
                    if (!session.isDirectory || Files.isSymbolicLink(session.toPath())) {
                        return LegacyMigrationResult(
                            skipped = false,
                            copied = false,
                            error = "legacy session is not a real directory: $session",
                        )
                    }
                    present = true
                    migrateDirectory(session, "session", session.name, ensureBroker = ensureBroker)
                }
            }
            WorkspaceFileClient.migrationComplete(ensureBroker = ensureBroker)
            LegacyMigrationResult(skipped = !present, copied = present)
        } catch (t: Throwable) {
            LegacyMigrationResult(
                skipped = false,
                copied = false,
                error = t.message ?: t::class.java.simpleName,
            )
        }
    }

    private suspend fun migrateDirectory(
        source: File,
        target: String,
        sessionId: String? = null,
        prefix: String = "",
        ensureBroker: Boolean,
    ) {
        for (entry in source.listFiles()?.sortedBy { it.name }
            ?: throw IllegalStateException("cannot list legacy migration directory: $source")) {
            if (entry.name.isEmpty() || entry.name == "." || entry.name == ".." ||
                entry.name.contains('/') || entry.name.contains('\\') ||
                entry.name.contains('\u0000') || Files.isSymbolicLink(entry.toPath())
            ) {
                throw IllegalStateException("unsafe legacy migration entry: ${entry.name}")
            }
            val path = if (prefix.isEmpty()) entry.name else "$prefix/${entry.name}"
            when {
                entry.isDirectory -> {
                    WorkspaceFileClient.migrationMkdir(
                        target,
                        path,
                        sessionId,
                        ensureBroker = ensureBroker,
                    )
                    migrateDirectory(entry, target, sessionId, path, ensureBroker)
                }
                entry.isFile -> migrateFile(entry, target, path, sessionId, ensureBroker)
                else -> throw IllegalStateException("unsupported legacy migration entry: $entry")
            }
        }
    }

    private suspend fun migrateFile(
        source: File,
        target: String,
        path: String,
        sessionId: String?,
        ensureBroker: Boolean,
    ) {
        val existing = WorkspaceFileClient.migrationInfo(
            target,
            path,
            sessionId,
            ensureBroker = ensureBroker,
        )
        if (existing.optBoolean("exists", true)) {
            if (existing.optString("type") != "file") {
                throw IllegalStateException("legacy migration target is not a file: $target/$path")
            }
            if (existing.optLong("size", -1L) == source.length()) return
        }
        FileInputStream(source).use { input ->
            val buffer = ByteArray(WorkspaceFileClient.MAX_WRITE_CHUNK)
            var append = false
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                WorkspaceFileClient.migrationWrite(
                    target = target,
                    path = path,
                    dataBase64 = Base64.encodeToString(buffer, 0, count, Base64.NO_WRAP),
                    append = append,
                    sessionId = sessionId,
                    ensureBroker = ensureBroker,
                )
                append = true
            }
            if (!append) {
                WorkspaceFileClient.migrationWrite(
                    target = target,
                    path = path,
                    dataBase64 = "",
                    append = false,
                    sessionId = sessionId,
                    ensureBroker = ensureBroker,
                )
            }
        }
    }

    private fun isSessionScopedPath(linuxPath: String): Boolean {
        if (!linuxPath.startsWith('/')) return true
        return sessionAliases.any { linuxPath == it.first || linuxPath.startsWith(it.first + "/") }
    }

    private fun childOf(base: String, rest: String): File? = runCatching {
        val root = File(base).canonicalFile
        val target = if (rest.isEmpty()) root else File(root, rest).canonicalFile
        if (target.path == root.path || target.path.startsWith(root.path + File.separator)) target else null
    }.getOrNull()

    private fun unsafePath(path: String): Boolean =
        path.isEmpty() || path.contains('\u0000') || path.split('/').any { it == ".." }
}
