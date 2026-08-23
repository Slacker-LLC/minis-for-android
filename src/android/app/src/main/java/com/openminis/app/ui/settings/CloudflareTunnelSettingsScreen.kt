package com.openminis.app.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.openminis.app.remote.CloudflareTunnelManager
import com.openminis.app.remote.RemoteAccessPrefs
import com.openminis.app.remote.RemoteAccessService
import kotlinx.coroutines.launch

private fun phaseLabel(phase: String?): String = when (phase) {
    "unconfigured" -> "未配置"
    "stopped" -> "已停止"
    "starting" -> "启动中"
    "connecting" -> "连接中"
    "healthy" -> "正常"
    "degraded" -> "连接降级"
    "reconnecting" -> "重连中"
    "auth-failed" -> "认证失败"
    "origin-down" -> "本地服务异常"
    "edge-down" -> "连接异常"
    "process-exited" -> "进程异常退出"
    "error" -> "错误"
    else -> phase ?: "未知"
}

private fun phaseTone(phase: String?): String = when (phase) {
    "healthy" -> "正常"
    "degraded", "reconnecting", "connecting", "starting" -> "提示"
    "error", "auth-failed", "origin-down", "edge-down", "process-exited" -> "异常"
    else -> "未配置"
}

private fun fmtDuration(ms: Long): String {
    if (ms <= 0L) return "—"
    val totalMin = ms / 60_000L
    if (totalMin < 60) return "${totalMin}m"
    val h = totalMin / 60L
    val m = totalMin % 60L
    if (h < 24) return "${h}h ${m}m"
    return "${h / 24L}d ${h % 24L}h"
}

