package com.openminis.app.runtime.ubuntu

import android.content.Context
import android.net.Uri
import android.util.Log
import com.openminis.app.runtime.RuntimePathRegistry
import com.openminis.app.runtime.files.SecureFileOps
import com.openminis.app.runtime.files.SafeFileTree
import kotlinx.coroutines.CancellationException
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Host/guest path contract for the direct Ubuntu backend.
 *
 * The Ubuntu rootfs remains Root-owned at /data/adb/minis/rootfs. User data is
 * deliberately App-owned again, matching upstream Android's storage model:
 * Android file tools operate directly on filesDir and the Root backend only
 * bind-mounts those directories into the chroot.
 */
object UbuntuPaths {
    /**
     * A path split into a trusted directory root and untrusted name components.
     * Consumers must walk it with directory-handle APIs; joining these fields
     * into a normal File path would reintroduce the TOCTOU the split prevents.
     */
    internal data class SecureFilePath(
        val root: File,
        val components: List<String>,
    ) {
        fun child(name: String): SecureFilePath = copy(components = components + name)
    }

    /** Legacy/root runtime root. Only rootfs and migration source remain here. */
    const val HOST_MINIS = "/data/adb/minis"
    const val HOST_ROOTFS = "$HOST_MINIS/rootfs"
    const val LEGACY_WORKSPACE = "$HOST_MINIS/workspace"
    const val LEGACY_MEMORY = "$HOST_MINIS/memory"
    const val LEGACY_SKILLS = "$HOST_MINIS/skills"
    const val LEGACY_SHARED = "$HOST_MINIS/shared"
    const val LEGACY_SESSIONS = "$HOST_MINIS/sessions"
    const val LEGACY_HOME = "$HOST_MINIS/home"

    @Volatile
    private var appContext: Context? = null

    var hostWorkspace: String = LEGACY_WORKSPACE
        private set
    var hostMemory: String = LEGACY_MEMORY
        private set
    var hostSkills: String = LEGACY_SKILLS
        private set
    var hostShared: String = LEGACY_SHARED
        private set
    var hostSessions: String = LEGACY_SESSIONS
        private set
    var hostHome: String = LEGACY_HOME
        private set
    var hostMcpServers: String = "$HOST_MINIS/mcp-servers"
        private set

    /** Additional Android-visible binds registered by RuntimePathRegistry. */
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
        "/var/minis/mcp-servers" to { hostMcpServers },
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

    fun initialize(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        val files = ctx.filesDir
        hostWorkspace = File(files, "minis/workspace").absolutePath
        hostMemory = File(files, "minis-global/memory").absolutePath
        hostSkills = File(files, "minis-global/skills").absolutePath
        hostShared = File(files, "minis-global/shared").absolutePath
        hostMcpServers = File(files, "minis-global/mcp-servers").absolutePath
        hostSessions = File(files, "minis-sessions").absolutePath
        hostHome = File(files, "minis/home").absolutePath
        ensureBaseDirs()
    }

    fun ensureBaseDirs(): Boolean = listOf(
        hostWorkspace,
        hostMemory,
        hostSkills,
        hostShared,
        hostMcpServers,
        hostSessions,
        hostHome,
    ).all { path -> ensureRealDirectory(File(path)) }

    /** Ensure a host path is a real directory without following symlinks. */
    internal fun ensureHostDirectory(directory: File): Boolean = ensureRealDirectory(directory)

    internal fun useLayoutForTest(root: File) {
        hostWorkspace = File(root, "workspace").absolutePath
        hostMemory = File(root, "memory").absolutePath
        hostSkills = File(root, "skills").absolutePath
        hostShared = File(root, "shared").absolutePath
        hostMcpServers = File(root, "mcp-servers").absolutePath
        hostSessions = File(root, "sessions").absolutePath
        hostHome = File(root, "home").absolutePath
    }

