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
 * Store for user-mounted external folders selected through Android's Storage
 * Access Framework (SAF).
 *
 * The persisted tree URI remembers the user's selection and grant. For the
 * current Ubuntu/minisd runtime, on-device ExternalStorageProvider trees are
 * additionally resolved to raw POSIX host paths. Those paths are bind-mounted
 * by minisd into its mount namespace at `/var/minis/mounts/<name>`.
 *
 * A SAF grant and raw `/storage/...` access are deliberately treated as two
 * separate capabilities. The store fails closed when Android scoped-storage
 * policy prevents the app from reading the raw path that minisd would bind.
 *
 * Persistence: `filesDir/minis-config/mounted-folders.json`. The path is
 * intentionally outside `minis-global/` so it can't leak into the
 * DocumentsProvider-exposed tree.
 */
class MountedFoldersStore(private val context: Context) {

    @Serializable
    data class Entry(
        val id: String = UUID.randomUUID().toString(),
        var name: String,
        val sourceDisplayName: String,
        val treeUri: String,
        val createdAt: Long = System.currentTimeMillis(),
        var isWritable: Boolean = true,
        var userAllowWrite: Boolean = true,
        /**
         * Cached POSIX host path resolved from the SAF tree URI (e.g.
         * `/storage/emulated/0/Documents/Vault`). null when the URI
         * came from a non-externalstorage provider or resolution failed —
         * UI / coordinator should treat such entries as inactive.
         */
        var resolvedHostPath: String? = null,
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

    /**
     * Fired after every CRUD/access-state change that changes the effective
     * runtime mount snapshot. MinisApp wires this to
     * `RuntimePathRegistry.applyMountedFoldersSnapshot`, so a later guest
     * execution receives the current minisd bind-mount inputs.
     */
    var onChange: (() -> Unit)? = null

    init {
        loadFromDisk()
    }

    /**
     * Persist a new tree URI as a mount. Caller is responsible for having
     * already called `contentResolver.takePersistableUriPermission(uri, …)`
     * — typically via the SAF picker result in [SafMountHelper.handlePickerResult].
     *
     * The current runtime needs a readable raw POSIX path because minisd binds
     * that host path into the guest. A persisted SAF grant alone is therefore
     * not sufficient. Returns null when raw-path access is unavailable, the
     * URI cannot map to on-device storage, or normal name/cap checks fail.
     */
    suspend fun add(
        treeUri: Uri,
        customName: String,
        userAllowWrite: Boolean = true,
    ): Entry? = mutex.withLock {
        val name = sanitizeName(customName).takeIf { it.isNotEmpty() } ?: return@withLock null
        if (_entries.value.any { it.name.equals(name, ignoreCase = true) }) return@withLock null
        if (_entries.value.size >= MAX_MOUNTS) return@withLock null

        val storageAccess = ExternalStorageAccessPolicy.current(context)
        if (!storageAccess.rawPathReadable) {
            AppLogger.warning(
                TAG,
                "add: raw external-storage path unavailable blocker=${storageAccess.blocker}",
            )
            return@withLock null
        }

        val resolvedHostPath = resolvePosixPath(treeUri, context) ?: run {
            AppLogger.warning(TAG, "add: rejected non-resolvable/inaccessible URI $treeUri")
            return@withLock null
        }

        val sourceDisplayName = DocumentsContract.getTreeDocumentId(treeUri)
            .substringAfterLast(':', treeUri.lastPathSegment.orEmpty())
            .ifEmpty { name }
        // The write probe targets the same raw host path minisd will bind. A
        // writable SAF grant does not imply java.io.File/raw-path writability.
        val probedWritable = storageAccess.rawPathWritable && probeWritable(resolvedHostPath)
        val entry = Entry(
            name = name,
            sourceDisplayName = sourceDisplayName,
            treeUri = treeUri.toString(),
            isWritable = probedWritable,
            userAllowWrite = userAllowWrite,
            resolvedHostPath = resolvedHostPath,
        )
        _entries.value = _entries.value + entry
        saveToDisk(_entries.value)
        onChange?.invoke()
        AppLogger.info(
            TAG,
            "add: name=$name host=$resolvedHostPath writable=$probedWritable ${storageDiag(context)}",
        )
        entry
    }

    /** Current raw-path access state used by UI/diagnostics. */
    internal fun rawPathAccess(): ExternalStorageAccessPolicy.Access =
        ExternalStorageAccessPolicy.current(context)

    /**
     * Entries safe to hand to minisd as bind sources right now.
     *
     * Permission revocation, scoped-storage changes, unmounted removable media,
     * and raw-path readdir denial all remove an entry from the runtime snapshot
     * instead of producing an apparently mounted but empty guest directory.
     */
    internal fun runtimeBindableEntries(): List<Entry> {
        val access = rawPathAccess()
        if (!access.rawPathReadable) {
            AppLogger.warning(
                TAG,
                "runtime bind snapshot blocked: ${access.blocker}; no raw paths exported",
            )
            return emptyList()
        }
        return _entries.value.filter { entry ->
            val host = entry.resolvedHostPath ?: return@filter false
            val dir = File(host)
            val readable = dir.isDirectory && dir.list() != null
            if (!readable) {
                AppLogger.warning(TAG, "runtime bind snapshot skipped unreadable path: $host")
            }
            readable
        }
    }

    /** One-line storage capability diagnostic for mount logs. */
    private fun storageDiag(context: Context): String {
        val access = ExternalStorageAccessPolicy.current(context)
        return "sdk=${Build.VERSION.SDK_INT} rawRead=${access.rawPathReadable} " +
            "rawWrite=${access.rawPathWritable} blocker=${access.blocker}"
    }

    suspend fun remove(id: String): Boolean = mutex.withLock {
        val before = _entries.value
        val after = before.filterNot { it.id == id }
        if (after.size == before.size) return@withLock false
        // Release the persisted URI grant so the system stops listing
        // us under "apps with access" and the user can re-pick later.
        before.firstOrNull { it.id == id }?.let { e ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(e.treeUri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        _entries.value = after
        saveToDisk(after)
        onChange?.invoke()
        true
    }

    suspend fun rename(id: String, newName: String): Boolean = mutex.withLock {
        val trimmed = sanitizeName(newName).takeIf { it.isNotEmpty() } ?: return@withLock false
        if (_entries.value.any { it.id != id && it.name.equals(trimmed, ignoreCase = true) }) {
            return@withLock false
        }
        _entries.value = _entries.value.map { e ->
            if (e.id == id) e.copy(name = trimmed) else e
        }
        saveToDisk(_entries.value)
        onChange?.invoke()
        true
    }

    suspend fun setUserAllowWrite(id: String, allow: Boolean): Boolean = mutex.withLock {
        var changed = false
        _entries.value = _entries.value.map { e ->
            if (e.id == id && e.userAllowWrite != allow) {
                changed = true
                e.copy(userAllowWrite = allow)
            } else e
        }
        if (changed) {
            saveToDisk(_entries.value)
            onChange?.invoke()
        }
        changed
    }

    /**
     * Re-probe OS-level writability against the actual host path. Called
     * on foreground resume so changes (user revoked permission, removed
     * the folder, removable storage unmounted, …) propagate into the UI
     * and the coordinator's bind specs. Entries whose `resolvedHostPath`
     * is null stay marked non-writable.
     */
    suspend fun refreshWritability() = mutex.withLock {
        val before = _entries.value
        val access = rawPathAccess()
        val after = before.map { e ->
            val probed = if (access.rawPathReadable && access.rawPathWritable) {
                e.resolvedHostPath?.let { probeWritable(it) } ?: false
            } else {
                false
            }
            if (probed != e.isWritable) e.copy(isWritable = probed) else e
        }
        if (after != before) {
            _entries.value = after
            saveToDisk(after)
            onChange?.invoke()
        }
    }

    /**
     * Decode an ExternalStorageProvider SAF tree URI into the POSIX host path
     * used as a minisd bind-mount source.
     *
     * Only `com.android.externalstorage.documents` can supply the volume/path
     * identity required by this design. Cloud providers have no stable host
     * filesystem path and are rejected.
     *
     * Returns null on a non-externalstorage authority, unknown volume, missing
     * directory, or a raw path whose directory entries cannot be read.
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
            val sep = docId.indexOf(':')
            val volume = if (sep < 0) docId else docId.substring(0, sep)
            val relPath = if (sep < 0) "" else docId.substring(sep + 1)

            val volumeRoot = resolveVolumeRoot(context, volume) ?: run {
                AppLogger.warning(TAG, "resolvePosixPath: unknown volume=$volume in docId=$docId")
                return@withContext null
            }
            val full = if (relPath.isEmpty()) File(volumeRoot)
                else File(volumeRoot, relPath)
            if (!full.exists() || !full.isDirectory) {
                AppLogger.warning(TAG, "resolvePosixPath: path missing or not dir: ${full.absolutePath}")
                return@withContext null
            }
            val children = full.list()
            if (children == null) {
                AppLogger.warning(
                    TAG,
                    "resolvePosixPath: ${full.absolutePath} raw readdir denied by storage policy",
                )
                return@withContext null
            }
            AppLogger.info(TAG, "resolvePosixPath: ${full.absolutePath} ok childCount=${children.size}")
            full.absolutePath
        }

    /**
     * Try to create + delete a hidden probe file under [hostPath]. This probes
     * the same raw filesystem path minisd will expose to the guest, so scoped
     * storage restrictions or revoked access become an honest read-only state.
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