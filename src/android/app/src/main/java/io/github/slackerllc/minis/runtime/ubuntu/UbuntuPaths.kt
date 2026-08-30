package io.github.slackerllc.minis.runtime.ubuntu

import android.content.Context
import java.io.File
import java.nio.file.Files

/**
 * Android-side view of minisd's fixed persistent data contract.
 *
 * Agent data has one host source of truth under `/data/adb/minis`. The App must
 * not redirect these paths to `filesDir`; minisd prepares and validates the
 * persistent layout before keeper/session mount namespaces are created.
 */
object UbuntuPaths {
    const val HOST_MINIS = "/data/adb/minis"
    const val HOST_ROOTFS = "$HOST_MINIS/rootfs"
    const val HOST_WORKSPACE = "$HOST_MINIS/workspace"
    const val HOST_SESSIONS = "$HOST_MINIS/sessions"
    const val HOST_MEMORY = "$HOST_MINIS/memory"
    const val HOST_SKILLS = "$HOST_MINIS/skills"
    const val HOST_SHARED = "$HOST_MINIS/shared"
    const val HOST_HOME = "$HOST_MINIS/home"

    const val GUEST_WORKSPACE = "/workspace"
    const val GUEST_HOME = "/home/minis"

    val hostWorkspace: String = HOST_WORKSPACE
    val hostMemory: String = HOST_MEMORY
    val hostSkills: String = HOST_SKILLS
    val hostShared: String = HOST_SHARED
    val hostSessions: String = HOST_SESSIONS
    val hostHome: String = HOST_HOME

    val bindMounts: MutableMap<String, String> = linkedMapOf()

    internal interface SessionRootProviderForTest {
        val sessionsRootForTest: File
    }

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
        GUEST_HOME to { hostHome },
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

    /** Stable Application init hook. Root-owned layout preparation happens in minisd bootstrap. */
    fun init(@Suppress("UNUSED_PARAMETER") context: Context) = Unit

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

    private fun ensureDirectory(path: File): Boolean {
        if (runCatching { Files.isSymbolicLink(path.toPath()) }.getOrDefault(false)) return false
        return path.isDirectory || path.mkdirs()
    }

    internal fun ensureSessionDirs(sessionId: String): File? =
        ensureSessionDirsAt(File(hostSessions), sessionId)

    internal fun ensureSessionDirs(
        @Suppress("UNUSED_PARAMETER") filesDir: File,
        sessionId: String,
    ): File? = ensureSessionDirs(sessionId)

    internal fun ensureSessionDirsAt(sessionsRoot: File, sessionId: String): File? {
        if (!isSafeSessionId(sessionId)) return null
        if (!ensureDirectory(sessionsRoot)) return null
        val session = childOf(sessionsRoot.absolutePath, sessionId) ?: return null
        if (!ensureDirectory(session)) return null
        listOf("workspace", "attachments", "offloads", "browser").forEach { subdir ->
            if (!ensureDirectory(File(session, subdir))) return null
        }
        return session
    }

    internal fun resolveSessionPath(sessionId: String, linuxPath: String): File? =
        resolveSessionPathAt(File(hostSessions), sessionId, linuxPath)

    internal fun resolveSessionPath(
        @Suppress("UNUSED_PARAMETER") filesDir: File,
        sessionId: String,
        linuxPath: String,
    ): File? = resolveSessionPath(sessionId, linuxPath)

    internal fun resolveSessionPathAt(sessionsRoot: File, sessionId: String, linuxPath: String): File? {
        if (unsafePath(linuxPath)) return null
        val session = ensureSessionDirsAt(sessionsRoot, sessionId) ?: return null
        val normalized = if (linuxPath.startsWith('/')) linuxPath else "/workspace/$linuxPath"
        val match = sessionAliases
            .filter { normalized == it.first || normalized.startsWith(it.first + "/") }
            .maxByOrNull { it.first.length }
            ?: return null
        val base = File(session, match.second)
        if (runCatching { Files.isSymbolicLink(base.toPath()) }.getOrDefault(false)) return null
        val rest = normalized.removePrefix(match.first).removePrefix("/")
        return childOf(base.absolutePath, rest)
    }

    fun resolveSessionHostPath(sessionId: String, linuxPath: String): File? =
        if (isSessionScopedPath(linuxPath)) resolveSessionPath(sessionId, linuxPath) else resolveHostPath(linuxPath)

    fun resolveSessionHostPath(sessionId: String, linuxPath: String, context: Context): File? =
        if (isSessionScopedPath(linuxPath)) {
            val testRoot = (context as? SessionRootProviderForTest)?.sessionsRootForTest
            if (testRoot != null) resolveSessionPathAt(testRoot, sessionId, linuxPath)
            else resolveSessionPath(sessionId, linuxPath)
        } else {
            resolveHostPath(linuxPath)
        }

    private fun isSessionScopedPath(linuxPath: String): Boolean {
        if (!linuxPath.startsWith('/')) return true
        return sessionAliases.any { linuxPath == it.first || linuxPath.startsWith(it.first + "/") }
    }

    internal fun deleteSessionFilesAt(sessionsRoot: File, sessionId: String): Boolean {
        if (!isSafeSessionId(sessionId)) return false
        val session = childOf(sessionsRoot.absolutePath, sessionId) ?: return false
        return !session.exists() || session.deleteRecursively()
    }

    internal fun deleteSessionFiles(
        @Suppress("UNUSED_PARAMETER") filesDir: File,
        sessionId: String,
    ): Boolean = deleteSessionFilesAt(File(hostSessions), sessionId)

    fun deleteSession(@Suppress("UNUSED_PARAMETER") context: Context, sessionId: String): Boolean =
        deleteSessionFilesAt(File(hostSessions), sessionId)
}
