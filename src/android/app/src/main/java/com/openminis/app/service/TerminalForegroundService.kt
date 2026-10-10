package com.openminis.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.openminis.app.R
import com.openminis.app.runtime.terminal.AgentTerminals
import com.openminis.app.runtime.terminal.TerminalKeepAlive
import com.openminis.app.runtime.terminal.TerminalSessionManager

/** When the terminal service runs. Pure, so the rule is tested without a device. */
object TerminalServicePolicy {
    /** Only a live process keeps the app resident: no terminals, or only ended ones, means no service. */
    fun shouldRun(runningTerminals: Int): Boolean = runningTerminals > 0
}

/**
 * Keeps Minis alive while a terminal has a live process (Issue #183): the user's tabs and the agent's terminals.
 * Without it a build or a coding agent left running in the terminal is killed by the system or the OEM shortly
 * after the app goes to the background.
 *
 * Same principles as [AgentForegroundService] (docs/AGENT-FOREGROUND-SERVICE.md): it exists only while there is
 * real work, uses the `specialUse` type and never borrows `mediaPlayback` or `dataSync`, and returns
 * `START_NOT_STICKY`, so if the system does kill the process nothing is replayed. The notification shows how many
 * terminals run, opens the Terminal page on tap and has an "End all" action.
 */
class TerminalForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        if (intent?.action == ACTION_STOP_ALL) {
            // Satisfy the start deadline first, then end everything and stop.
            startAsForeground(buildNotification(0))
            TerminalSessionManager.existing()?.closeAll()
            AgentTerminals.existing()?.closeAll()
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        val count = intent?.getIntExtra(EXTRA_COUNT, 0) ?: 0
        startAsForeground(buildNotification(count))
        if (!TerminalServicePolicy.shouldRun(count)) {
            stopForegroundCompat()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }

    private fun buildNotification(count: Int): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(Intent.ACTION_VIEW, Uri.parse("minis://open_terminal"))
                .setClassName(this, "com.openminis.app.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopAll = PendingIntent.getService(
            this,
            1,
            Intent(this, TerminalForegroundService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(resources.getQuantityString(R.plurals.terminal_fgs_running, count, count))
            .setContentIntent(open)
            .addAction(0, getString(R.string.terminal_fgs_stop_all), stopAll)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.terminal_fgs_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        private const val TAG = "TerminalFgs"
        private const val CHANNEL_ID = "terminal_sessions"
        private const val NOTIFICATION_ID = 0x1830
        private const val EXTRA_COUNT = "count"
        internal const val ACTION_STOP_ALL = "com.openminis.app.STOP_ALL_TERMINALS"
        private const val MIN_LIFETIME_BEFORE_STOP_MS = 1_500L

        @Volatile private var lastStartRequestMs = 0L

        /** A [TerminalKeepAlive] that starts, updates and stops this service as the running count changes. */
        fun keepAlive(app: Context): TerminalKeepAlive = TerminalKeepAlive { count ->
            if (TerminalServicePolicy.shouldRun(count)) start(app, count) else stop(app)
        }

        private fun start(app: Context, count: Int) {
            lastStartRequestMs = SystemClock.elapsedRealtime()
            try {
                app.startForegroundService(Intent(app, TerminalForegroundService::class.java).putExtra(EXTRA_COUNT, count))
            } catch (t: Throwable) {
                // Not allowed to start a foreground service from the background right now; the terminals keep
                // running while the process lives, and the next change retries.
                Log.w(TAG, "could not start the terminal service: ${t.message}")
            }
        }

        private fun stop(app: Context) {
            // Stopping right behind a pending startForegroundService() kills the app (the start deadline), so
            // let the start settle first and stop only if there is still nothing running.
            val wait = MIN_LIFETIME_BEFORE_STOP_MS - (SystemClock.elapsedRealtime() - lastStartRequestMs)
            val stopNow = {
                if (!TerminalServicePolicy.shouldRun(currentCount())) {
                    app.stopService(Intent(app, TerminalForegroundService::class.java))
                }
            }
            if (wait <= 0L) stopNow() else Handler(Looper.getMainLooper()).postDelayed({ stopNow() }, wait)
        }

        private fun currentCount(): Int {
            val user = TerminalSessionManager.existing()?.runningCount() ?: 0
            val agent = AgentTerminals.existing()?.list()?.count { !it.exited } ?: 0
            return user + agent
        }
    }
}
