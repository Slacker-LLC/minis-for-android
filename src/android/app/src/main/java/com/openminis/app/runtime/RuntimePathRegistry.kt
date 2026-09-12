package com.openminis.app.runtime

import android.content.Context
import android.net.Uri
import android.util.Log
import com.openminis.app.data.FileMentionIndex
import com.openminis.app.data.MountedFoldersStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.TimeZone
import kotlin.math.abs

/**
 * Android-side registry for host/guest path resolution and bind-mount inputs.
 * Ubuntu process and mount-namespace lifecycle is App-owned by the direct runtime;
 * this object maintains the app-visible path registry, SAF mount state, and host
 * environment helpers consumed while constructing direct launches.
 */
object RuntimePathRegistry {

    private const val TAG = "RuntimePathRegistry"

    /**
     * Serializes a mounted-folder snapshot transition with construction of a
     * new Ubuntu namespace. The transition callback stops existing shells
     * before the Store publishes its candidate; without this gate a new launch
     * could observe the old snapshot in that small interval and outlive the
     * requested mount removal or read-only change.
     */
    private val mountMutationLock = Mutex()

    internal suspend fun <T> withMountMutationLock(block: suspend () -> T): T =
        mountMutationLock.withLock { block() }

    /** Bind mounts: Linux path -> host filesystem path. */
    val bindMounts: MutableMap<String, String>
        get() = com.openminis.app.runtime.ubuntu.UbuntuPaths.bindMounts

    fun resolveHostPath(linuxPath: String): File? =
        com.openminis.app.runtime.ubuntu.UbuntuPaths.resolveHostPath(linuxPath)

    fun resolveSessionHostPath(sessionId: String, linuxPath: String, context: Context): File? =
        com.openminis.app.runtime.ubuntu.UbuntuPaths.resolveSessionHostPath(sessionId, linuxPath, context)

    /**
     * Register the global (session-independent) Minis bind mounts so direct
     * file I/O tools (file_read, file_edit) can resolve
     * `/var/minis/{memory,skills,shared}/...`. Safe to call repeatedly.
     */
    fun registerGlobalBindMounts(context: Context) {
        val paths = com.openminis.app.runtime.ubuntu.UbuntuPaths
        val base = File(context.filesDir, "minis-global")
        listOf("memory", "skills", "shared", "mcp-servers").forEach { subdir ->
            val hostDir = when (subdir) {
                "memory" -> File(paths.hostMemory)
                "skills" -> File(paths.hostSkills)
                "shared" -> File(paths.hostShared)
                else -> File(base, subdir)
            }
            check(paths.ensureHostDirectory(hostDir)) {
                "cannot create safe global bind directory: ${hostDir.absolutePath}"
            }
            bindMounts["/var/minis/$subdir"] = hostDir.absolutePath
        }
    }

    // ── User-mounted external folders (T219) ──────────────────────────────
    private const val MOUNTS_LINUX_PREFIX = "/var/minis/mounts/"

    @Volatile
    var mountedFoldersStore: MountedFoldersStore? = null

    /**
     * True when [linuxPath] resolves under a known `/var/minis/mounts/<name>`
     * mount that is not currently authorized for writes.
     */
    fun isLinuxPathUnderReadOnlyMount(linuxPath: String): Boolean {
        if (!linuxPath.startsWith(MOUNTS_LINUX_PREFIX)) return false
        val store = mountedFoldersStore ?: return false
        val rest = linuxPath.removePrefix(MOUNTS_LINUX_PREFIX)
        val name = rest.substringBefore('/')
        if (name.isEmpty()) return false
        store.entries.value.firstOrNull { it.name == name } ?: return false
        return !com.openminis.app.runtime.ubuntu.UbuntuPaths.isExternalMountWritable(linuxPath)
    }

    /**
     * Build the transient host roots used only by the background @-mention
     * index. Host paths are re-derived from the current SAF grant on every
     * scan; they are never persisted or inserted into [bindMounts].
     */
    suspend fun mountEntriesForIndex(context: Context): List<FileMentionIndex.MountEntry> {
        val store = mountedFoldersStore ?: return emptyList()
        val out = ArrayList<FileMentionIndex.MountEntry>()
        for (entry in store.entries.value) {
            if (!entry.isActive) continue
            val uri = try {
                Uri.parse(entry.treeUri)
            } catch (_: Exception) {
                continue
            }
            val rootPath = try {
                store.validateMountEntries(listOf(entry))
                store.resolvePosixPath(uri, context)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Skipping unavailable @-mention mount ${entry.name}: ${error.message}")
                null
            } ?: continue
            out += FileMentionIndex.MountEntry(entry.name, File(rootPath))
        }
        return out
    }

    /**
     * Build a POSIX TZ string from the current system timezone.
     * UTC+8:00 → "LCL-8" (POSIX sign inverted, fixed name avoids musl
     * confusion with abbreviations like "GMT+8").
     */
    fun posixTz(): String {
        val offsetMs = TimeZone.getDefault().getOffset(System.currentTimeMillis())
        val secs = offsetMs / 1000L
        val hrs = (secs / 3600).toInt()
        val mins = ((abs(secs) % 3600) / 60).toInt()
        val posixHrs = -hrs
        val sign = if (posixHrs >= 0) "+" else "-"
        return if (mins != 0) {
            "LCL$sign${abs(posixHrs)}:${"%02d".format(mins)}"
        } else {
            "LCL$sign${abs(posixHrs)}"
        }
    }

}
