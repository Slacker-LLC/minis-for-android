package com.openminis.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Store for user-mounted external folders (SAF-picked trees). Mirrors iOS
 * `MountedFoldersManager` with the following deliberate differences:
 *
 *   - iOS uses `URL.bookmarkData` + `startAccessingSecurityScopedResource`;
 *     Android uses tree URIs + `takePersistableUriPermission`. The URI
 *     survives process death and reboots once persisted, so there's no
 *     "activation" step — access is always available while the permission
 *     grant is held.
 *   - The shell-level bind-mount at `/var/minis/mounts/<name>` is owned by
 *     minisd. This store sends only a URI-derived volume and segment identity
 *     in a complete `mount.reconcile` snapshot; it never persists a resolved
 *     host path as an authorization capability.
 *
 * Persistence: `filesDir/minis-config/mounted-folders.json`. The path is
 * intentionally outside `minis-global/` so it can't leak into the
 * DocumentsProvider-exposed tree.
 */
class MountedFoldersStore(private val context: Context) {

    data class MountIdentity(
        val volume: String,
        val pathSegments: List<String>,
    )

    @Serializable
    data class Entry(
        val id: String = UUID.randomUUID().toString(),
        var name: String,
        val sourceDisplayName: String,
        val treeUri: String,
        val createdAt: Long = System.currentTimeMillis(),
        var isWritable: Boolean = true,
        var userAllowWrite: Boolean = true,
        /** URI-derived identity used to build the next mount attestation. */
        val volume: String? = null,
        val pathSegments: List<String> = emptyList(),
        var isActive: Boolean = true,
    ) {
        /** Final effective writable = OS-level `isWritable` AND user intent. */
        val effectiveWritable: Boolean get() = isWritable && userAllowWrite
    }

    private val storeFile: File by lazy {
        File(context.filesDir, "minis-config/mounted-folders.json").apply {
            parentFile?.mkdirs()
        }
    }

    private val mutex = Mutex()
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    /** Fires after a snapshot has been accepted by minisd and persisted. */
    var onChange: (() -> Unit)? = null

    /**
     * Candidate snapshots are offered to minisd before they are persisted.
     * Returning false keeps the old snapshot, which makes delete/rename and
     * permission changes fail closed when replacement keeper creation fails.
     */
    var onSnapshotChange: (suspend (List<Entry>) -> Boolean)? = null

    init {
        loadFromDisk()
    }

    /**
     * Persist a new tree URI as a mount. Caller is responsible for having
     * already called `contentResolver.takePersistableUriPermission(uri, …)`
     * — typically via the SAF picker result in [SafMountHelper.handlePickerResult].
     *
     * Resolves the SAF tree URI transiently to a real POSIX path under
     * `/storage/emulated/0/...` (Option A — see T219 spec §1.3). Returns
     * null when:
     *   - the URI came from a non-externalstorage provider (Drive, Dropbox,
     *     etc. have no POSIX path PRoot can `-b` mount);
     *   - the resolved path doesn't exist or isn't readable by us;
     *   - the name is invalid / duplicate / cap reached.
     */
    suspend fun add(
        treeUri: Uri,
        customName: String,
        userAllowWrite: Boolean = true,
    ): Entry? = mutex.withLock {
        val name = sanitizeName(customName).takeIf { it.isNotEmpty() } ?: return@withLock null
        if (_entries.value.any { it.name.equals(name, ignoreCase = true) }) return@withLock null
        if (_entries.value.size >= MAX_MOUNTS) return@withLock null
        if (!hasPersistedRead(treeUri) || !hasRawReadCapability()) {
            AppLogger.warning(TAG, "add: rejected URI without read grant or raw read capability $treeUri")
            return@withLock null
        }

        val identity = mountIdentity(treeUri) ?: run {
            AppLogger.warning(TAG, "add: rejected invalid external-storage URI $treeUri")
            return@withLock null
        }
        val resolvedHostPath = resolvePosixPath(treeUri, context) ?: run {
            AppLogger.warning(TAG, "add: rejected non-resolvable URI $treeUri")
            return@withLock null
        }

        val sourceDisplayName = DocumentsContract.getTreeDocumentId(treeUri)
            .substringAfterLast(':', treeUri.lastPathSegment.orEmpty())
            .ifEmpty { name }
        // OS-level writability driven by the actual filesystem (Option A path
        // is what PRoot will use, not the SAF grant). isWritePermission can
        // be true while a per-package scoped-storage rule still rejects open(2).
        val probedWritable = hasRawWriteCapability() && probeWritable(resolvedHostPath)
        val entry = Entry(
            name = name,
            sourceDisplayName = sourceDisplayName,
            treeUri = treeUri.toString(),
            isWritable = probedWritable,
            userAllowWrite = userAllowWrite,
            volume = identity.volume,
            pathSegments = identity.pathSegments,
        )
        if (!commitSnapshot(_entries.value + entry)) return@withLock null
        AppLogger.info(
            TAG,
            "add: name=$name volume=${identity.volume} segments=${identity.pathSegments} " +
                "writable=$probedWritable ${storageDiag(context)}",
        )
        entry
    }

