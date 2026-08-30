package com.openminis.app.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment

/**
 * Access contract for external folders that are passed to minisd as raw POSIX
 * bind-mount sources.
 *
 * A persisted SAF tree grant is necessary to remember the user's selection,
 * but it does not by itself grant arbitrary java.io/File access to the
 * corresponding `/storage/...` path. The current runtime resolves an
 * ExternalStorageProvider tree to a host path and asks minisd to bind that
 * path into its mount namespace, so raw-path access is a separate capability.
 */
internal object ExternalStorageAccessPolicy {
    enum class Blocker {
        NONE,
        ALL_FILES_ACCESS_REQUIRED,
        LEGACY_READ_PERMISSION_REQUIRED,
        LEGACY_WRITE_PERMISSION_REQUIRED,
        LEGACY_STORAGE_VIEW_REQUIRED,
    }

    data class Access(
        val rawPathReadable: Boolean,
        val rawPathWritable: Boolean,
        val blocker: Blocker,
    )

    fun evaluate(
        sdkInt: Int,
        allFilesAccess: Boolean,
        readGranted: Boolean,
        writeGranted: Boolean,
        legacyStorageView: Boolean,
    ): Access {
        if (sdkInt >= Build.VERSION_CODES.R) {
            return if (allFilesAccess) {
                Access(true, true, Blocker.NONE)
            } else {
                Access(false, false, Blocker.ALL_FILES_ACCESS_REQUIRED)
            }
        }

        if (sdkInt == Build.VERSION_CODES.Q && !legacyStorageView) {
            return Access(false, false, Blocker.LEGACY_STORAGE_VIEW_REQUIRED)
        }
        if (!readGranted) {
            return Access(false, false, Blocker.LEGACY_READ_PERMISSION_REQUIRED)
        }
        if (!writeGranted) {
            return Access(true, false, Blocker.LEGACY_WRITE_PERMISSION_REQUIRED)
        }
        return Access(true, true, Blocker.NONE)
    }

    fun current(context: Context): Access {
        val sdk = Build.VERSION.SDK_INT
        val allFiles = sdk >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
        val read = context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        val write = context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        val legacyView = if (sdk == Build.VERSION_CODES.Q) {
            Environment.isExternalStorageLegacy()
        } else {
            true
        }
        return evaluate(
            sdkInt = sdk,
            allFilesAccess = allFiles,
            readGranted = read,
            writeGranted = write,
            legacyStorageView = legacyView,
        )
    }
}