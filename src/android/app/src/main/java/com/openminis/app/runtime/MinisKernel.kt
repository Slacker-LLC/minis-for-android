package com.openminis.app.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.util.Log
import com.openminis.app.data.FileMentionIndex
import com.openminis.app.data.MountedFoldersStore
import java.io.File
import java.util.TimeZone
import kotlin.math.abs

/**
 * P2: PRoot removed. This object is the Minis sandbox path/mount registry:
 * guest `/workspace` ↔ App filesDir workspaces, SAF external mounts, and the
 * POSIX TZ helper. No proot process management; execution goes through
 * [com.openminis.app.runtime.ubuntu.UbuntuRuntime] / minisd.
 */
object MinisKernel {

    private const val TAG = "MinisKernel"

    /** True once the sandbox (Ubuntu) has been initialized by the App. */
    var isBooted: Boolean = false
        private set

    internal fun markBooted() {
        isBooted = true
    }

    /** Bind mounts: Linux path -> host filesystem path. */
    val bindMounts: MutableMap<String, String>
        get() = com.openminis.app.runtime.ubuntu.UbuntuPaths.bindMounts

    /**
     * Seed mount registry + SAF snapshot. Idempotent; called from
     * MinisApp.onCreate. Ubuntu runtime start is handled by
     * [com.openminis.app.runtime.ubuntu.UbuntuRuntime.ensureReady].
     */
    fun boot(context: Context) {
        if (isBooted) return
        registerGlobalBindMounts(context)
        applyMountedFoldersSnapshot(context)
        markBooted()
        Log.i(TAG, "sandbox path registry seeded bindMounts=${bindMounts.size}")
    }

    fun addBindMount(linuxPath: String, hostPath: String) {
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

    /**
     * Reconcile [bindMounts] keys under `/var/minis/mounts/` with the
     * current snapshot of [mountedFoldersStore]. Idempotent.
     */
    fun applyMountedFoldersSnapshot(context: Context) {
        val store = mountedFoldersStore
        val desired: Map<String, String> = if (store == null) {
            emptyMap()
        } else {
            store.entries.value
                .mapNotNull { entry ->
                    val host = resolveTreeUriToHostPath(entry.treeUri, context) ?: return@mapNotNull null
                    "$MOUNTS_LINUX_PREFIX${entry.name}" to host
                }
                .toMap()
        }
        val stale = bindMounts.keys
            .filter { it.startsWith(MOUNTS_LINUX_PREFIX) }
            .filter { it !in desired }
        for (key in stale) bindMounts.remove(key)
        for ((linuxPath, hostPath) in desired) {
            bindMounts[linuxPath] = hostPath
        }
        val entryCount = store?.entries?.value?.size ?: 0
        Log.i(TAG, "applyMountedFoldersSnapshot: entries=$entryCount active=${desired.size} removed=${stale.size}")
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
        return !entry.effectiveWritable
    }

    /**
     * Snapshot of mount roots for the @-mention index. Skips entries
     * whose tree URI doesn't resolve to a POSIX path on this device.
     */
    fun mountEntriesForIndex(context: Context): List<FileMentionIndex.MountEntry> {
        val store = mountedFoldersStore ?: return emptyList()
        return store.entries.value.mapNotNull { entry ->
            val host = resolveTreeUriToHostPath(entry.treeUri, context) ?: return@mapNotNull null
            FileMentionIndex.MountEntry(name = entry.name, root = File(host))
        }
    }

    /**
     * Decode a SAF tree URI into an absolute POSIX path on the host
     * filesystem. Returns null for non-externalstorage providers,
     * unresolvable volumes, or paths not readable by the app uid.
     */
    private fun resolveTreeUriToHostPath(treeUriString: String, context: Context): String? = try {
        val treeUri = Uri.parse(treeUriString)
        if (treeUri.authority != "com.android.externalstorage.documents") {
            null
        } else {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val parts = docId.split(':', limit = 2)
            val volumeId = parts.getOrNull(0).orEmpty()
            val relPath = parts.getOrNull(1).orEmpty()
            val volumeRoot = resolveVolumeRoot(volumeId, context)
            if (volumeRoot == null) {
                Log.w(TAG, "resolveTreeUriToHostPath: unknown volume '$volumeId' for $treeUriString")
                null
            } else {
                val candidate = if (relPath.isEmpty()) File(volumeRoot) else File(volumeRoot, relPath)
                if (candidate.exists() && candidate.canRead()) {
                    candidate.absolutePath
                } else {
                    Log.w(
                        TAG,
                        "resolveTreeUriToHostPath: host=${candidate.absolutePath} " +
                            "exists=${candidate.exists()} canRead=${candidate.canRead()} — bind skipped",
                    )
                    null
                }
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "resolveTreeUriToHostPath failed for $treeUriString: ${t.message}")
        null
    }

    private fun resolveVolumeRoot(volumeId: String, context: Context): String? {
        if (volumeId.equals("primary", ignoreCase = true) || volumeId.isEmpty()) {
            return Environment.getExternalStorageDirectory()?.absolutePath
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager ?: return null
            for (volume in sm.storageVolumes) {
                if (volume.uuid?.equals(volumeId, ignoreCase = true) == true) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        return volume.directory?.absolutePath
                    }
                }
            }
        }
        return null
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
