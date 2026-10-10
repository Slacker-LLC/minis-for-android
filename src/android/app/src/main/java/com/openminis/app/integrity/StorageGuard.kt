package com.openminis.app.integrity

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import com.openminis.app.runtime.ubuntu.DirectRootRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The storage watermark of Issue #182: a full `/data` can stop the phone from booting, so when free
 * space falls below the threshold the app stops *its own* sources of writes until space comes back.
 *
 * While paused it
 *  - freezes the guest shells' process groups (SIGSTOP) and resumes them (SIGCONT) afterwards,
 *  - refuses new privileged commands that bulk-write ([consumesSpace]) through [ProtectedRoot], and
 *  - posts one notification.
 * It never deletes anything. Deleting (`rm`) stays allowed so the agent or the user can free space.
 *
 * Which `/data` is measured is the one the app can read via [StatFs]; the threshold is
 * `max(3 GB, 5 %)` of the volume. Resuming needs 25 % more free space than pausing, so the state does
 * not flap around the line.
 */
object StorageGuard {
    const val MIN_THRESHOLD_BYTES = 3L * 1024 * 1024 * 1024
    private const val PERCENT = 5
    private const val POLL_MS = 30_000L
    private const val CHANNEL_ID = "device_protection"
    private const val NOTIFICATION_ID = 0x1824
    private const val TAG = "StorageGuard"

    @Volatile
    var writesBlocked: Boolean = false
        private set

    fun thresholdBytes(totalBytes: Long): Long = maxOf(MIN_THRESHOLD_BYTES, totalBytes / 100 * PERCENT)

    /** The next paused state given the current free space, with hysteresis. Pure. */
    fun nextPaused(paused: Boolean, freeBytes: Long, totalBytes: Long): Boolean {
        val threshold = thresholdBytes(totalBytes)
        return if (paused) freeBytes < threshold + threshold / 4 else freeBytes < threshold
    }

    private val SPACE_COMMANDS = setOf(
        "cp", "dd", "tar", "unzip", "fallocate", "truncate", "rsync", "install", "wget", "curl", "mkfile",
    )

    /** Whether a command is one that writes bulk data. `rm` and friends are never in this set. Pure. */
    fun consumesSpace(name: String, fullCommand: String): Boolean {
        if (name !in SPACE_COMMANDS) return false
        if (name == "dd" && Regex("""\bof=/dev/null\b""").containsMatchIn(fullCommand)) return false
        if (name == "truncate" && !Regex("""(^|\s)(-s|--size)""").containsMatchIn(fullCommand)) return false
        return true
    }

    internal fun signalGuestShellsScript(signal: String): String {
        require(signal == "STOP" || signal == "CONT") { "unsupported signal" }
        return "DIR=${DirectRootRunner.ROOT_STATE_DIR}/shells; [ -d \"\$DIR\" ] || exit 0; " +
            "for m in \"\$DIR\"/shell-*.pid; do [ -f \"\$m\" ] || continue; " +
            "PID=\$(sed -n 1p \"\$m\" 2>/dev/null); case \"\$PID\" in ''|*[!0-9]*) continue;; esac; " +
            "[ \"\$PID\" -gt 1 ] && kill -$signal -\$PID 2>/dev/null; done; exit 0"
    }

    private var job: Job? = null

    /** Start polling. Idempotent; call from MinisApp.onCreate. */
    fun start(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            while (isActive) {
                val stat = runCatching { StatFs("/data") }.getOrNull()
                if (stat != null) {
                    val paused = nextPaused(writesBlocked, stat.availableBytes, stat.totalBytes)
                    if (paused != writesBlocked) {
                        writesBlocked = paused
                        onChanged(app, paused, stat.availableBytes)
                    }
                }
                delay(POLL_MS)
            }
        }
    }

    private suspend fun onChanged(context: Context, paused: Boolean, freeBytes: Long) {
        AppLogger.warning(TAG, if (paused) "paused guest writes: free=${freeBytes / (1024 * 1024)} MB" else "resumed guest writes")
        // Freezing needs Root; if it is unavailable the command-level refusal still applies.
        runCatching { DirectRootRunner.runScript(signalGuestShellsScript(if (paused) "STOP" else "CONT"), 10_000L) }
        if (paused) notify(context, freeBytes) else NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun notify(context: Context, freeBytes: Long) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.device_protection_channel), NotificationManager.IMPORTANCE_HIGH),
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.device_storage_low_title))
            .setContentText(context.getString(R.string.device_storage_low_body, freeBytes / (1024 * 1024)))
            .setOngoing(true)
            .build()
        // POST_NOTIFICATIONS is a runtime permission on Android 13+; without it the freeze and the command
        // refusals still apply, only the notice is skipped.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }
}