    /**
     * One-line storage-access diagnostic for the mount log. Distinguishes the
     * two reasons a folder can read but not write: on Android 11+ it's All Files
     * Access; on Android 10 it's whether WRITE_EXTERNAL_STORAGE was actually
     * granted at runtime (legacy opt-in alone is not enough) and whether the
     * process still holds the legacy storage view.
     */
    private fun storageDiag(context: Context): String {
        val sdk = Build.VERSION.SDK_INT
        return if (sdk >= Build.VERSION_CODES.R) {
            "sdk=$sdk allFilesAccess=${Environment.isExternalStorageManager()}"
        } else {
            val read = context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            val write = context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            // Issue #118: Environment.isExternalStorageLegacy() is API 29, but this
            // branch covers everything below R (30) — i.e. our whole 26..28 floor.
            // Same defect as RuntimePathRegistry.storageAccessDiag; both are reachable from
            // the boot path, so an unguarded call is a NoSuchMethodError that kills
            // the process in Application.onCreate. Below 29 scoped storage doesn't
            // exist, so the legacy view is unconditionally in effect.
            val legacyView = if (sdk >= Build.VERSION_CODES.Q) {
                Environment.isExternalStorageLegacy().toString()
            } else {
                "n/a(pre-Q)"
            }
            "sdk=$sdk readGranted=$read writeGranted=$write legacyView=$legacyView"
        }
    }