/** 设备本地时间 HH:mm:ss（与 Web 端展示一致的格式）。 */
private fun fmtClock(ms: Long): String {
    if (ms <= 0L) return "—"
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    return String.format("%02d:%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), cal.get(java.util.Calendar.SECOND))
}

/**
 * Cloudflare Tunnel detail (二级设置页).
 *
 * 入口固定为：设置 → Web 远程控制 → Cloudflare Tunnel / 连接状态。
 * 这里的所有状态与操作都作用于唯一的 Android
 * [CloudflareTunnelManager]；本页不保存任何第二份配置。
 */
@Composable
fun CloudflareTunnelSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val health by CloudflareTunnelManager.health.collectAsState()
    val status by CloudflareTunnelManager.status.collectAsState()

    var hostname by remember { mutableStateOf(RemoteAccessPrefs.cloudflareHostname(context)) }
    var token by remember { mutableStateOf("") }
    var tokenConfigured by remember { mutableStateOf(RemoteAccessPrefs.hasCloudflareTunnelToken(context)) }
    var protocolMode by remember { mutableStateOf(RemoteAccessPrefs.cloudflareTunnelProtocol(context)) }
    var diagnostics by remember { mutableStateOf<List<Pair<String, Boolean>>?>(null) }
    var diagnosing by remember { mutableStateOf(false) }
    var tunnelEnabled by remember { mutableStateOf(RemoteAccessPrefs.cloudflareTunnelEnabled(context)) }

    LaunchedEffect(Unit) { CloudflareTunnelManager.refresh(context) }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    fun runDiagnose() {
        scope.launch {
            diagnosing = true
            diagnostics = null
            try {
                val checks = CloudflareTunnelManager.diagnose(context)
                diagnostics = (0 until checks.length()).map { i ->
                    val row = checks.getJSONObject(i)
                    row.optString("name") to row.optBoolean("ok")
                }
            } finally {
                diagnosing = false
            }
        }
    }

    fun saveConfig(restart: Boolean) {
        RemoteAccessPrefs.setCloudflareHostname(context, hostname)
        if (token.isNotBlank()) {
            RemoteAccessPrefs.setCloudflareTunnelToken(context, token)
            token = ""
            tokenConfigured = true
        }
        RemoteAccessPrefs.setCloudflareTunnelProtocol(context, protocolMode)
        if (restart) {
            RemoteAccessService.restart(context)
        }
        toast("Tunnel 配置已保存")
    }

    val running = health.running
    val tone = phaseTone(health.phase)
    val hostnameLabel = RemoteAccessPrefs.cloudflareHostname(context).ifBlank { "未配置" }
    val originLabel = "127.0.0.1:${RemoteAccessPrefs.port(context)}"


    SettingsScaffold(title = "Cloudflare Tunnel", onBack = onBack) {
        // 状态摘要（健康 = 三层：进程 / Edge / Origin）
        SettingsSection(
            header = "状态",
            footer = "健康 = cloudflared 进程 + Edge 连接 + 本地 Origin 三层均正常；只显示单一“开/关”无法定位问题。",
        ) {
            SettingsRow(
                title = "Tunnel 状态",
                subtitle = "$tone · ${health.detail.ifBlank { "—" }}",
                icon = Icons.Outlined.Cloud,
                trailing = {
                    Text(
                        health.phase,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                showDivider = true,
            )
            SettingsRow(title = "公开域名", subtitle = hostnameLabel, showDivider = true)
            SettingsRow(
                title = "Edge 连接",
                subtitle = "${health.edgeConnected} / ${health.edgeExpected}",
                showDivider = true,
            )
            SettingsRow(title = "协议", subtitle = "${health.protocol}（配置：${health.configuredProtocol}）", showDivider = true)
            SettingsRow(title = "运行时长", subtitle = fmtDuration(health.uptimeMs), showDivider = true)
            if (health.lastError.isNotBlank()) {
                SettingsRow(
                    title = "最近错误",
                    subtitle = health.lastError,
                    icon = Icons.Outlined.Info,
                    showDivider = true,
                )
            }
        }

        // 主操作区：只保留两个（启动/停止 与 重启）
        SettingsSection {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (running) {
                    Button(
                        onClick = {
                            CloudflareTunnelManager.stop()
                            toast("Tunnel 已停止")
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("停止 Tunnel") }
                    Button(
                        onClick = {
                            scope.launch { CloudflareTunnelManager.restart(context) }
                            toast("Tunnel 重启中…")
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("重启") }
                } else {
                    Button(
                        onClick = {
                            if (!tokenConfigured) {
                                toast("先填写 Tunnel Token")
                                return@Button
                            }
                            scope.launch { CloudflareTunnelManager.start(context) }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("启动 Tunnel") }
                }
            }
        }
        if (health.phase == "auth-failed" || health.phase == "error" || health.phase == "process-exited") {
            SettingsSection(
                header = "需要处理",
                footer = "Tunnel 无法自动恢复的原因请见状态行；认证失败不会无限重启。",
            ) {
                SettingsRow(
                    title = "诊断",
                    subtitle = "运行连接诊断以定位故障层",
                    icon = Icons.Outlined.Build,
                    onClick = { runDiagnose() },
                )
            }
        }

        // 配置
        SettingsSection(
            header = "配置",
            footer = "Tunnel Token 按密码同等级存储（EncryptedPrefs），任何读取接口只返回“已配置”。",
        ) {
            SettingsCardBlock {
                OutlinedTextField(
                    value = hostname,
                    onValueChange = { hostname = it },
                    label = { Text("公开域名") },
                    placeholder = { Text("remote.example.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text(if (tokenConfigured) "Tunnel Token（已配置，输入新值可替换）" else "Tunnel Token") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Button(
                    onClick = { saveConfig(restart = true) },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                ) { Text("保存配置") }
            }
            SettingsRow(
                title = "cloudflared 组件",
                subtitle = if (status.installed) "v${status.version.ifBlank { "已安装" }} · 已安装" else "未安装",
                icon = Icons.Outlined.Build,
                onClick = {
                    scope.launch {
                        val result = CloudflareTunnelManager.installOrUpdate(context)
                        toast(result.fold({ "cloudflared 已就绪" }, { it.message ?: "安装失败" }))
                    }
                },
            )
        }

        // 连接详情（只读状态行，不用禁用输入框）
        SettingsSection(
            header = "连接详情",
            footer = "这些是同一份 Android Runtime 快照的只读投影；App 与 Web 两端展示相同数值。",
        ) {
            SettingsRow(title = "Edge 连接", subtitle = "${health.edgeConnected} / ${health.edgeExpected}", showDivider = true)
            SettingsRow(title = "传输协议", subtitle = health.protocol, showDivider = true)
            SettingsRow(title = "本地服务", subtitle = "${health.originHealth} · $originLabel", showDivider = true)
            SettingsRow(title = "公网入口", subtitle = "${health.publicHealth} · $hostnameLabel", showDivider = true)
            SettingsRow(title = "启动时间", subtitle = fmtClock(health.startedAtMs), showDivider = true)
            SettingsRow(
                title = "累计重连",
                subtitle = "${health.reconnectCount} 次",
                showDivider = true,
            )
        }

        // 诊断
        SettingsSection(
            header = "诊断",
            footer = "诊断在设备上真实执行：cloudflared 可执行文件 / Token / Origin / DNS / TCP 7844 / UDP 7844 / Cloudflare Edge。",
        ) {
            SettingsRow(
                title = "运行连接诊断",
                subtitle = if (diagnosing) "正在诊断…" else "点击执行真实检查",
                icon = Icons.Outlined.Info,
                onClick = { runDiagnose() },
            )
            if (diagnostics != null) {
                SettingsCardBlock {
                    for ((name, ok) in diagnostics!!) {
                        Text(
                            text = "${if (ok) "✓" else "×"} $name",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(vertical = 3.dp),
                        )
                    }
                }
            }
        }

        // 最近事件（摘要）
        SettingsSection(
            header = "最近事件",
            footer = "最近一次断开/恢复/重连摘要；完整日志在应用日志管理中查看（已脱敏）。",
        ) {
            SettingsRow(
                title = "最近一次断开",
                subtitle = fmtClock(health.lastDisconnectedAtMs),
                icon = Icons.Outlined.Info,
                showDivider = true,
            )
            SettingsRow(
                title = "累计重连次数",
                subtitle = "${health.reconnectCount} 次",
                showDivider = true,
            )
            SettingsRow(
                title = "查看日志",
                subtitle = "脱敏后的 cloudflared 输出",
                icon = Icons.Outlined.Refresh,
                onClick = { toast("日志入口：设置 → 日志管理（内容已脱敏）") },
            )
        }

        // 高级（低频设置）
        SettingsSection(
            header = "高级",
            footer = "移动网络默认固定 HTTP/2；仅当测试确证 QUIC 更稳定时才选择 QUIC。",
        ) {
            SettingsRow(
                title = "传输协议",
                subtitle = when (protocolMode) {
                    "quic" -> "QUIC（手动指定）"
                    "http2" -> "HTTP/2（手动指定）"
                    else -> "自动稳定模式（移动网络 → HTTP/2）"
                },
                icon = Icons.Outlined.SettingsEthernet,
                onClick = {
                    protocolMode = when (protocolMode) {
                        "auto" -> "http2"
                        "http2" -> "quic"
                        else -> "auto"
                    }
                    saveConfig(restart = tunnelEnabled && health.running)
                },
            )
            SettingsRow(
                title = "自动恢复",
                subtitle = "已启用（进程退出后带退避重启；认证失败不无限重试）",
            )
        }
    }
}
