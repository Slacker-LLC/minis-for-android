package com.openminis.app.webapp

import android.content.Context
import com.openminis.app.data.db.WebAppShortcutEntity
import com.openminis.app.data.repository.WebAppShortcutRepository
import com.openminis.app.runtime.RuntimePathRegistry
import com.openminis.app.runtime.minisd.WorkspaceFileClient
import com.openminis.app.tools.ExternalMountAccess
import java.io.File

/**
 * T-pwa-1 (renamed Pwa → WebApp): resolve the stored
 * ([WebAppShortcutEntity.pathScope], [WebAppShortcutEntity.scopeContext],
 * [WebAppShortcutEntity.htmlPath]) triple back to a local File. Canonical
 * guest files are staged through minisd; returns
 * null if the file no longer exists — caller should surface a "source
 * missing" UI instead of crashing.
 *
 *   - session_attachment → `/var/minis/attachments/<htmlPath>` through minisd
 *     (with a legacy app-private fallback for already-persisted shortcuts)
 *   - shared             → staged through minisd
 *   - mount              → staged through the minisd broker
 */
object WebAppPathResolver {

    suspend fun resolve(context: Context, shortcut: WebAppShortcutEntity): File? {
        val file = when (shortcut.pathScope) {
            WebAppShortcutRepository.SCOPE_SESSION_ATTACHMENT -> resolveSession(context, shortcut)
            WebAppShortcutRepository.SCOPE_SHARED -> stageGuestFile(context, shortcut.htmlPath, null, shortcut.id)
            WebAppShortcutRepository.SCOPE_MOUNT -> stageGuestFile(
                context,
                shortcut.htmlPath,
                null,
                shortcut.id,
            )
            else -> null
        }
        return file?.takeIf { it.exists() && it.isFile }
    }

    /**
     * T-pwa-3: reverse-resolve a host file path to a `(pathScope,
     * scopeContext, linuxPath)` triple suitable for
     * [WebAppShortcutRepository.create]. Walks the
     * [RuntimePathRegistry.bindMounts] map looking for an entry whose host
     * directory is a prefix of [hostFile]; returns null if none matches
     * (caller should hide the "Add to Home Screen" menu item).
     *
     * Mapping rules:
     *  - `/var/minis/shared` bind  → `pathScope = "shared"`,  `scopeContext = null`
     *  - `/var/minis/mounts/<n>`   → `pathScope = "mount"`,   `scopeContext = "<n>"`
     *  - everything else (incl. memory/skills, per-session subdirs, rootfs) → null
     */
    fun inferScope(hostFile: File): Triple<String, String?, String>? {
        val hostAbs = hostFile.absolutePath
        // Longest host-prefix wins, mirroring resolveHostPath's longest-key match.
        val sorted = com.openminis.app.runtime.RuntimePathRegistry
            .bindMounts.entries.sortedByDescending { it.value.length }
        for ((linuxPrefix, hostBase) in sorted) {
            val baseNorm = hostBase.trimEnd('/')
            if (hostAbs == baseNorm || hostAbs.startsWith("$baseNorm/")) {
                val tail = hostAbs.removePrefix(baseNorm).removePrefix("/")
                val linuxPath = if (tail.isEmpty()) linuxPrefix else "$linuxPrefix/$tail"
                return when {
                    linuxPrefix == "/var/minis/shared" ->
                        Triple(WebAppShortcutRepository.SCOPE_SHARED, null, linuxPath)
                    linuxPrefix.startsWith("/var/minis/mounts/") -> {
                        val mountName = linuxPrefix.removePrefix("/var/minis/mounts/")
                            .substringBefore('/')
                        Triple(WebAppShortcutRepository.SCOPE_MOUNT, mountName, linuxPath)
                    }
                    else -> null  // memory/skills/etc — no WebApp support
                }
            }
        }
        return null
    }

    private suspend fun resolveSession(context: Context, shortcut: WebAppShortcutEntity): File? {
        val sessionId = shortcut.scopeContext ?: return null
        // Absolute guest paths are owned by the broker, not the Android host.
        if (shortcut.htmlPath.startsWith("/var/minis/")) {
            return stageGuestFile(context, shortcut.htmlPath, sessionId, shortcut.id)
        }
        // New relative shortcuts are stored under the canonical attachment
        // root. Keep the old app-private path only for persisted shortcuts
        // created before the canonical migration.
        val guestPath = "/var/minis/attachments/${shortcut.htmlPath}"
        stageGuestFile(context, guestPath, sessionId, shortcut.id)?.let { return it }
        val attachmentsDir = File(context.filesDir, "sessions/$sessionId/attachments")
        return File(attachmentsDir, shortcut.htmlPath)
    }

    private suspend fun stageGuestFile(
        context: Context,
        guestPath: String,
        sessionId: String?,
        shortcutId: String,
    ): File? {
        if (!isCanonicalGuestPath(guestPath) && !ExternalMountAccess.isPath(guestPath)) return null
        val name = guestPath.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        val id = shortcutId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val staged = File(File(context.cacheDir, "webapp"), "$id-$name")
        return runCatching {
            val brokerSession = if (ExternalMountAccess.isPath(guestPath)) null else sessionId.orEmpty()
            WorkspaceFileClient.readToFile(brokerSession, guestPath, staged)
            staged.takeIf { it.isFile }
        }.getOrNull()
    }

    private fun isCanonicalGuestPath(path: String): Boolean {
        val roots = listOf(
            "/var/minis",
            "/workspace",
            "/memory",
            "/skills",
            "/shared",
            "/home/minis",
        )
        return roots.any { path == it || path.startsWith("$it/") } &&
            path != "/var/minis/mounts" && !path.startsWith("/var/minis/mounts/")
    }
}
