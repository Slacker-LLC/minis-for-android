package com.openminis.app.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.sandbox.RootfsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocketFactory

/**
 * Runs Cloudflare's official Linux/ARM64 cloudflared inside OpenMinis' existing
 * PRoot environment, and owns the FULL tunnel lifecycle: process supervision,
 * health state machine, edge-connection accounting, origin + public-hostname
 * probes, diagnostics, logs and lifecycle events.
 *
 * The tunnel token is passed through the documented TUNNEL_TOKEN environment
 * variable rather than a process argument, so it does not appear in ps output
 * or in the command line we log; it is never echoed, stored in a log, or
 * returned through any read API.
 *
 * Health is NOT "process alive": the state machine distinguishes
 * configured/stopped/starting/connecting/healthy/degraded/reconnecting/
 * auth-failed/edge-down/origin-down/process-exited so both UIs can show where
 * the failure actually is. edgeConnected/edgeExpected are parsed from
 * cloudflared's connection log lines.
 */
object CloudflareTunnelManager {
    private const val TAG = "CloudflareTunnel"
    private const val DOWNLOAD_URL =
        "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64"
    private const val MAX_BINARY_BYTES = 80L * 1024L * 1024L
    private const val METRICS_PORT = 8288
    private const val LOG_BUFFER = 400
    private const val EVENT_BUFFER = 200

    // ------------------------------------------------------------------ state

    /** Stable phases shared by both UIs. */
    object Phase {
        const val UNCONFIGURED = "unconfigured"
        const val STOPPED = "stopped"
        const val STARTING = "starting"
        const val CONNECTING = "connecting"
        const val HEALTHY = "healthy"
        const val DEGRADED = "degraded"
        const val RECONNECTING = "reconnecting"
        const val AUTH_FAILED = "auth-failed"
        const val ORIGIN_DOWN = "origin-down"
        const val EDGE_DOWN = "edge-down"
        const val PROCESS_EXITED = "process-exited"
        const val ERROR = "error"
    }

    /** Full runtime-visible snapshot (same object for App UI, Web RPC, diagnostics). */
    data class HealthSnapshot(
        val phase: String = Phase.UNCONFIGURED,
        val detail: String = "",
        val installed: Boolean = false,
        val running: Boolean = false,
        val version: String = "",
        val protocol: String = "http2",
        val configuredProtocol: String = "auto",
        val edgeConnected: Int = 0,
        val edgeExpected: Int = 0,
        val edgeLocations: List<String> = emptyList(),
        val originHealth: String = "unknown", // unknown | healthy | down
        val publicHealth: String = "unknown",
        val uptimeMs: Long = 0L,
        val startedAtMs: Long = 0L,
        val lastConnectedAtMs: Long = 0L,
        val lastDisconnectedAtMs: Long = 0L,
        val reconnectCount: Int = 0,
        val lastError: String = "",
        val metrics: MetricsSnapshot? = null,
    )

    data class MetricsSnapshot(
        val totalRequests: Long = 0,
        val requestErrors: Long = 0,
        val connectionLatencyMs: Double? = null,
        val sampledAtMs: Long = 0,
    )

    data class LogLine(
        val timeMs: Long,
        val level: String, // INFO | WARN | ERROR
        val text: String, // already redacted
        val kind: String, // connection | reconnect | dns | quic | http2 | origin | auth | metric | other
    )

    private data class Event(
        val timeMs: Long,
        val text: String, // already redacted
    )

    @Volatile private var process: Process? = null
    private val lock = Any()
    private var startedAtMs = 0L
    private var lastConnectedAtMs = 0L
    private var lastDisconnectedAtMs = 0L
    private var reconnectCount = 0
    private var lastError = ""
    private var supervisorGeneration = 0
    private var latestProtocol = "http2"
    @Volatile private var managerContext: Context? = null

