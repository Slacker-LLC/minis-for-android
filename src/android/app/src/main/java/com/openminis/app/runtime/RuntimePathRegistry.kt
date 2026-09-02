package com.openminis.app.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.openminis.app.data.FileMentionIndex
import com.openminis.app.data.MountedFoldersStore
import java.io.File
import java.util.TimeZone
import kotlin.math.abs

/**
 * Android-side registry for host/guest path resolution and bind-mount inputs.
 * Ubuntu process and mount-namespace lifecycle is owned by minisd; this object
 * only maintains the app-visible path registry, SAF mount snapshots, and host
 * environment helpers consumed while constructing runtime requests.
 */
object RuntimePathRegistry {

    private const val TAG = "RuntimePathRegistry"

    /** True once the Android-side path registry has been initialized. */
    var isInitialized: Boolean = false
        private set

    internal fun markInitialized() {
        isInitialized = true
    }

    /** Bind mounts: Linux path -> host filesystem path. */
    val bindMounts: MutableMap<String, String>
        get() = com.openminis.app.runtime.ubuntu.UbuntuPaths.bindMounts

    /**
     * Seed mount registry + SAF snapshot. Idempotent; called from
     * MinisApp.onCreate. Ubuntu runtime start is handled by
     * [com.openminis.app.runtime.ubuntu.UbuntuRuntime.ensureReady].
     */
    fun initialize(context: Context) {
        if (isInitialized) return
        registerGlobalBindMounts(context)
        applyMountedFoldersSnapshot(context)
        markInitialized()
        Log.i(TAG, "runtime path registry seeded bindMounts=${bindMounts.size}")
    }

    fun addBindMount(linuxPath: String, hostPath: String) {
        if (linuxPath == MOUNTS_LINUX_PREFIX.trimEnd('/') || linuxPath.startsWith(MOUNTS_LINUX_PREFIX)) {
            Log.w(TAG, "rejecting App-owned external bind mount for $linuxPath")
            return
        }
        bindMounts[linuxPath] = hostPath
    }

    fun resolveHostPath(linuxPath: String): File? =
        com.openminis.app.runtime.ubuntu.UbuntuPaths.resolveHostPath(linuxPath)

    fun resolveSessionHostPath(sessionId: String, linuxPath: String, context: Context): File? =
        com.openminis.app.runtime.ubuntu.UbuntuPaths.resolveSessionHostPath(sessionId, linuxPath, context)

    fun removeBindMount(linuxPath: String) {
        bindMounts.remove(linuxPath)
    }

    fun clearBindMounts() {
        bindMounts.clear()
    }

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
            }.also { it.mkdirs() }
            bindMounts["/var/minis/$subdir"] = hostDir.absolutePath
        }
    }

    // ── User-mounted external folders (T219) ──────────────────────────────
    private const val MOUNTS_LINUX_PREFIX = "/var/minis/mounts/"

    @Volatile
    var mountedFoldersStore: MountedFoldersStore? = null

    /** External mounts are broker-owned; this only clears the obsolete App bind map. */
    @Suppress("UNUSED_PARAMETER")
    fun applyMountedFoldersSnapshot(context: Context) {
        val stale = bindMounts.keys
            .filter { it.startsWith(MOUNTS_LINUX_PREFIX) }
        for (key in stale) bindMounts.remove(key)
        Log.i(TAG, "applyMountedFoldersSnapshot: broker-owned mounts; removedLegacy=${stale.size}")
    }

    /**
     * True when [linuxPath] resolves under a `/var/minis/mounts/<name>`
     * mount whose effective writability is false.
     */
    fun isLinuxPathUnderReadOnlyMount(linuxPath: String): Boolean {
        if (!linuxPath.startsWith(MOUNTS_LINUX_PREFIX)) return false
        val store = mountedFoldersStore ?: return false
        val rest = linuxPath.removePrefix(MOUNTS_LINUX_PREFIX)
        val name = rest.substringBefore('/')
        if (name.isEmpty()) return false
        val entry = store.entries.value.firstOrNull { it.name == name } ?: return false
        return !entry.isActive || !entry.effectiveWritable
    }

    /** External roots are indexed through broker listings, never host Files. */
    @Suppress("UNUSED_PARAMETER")
    fun mountEntriesForIndex(context: Context): List<FileMentionIndex.MountEntry> = emptyList()

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

    /**
     * Read the system HTTP proxy configuration. Returns all six proxy env
     * keys even when no proxy is set (empty values = direct connection).
     */
    fun systemProxyEnv(context: Context): Map<String, String> {
        val proxyUri = try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val info = cm?.defaultProxy
            if (info != null && info.host.isNotBlank() && info.port > 0) {
                "http://${info.host}:${info.port}"
            } else {
                ""
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read system proxy: ${t.message}")
            ""
        }
        val out = linkedMapOf<String, String>()
        for ((k, v) in proxyBlock(proxyUri)) out[k] = v
        return out
    }

    /** Six-key proxy block; empty values when no proxy configured. */
    private fun proxyBlock(uri: String): Map<String, String> {
        val keys = listOf(
            "http_proxy", "https_proxy",
            "HTTP_PROXY", "HTTPS_PROXY",
            "no_proxy", "NO_PROXY",
        )
        val noProxy = "localhost,127.0.0.1,::1"
        return keys.associateWith { key ->
            when (key) {
                "no_proxy", "NO_PROXY" -> if (uri.isNotEmpty()) noProxy else ""
                else -> uri
            }
        }
    }
}
