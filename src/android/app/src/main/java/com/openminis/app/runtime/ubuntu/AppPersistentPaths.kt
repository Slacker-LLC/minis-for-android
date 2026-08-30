package com.openminis.app.runtime.ubuntu

import java.io.File

/**
 * Android's direct view of Issue #50 persistent storage.
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

    internal data class Layout(
        val workspace: File,
        val sessions: File,
        val memory: File,
        val skills: File,
        val shared: File,
    )

    /** Test-only root factory; production callers use the fixed fields above. */
    internal fun at(root: File): Layout = Layout(
        workspace = File(root, "workspace"),
        sessions = File(root, "sessions"),
        memory = File(root, "memory"),
        skills = File(root, "skills"),
        shared = File(root, "shared"),
    )

    /**
     * Compatibility for the one remaining production MemoryRepository call
     * that still passes `<filesDir>/minis-global/memory`. Test-injected paths
     * outside Android's app-private roots are left untouched.
     */
    internal fun memoryForRepository(requested: File): File {
        val normalized = requested.absolutePath.replace('\\', '/').trimEnd('/')
        val isLegacyMemory = normalized.endsWith("/minis-global/memory")
        val isAndroidPrivate = normalized.startsWith("/data/user/") ||
            normalized.startsWith("/data/user_de/") ||
            normalized.startsWith("/data/data/")
        return if (isLegacyMemory && isAndroidPrivate) memory else requested
    }
}