    // cloudflared line classification keeps the last N lines verbatim.
    private val logBuffer = ArrayDeque<LogLine>()
    private val eventBuffer = ArrayDeque<Event>()

    private val _status = MutableStateFlow(Status())
    private val _health = MutableStateFlow(HealthSnapshot())

    /** Legacy-shaped status kept for the existing settings screen. */
    data class Status(
        val installed: Boolean = false,
        val running: Boolean = false,
        val phase: String = "idle",
        val detail: String = "",
        val version: String = "",
    )

    val status: StateFlow<Status> = _status.asStateFlow()
    val health: StateFlow<HealthSnapshot> = _health.asStateFlow()

    private val TALLOC_BLOCK = Regex("""contains\s+\d+ bytes in\s+\d+ blocks""")
    private val CONNECTION_LINE = Regex(
        """Registered tunnel connection\s+connIndex=(\d+)/(\d+).*(?:edge=([A-Za-z0-9.-]+))?""",
        RegexOption.IGNORE_CASE,
    )
    private val CONNECTION_SIMPLE = Regex(
        """Registered tunnel connection(?:\s+connIndex=(\d+)/(\d+))?""",
        RegexOption.IGNORE_CASE,
    )

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    // ---------------------------------------------------------------- pub API

    suspend fun refresh(context: Context): Status = withContext(Dispatchers.IO) {
        val current = refreshHealthLocked(context)
        publishHealth(current)
        _status.value
    }

    suspend fun installOrUpdate(context: Context): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            publish(Phase.STARTING, "Downloading cloudflared…")
            PRootKernel.boot(context.applicationContext)
            val target = binaryFile(context)
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.download")
            tmp.delete()

