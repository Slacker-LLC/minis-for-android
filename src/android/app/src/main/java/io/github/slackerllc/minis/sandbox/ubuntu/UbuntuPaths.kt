package io.github.slackerllc.minis.sandbox.ubuntu

import android.content.Context
import java.io.File

/**
 * Normal commands resolve `/workspace` through the owning chat's
 * `<filesDir>/minis-sessions/<sessionId>/workspace` directory. Memory, skills,
 * and shared remain App-global under `<filesDir>/minis-global`.
 *
 * `/data/adb/minis` is reserved for root-owned runtime state such as the Ubuntu
 * rootfs and minisd. It is not the App workspace. minisd bind-mounts the
 * App-private directories into the chroot when Ubuntu starts.
 */
object UbuntuPaths {
    const val HOST_MINIS = "/data/adb/minis"
    const val HOST_ROOTFS = "$HOST_MINIS/rootfs"

    // Defaults for tests / mock; real values set by [init].
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

    /** Called from MinisApp.onCreate once. Safe to call twice. */
    fun init(context: Context) {
        val legacyWorkspaceBase = File(context.filesDir, "minis")
        val globalBase = File(context.filesDir, "minis-global")
        hostWorkspace = File(legacyWorkspaceBase, "workspace").absolutePath
        hostMemory = File(globalBase, "memory").absolutePath
        hostSkills = File(globalBase, "skills").absolutePath
        hostShared = File(globalBase, "shared").absolutePath
        hostSessions = File(context.filesDir, "minis-sessions").absolutePath
        File(hostWorkspace, "attachments").mkdirs()
        File(hostWorkspace, "offloads").mkdirs()
        File(hostWorkspace, "browser").mkdirs()
        File(hostSessions).mkdirs()
        File(hostMemory).mkdirs()
        File(hostSkills).mkdirs()
        File(hostShared).mkdirs()
    }

    /** Resolves a child only when its canonical target remains below [base]. */
    private fun childOf(base: String, rest: String): File? = runCatching {
        val root = File(base).canonicalFile
        val target = if (rest.isEmpty()) root else File(root, rest).canonicalFile
        if (target.path == root.path || target.path.startsWith(root.path + File.separator)) target else null
    }.getOrNull()

    private fun unsafePath(path: String): Boolean =
        path.isEmpty() || path.contains('\u0000') || path.split('/').any { it == ".." }

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
        // Relative path falls back to the workspace (schema contracts of the
        // linux.file.* tools say "relative to workspace").
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

    internal fun ensureSessionDirs(filesDir: File, sessionId: String): File? {
        if (!isSafeSessionId(sessionId)) return null
        val sessions = File(filesDir, "minis-sessions")
        if (!sessions.isDirectory && !sessions.mkdirs()) return null
        val session = childOf(sessions.absolutePath, sessionId) ?: return null
        listOf("workspace", "attachments", "offloads", "browser").forEach { subdir ->
            val dir = File(session, subdir)
            if (!dir.isDirectory && !dir.mkdirs()) return null
        }
        return session
    }

    internal fun resolveSessionPath(filesDir: File, sessionId: String, linuxPath: String): File? {
        if (unsafePath(linuxPath)) return null
        val session = ensureSessionDirs(filesDir, sessionId) ?: return null
        val normalized = if (linuxPath.startsWith('/')) linuxPath else "/workspace/$linuxPath"
        val match = sessionAliases
            .filter { normalized == it.first || normalized.startsWith(it.first + "/") }
            .maxByOrNull { it.first.length }
            ?: return null
        val base = File(session, match.second)
        val rest = normalized.removePrefix(match.first).removePrefix("/")
        return childOf(base.absolutePath, rest)
    }

    /**
     * Resolve task/output resources against the owning chat. Global resources
     * and user mounts deliberately fall back to [resolveHostPath]. The former
     * App-wide workspace remains at [hostWorkspace] as a legacy compatibility
     * location for callers that genuinely have no session context; it is never
     * silently shared into a session.
     */
    fun resolveSessionHostPath(sessionId: String, linuxPath: String, context: Context): File? =
        if (isSessionScopedPath(linuxPath)) {
            resolveSessionPath(context.filesDir, sessionId, linuxPath)
        } else {
            resolveHostPath(linuxPath)
        }

    private fun isSessionScopedPath(linuxPath: String): Boolean {
        if (!linuxPath.startsWith('/')) return true
        return sessionAliases.any { linuxPath == it.first || linuxPath.startsWith(it.first + "/") }
    }

    /** Best-effort cleanup used after the database has deleted a chat. */
    internal fun deleteSessionFiles(filesDir: File, sessionId: String): Boolean {
        if (!isSafeSessionId(sessionId)) return false
        val sessions = File(filesDir, "minis-sessions")
        val session = childOf(sessions.absolutePath, sessionId) ?: return false
        return !session.exists() || session.deleteRecursively()
    }

    fun deleteSession(context: Context, sessionId: String): Boolean =
        deleteSessionFiles(context.filesDir, sessionId)
}
