package io.github.slackerllc.minis.offload

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.github.slackerllc.minis.logging.AppLogger

/**
 * BroadcastReceiver that fires when an alarm or timer triggers.
 * Shows a high-priority notification with the alarm label.
 */
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AlarmReceiver"
        private const val NOTIFICATION_GROUP = "minis_alarm_group"
    }

    override fun onReceive(context: Context, intent: Intent) {
        // The boot branch does DB work (rescheduleAll) plus up to two
        // startForegroundService calls; the alarm branch reads/writes prefs
        // and posts a notification. BroadcastReceiver.onReceive runs on the
        // main thread with a ~10s budget — do the work on a background
        // thread via goAsync() so a slow device cannot ANR on boot or on
        // every alarm fire.
        val pending = goAsync()
        Thread {
            try {
                handle(context, intent)
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "alarm/boot handling failed: ${t.message}")
            } finally {
                pending.finish()
            }
        }.apply { name = "alarm-receiver" }.start()
    }

    private fun handle(context: Context, intent: Intent) {
        // BOOT_COMPLETED arrives with no extras; it just keeps the receiver
        // resident so the AlarmManager re-fires the persisted alarms after
        // a reboot. Nothing to clean up in that branch.
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            AppLogger.info(TAG, "BOOT_COMPLETED — alarm receiver resident")
            // [T-android-scheduled-tasks-design] Re-register every enabled
            // ScheduledTask with AlarmManager. The OS drops all pending
            // alarms on reboot, so persisted tasks would silently stop
            // firing without this. The legacy AlarmOffloadManager entries
            // are repeating alarms and DO survive reboot via the OS, so
            // we don't have to re-register them here.
            try {
                io.github.slackerllc.minis.scheduled.ScheduledTaskManager(context).rescheduleAll()
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "scheduled-task rescheduleAll failed: ${t.message}")
            }
            // The floating pet is a foreground overlay the user enabled; bring
            // it back on boot too (startIfEnabled is a no-op when disabled).
            // Wrapped: OEM ROMs may reject the background FGS start, and the
            // pet must never take the boot path down with it.
            try {
                io.github.slackerllc.minis.pet.PetBridge.startIfEnabled(context)
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "pet restore failed: ${t.message}")
            }
            return
        }

        val alarmId = intent.getStringExtra(AlarmOffloadManager.EXTRA_ALARM_ID) ?: "unknown"
        val label = intent.getStringExtra(AlarmOffloadManager.EXTRA_ALARM_LABEL) ?: "Alarm"

        AppLogger.debug(TAG, "Alarm triggered: id=$alarmId label=$label")

        // Drop the entry from prefs for ONCE alarms / timers so the next
        // `android-alarm list` call no longer surfaces them. DAILY/WEEKDAYS
        // are kept (the OS re-fires them).
        if (alarmId != "unknown") {
            try {
                AlarmOffloadManager(context).onAlarmFired(alarmId)
            } catch (e: Throwable) {
                AppLogger.warning(TAG, "onAlarmFired($alarmId) failed: ${e.message}")
            }
        }

        val notificationId = alarmId.hashCode() and 0x7FFFFFFF

        // Launch intent – open the app when the notification is tapped
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentPi = if (launchIntent != null) {
            PendingIntent.getActivity(
                context,
                notificationId,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } else {
            null
        }

        val notification = NotificationCompat.Builder(context, AlarmOffloadManager.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Minis Alarm")
            .setContentText(label)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setGroup(NOTIFICATION_GROUP)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .apply { if (contentPi != null) setContentIntent(contentPi) }
            .build()

        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            nm.notify(notificationId, notification)
        } catch (e: SecurityException) {
            AppLogger.error(TAG, "Cannot post notification – missing POST_NOTIFICATIONS permission: ${e.message}")
        }
    }
}
