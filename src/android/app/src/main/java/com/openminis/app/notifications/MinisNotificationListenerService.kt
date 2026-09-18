package com.openminis.app.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.openminis.app.data.repository.NotificationHistoryRepository

/**
 * [T-eta-notification-history] Reads the notification shade only while the device owner has
 * granted Notification Access to Minis.
 *
 * Ported from Eta `agent/device/AgentNotificationHistoryService.kt` (Mangi-11/Eta @
 * c15de97); attribution in THIRD_PARTY_LICENSES.md. Nothing is collected before the grant:
 * Android does not bind this service until the user enables it in system settings, and
 * [isAccessGranted] is what the tools check before promising anything.
 */
class MinisNotificationListenerService : NotificationListenerService() {

    private val repository by lazy { NotificationHistoryRepository(applicationContext) }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        record(sbn)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = this
        // Notifications posted while the grant was pending are still on screen; record them
        // so the history does not start empty after the user enables access.
        runCatching { activeNotifications?.forEach(::record) }
    }

    override fun onListenerDisconnected() {
        if (connected === this) connected = null
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        if (connected === this) connected = null
        super.onDestroy()
    }

    private fun record(sbn: StatusBarNotification) {
        val extras = sbn.notification?.extras ?: return
        repository.record(
            key = sbn.key,
            packageName = sbn.packageName,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            postedAt = sbn.postTime,
        )
    }

    companion object {
        @Volatile
        private var connected: MinisNotificationListenerService? = null

        /** One row of the live shade, already reduced to what the tools may report. */
        data class ShadeNotification(
            val packageName: String,
            val title: String?,
            val text: String?,
            val subText: String?,
            val postedAt: Long,
        )

        /** The live shade, or null when the listener is not bound. */
        fun currentShade(): List<ShadeNotification>? {
            val service = connected ?: return null
            val active = try {
                service.activeNotifications
            } catch (_: RuntimeException) {
                // The platform throws when the service is being torn down mid-read.
                return null
            } ?: return emptyList()
            return active.mapNotNull { sbn ->
                val extras = sbn.notification?.extras ?: return@mapNotNull null
                ShadeNotification(
                    packageName = sbn.packageName,
                    title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
                    text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
                    subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
                    postedAt = sbn.postTime,
                )
            }
        }

        /** True when the user granted Notification Access to this component. */
        fun isAccessGranted(context: Context): Boolean {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                return runCatching {
                    manager.isNotificationListenerAccessGranted(
                        ComponentName(context, MinisNotificationListenerService::class.java),
                    )
                }.getOrDefault(false)
            }
            // API 26 has no per-component grant query; the enabled-listener list is the only
            // honest signal there, and it is exactly what the system binds the service from.
            return NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)
        }
    }
}