    suspend fun remove(id: String): Boolean = mutex.withLock {
        val before = _entries.value
        val after = before.filterNot { it.id == id }
        if (after.size == before.size) return@withLock false
        // Reconcile first. Only after the replacement is live do we remove
        // the persisted record and release the URI grant.
        if (!commitSnapshot(after)) return@withLock false
        before.firstOrNull { it.id == id }?.let { e ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(e.treeUri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        true
    }

    suspend fun rename(id: String, newName: String): Boolean = mutex.withLock {
        val trimmed = sanitizeName(newName).takeIf { it.isNotEmpty() } ?: return@withLock false
        if (_entries.value.any { it.id != id && it.name.equals(trimmed, ignoreCase = true) }) {
            return@withLock false
        }
        val after = _entries.value.map { e ->
            if (e.id == id) e.copy(name = trimmed) else e
        }
        commitSnapshot(after)
    }

    suspend fun setUserAllowWrite(id: String, allow: Boolean): Boolean = mutex.withLock {
        var changed = false
        val after = _entries.value.map { e ->
            if (e.id == id && e.userAllowWrite != allow) {
                changed = true
                e.copy(userAllowWrite = allow)
            } else e
        }
        if (!changed) return@withLock false
        commitSnapshot(after)
    }

    /**
     * Re-prove the URI grant, storage capability and source directory on
     * foreground resume. Invalid entries are marked inactive and the same
     * complete snapshot is reconciled immediately.
     */
    suspend fun refreshWritability() = mutex.withLock {
        val before = _entries.value
        val after = before.map { e ->
            val uri = Uri.parse(e.treeUri)
            val identity = mountIdentity(uri)
            val readable = identity != null && hasPersistedRead(uri) && hasRawReadCapability()
            val host = if (readable) resolvePosixPath(uri, context) else null
            val active = host != null
            val writable = active && hasRawWriteCapability() && probeWritable(host!!)
            if (writable != e.isWritable || active != e.isActive) {
                e.copy(isWritable = writable, isActive = active)
            } else e
        }
        if (after != before) {
            commitSnapshot(after)
        }
    }

    /** Build the only external-mount authorization payload accepted by minisd. */
    suspend fun buildMountSnapshot(entries: List<Entry> = _entries.value): org.json.JSONObject =
        withContext(Dispatchers.IO) {
            val mounts = org.json.JSONArray()
            entries.forEach { entry ->
                // An inactive entry is an explicit, persisted result of the
                // foreground re-proof. It is deliberately absent from the
                // active mount set; active entries must never be silently
                // omitted when their authorization cannot be re-derived.
                if (!entry.isActive) return@forEach
                val uri = Uri.parse(entry.treeUri)
                val identity = mountIdentity(uri)
                    ?: error("active mount ${entry.id} has invalid storage identity")
                check(hasPersistedRead(uri)) { "active mount ${entry.id} has no persisted read grant" }
                check(hasRawReadCapability()) { "active mount ${entry.id} has no raw read capability" }
                val host = resolvePosixPath(uri, context)
                    ?: error("active mount ${entry.id} source is unavailable")
                val writable = entry.userAllowWrite &&
                    hasPersistedWrite(uri) &&
                    hasRawWriteCapability() &&
                    probeWritable(host)
                mounts.put(org.json.JSONObject().apply {
                    put("id", entry.id)
                    put("name", entry.name)
                    put("volume", identity.volume)
                    put("path_segments", org.json.JSONArray(identity.pathSegments))
                    put("access", if (writable) "rw" else "ro")
                })
            }
            org.json.JSONObject().put("mounts", mounts)
        }

    /**
     * Decode a SAF tree URI into a transient POSIX validation path. The
     * returned path is never persisted or sent over the RPC boundary.
     *
     * Only accepts `com.android.externalstorage.documents` URIs — those
     * encode a `volume:relPath` document id where `volume` is either
     * `primary` (the device's internal shared storage) or a removable
     * storage UUID. Cloud providers (Drive, Dropbox, …) that return tree
     * URIs without a real filesystem mapping are rejected with a null
     * return so [add] can surface the "only on-device folders" error.
     *
     * Returns null on:
     *   - non-externalstorage authority,
     *   - unknown removable volume uuid,
     *   - resolved File doesn't exist / isn't a directory / unreadable.
     */
    suspend fun resolvePosixPath(treeUri: Uri, context: Context): String? =
        withContext(Dispatchers.IO) {
            val authority = treeUri.authority
            if (authority != EXTERNALSTORAGE_AUTHORITY) {
                AppLogger.warning(TAG, "resolvePosixPath: rejecting non-externalstorage authority=$authority")
                return@withContext null
            }
            val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
                ?: return@withContext null
            val identity = mountIdentity(treeUri) ?: return@withContext null
            val volume = identity.volume

            val volumeRoot = resolveVolumeRoot(context, volume) ?: run {
                AppLogger.warning(TAG, "resolvePosixPath: unknown volume=$volume in docId=$docId")
                return@withContext null
            }
            val full = identity.pathSegments.fold(File(volumeRoot)) { current, segment ->
                File(current, segment)
            }
            if (!full.exists() || !full.isDirectory) {
                AppLogger.warning(TAG, "resolvePosixPath: path missing or not dir: ${full.absolutePath}")
                return@withContext null
            }
            // Read probe: a resolvable dir the app can't actually readdir will
            // mount but show empty. Surface it now so the log explains the
            // eventual empty-folder report instead of leaving it silent.
            val children = full.list()
            if (children == null) {
                AppLogger.warning(
                    TAG,
                    "resolvePosixPath: ${full.absolutePath} canRead=${full.canRead()} but list()=null " +
                    "(readdir blocked by scoped storage) — mount is inactive until access is restored",
                )
                return@withContext null
            } else {
                AppLogger.info(TAG, "resolvePosixPath: ${full.absolutePath} ok childCount=${children.size}")
            }
            full.absolutePath
        }

    fun mountIdentity(treeUri: Uri): MountIdentity? {
        if (treeUri.authority != EXTERNALSTORAGE_AUTHORITY) return null
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
        val parts = docId.split(':', limit = 2)
        val volume = parts.firstOrNull().orEmpty()
        if (volume != "primary" && !isStorageUuid(volume)) return null
        val relative = parts.getOrNull(1).orEmpty()
        val segments = if (relative.isEmpty()) emptyList() else relative.split('/')
        if (segments.any { !isSafeSegment(it) }) return null
        return MountIdentity(volume = volume.lowercase(), pathSegments = segments)
    }

    private fun isStorageUuid(value: String): Boolean =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
            .matches(value)

    private fun isSafeSegment(value: String): Boolean =
        value.isNotEmpty() && value.length <= 255 && value != "." && value != ".." &&
            !value.contains('/') && !value.contains('\\') && !value.any(Char::isISOControl)

    private fun hasPersistedRead(uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    private fun hasPersistedWrite(uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }

    private fun hasRawReadCapability(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
            context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        else -> true
    }

    private fun hasRawWriteCapability(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
            context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        else -> true
    }

    private suspend fun commitSnapshot(after: List<Entry>): Boolean {
        if (onSnapshotChange?.invoke(after) == false) return false
        _entries.value = after
        saveToDisk(after)
        onChange?.invoke()
        return true
    }

    /**
     * Try to create + delete a hidden probe file under [hostPath]. Captures
     * the same reality PRoot will see — `open(O_WRONLY|O_CREAT)` against
     * the real filesystem — so scoped-storage restrictions or freshly
     * revoked permissions reflect honestly in the badge.
     */
    fun probeWritable(hostPath: String): Boolean {
        val dir = File(hostPath)
        if (!dir.isDirectory) return false
        val probe = File(dir, ".minis-probe-${UUID.randomUUID()}")
        return runCatching {
            probe.outputStream().use { it.write(0) }
            true
        }.onFailure { e ->
            AppLogger.warning(TAG, "probeWritable: $hostPath not writable: ${e.message}")
        }.getOrDefault(false).also {
            runCatching { probe.delete() }
        }
    }

    private fun resolveVolumeRoot(context: Context, volume: String): String? {
        if (volume.equals("primary", ignoreCase = true)) {
            return Environment.getExternalStorageDirectory()?.absolutePath
        }
        // Removable storage — match by uuid via StorageManager (API 24+ for
        // storageVolumes, but `directory` is API 30+. Fall back gracefully
        // on older devices by walking /storage/<uuid>).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            sm?.storageVolumes?.firstOrNull { it.uuid?.equals(volume, ignoreCase = true) == true }
                ?.directory?.absolutePath?.let { return it }
        }
        val fallback = File("/storage/$volume")
        return if (fallback.isDirectory) fallback.absolutePath else null
    }

    private fun loadFromDisk() {
        if (!storeFile.isFile) return
        runCatching {
            val text = storeFile.readText()
            val parsed = JSON.decodeFromString<List<Entry>>(text)
            _entries.value = parsed
        }
    }

    private suspend fun saveToDisk(list: List<Entry>) = withContext(Dispatchers.IO) {
        runCatching {
            storeFile.writeText(JSON.encodeToString(list))
        }
    }

    private fun sanitizeName(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed == "." || trimmed == "..") return ""
        if (trimmed.contains('/') || trimmed.contains('\u0000')) return ""
        return trimmed.take(64)
    }

    companion object {
        const val MAX_MOUNTS = 10
        private const val TAG = "MountedFolders"
        private const val EXTERNALSTORAGE_AUTHORITY = "com.android.externalstorage.documents"
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

/**
 * SAF helper — call [buildPickerIntent] from an `ActivityResultContract`,
 * then pipe the resulting Uri back through [handlePickerResult] to persist
 * the permission grant before adding it to [MountedFoldersStore].
 */
object SafMountHelper {
    fun buildPickerIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
    }

    /**
     * Take a persistable permission on the picked tree URI so subsequent
     * app launches can still access it without another picker round-trip.
     * Returns true on success.
     */
    fun handlePickerResult(context: Context, treeUri: Uri): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        true
    }.getOrDefault(false)

    /** Sugar over [DocumentsContract.getTreeDocumentId] for display purposes. */
    fun treeDisplayPath(uri: Uri): String =
        DocumentsContract.getTreeDocumentId(uri)
}