    internal fun resetLayoutForTest() {
        appContext = null
        hostWorkspace = LEGACY_WORKSPACE
        hostMemory = LEGACY_MEMORY
        hostSkills = LEGACY_SKILLS
        hostShared = LEGACY_SHARED
        hostMcpServers = "$HOST_MINIS/mcp-servers"
        hostSessions = LEGACY_SESSIONS
        hostHome = LEGACY_HOME
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

    /** Resolve only App-owned/global paths synchronously. */
    fun resolveHostPath(linuxPath: String): File? {
        if (unsafePath(linuxPath)) return null
        if (isExternalMountPath(linuxPath)) return null
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

    /**
     * Resolve a path for Android-side file I/O. External mounts are re-derived
     * from the persisted SAF grant on each access; no Root process receives a
     * host-path handoff from this resolver.
     */
    suspend fun resolveForFileAccess(sessionId: String?, linuxPath: String): File? {
        if (unsafePath(linuxPath)) return null
        if (isExternalMountPath(linuxPath)) return resolveExternalMount(linuxPath)
        if (!sessionId.isNullOrBlank() && isSessionScopedPath(linuxPath)) {
            return resolveSessionPath(File(hostSessions), sessionId, linuxPath)
        }
        return resolveHostPath(linuxPath)
    }

    /**
     * Resolve a guest path without returning a path that can later be reopened
     * through a raced symlink. The returned root is trusted; every component
     * below it must be opened with SecureDirectoryStream/openat semantics.
     */
    internal suspend fun resolveSecureForFileAccess(
        sessionId: String?,
        linuxPath: String,
    ): SecureFilePath? {
        if (unsafePath(linuxPath)) return null
        if (isExternalMountPath(linuxPath)) return resolveExternalMountSecure(linuxPath)

        if (!sessionId.isNullOrBlank() && isSessionScopedPath(linuxPath)) {
            if (!isSafeSessionId(sessionId)) return null
            val normalized = if (linuxPath.startsWith('/')) linuxPath else "/workspace/$linuxPath"
            val match = sessionAliases
                .filter { normalized == it.first || normalized.startsWith(it.first + "/") }
                .maxByOrNull { it.first.length }
                ?: return null
            val rest = normalized.removePrefix(match.first).removePrefix("/")
            val relative = secureComponents(rest) ?: return null
            return SecureFilePath(
                root = File(hostSessions).absoluteFile,
                components = listOf(sessionId, match.second) + relative,
            )
        }

        resolveSecureGuest(linuxPath)?.let { return it }
        val sorted = bindMounts.keys.sortedByDescending { it.length }
        for (mount in sorted) {
            if (linuxPath == mount || linuxPath.startsWith("$mount/")) {
                val hostBase = bindMounts[mount] ?: continue
                val relative = secureComponents(linuxPath.removePrefix(mount).removePrefix("/"))
                    ?: return null
                return SecureFilePath(File(hostBase).absoluteFile, relative)
            }
        }
        if (!linuxPath.startsWith('/')) {
            return resolveSecureGuest("/workspace/$linuxPath")
        }
        return null
    }

    suspend fun externalMountRoot(name: String): File? {
        if (name.isBlank() || name == "." || name == ".." || name.contains('/')) return null
        return resolveExternalMount("/var/minis/mounts/$name")
    }

    fun isExternalMountPath(path: String): Boolean =
        path == "/var/minis/mounts" || path.startsWith("/var/minis/mounts/")

    fun isExternalMountWritable(path: String): Boolean {
        if (!path.startsWith("/var/minis/mounts/")) return false
        val ctx = appContext ?: return false
        val name = path.removePrefix("/var/minis/mounts/").substringBefore('/')
        val entry = RuntimePathRegistry.mountedFoldersStore?.entries?.value
            ?.firstOrNull { it.name == name && it.isActive }
            ?: return false
        val uri = Uri.parse(entry.treeUri)
        val permission = ctx.contentResolver.persistedUriPermissions
            .firstOrNull { it.uri == uri }
            ?: return false
        return entry.effectiveWritable && permission.isReadPermission && permission.isWritePermission
    }

    private suspend fun resolveExternalMount(linuxPath: String): File? {
        if (!linuxPath.startsWith("/var/minis/mounts/")) return null
        val ctx = appContext ?: return null
        val rest = linuxPath.removePrefix("/var/minis/mounts/")
        val name = rest.substringBefore('/')
        if (!isSafeComponent(name)) return null
        val entry = RuntimePathRegistry.mountedFoldersStore?.entries?.value
            ?.firstOrNull { it.name == name && it.isActive }
            ?: return null
        val store = RuntimePathRegistry.mountedFoldersStore ?: return null
        val uri = Uri.parse(entry.treeUri)
        try {
            store.validateMountEntries(listOf(entry))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        val rootPath = store.resolvePosixPath(uri, ctx) ?: return null
        return childOf(rootPath, rest.substringAfter('/', ""))
    }

    private suspend fun resolveExternalMountSecure(linuxPath: String): SecureFilePath? {
        if (!linuxPath.startsWith("/var/minis/mounts/")) return null
        val ctx = appContext ?: return null
        val rest = linuxPath.removePrefix("/var/minis/mounts/")
        val name = rest.substringBefore('/')
        if (!isSafeComponent(name)) return null
        val entry = RuntimePathRegistry.mountedFoldersStore?.entries?.value
            ?.firstOrNull { it.name == name && it.isActive }
            ?: return null
        val store = RuntimePathRegistry.mountedFoldersStore ?: return null
        val uri = Uri.parse(entry.treeUri)
        try {
            store.validateMountEntries(listOf(entry))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        val rootPath = store.resolvePosixPath(uri, ctx) ?: return null
        val relative = secureComponents(rest.substringAfter('/', "")) ?: return null
        return SecureFilePath(File(rootPath).absoluteFile, relative)
    }

    private fun resolveSecureGuest(linuxPath: String): SecureFilePath? {
        val match = aliases
            .filter { linuxPath == it.first || linuxPath.startsWith(it.first + "/") }
            .maxByOrNull { it.first.length }
            ?: return null
        val relative = secureComponents(linuxPath.removePrefix(match.first).removePrefix("/"))
            ?: return null
        return SecureFilePath(File(match.second()).absoluteFile, relative)
    }

    private fun secureComponents(rest: String): List<String>? {
        if (rest.isEmpty()) return emptyList()
        val parts = rest.split('/')
        if (parts.lastOrNull() == "") {
            if (parts.dropLast(1).any { it.isEmpty() }) return null
            return parts.dropLast(1).map { component ->
                if (!isSafeComponent(component)) return null
                component
            }
        }
        if (parts.any { !isSafeComponent(it) }) return null
        return parts
    }

    private fun isSafeComponent(component: String): Boolean =
        component.isNotEmpty() &&
            component != "." &&
            component != ".." &&
            !component.contains('/') &&
            !component.contains('\\') &&
            !component.contains('\u0000')

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
        if (!ensureRealDirectory(sessionsRoot)) return null

        val rawSession = File(sessionsRoot, sessionId)
        if (!ensureRealDirectory(rawSession)) return null
        val session = childOf(sessionsRoot.absolutePath, sessionId) ?: return null
        if (session.canonicalFile != rawSession.canonicalFile) return null

        val namedDirs = listOf("workspace", "attachments", "offloads", "browser")
        for (subdir in namedDirs) {
            if (!ensureRealDirectory(File(session, subdir))) return null
        }

        // Bind targets under /workspace must be real directories. A guest-created
        // symlink here would otherwise be followed by Root mount --bind before
        // privilege drop on the next shell launch.
        val workspace = File(session, "workspace")
        for (subdir in listOf("attachments", "offloads", "browser")) {
            if (!ensureRealDirectory(File(workspace, subdir))) return null
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

    fun resolveSessionHostPath(sessionId: String, linuxPath: String, context: Context): File? {
        if (appContext == null) initialize(context)
        return resolveSessionHostPath(sessionId, linuxPath)
    }

    fun resolveSessionHostPath(sessionId: String, linuxPath: String): File? =
        if (isSessionScopedPath(linuxPath)) {
            resolveSessionPath(File(hostSessions), sessionId, linuxPath)
        } else {
            resolveHostPath(linuxPath)
        }

    fun deleteSession(context: Context, sessionId: String): Boolean {
        if (appContext == null) initialize(context)
        return deleteSessionAt(File(hostSessions), sessionId)
    }

    internal fun deleteSessionAt(sessionsRoot: File, sessionId: String): Boolean {
        if (!isSafeSessionId(sessionId)) return false
        if (SafeFileTree.isSymbolicLink(sessionsRoot)) return false
        if (!SafeFileTree.existsNoFollow(sessionsRoot)) return true
        if (!sessionsRoot.isDirectory) return false
        val target = File(sessionsRoot, sessionId)
        return !SafeFileTree.existsNoFollow(target) || SafeFileTree.deleteRecursively(target)
    }

    private fun ensureRealDirectory(directory: File): Boolean {
        val path = try {
            directory.toPath().toAbsolutePath().normalize()
        } catch (_: Exception) {
            return false
        }

        // Android app UIDs may traverse their own filesDir but cannot open
        // the global /data directory as a directory fd. Find the nearest
        // existing, non-symlink ancestor and let the native helper walk only
        // the components below that trusted anchor.
        var anchor = path
        while (!Files.exists(anchor, LinkOption.NOFOLLOW_LINKS)) {
            anchor = anchor.parent ?: return false
        }
        if (Files.isSymbolicLink(anchor) ||
            !Files.isDirectory(anchor, LinkOption.NOFOLLOW_LINKS)
        ) {
            return false
        }
        if (path == anchor) return true
        val components = try {
            anchor.relativize(path).map { it.toString() }
        } catch (_: Exception) {
            return false
        }

        // Android's java.nio Files implementation does not consistently
        // support the no-follow directory operations used below on all API
        // levels/OEM builds. The bundled openat(O_NOFOLLOW) helper is the
        // production path; plain JVM tests use the equivalent NIO fallback.
        val nativeResult = try {
            SecureFileOps.ensureDirectories(anchor.toString(), components)
        } catch (error: UnsatisfiedLinkError) {
            if (isAndroidRuntime()) return false
            null
        } catch (error: NoClassDefFoundError) {
            if (isAndroidRuntime()) return false
            null
        }
        if (nativeResult != null) {
            if (nativeResult != 0) {
                Log.w("UbuntuPaths", "secure directory creation failed for $path: errno=${-nativeResult}")
            }
            return nativeResult == 0
        }

        var current = anchor
        for (component in components) {
            current = current.resolve(component)
            if (Files.isSymbolicLink(current)) return false
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.createDirectory(current)
                } catch (_: FileAlreadyExistsException) {
                    // Re-check below with NOFOLLOW_LINKS after a creator won
                    // the race.
                } catch (_: Exception) {
                    return false
                }
            }
            if (Files.isSymbolicLink(current) ||
                !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)
            ) {
                return false
            }
        }
        return true
    }

    private fun isAndroidRuntime(): Boolean =
        System.getProperty("java.vm.name")?.let { name ->
            name.contains("Dalvik", ignoreCase = true) || name.contains("ART", ignoreCase = true)
        } == true

    private fun isSessionScopedPath(linuxPath: String): Boolean {
        if (!linuxPath.startsWith('/')) return true
        return sessionAliases.any { linuxPath == it.first || linuxPath.startsWith(it.first + "/") }
    }

    internal fun childOf(base: String, rest: String): File? = try {
        val root = File(base).canonicalFile
        val target = if (rest.isEmpty()) root else File(root, rest).canonicalFile
        if (target.path == root.path || target.path.startsWith(root.path + File.separator)) target else null
    } catch (_: Exception) {
        null
    }

    private fun unsafePath(path: String): Boolean =
        path.isEmpty() || path.contains('\u0000') || path.split('/').any { it == ".." }
}
