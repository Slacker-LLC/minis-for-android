package com.openminis.app.sandbox.ubuntu

import android.content.Context
import java.io.File

/**
 * Q16 path contract: guest `/workspace` ↔ host App `filesDir/minis-workspace`
 * (host dirs live under the App data dir so the App's own File API can read
 * and write them — /data/adb is root-owned and not readable by the App).
 * minisd bind-mounts these dirs into the chroot at ubuntu.start.
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

    /** Called from MinisApp.onCreate once. Safe to call twice. */
    fun init(context: Context) {
        val base = File(context.filesDir, "minis")
        hostWorkspace = File(base, "workspace").absolutePath
        hostMemory = File(base, "memory").absolutePath
        hostSkills = File(base, "skills").absolutePath
        hostShared = File(base, "shared").absolutePath
        File(hostWorkspace, "attachments").mkdirs()
        File(hostWorkspace, "offloads").mkdirs()
        File(hostWorkspace, "browser").mkdirs()
        File(hostWorkspace, "sessions").mkdirs()
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
        return null
    }

    @Suppress("UNUSED_PARAMETER")
    fun resolveSessionHostPath(sessionId: String, linuxPath: String, context: Context): File? =
        resolveHostPath(linuxPath)
}
