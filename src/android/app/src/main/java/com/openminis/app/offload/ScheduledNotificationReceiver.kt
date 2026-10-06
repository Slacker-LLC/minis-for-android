package com.openminis.app.offload

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.openminis.app.MainActivity
import com.openminis.app.R
import com.openminis.app.logging.AppLogger

/**
 * Fires when an `android-notification schedule` AlarmManager alarm
 * triggers. Posts the notification immediately and removes the entry
 * from the pending-notifications prefs so a follow-up `pending` call
 * no longer lists it.
 *
 * Mirrors the AlarmReceiver pattern but for the user-scheduled
 * notification path (apple-notification schedule), which on iOS goes
 * through UNUserNotificationCenter directly. Android has no equivalent
 * "scheduled local notification" API, so we use AlarmManager + a
 * receiver as the moral equivalent.
 */
class ScheduledNotificationReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_ID = "scheduled_notification_id"
        const val EXTRA_TITLE = "scheduled_notification_title"
        const val EXTRA_BODY = "scheduled_notification_body"
        const val CHANNEL_ID = "minis_agent_notifications"
        private const val TAG = "ScheduledNotifReceiver"

        /**
         * [GH#116] The tap target for an `android-notification` notification.
         *
         * A notification with no contentIntent is INERT — tapping it does
         * nothing and `setAutoCancel(true)` merely dismisses it, which is
         * exactly what #116 reported. Android has no implicit "tap opens the
         * app" behaviour; iOS's UNUserNotificationCenter does, which is how
         * the gap survived a port that otherwise mirrors apple-notification.
         *
         * Shared by BOTH post sites (immediate send in
         * NotificationOffloadHandler, deferred fire here) on purpose: they
         * previously each built their own notification and drifted apart —
         * the deferred path set a contentIntent and the immediate path never
         * did. One helper means a future change can't re-open that gap.
         *
         * Targets MainActivity explicitly rather than
         * getLaunchIntentForPackage(): T-android-dynamic-app-icon moved the
         * LAUNCHER filter onto activity-alias entries that get enabled and
         * disabled at runtime, so the launcher intent depends on which alias
         * is currently active. An explicit component is immune to that.
         *
         * NEW_TASK because we post from a receiver / background context with
         * no Activity on the stack; CLEAR_TOP so MainActivity (singleTask)
         * reuses its existing instance instead of stacking a duplicate.
         *
         * FLAG_IMMUTABLE is mandatory, not defensive: targetSdk 35 means
         * Android 12+ throws if a PendingIntent declares neither mutability.
         * It also stops a malicious app from filling in extras on an intent
         * that would then be sent AS Minis.
         */
        fun contentIntentFor(context: Context, notifId: Int): PendingIntent {
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            return PendingIntent.getActivity(
                context,
                // Unique per notification id: with FLAG_UPDATE_CURRENT a shared
                // request code would let one notification's intent overwrite
                // another's once these carry per-notification extras.
                notifId,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /** The alarm token for scheduled notification [id]; the same one for set, cancel and restore. */
        fun alarmIntentFor(context: Context, id: String, title: String?, body: String?): PendingIntent {
            val intent = Intent(context, ScheduledNotificationReceiver::class.java).apply {
                putExtra(EXTRA_ID, id)
                if (title != null) putExtra(EXTRA_TITLE, title)
                if (body != null) putExtra(EXTRA_BODY, body)
            }
            return PendingIntent.getBroadcast(
                context, requestCodeFor(id), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        fun requestCodeFor(id: String): Int = id.hashCode() and 0x7FFFFFFF

        /** Removes the pending entry for [id] and posts its notification. */
        fun deliver(context: Context, id: String, title: String, body: String) {
            // Drop the prefs entry so `pending` no longer surfaces it.
            try {
                ScheduledNotificationStore(context).remove(id)
            } catch (e: Throwable) {
                AppLogger.warning(TAG, "remove($id) failed: ${e.message}")
            }

            val notifId = requestCodeFor(id)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .setContentIntent(contentIntentFor(context, notifId))
                .build()
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                    as android.app.NotificationManager
                nm.notify(notifId, notification)
            } catch (e: SecurityException) {
                AppLogger.error(TAG, "post denied — POST_NOTIFICATIONS missing: ${e.message}")
            }
        }

        /**
         * After a reboot (the system drops every alarm) put the stored scheduled notifications back:
         * future ones are re-armed, ones that came due while the device was off are delivered now,
         * ones more than an hour overdue are dropped.
         */
        fun restoreAfterBoot(context: Context, now: Long = System.currentTimeMillis()) {
            val store = ScheduledNotificationStore(context)
            val all = store.loadAll()
            val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            for (i in 0 until all.length()) {
                val entry = all.getJSONObject(i)
                val id = entry.optString("id")
                if (id.isEmpty()) continue
                val title = entry.optString("title")
                val body = entry.optString("body")
                when (ScheduledNotificationStore.restoreAction(entry, now)) {
                    ScheduledNotificationStore.Companion.RestoreAction.RESCHEDULE -> try {
                        am.setExactAndAllowWhileIdle(
                            android.app.AlarmManager.RTC_WAKEUP,
                            entry.optLong("trigger_at_ms"),
                            alarmIntentFor(context, id, title, body),
                        )
                    } catch (e: SecurityException) {
                        AppLogger.warning(TAG, "restore($id): exact alarm denied: ${e.message}")
                    }
                    ScheduledNotificationStore.Companion.RestoreAction.DELIVER_NOW -> deliver(context, id, title, body)
                    ScheduledNotificationStore.Companion.RestoreAction.DROP -> store.remove(id)
                }
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: "unknown"
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Minis"
        val body = intent.getStringExtra(EXTRA_BODY) ?: ""
        AppLogger.debug(TAG, "scheduled notification fired: id=$id title='$title'")
        deliver(context, id, title, body)
    }

}
