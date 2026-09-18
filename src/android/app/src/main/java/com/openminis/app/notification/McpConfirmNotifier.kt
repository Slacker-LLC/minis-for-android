package com.openminis.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import com.openminis.app.mcp.server.ConfirmQueue

/**
 * [T-android-mcp-server] Phone notification for CONFIRM-level MCP tool calls
 * (07 §6): a high-priority notification with inline 批准/拒绝 actions, closing
 * the human-confirmation loop without opening the app. Mirrors the
 * ApprovalSeam.answer(approvalId, allowed) pattern: both actions route to
 * [McpConfirmReceiver], which answers the shared [ConfirmQueue] and clears
 * the notification. The queue's own TTL (sweep) governs expiry; the
 * notification mirrors it via [NotificationCompat.Builder.setTimeoutAfter].
 */
object McpConfirmNotifier {

    const val ACTION_APPROVE = "com.openminis.app.mcp.confirm.APPROVE"
    const val ACTION_REJECT = "com.openminis.app.mcp.confirm.REJECT"
    const val EXTRA_CONFIRM_ID = "confirm_id"
    const val EXTRA_METHOD = "method"

    private const val TAG = "McpConfirmNotifier"
    private const val CHANNEL_ID = "minis_mcp_confirm"
    private const val NOTIFICATION_TAG = "mcp-confirm"

    /**
     * Posts the confirm notification for one issued pending confirm. Safe to
     * call from any thread; a no-op (logged) when POST_NOTIFICATIONS is
     * denied. The notification id is [confirmId].hashCode() — the same key
     * [McpConfirmReceiver] cancels.
     */
    fun show(context: Context, toolName: String, summary: String, confirmId: String, expiresInMs: Long) {
        ensureChannel(context)

        val actionIntent = Intent(context, McpConfirmReceiver::class.java)
        val approvePi = actionPendingIntent(
            context,
            confirmId,
            Intent(actionIntent)
                .setAction(ACTION_APPROVE)
                .putExtra(EXTRA_CONFIRM_ID, confirmId)
                .putExtra(EXTRA_METHOD, toolName),
        )
        val rejectPi = actionPendingIntent(
            context,
            confirmId,
            Intent(actionIntent)
                .setAction(ACTION_REJECT)
                .putExtra(EXTRA_CONFIRM_ID, confirmId)
                .putExtra(EXTRA_METHOD, toolName),
        )

        val body = "$toolName：$summary"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Minis 需要确认")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .addAction(0, "批准", approvePi)
            .addAction(0, "拒绝", rejectPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setTimeoutAfter(expiresInMs)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_TAG, confirmId.hashCode(), notification)
            AppLogger.info(TAG, "mcp-confirm notify id=$confirmId tool=$toolName")
        } catch (se: SecurityException) {
            AppLogger.info(TAG, "notify denied (POST_NOTIFICATIONS not granted)")
        }
    }

    /** Cancels the confirm notification (used by [McpConfirmReceiver] once answered). */
    fun cancel(context: Context, confirmId: String) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_TAG, confirmId.hashCode())
    }

    private fun actionPendingIntent(context: Context, confirmId: String, intent: Intent): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            confirmId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun ensureChannel(context: Context) {
        val manager = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "MCP 工具确认",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "MCP 工具调用需要确认时的高优先级通知"
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }
}

private const val MCP_CONFIRM_RECEIVER_TAG = "McpConfirmReceiver"

/**
 * [T-android-mcp-server] Statically-registered target of the 批准/拒绝
 * notification actions. Explicit intents only (exported=false), so the action
 * string + extras are app-controlled. Answers the shared [ConfirmQueue] and
 * clears the notification; a stale action (queue gone, e.g. after process
 * death) just cancels the notification.
 */
class McpConfirmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val confirmId = intent.getStringExtra(McpConfirmNotifier.EXTRA_CONFIRM_ID) ?: return
        val method = intent.getStringExtra(McpConfirmNotifier.EXTRA_METHOD) ?: return
        val queue = ConfirmQueue.shared
        if (queue == null) {
            McpConfirmNotifier.cancel(context, confirmId)
            AppLogger.warning(MCP_CONFIRM_RECEIVER_TAG, "mcp-confirm answer dropped, no live queue: id=$confirmId")
            return
        }
        val result = when (intent.action) {
            McpConfirmNotifier.ACTION_APPROVE -> queue.approve(confirmId, method)
            McpConfirmNotifier.ACTION_REJECT -> queue.reject(confirmId, method)
            else -> return
        }
        McpConfirmNotifier.cancel(context, confirmId)
        AppLogger.info(MCP_CONFIRM_RECEIVER_TAG, "mcp-confirm answered id=$confirmId action=${intent.action} -> $result")
    }
}
