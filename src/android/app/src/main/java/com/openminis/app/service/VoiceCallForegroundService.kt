package com.openminis.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.openminis.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 通话页和前台服务之间的桥：通话页发布状态，通知上的按钮把动作回调给通话页。
 * 服务本身不碰麦克风，也不持有通话状态；它只让进程在通话期间继续存活，并给用户一个看得见的入口。
 */
object VoiceCallBridge {
    /** 一通进行中的电话：[muted] 是麦克风是否暂停，[startedAtMs] 用于通知上的计时。 */
    data class Call(val muted: Boolean, val startedAtMs: Long)

    private val _call = MutableStateFlow<Call?>(null)
    val call: StateFlow<Call?> = _call.asStateFlow()

    /** 前台服务已成功以 microphone 类型启动。切后台时只有它为 true 才允许继续通话。 */
    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    @Volatile var onHangUp: (() -> Unit)? = null
    @Volatile var onToggleMute: (() -> Unit)? = null

    fun publish(call: Call?) { _call.value = call }
    internal fun setServiceRunning(running: Boolean) { _serviceRunning.value = running }
}

/** 通知上「挂断 / 静音」按钮的接收端。只转给当前通话页，没有通话时什么也不做。 */
class VoiceCallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            VoiceCallForegroundService.ACTION_HANG_UP -> VoiceCallBridge.onHangUp?.invoke()
            VoiceCallForegroundService.ACTION_TOGGLE_MUTE -> VoiceCallBridge.onToggleMute?.invoke()
        }
    }
}

/**
 * 语音通话进行期间的前台服务（`microphone` 类型）。没有它，切到后台或锁屏后系统会停掉麦克风采集，
 * 所以此前通话页在 ON_STOP 时直接静音。现在通话页在通话开始（应用在前台，满足 Android 的 while-in-use 要求）
 * 时启动它，通知栏常驻并带「静音 / 挂断」按钮，用户随时看得见麦克风在用。
 *
 * 通话结束（[VoiceCallBridge.call] 变 null）或进程被杀都会停止；`START_NOT_STICKY`，系统杀掉后不会自动重启，
 * 也就不会在用户不知情时重新开麦。
 */
class VoiceCallForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val call = VoiceCallBridge.call.value
        if (call == null) {
            // 通话已经结束：仍要先满足前台启动时限再停。
            runCatching { startAsForeground(buildNotification(VoiceCallBridge.Call(muted = false, startedAtMs = 0L))) }
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startAsForeground(buildNotification(call))
        } catch (t: Throwable) {
            // 没有麦克风权限或系统不允许此时启动：不留半截服务，通话页会因此在切后台时静音。
            Log.w(TAG, "could not start the voice call service: ${t.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        VoiceCallBridge.setServiceRunning(true)
        acquireWakeLock()
        scope.launch {
            VoiceCallBridge.call.collect { latest ->
                if (latest == null) {
                    stopForegroundCompat()
                    stopSelf()
                } else {
                    getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(latest))
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        VoiceCallBridge.setServiceRunning(false)
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }

    private fun buildNotification(call: VoiceCallBridge.Call): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent().setClassName(this, "com.openminis.app.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.voice_call_notif_title))
            .setContentText(getString(if (call.muted) R.string.voice_call_notif_muted else R.string.voice_call_notif_live))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        if (call.startedAtMs > 0L) builder.setUsesChronometer(true).setWhen(call.startedAtMs)
        builder.addAction(
            0,
            getString(if (call.muted) R.string.voice_call_unmute else R.string.voice_call_mute),
            action(ACTION_TOGGLE_MUTE, 1),
        )
        builder.addAction(0, getString(R.string.voice_call_end), action(ACTION_HANG_UP, 2))
        return builder.build()
    }

    private fun action(name: String, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        this,
        requestCode,
        Intent(this, VoiceCallActionReceiver::class.java).setAction(name),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.voice_call_notif_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        // 锁屏后 CPU 不休眠才能继续识别和朗读；有上限，服务异常时也不会一直占着。
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "minis:voice-call").apply {
            setReferenceCounted(false)
            acquire(MAX_CALL_MS)
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    companion object {
        private const val TAG = "VoiceCallFgs"
        private const val CHANNEL_ID = "voice_call"
        private const val NOTIFICATION_ID = 0x1840
        private const val MAX_CALL_MS = 2 * 60 * 60 * 1000L
        const val ACTION_HANG_UP = "com.openminis.app.VOICE_CALL_HANG_UP"
        const val ACTION_TOGGLE_MUTE = "com.openminis.app.VOICE_CALL_TOGGLE_MUTE"

        /** 在通话页处于前台时调用；系统不允许时返回 false，通话退回「切后台即静音」。 */
        fun start(app: Context): Boolean = try {
            app.startForegroundService(Intent(app, VoiceCallForegroundService::class.java))
            true
        } catch (t: Throwable) {
            Log.w(TAG, "could not start the voice call service: ${t.message}")
            false
        }
    }
}
