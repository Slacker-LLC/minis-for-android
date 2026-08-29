package io.github.slackerllc.minis.mcp.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import io.github.slackerllc.minis.R
import io.github.slackerllc.minis.logging.AppLogger

/**
 * Keep-alive foreground service for the MCP Server (P8 audit §4.3).
 * HyperOS freezes loopback sockets of background apps — a foreground service
 * keeps the process exempt while MCP is running, so remote MCP clients stay
 * reachable across lock-screen. Started by [MCPServerManager.start], stopped
 * by [MCPServerManager.stop]. Independent of AgentForegroundService so the
 * pet/agent overlay state is never clobbered by MCP lifecycle.
 */
class MCPKeepAliveService : Service() {

    companion object {
        private const val TAG = "MCPKeepAliveService"
        private const val CHANNEL_ID = "minis_mcp_server"
        private const val NOTIFICATION_ID = 0x4D4350 // "MCP"

        fun start(context: Context) {
            runCatching {
                context.startForegroundService(
                    Intent(context, MCPKeepAliveService::class.java),
                )
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, MCPKeepAliveService::class.java))
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "MCP 服务",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Minis MCP Server 运行时保活通知"
                    setShowBadge(false)
                },
            )
        }
        val notification: Notification =
            android.app.Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Minis MCP Server 运行中")
                .setContentText("远程 Agent 可通过 MCP 连接此设备")
                .setOngoing(true)
                .build()
        startForeground(NOTIFICATION_ID, notification)
        AppLogger.info(TAG, "keep-alive started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        AppLogger.info(TAG, "keep-alive stopped")
        super.onDestroy()
    }
}
