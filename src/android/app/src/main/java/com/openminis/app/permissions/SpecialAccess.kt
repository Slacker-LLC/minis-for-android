package com.openminis.app.permissions

import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.net.toUri

/**
 * The "special app access" switches Android keeps apart from runtime permissions: each is an app-op the user
 * allows in a system page (Settings > Apps > Special access), not a dialog the app can raise. The app declares
 * what it can use; this is the one place that knows, for each, how to tell whether it is on, which system page
 * turns it on, and the app-op a rooted phone can set directly (see [RuntimePermissionGranter]).
 */
enum class SpecialAccess(
    /** The manifest permission behind the switch. */
    val permission: String,
    /** The `cmd appops` name that sets it, for the root grant. */
    val appOp: String,
    private val minSdk: Int,
) {
    INSTALL_APPS("android.permission.REQUEST_INSTALL_PACKAGES", "REQUEST_INSTALL_PACKAGES", 26),
    EXACT_ALARM("android.permission.SCHEDULE_EXACT_ALARM", "SCHEDULE_EXACT_ALARM", 31),
    USAGE_STATS("android.permission.PACKAGE_USAGE_STATS", "GET_USAGE_STATS", 26),
    FULL_SCREEN("android.permission.USE_FULL_SCREEN_INTENT", "USE_FULL_SCREEN_INTENT", 34),
    WRITE_SETTINGS("android.permission.WRITE_SETTINGS", "WRITE_SETTINGS", 26),
    ;

    /** Whether this Android version has the switch at all (older ones allow the thing without it). */
    val applies: Boolean get() = Build.VERSION.SDK_INT >= minSdk

    fun isGranted(context: Context): Boolean = when (this) {
        INSTALL_APPS -> context.packageManager.canRequestPackageInstalls()
        EXACT_ALARM -> Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        USAGE_STATS -> usageAccessAllowed(context)
        FULL_SCREEN -> Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        WRITE_SETTINGS -> Settings.System.canWrite(context)
    }

    /** The system page where the user turns this on for this app. */
    fun settingsIntent(context: Context): Intent {
        val pkg = "package:${context.packageName}".toUri()
        return when (this) {
            INSTALL_APPS -> Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkg)
            EXACT_ALARM -> Intent("android.settings.REQUEST_SCHEDULE_EXACT_ALARM", pkg)
            USAGE_STATS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, pkg)
            FULL_SCREEN -> Intent("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT", pkg)
            WRITE_SETTINGS -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkg)
        }
    }

    companion object {
        private const val USAGE_STATS_OP = "android:get_usage_stats"

        private fun usageAccessAllowed(context: Context): Boolean {
            val ops = context.getSystemService(AppOpsManager::class.java)
            val uid = Process.myUid()
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                ops.unsafeCheckOpNoThrow(USAGE_STATS_OP, uid, context.packageName)
            } else {
                ops.checkOpNoThrow(USAGE_STATS_OP, uid, context.packageName)
            }
            return mode == AppOpsManager.MODE_ALLOWED
        }

        fun forPermission(permission: String): SpecialAccess? = entries.firstOrNull { it.permission == permission }

        /** Whether background data is not blocked by Data Saver for this app (Data Saver off, or the app exempt). */
        fun dataSaverExempt(context: Context): Boolean {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            return cm.restrictBackgroundStatus != ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        }

        fun dataSaverIntent(context: Context): Intent =
            Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS, "package:${context.packageName}".toUri())
    }
}