            val request = Request.Builder().url(DOWNLOAD_URL).get().build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("cloudflared download HTTP ${response.code}")
                val body = response.body ?: error("cloudflared download returned no body")
                val announced = body.contentLength()
                if (announced > MAX_BINARY_BYTES) error("cloudflared binary is unexpectedly large")
                body.byteStream().use { input ->
                    tmp.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > MAX_BINARY_BYTES) error("cloudflared download exceeded size limit")
                            output.write(buffer, 0, n)
                        }
                    }
                }
            }
            if (tmp.length() < 1_000_000L) error("cloudflared binary download is incomplete")
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            target.setReadable(true, true)
            target.setExecutable(true, true)

            val versionResult = runOneShot(context, "/opt/bin/cloudflared version", emptyMap(), 15_000L)
            if (versionResult.first != 0) {
                target.delete()
                error("cloudflared cannot run in PRoot: ${versionResult.second.take(300)}")
            }
            val version = versionResult.second.trim().lineSequence().firstOrNull().orEmpty()
            logLine("INFO", "cloudflared installed: $version", "other")
            refreshHealthLocked(context).copy(version = version).also { publishHealth(it) }
            version
        }.onFailure {
            Log.w(TAG, "install failed: ${it.message}", it)
            recordPhase(Phase.ERROR, it.message ?: "cloudflared install failed", markDisconnect = false)
        }
    }

    /**
     * Start the connector and hand the process to the supervisor loop. Safe to
     * call when already running (no-op) and when no token is configured
     * (honest failure, no infinite retry).
     */
    suspend fun start(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val app = context.applicationContext
            val token = RemoteAccessPrefs.cloudflareTunnelToken(app)
                ?: error("Cloudflare Tunnel Token is not configured")
            if (!RemoteAccessPrefs.cloudflareTunnelEnabled(app)) return@runCatching

            PRootKernel.boot(app)
            if (!binaryFile(app).isFile) {
                installOrUpdate(app).getOrThrow()
            }

            synchronized(lock) {
                if (process?.isAlive == true) return@runCatching
                // Proactive (not polled) network change handling: the android
                // ConnectivityManager callback below restarts supervision on
                // loss/restoral. Nothing here polls.
                startedAtMs = System.currentTimeMillis()
                lastConnectedAtMs = 0L
                lastDisconnectedAtMs = 0L
                reconnectCount = 0
                supervisorGeneration++
                val gen = supervisorGeneration
                publish(Phase.STARTING, "启动 cloudflared…")
                val pb = prootProcessBuilder(
                    app,
                    // `--metrics` binds a Prometheus endpoint on loopback inside
                    // the shared PRoot/Android network namespace; we poll it for
                    // connection counts and request counters (never exposed
                    // beyond 127.0.0.1). Protocol: mobile networks keep HTTP/2
                    // (carrier UDP/QUIC is unreliable); quic only when pinned.
                    "/opt/bin/cloudflared tunnel --no-autoupdate --protocol ${resolvedProtocol(app)} --metrics 127.0.0.1:$METRICS_PORT run",
                )
                pb.environment()["TUNNEL_TOKEN"] = token
                val p = pb.start()
                process = p
                Thread({ drainProcess(p, gen) }, "cloudflared-log").apply { isDaemon = true }.start()
            }
        }.onFailure {
            Log.w(TAG, "start failed: ${it.message}", it)
            recordPhase(Phase.ERROR, it.message ?: "Tunnel failed", markDisconnect = false)
        }
    }

    fun stop() {
        supervisorGeneration++ // invalidate any in-flight supervisor loop
        synchronized(lock) {
            val p = process
            process = null
            if (p != null) {
                runCatching { p.destroy() }
                runCatching {
                    if (p.isAlive) {
                        Thread.sleep(250)
                        if (p.isAlive) p.destroyForcibly()
                    }
                }
            }
        }
        lastDisconnectedAtMs = System.currentTimeMillis()
        recordPhase(Phase.STOPPED, "Tunnel stopped", markDisconnect = false)
    }

    /** Restart: stop any current process and start again (transactional). */
    suspend fun restart(context: Context) {
        stop()
        if (RemoteAccessPrefs.cloudflareTunnelEnabled(context)) {
            start(context)
        }
    }

    /** Redacted copy of the recent cloudflared log buffer (newest last). */
    fun recentLogs(limit: Int = LOG_BUFFER): List<LogLine> = synchronized(logBuffer) {
        logBuffer.takeLast(limit.coerceIn(1, LOG_BUFFER)).toList()
    }

    /** Redacted lifecycle events (newest last). */
    fun recentEvents(limit: Int = EVENT_BUFFER): List<JSONObject> = synchronized(eventBuffer) {
        eventBuffer.takeLast(limit.coerceIn(1, EVENT_BUFFER)).map {
            JSONObject().put("timeMs", it.timeMs).put("text", it.text)
        }
    }

    /**
     * Real on-device diagnostics. Every check is a real probe; failures carry a
     * one-line reason. Never returns the tunnel token or any credential.
     */
    suspend fun diagnose(context: Context): JSONArray = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val out = JSONArray()
        fun add(name: String, ok: Boolean, detail: String) {
            out.put(JSONObject()
                .put("name", name)
                .put("ok", ok)
                .put("detail", detail.take(200)))
        }

        // 1. cloudflared binary
        val bin = binaryFile(app)
        val versionOk = bin.isFile && bin.length() > 1_000_000L
        add("cloudflared", versionOk, if (versionOk) {
            val (_, v) = runOneShot(app, "/opt/bin/cloudflared version", emptyMap(), 15_000L)
            v.trim().lineSequence().firstOrNull()?.take(120) ?: "已安装"
        } else {
            "未安装或文件不完整；请点击「安装/更新组件」"
        })

        // 2. token configured (presence only)
        val tokenOK = RemoteAccessPrefs.hasCloudflareTunnelToken(app)
        add("tunnel-token", tokenOK, if (tokenOK) "已配置" else "未配置 Tunnel Token")

        // 3. origin (RemoteAccessServer on loopback)
        val originPort = RemoteAccessPrefs.port(app)
        val originReachable = probeTcp(originHost(app), originPort, 1500)
        add("origin", originReachable, if (originReachable) "127.0.0.1:$originPort 可访问" else "127.0.0.1:$originPort 不可访问（RemoteAccessServer 未运行？）")

        // 4. DNS (cloudflared edge discovery host + api host)
        val dnsOk = runCatching { InetAddress.getByName("region1.v2.argotunnel.com") }.isSuccess
        add("dns", dnsOk, if (dnsOk) "region1.v2.argotunnel.com 解析正常" else "region1.v2.argotunnel.com 解析失败")

        // 5. TCP/7844
        val tcp7844 = probeTcpCloudflare(7844, 2500)
        add("tcp-7844", tcp7844, if (tcp7844) "TCP 7844 可达" else "TCP 7844 不可达（HTTP/2 路径可能仍可用）")

        // 6. UDP/7844
        val udp7844 = probeUdpCloudflare(7844)
        add("udp-7844", udp7844, if (udp7844) "UDP 7844 可达" else "UDP 7844 不可达或返回不可用（当前协议为 HTTP/2，不受影响）")

        // 7. management API 443
        val api443 = probeTcp("api.cloudflare.com", 443, 2500)
        add("api-443", api443, if (api443) "api.cloudflare.com:443 可达" else "api.cloudflare.com:443 不可达")

        // 8. current runtime health
        val h = _health.value
        add("runtime", when (h.phase) {
            Phase.HEALTHY -> true
            Phase.DEGRADED, Phase.RECONNECTING -> true
            Phase.ORIGIN_DOWN, Phase.EDGE_DOWN -> true
            else -> h.running
        }, "phase=${h.phase} · edge=${h.edgeConnected}/${h.edgeExpected} · protocol=${h.protocol}")

        out
    }

    // -------------------------------------------------------------- supervisor

    /**
     * Restart with bounded exponential backoff when cloudflared exits while
     * still enabled. Deliberately short first waits, capped ceiling, and NO
     * tight loop: cloudflared gets its own retry window first (the process
     * itself stays up), this only reacts to a process that is gone.
     */
    private fun scheduleSupervisor(context: Context, generation: Int) {
        Thread({
            var attempt = 0
            while (generation == supervisorGeneration) {
                attempt++
                val backoffMs = (1_000L shl attempt.coerceAtMost(6)).coerceAtMost(64_000L)
                Log.i(TAG, "supervisor: cloudflared exited, retry #$attempt in ${backoffMs}ms")
                try {
                    Thread.sleep(backoffMs)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (generation != supervisorGeneration) return@Thread
                if (!RemoteAccessPrefs.cloudflareTunnelEnabled(context)) {
                    recordPhase(Phase.STOPPED, "Tunnel disabled", markDisconnect = false)
                    return@Thread
                }
                val token = try {
                    RemoteAccessPrefs.cloudflareTunnelToken(context)
                } catch (_: Exception) {
                    null
                }
                if (token == null) return@Thread
                if (lastError.contains("not valid", ignoreCase = true) ||
                    lastError.contains("unauthorized", ignoreCase = true) ||
                    lastError.contains("401", ignoreCase = true) ||
                    lastError.contains("403", ignoreCase = true) ||
                    lastError.contains("token", ignoreCase = true) && lastError.contains("invalid", ignoreCase = true)
                ) {
                    // Bad credentials must not restart forever.
                    recordPhase(Phase.AUTH_FAILED, "认证失败：请检查 Tunnel Token（已停止重试）", markDisconnect = false)
                    return@Thread
                }
                val p = synchronized(lock) {
                    if (process?.isAlive == true) return@Thread
                    process
                }
                if (p == null) {
                    Log.i(TAG, "supervisor: restarting cloudflared (gen=$generation attempt=$attempt)")
                    runCatching { kotlinx.coroutines.runBlocking { start(context) } }
                }
            }
        }, "cloudflared-supervisor").apply { isDaemon = true; start() }
    }

    // ---------------------------------------------------------------- threads

    private fun drainProcess(p: Process, generation: Int) {
        var last = ""
        var lastErrorLine = ""
        var connectedSeen = false
        var expectedCount = 0
        val registeredIndexes = mutableSetOf<Int>()
        try {
            p.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    val clean = redact(line)
                    val kind = classifyLine(clean)
                    if (isNoise(clean)) return@forEach
                    logLine(
                        if (looksLikeError(clean)) "ERROR" else "INFO",
                        clean,
                        kind,
                    )
                    if (looksLikeError(clean)) lastErrorLine = clean
                    last = clean

                    val m = CONNECTION_LINE.find(clean) ?: CONNECTION_SIMPLE.find(clean)
                    if (m != null) {
                        // Index-tracked so a reconnect replaces, not adds.
                        val index = m.groupValues[1].toIntOrNull()
                        if (index != null) registeredIndexes.add(index)
                        if (m.groupValues.size >= 3 && m.groupValues[2].isNotEmpty()) {
                            expectedCount = m.groupValues[2].toIntOrNull() ?: expectedCount
                        }
                        edgeConnected = registeredIndexes.size
                        edgeExpected = expectedCount
                        recordEvent("Edge 连接已建立（${registeredIndexes.size}/${expectedCount.takeIf { it > 0 } ?: "?"}）")
                        if (expectedCount > 0 && registeredIndexes.size >= expectedCount && !connectedSeen) {
                            connectedSeen = true
                            lastConnectedAtMs = System.currentTimeMillis()
                            recordPhase(Phase.HEALTHY, "Tunnel 正常（${"${registeredIndexes.size}/${expectedCount}"}）")
                        } else if (!connectedSeen) {
                            if (_health.value.phase != Phase.CONNECTING) {
                                recordPhase(Phase.CONNECTING, "Edge 连接 ${registeredIndexes.size}/${expectedCount.takeIf { it > 0 } ?: "?"}")
                            }
                        } else if (registeredIndexes.size < expectedCount) {
                            // Healthy before and connections dropped: degraded,
                            // cloudflared is free to restore them itself.
                            recordPhase(Phase.DEGRADED, "Edge 连接降级：${registeredIndexes.size}/${expectedCount}")
                        } else if (_health.value.phase == Phase.DEGRADED || _health.value.phase == Phase.RECONNECTING) {
                            recordPhase(Phase.HEALTHY, "Tunnel 已恢复（${registeredIndexes.size}/${expectedCount}）")
                        }
                    } else if (clean.contains("reconnect", ignoreCase = true) ||
                        clean.contains("retrying", ignoreCase = true) ||
                        clean.contains("backoff", ignoreCase = true) ||
                        clean.contains("disconnected", ignoreCase = true)
                    ) {
                        if (_health.value.phase != Phase.RECONNECTING) {
                            reconnectCount++
                            recordEvent("cloudflared 正在重连（第 $reconnectCount 次）")
                        }
                        recordPhase(Phase.RECONNECTING, clean.take(220))
                    } else if (clean.contains("unable to reach", ignoreCase = true) ||
                        clean.contains("connection failed", ignoreCase = true) ||
                        clean.contains("timeout", ignoreCase = true) ||
                        clean.contains("503", ignoreCase = true) ||
                        clean.contains("bad gateway", ignoreCase = true)
                    ) {
                        recordPhase(Phase.EDGE_DOWN, clean.take(220))
                    } else if (clean.contains("certificate", ignoreCase = true) && clean.contains("error", ignoreCase = true)) {
                        recordPhase(Phase.AUTH_FAILED, "TLS 认证失败：${clean.take(220)}", markDisconnect = false)
                    }
                }
            }
        } catch (e: Exception) {
            last = e.message ?: "cloudflared output closed"
        } finally {
            if (lastErrorLine.isNotBlank()) last = lastErrorLine
            val exit = runCatching { p.waitFor() }.getOrNull()
            synchronized(lock) {
                if (process === p) process = null
            }
            if (generation == supervisorGeneration) {
                lastDisconnectedAtMs = System.currentTimeMillis()
                val enabled = managerContext?.let { RemoteAccessPrefs.cloudflareTunnelEnabled(it) } == true
                lastError = lastErrorLine.ifBlank { last }
                recordEvent("cloudflared 进程退出（exit ${exit?.toString() ?: "?"}）")
                recordPhase(
                    if (enabled) Phase.PROCESS_EXITED else Phase.STOPPED,
                    buildString {
                        append("cloudflared stopped")
                        if (exit != null) append(" (exit $exit)")
                        if (last.isNotBlank()) append(": ").append(last.take(180))
                    },
                    markDisconnect = false,
                )
                managerContext?.let { scheduleSupervisor(it, generation) }
            }
        }
    }

    // ------------------------------------------------------------- health pub

    /** Latest observed edge counts (written by the log drain; read by publish). */
    @Volatile private var edgeConnected = 0
    @Volatile private var edgeExpected = 0

    private fun publish(phase: String, detail: String) {
        val h = _health.value
        val snapshot = h.copy(
            phase = phase,
            detail = detail.take(220),
            running = process?.isAlive == true,
            protocol = latestProtocol,
            edgeConnected = edgeConnected,
            edgeExpected = edgeExpected,
            uptimeMs = if (startedAtMs > 0) System.currentTimeMillis() - startedAtMs else 0,
            lastConnectedAtMs = lastConnectedAtMs,
            lastDisconnectedAtMs = lastDisconnectedAtMs,
            reconnectCount = reconnectCount,
            lastError = if (phase == Phase.ERROR || phase == Phase.AUTH_FAILED) detail.take(220) else lastError,
            metrics = fetchMetricsLocked(),
        )
        publishHealth(snapshot)
    }

    private fun recordPhase(phase: String, detail: String, markDisconnect: Boolean = true) {
        if (markDisconnect) lastDisconnectedAtMs = System.currentTimeMillis()
        publish(phase, detail)
    }

    private fun publishHealth(h: HealthSnapshot) {
        _health.value = h
        _status.value = Status(
            installed = h.installed,
            running = h.running,
            phase = h.phase,
            detail = h.detail,
            version = h.version,
        )
    }

    /** Refresh process-derived fields only (no event emission). */
    private fun refreshHealthLocked(context: Context): HealthSnapshot {
        val file = binaryFile(context)
        val p = synchronized(lock) { process }
        val alive = p?.isAlive == true
        val current = _health.value
        val snapshot = current.copy(
            installed = file.isFile && file.length() > 1_000_000L,
            running = alive,
            phase = if (!alive && current.phase != Phase.STOPPED && current.phase != Phase.UNCONFIGURED && current.phase.isNotEmpty()) {
                if (RemoteAccessPrefs.cloudflareTunnelEnabled(context)) Phase.PROCESS_EXITED else Phase.STOPPED
            } else current.phase,
            protocol = latestProtocol,
            uptimeMs = if (startedAtMs > 0) System.currentTimeMillis() - startedAtMs else 0,
            metrics = fetchMetricsLocked(),
        )
        return snapshot
    }

    private fun logLine(level: String, text: String, kind: String) {
        synchronized(logBuffer) {
            logBuffer.addLast(LogLine(System.currentTimeMillis(), level, text, kind))
            while (logBuffer.size > LOG_BUFFER) logBuffer.removeFirst()
        }
    }

    private fun recordEvent(text: String) {
        synchronized(eventBuffer) {
            eventBuffer.addLast(Event(System.currentTimeMillis(), text))
            while (eventBuffer.size > EVENT_BUFFER) eventBuffer.removeFirst()
        }
    }

    // ---------------------------------------------------------------- metrics

    /**
     * cloudflared exposes a Prometheus endpoint when started with `--metrics`
     * (we add it in [prootProcessBuilder]); the snapshot here either reads the
     * real current counters or stays null when the endpoint is not up yet.
     * Metrics stay bound to 127.0.0.1 inside the PRoot namespace — the app's
     * loopback — and are never exposed to the network.
     */
    private fun fetchMetricsLocked(): MetricsSnapshot? {
        return try {
            val request = Request.Builder().url("http://127.0.0.1:$METRICS_PORT/metrics").get().build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                MetricsSnapshot(
                    totalRequests = metricValue(body, "cloudflared_tunnel_requests_total")
                        ?: metricValue(body, "tunnel_requests_total") ?: 0L,
                    requestErrors = metricValue(body, "cloudflared_tunnel_requests_errors_total")
                        ?: metricValue(body, "tunnel_requests_errors_total") ?: 0L,
                    connectionLatencyMs = metricValue(body, "tunnel_rtt")?.div(1000.0),
                    sampledAtMs = System.currentTimeMillis(),
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun metricValue(body: String, name: String): Long? {
        for (line in body.lineSequence()) {
            if (line.startsWith("#")) continue
            val key = line.substringBefore(' ')
            if (key != name) continue
            return line.substringAfter(' ').toLongOrNull()
        }
        return null
    }

    // ------------------------------------------------------------ diagnostics

    private fun probeTcp(host: String, port: Int, timeoutMs: Int): Boolean = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), timeoutMs)
            true
        }
    }.getOrDefault(false)

    private fun probeTcpCloudflare(port: Int, timeoutMs: Int): Boolean = runCatching {
        // Cloudflare's edge connectivity host (the same DNS the connector uses).
        Socket().use { s ->
            s.connect(InetSocketAddress("region1.v2.argotunnel.com", port), timeoutMs)
            true
        }
    }.getOrDefault(false)

    private fun probeUdpCloudflare(port: Int): Boolean {
        // A UDP send that doesn't throw only proves the local socket exists;
        // without a server reply it is IMPOSSIBLE to prove reachability, so
        // report honestly as unavailable and let the TCP/HTTP2 probe carry
        // the verdict. The send still surfaces an immediately-refused local
        // socket (rare) — but the conservative answer keeps the UI truthful.
        return runCatching {
            DatagramSocket().use { socket ->
                socket.soTimeout = 800
                val payload = ByteArray(24)
                socket.send(DatagramPacket(payload, payload.size, InetAddress.getByName("region1.v2.argotunnel.com"), port))
                false
            }
        }.getOrDefault(false)
    }

    private fun originHost(context: Context): String = "127.0.0.1"

    // -------------------------------------------------------------- classify

    private fun classifyLine(line: String): String {
        val l = line.lowercase()
        return when {
            "registered tunnel connection" in l || "connection registered" in l -> "connection"
            "reconnect" in l || "retry" in l || "backoff" in l -> "reconnect"
            "dns" in l || "couldn't resolve" in l || "lookup" in l -> "dns"
            "quic" in l -> "quic"
            "http2" in l || "http/2" in l -> "http2"
            "unauthorized" in l || "token" in l || "certificate" in l || "handshake" in l -> "auth"
            "origin" in l || "originurl" in l || "proxy" in l -> "origin"
            "error" in l || "failed" in l || "fatal" in l -> "error"
            else -> "other"
        }
    }

    /** Lines worth surfacing over whatever cloudflared printed last. */
    private fun looksLikeError(line: String): Boolean {
        val t = line.lowercase()
        return "not valid" in t || "invalid" in t || "error" in t ||
            "failed" in t || "unauthorized" in t || "cannot" in t ||
            "refused" in t || "no such" in t || "fatal" in t || "blocked" in t
    }

    /**
     * PRoot's talloc dumps its whole allocation table when the sandboxed
     * process exits. Dozens of lines of "NAME contains N bytes in M blocks",
     * none of which say anything about why the tunnel failed.
     */
    private fun isNoise(line: String): Boolean {
        val t = line.trim()
        return t.isEmpty() ||
            t.startsWith("talloc report on") ||
            t.startsWith("proot info:") ||
            TALLOC_BLOCK.containsMatchIn(t)
    }

    /**
     * Secret redaction: never allow a JWT-shaped token (or the raw token
     * value if cloudflared ever echoes it) into the log buffer, events, or
     * diagnostics. Defensive — the token travels via env, but upstream log
     * formats change.
     */
    internal fun redact(line: String): String {
        var out = line
            .replace(Regex("eyJ[A-Za-z0-9._=-]{24,}"), "<redacted-token>")
            .replace(Regex("(?i)tunnel[-_]?token[=: ]+[A-Za-z0-9._-]{10,}"), "tunnel_token=<redacted>")
        return out
    }

    private fun resolvedProtocol(context: Context): String {
        // README: mobile networks fix HTTP/2 (carrier UDP/QUIC is unreliable).
        // "auto" (the default) resolves to http2; users may pin quic explicitly.
        val configured = RemoteAccessPrefs.cloudflareTunnelProtocol(context)
        latestProtocol = if (configured == "quic") "quic" else "http2"
        return latestProtocol
    }

    // ------------------------------------------------------------- lifecycle

    /** Network-change listener: proactively log and re-sync supervision. */
    fun registerNetworkCallback(context: Context) {
        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    recordEvent("网络已切换/恢复（${describeNetwork(cm, network)}）")
                    // Let cloudflared's own reconnect run; if the process died,
                    // the supervisor (or the next onLost→onAvailable edge) revives
                    // it. Nothing here polls.
                }

                override fun onLost(network: Network) {
                    recordEvent("网络连接丢失（${describeNetwork(cm, network)}）")
                }
            })
        }
    }

    private fun describeNetwork(cm: ConnectivityManager, network: Network): String {
        return runCatching {
            val caps = cm.getNetworkCapabilities(network) ?: return "unknown"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "蜂窝网络"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> "其他网络"
            }
        }.getOrDefault("unknown")
    }

    // ------------------------------------------------------------------ infra

    internal fun binaryFile(context: Context): File {
        managerContext = context.applicationContext
        return File(RootfsManager.getInstance(context.applicationContext).rootfsDir, "opt/bin/cloudflared")
    }

    private fun prootProcessBuilder(context: Context, command: String): ProcessBuilder {
        val pb = ProcessBuilder(PRootKernel.buildProotCommand(command))
        pb.redirectErrorStream(true)
        val env = pb.environment()
        env["PROOT_TMP_DIR"] = PRootKernel.getProotTmpDir(context).absolutePath
        if (PRootKernel.nativeLibDir.isNotEmpty()) env["LD_LIBRARY_PATH"] = PRootKernel.nativeLibDir
        if (PRootKernel.prootLoaderPath.isNotEmpty()) env["PROOT_LOADER"] = PRootKernel.prootLoaderPath
        if (PRootKernel.prootLoader32Path.isNotEmpty()) env["PROOT_LOADER_32"] = PRootKernel.prootLoader32Path
        for ((key, value) in PRootKernel.customEnvironment) env[key] = value
        return pb
    }

    private fun runOneShot(
        context: Context,
        command: String,
        environment: Map<String, String>,
        timeoutMs: Long,
    ): Pair<Int, String> {
        val pb = prootProcessBuilder(context, command)
        pb.environment().putAll(environment)
        val p = pb.start()
        val text = StringBuilder()
        val reader = Thread {
            runCatching { p.inputStream.bufferedReader(Charsets.UTF_8).use { text.append(it.readText()) } }
        }.apply { isDaemon = true; start() }
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly()
            reader.join(1_000L)
            return -1 to "Timed out"
        }
        reader.join(1_000L)
        return p.exitValue() to text.toString()
    }
}
