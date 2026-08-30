package io.github.slackerllc.minis.runtime.ubuntu

import java.io.File

/**
 * Android's direct view of the Issue #50 persistent storage contract.
 *
 * Production paths are always rooted at `/data/adb/minis`. App-private
 * `filesDir` locations are migration inputs only and must never become a second
 * active source of truth.
 */
internal object AppPersistentPaths {
    val workspace: File = File(UbuntuPaths.HOST_WORKSPACE)
    val sessions: File = File(UbuntuPaths.HOST_SESSIONS)
    val memory: File = File(UbuntuPaths.HOST_MEMORY)
    val skills: File = File(UbuntuPaths.HOST_SKILLS)
    val shared: File = File(UbuntuPaths.HOST_SHARED)
    val home: File = File(UbuntuPaths.HOST_HOME)

    internal data class Layout(
        val workspace: File,
        val sessions: File,
        val memory: File,
        val skills: File,
        val shared: File,
        val home: File,
    )

    /** Test-only root factory; production callers use the fixed fields above. */
    internal fun at(root: File): Layout = Layout(
        workspace = File(root, "workspace"),
        sessions = File(root, "sessions"),
        memory = File(root, "memory"),
        skills = File(root, "skills"),
        shared = File(root, "shared"),
        home = File(root, "home"),
    )

    /**
     * Compatibility for repository constructors that still receive an old
     * `<filesDir>/minis-global/*` path. Android app-private paths are migration
     * inputs only; arbitrary test paths remain injectable.
     */
    internal fun persistentForRepository(requested: File, leaf: String): File {
        val normalized = requested.absolutePath.replace('\\', '/').trimEnd('/')
        val isLegacy = normalized.endsWith("/minis-global/$leaf")
        val isAndroidPrivate = normalized.startsWith("/data/user/") ||
            normalized.startsWith("/data/user_de/") ||
            normalized.startsWith("/data/data/")
        if (!isLegacy || !isAndroidPrivate) return requested
        return when (leaf) {
            "memory" -> memory
            "skills" -> skills
            "shared" -> shared
            "home" -> home
            else -> requested
        }
    }

    internal fun memoryForRepository(requested: File): File =
        persistentForRepository(requested, "memory")
}
