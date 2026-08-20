package com.openminis.app.remote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.util.Log
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.openminis.app.MainActivity
import com.openminis.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Foreground owner for both the loopback Web Remote and optional cloudflared connector. */
class RemoteAccessService : Service() {
    companion object {
        private const val CHANNEL_ID = "minis_web_remote"
        private const val NOTIFICATION_ID = 7402
        private const val ACTION_STOP = "com.openminis.app.remote.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RemoteAccessService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RemoteAccessService::class.java))
        }

        /**
         * Bring the server back after a process death or a reboot, but only if
         * the user actually left it on and a login password is set.
         *
         * Wrapped in runCatching because a boot / process-recreate start is a
         * background FGS start: OEM ROMs can reject it with
         * ForegroundServiceStartNotAllowedException, and remote access failing
         * to come up must never take the whole app down with it.
         */
        /** In-flight guard so boot-time MinisApp + AlarmReceiver + user toggle
         *  can't triple-start the service (each startForegroundService would
         *  otherwise re-run onStartCommand and rebuild the server socket). */
        @Volatile private var startInFlight = false

        fun startIfEnabled(context: Context) {
            if (!RemoteAccessPrefs.isEnabled(context)) return
            // Never expose remote control without a password, even on restore.
            if (!RemoteAccessPrefs.hasPassword(context)) return
            if (startInFlight) return
            startInFlight = true
            try {
                runCatching { start(context) }
                    .onFailure { Log.w("RemoteAccessService", "restore failed: ${it.message}") }
            } finally {
                startInFlight = false
            }
        }

        /** Apply a bind-address/port/security change without changing the enabled preference. */
        fun restart(context: Context) {
            stop(context)
            if (RemoteAccessPrefs.isEnabled(context)) start(context)
        }
    }

    private var server: RemoteAccessServer? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tunnelJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            RemoteAccessPrefs.setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!RemoteAccessPrefs.isEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Public remote control without a login password is never allowed.
        // This also protects against an old preference that had Web Remote
        // enabled before the login feature existed.
        if (!RemoteAccessPrefs.hasPassword(this)) {
            RemoteAccessPrefs.setEnabled(this, false)
            CloudflareTunnelManager.stop()
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, notification())

        server?.stop()
        val candidate = RemoteAccessServer(
            context = this,
            port = RemoteAccessPrefs.port(this),
            token = RemoteAccessPrefs.token(this),
            bindHost = RemoteAccessPrefs.bindHost(this),
        )
        if (!candidate.start()) {
            server = null
            CloudflareTunnelManager.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        server = candidate

        tunnelJob?.cancel()
        if (RemoteAccessPrefs.cloudflareTunnelEnabled(this)) {
            tunnelJob = serviceScope.launch {
                CloudflareTunnelManager.start(this@RemoteAccessService)
            }
        } else {
            CloudflareTunnelManager.stop()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        tunnelJob?.cancel()
        tunnelJob = null
        CloudflareTunnelManager.stop()
        server?.stop()
        server = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Minis Web Remote", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notification(): android.app.Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = Intent(this, RemoteAccessService::class.java).setAction(ACTION_STOP)
        val stop = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val hostname = RemoteAccessPrefs.cloudflareHostname(this)
        val detail = if (RemoteAccessPrefs.cloudflareTunnelEnabled(this) && hostname.isNotBlank()) {
            "https://$hostname"
        } else {
            "${RemoteAccessPrefs.bindHost(this)}:${RemoteAccessPrefs.port(this)}"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Minis Web Remote")
            .setContentText(detail)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "停止", stop)
            .build()
    }
}
