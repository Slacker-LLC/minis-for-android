package com.openminis.app.accessibility

import android.content.Context
import android.content.pm.PackageInstaller
import android.os.Build
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.ShizukuManager

object RestrictedSettingsManager {
    private const val TAG = "RestrictedSettings"

    @Volatile
    private var cleared = false

    fun isRestricted(context: Context): Boolean {
        if (cleared) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        return try {
            val source = context.packageManager
                .getInstallSourceInfo(context.packageName)
                .packageSource
            val restricted = isRestrictedSource(source)
            if (restricted) {
                AppLogger.info(TAG, "install flagged restricted (packageSource=$source)")
            }
            restricted
        } catch (t: Throwable) {
            AppLogger.info(TAG, "install-source probe unavailable: ${t.javaClass.simpleName}")
            false
        }
    }

    internal fun isRestrictedSource(packageSource: Int): Boolean =
        packageSource == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE ||
            packageSource == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE

    suspend fun clearWithShizuku(context: Context): Boolean {
        if (!ShizukuManager.isReady()) {
            AppLogger.info(TAG, "clear skipped: Shizuku not ready")
            return false
        }
        val set = ShizukuManager.runProcess(
            arrayOf(
                "appops", "set", context.packageName,
                "ACCESS_RESTRICTED_SETTINGS", "allow",
            ),
        )
        if (set.exitCode != 0) {
            AppLogger.warning(TAG, "appops set failed: exit=${set.exitCode} ${set.combined}")
            return false
        }
        val get = ShizukuManager.runProcess(
            arrayOf("appops", "get", context.packageName, "ACCESS_RESTRICTED_SETTINGS"),
        )
        val ok = get.exitCode == 0 && get.combined.contains("allow")
        if (ok) cleared = true
        AppLogger.info(TAG, "cleared restricted settings via Shizuku; ok=$ok (${get.combined.trim()})")
        return ok
    }
}
