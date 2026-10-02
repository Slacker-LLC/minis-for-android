package com.openminis.app.backup

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException

/**
 * The phone's own `Download/Minis Backups` folder as a backup destination.
 *
 * Every other destination is a network server, so a user with none could not make a backup at all, and the
 * only copy the app kept was inside its own sandbox, which is wiped with the app. A package written here is an
 * ordinary file in shared storage: it is visible in the system file manager and survives uninstalling and
 * reinstalling the app, which is what moving to a differently signed build needs. Restore it with *Choose
 * file*, from Downloads.
 *
 * Android 10+ writes through MediaStore, so no storage permission is needed. Android 8 and 9 write the file
 * directly and need the legacy write permission, which the manifest declares up to API 29.
 */
class BackupDeviceStorage(private val context: Context) {

    /** Where the package landed: the name it was stored under and its verified size. */
    data class Delivery(val displayName: String, val bytes: Long)

    /** Whether new backups are written here. On by default: it needs no setup and cannot fail on a bad network. */
    var enabled: Boolean
        get() = prefs().getBoolean(KEY_ENABLED, true)
        set(value) {
            prefs().edit().putBoolean(KEY_ENABLED, value).apply()
        }

    /**
     * Copy [packageFile] into the folder and verify the stored size. A partial copy is removed rather than left
     * behind, since a truncated `.minisbak` listed next to good ones is worse than none.
     */
    @Throws(IOException::class)
    fun deliver(packageFile: File, onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> }): Delivery {
        if (!isPackageName(packageFile.name)) throw IOException("Not a backup package name: ${packageFile.name}")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            deliverThroughMediaStore(packageFile, onProgress)
        } else {
            deliverToPublicDirectory(packageFile, onProgress)
        }
    }

    /** Remove a package this app put here. False when it is not there or is no longer ours to delete. */
    fun delete(packageName: String): Boolean {
        if (!isPackageName(packageName)) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            deleteThroughMediaStore(packageName)
        } else {
            File(publicDirectory(), packageName).takeIf { it.isFile }?.delete() ?: false
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun deleteThroughMediaStore(packageName: String): Boolean =
        context.contentResolver.delete(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf("${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/", packageName),
        ) > 0

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun deliverThroughMediaStore(packageFile: File, onProgress: (Long, Long) -> Unit): Delivery {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, packageFile.name)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("The system refused to create ${packageFile.name} in Downloads")
        try {
            resolver.openOutputStream(uri, "w").use { out ->
                if (out == null) throw IOException("Could not open ${packageFile.name} for writing")
                copy(packageFile, out, onProgress)
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            var storedName = packageFile.name
            var storedBytes = -1L
            resolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE),
                null,
                null,
                null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    storedName = c.getString(0) ?: storedName
                    storedBytes = c.getLong(1)
                }
            }
            if (storedBytes != packageFile.length()) {
                throw IOException("Stored $storedBytes bytes of ${packageFile.length()} for ${packageFile.name}")
            }
            return Delivery(storedName, storedBytes)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw if (e is IOException) e else IOException(e.message ?: e.javaClass.simpleName, e)
        }
    }

    @Suppress("DEPRECATION")
    private fun deliverToPublicDirectory(packageFile: File, onProgress: (Long, Long) -> Unit): Delivery {
        if (context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            throw IOException("Storage permission is needed to save backups to Downloads on this Android version")
        }
        val dir = publicDirectory().apply { mkdirs() }
        if (!dir.isDirectory) throw IOException("Could not create ${dir.path}")
        val target = File(dir, uniqueName(packageFile.name) { File(dir, it).exists() })
        try {
            target.outputStream().use { out -> copy(packageFile, out, onProgress) }
            if (target.length() != packageFile.length()) {
                throw IOException("Stored ${target.length()} bytes of ${packageFile.length()} for ${packageFile.name}")
            }
            return Delivery(target.name, target.length())
        } catch (e: Exception) {
            target.delete()
            throw if (e is IOException) e else IOException(e.message ?: e.javaClass.simpleName, e)
        }
    }

    @Suppress("DEPRECATION")
    private fun publicDirectory(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)

    private fun copy(source: File, out: java.io.OutputStream, onProgress: (Long, Long) -> Unit) {
        val total = source.length()
        var sent = 0L
        var reported = 0L
        source.inputStream().use { input ->
            val buffer = ByteArray(COPY_BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                sent += read
                if (sent - reported >= PROGRESS_STEP) {
                    reported = sent
                    onProgress(sent, total)
                }
            }
        }
        out.flush()
        onProgress(total, total)
    }

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    companion object {
        /** Folder under Downloads, as the file manager shows it. */
        const val FOLDER = "Minis Backups"

        /** Shown to the user, and recorded as the destination's name in Backup History. */
        const val NAME = "This device"

        /** Recorded as the destination's kind in Backup History, in place of a server backend such as `smb`. */
        const val KIND = "device"

        const val DISPLAY_PATH = "Download/$FOLDER"
        const val EXTENSION = ".minisbak"

        private const val MIME_TYPE = "application/octet-stream"
        private const val PREFS = "backup_ui"
        private const val KEY_ENABLED = "device_destination_enabled"
        private const val COPY_BUFFER = 64 * 1024
        private const val PROGRESS_STEP = 1L shl 20

        /**
         * A name this destination will write or delete: a plain `.minisbak` file name. Anything with a path
         * separator or a `..` is refused, so a crafted history record cannot point a delete elsewhere.
         */
        fun isPackageName(name: String): Boolean =
            name.length in (EXTENSION.length + 1)..200 &&
                name.endsWith(EXTENSION) &&
                !name.startsWith(".") &&
                '/' !in name && '\\' !in name && ".." !in name &&
                name.none { it.isISOControl() }

        /** [name], or `name (2).minisbak`, `name (3).minisbak`... when [exists] says the name is taken. */
        fun uniqueName(name: String, exists: (String) -> Boolean): String {
            if (!exists(name)) return name
            val stem = name.removeSuffix(EXTENSION)
            var n = 2
            while (exists("$stem ($n)$EXTENSION")) n++
            return "$stem ($n)$EXTENSION"
        }
    }
}
