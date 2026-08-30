package com.openminis.app.runtime.ubuntu

import android.content.Context
import java.io.File

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

    fun init(context: Context) {
        migrateLegacyFilesDir(context.filesDir)
    }

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

    internal fun deleteSessionFiles(sessionsRoot: File, sessionId: String): Boolean {
        if (!isSafeSessionId(sessionId)) return false
        val session = childOf(sessionsRoot.absolutePath, sessionId) ?: return false
        return !session.exists() || session.deleteRecursively()
    }

    fun deleteSession(context: Context, sessionId: String): Boolean {
        val canonical = deleteSessionFiles(File(hostSessions), sessionId)
        val legacy = deleteSessionFiles(File(context.filesDir, "minis-sessions"), sessionId)
        return canonical || legacy
    }

    fun migrateLegacyFilesDir(filesDir: File, destRoot: File = File(HOST_MINIS)): LegacyMigrationResult =
        migrateLegacyLayout(filesDir, destRoot)

    internal fun migrateLegacyLayout(filesDir: File, destRoot: File): LegacyMigrationResult {
        val marker = File(destRoot, "run/legacy-filesdir-migrated")
        if (marker.isFile) {
            return LegacyMigrationResult(skipped = true, copied = false)
        }
        val sources = listOf(
            File(filesDir, "minis/workspace") to File(destRoot, "workspace"),
            File(filesDir, "minis-global/memory") to File(destRoot, "memory"),
            File(filesDir, "minis-global/skills") to File(destRoot, "skills"),
            File(filesDir, "minis-global/shared") to File(destRoot, "shared"),
            File(filesDir, "minis-sessions") to File(destRoot, "sessions"),
            File(filesDir, "minis/home") to File(destRoot, "home"),
        )
        val present = sources.filter { it.first.isDirectory }
        if (present.isEmpty()) {
            return writeMarker(marker, destRoot)
                ?: LegacyMigrationResult(skipped = true, copied = false)
        }
        if (!destRoot.exists() && !destRoot.mkdirs()) {
            return LegacyMigrationResult(
                skipped = false,
                copied = false,
                error = "persistent root unavailable: ${destRoot.path}",
            )
        }
        for ((src, dest) in present) {
            val error = copyDirectoryContents(src, dest)
            if (error != null) {
                return LegacyMigrationResult(skipped = false, copied = false, error = error)
            }
        }
        return writeMarker(marker, destRoot)
            ?: LegacyMigrationResult(skipped = false, copied = true)
    }

    private fun writeMarker(marker: File, destRoot: File): LegacyMigrationResult? {
        val run = File(destRoot, "run")
        if (!run.exists() && !run.mkdirs()) {
            return LegacyMigrationResult(
                skipped = false,
                copied = false,
                error = "cannot create ${run.path}",
            )
        }
        return try {
            marker.writeText("ok\n")
            null
        } catch (t: Throwable) {
            LegacyMigrationResult(skipped = false, copied = false, error = t.message)
        }
    }

    internal fun copyDirectoryContents(src: File, dest: File): String? {
        if (!src.isDirectory) return null
        if (!dest.exists() && !dest.mkdirs()) return "mkdir ${dest.path}"
        src.walkTopDown().forEach { file ->
            val rel = file.relativeTo(src)
            if (rel.path.isEmpty()) return@forEach
            val target = File(dest, rel.path)
            when {
                file.isDirectory -> {
                    if (!target.exists() && !target.mkdirs()) return "mkdir ${target.path}"
                }
                !target.exists() -> {
                    target.parentFile?.mkdirs()
                    runCatching { file.copyTo(target, overwrite = false) }
                        .onFailure { return "copy ${file.path}: ${it.message}" }
                }
            }
        }
        return null
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
