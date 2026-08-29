package io.github.slackerllc.minis.mcp.server

import android.content.Context
import android.util.Log
import io.github.slackerllc.minis.sandbox.ubuntu.UbuntuRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * [T-android-mcp-server] Lifecycle gatekeeper for the on-device MCP server.
 *
 * - Fail-closed: starts only when [TokenStore] is configured; otherwise
 *   [start] refuses with a log line and no user-visible notification.
 * - No auto-start: [MinisApp.onCreate] calls [init] only. Boot / settings
 *   explicitly opt in via the `mcp_server_enabled` pref (default false).
 * - Liveness signal: [linuxToolsAvailable] is the single gate W2's linux.*
 *   dispatch consults; false ⇒ tool answers `ubuntu_runtime_unavailable`.
 *
 * WIRING-PENDING (W3): [TokenStore] (W1) and [MCPServer] (W2) were not yet in
 * the tree when this landed, so their references below are commented inside
 * clearly marked blocks. Uncomment when both exist — every commented line also
 * appears in this file's own diff, nothing else changes.
 */
object MCPServerManager {

    private const val TAG = "MCPServerManager"

    /** Port the MCP server binds. Matches W2's MCPServer(context, port). */
    const val PORT = 18789

    private const val PREFS = "minis_mcp_prefs"
    private const val KEY_ENABLED = "mcp_server_enabled"

    /** Supervisor liveness poll (07 §7): checks server health every 3 s. */
    private const val SUPERVISOR_POLL_MS = 3_000L

    /** Delay between crash detection and the restart attempt. */
    private const val RESTART_DELAY_MS = 1_000L

    /** Max consecutive restarts before giving up (restart-storm guard). */
    internal const val MAX_CONSECUTIVE_RESTARTS = 5

    @Volatile
    var running: Boolean = false
        private set

    /** Refreshed by [init] from TokenStore.isConfigured (see WIRING-PENDING). */
    @Volatile
    var configured: Boolean = false
        private set

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var server: MCPServer? = null

    /** True between [stop] and the next [start]: the supervisor must never
     * resurrect the server after a user stop. */
    @Volatile
    private var stopRequested: Boolean = false

    /** Supervisor coroutine that watches [server] and restarts it on crash. */
    @Volatile
    private var supervisorJob: Job? = null

    /**
     * Application-context wiring. Reads the token-configured flag and, when
     * the user opted in at boot (`mcp_server_enabled=true`), starts the
     * server. Never auto-starts otherwise.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        TokenStore.init(context)
        configured = TokenStore.isConfigured
        if (isEnabled()) {
            Log.i(TAG, "mcp_server_enabled=true — auto-starting")
            start()
        } else {
            Log.i(TAG, "init: configured=$configured, auto-start off (mcp_server_enabled=false)")
        }
    }

    /**
     * Fail-closed: no token ⇒ refuse (logged, no notification). Idempotent
     * when already running.
     */
    fun start(): Boolean {
        if (!configured) {
            Log.w(TAG, "start() refused: MCP token not configured (fail-closed)")
            return false
        }
        if (running) return true
        stopRequested = false
        val ok = startServerInstance()
        if (ok) {
            startSupervisor()
            // Keep-alive: MCP must survive lock-screen app freezing (HyperOS
            // freezes loopback sockets of background apps). The dedicated
            // foreground service keeps the process exempt from that.
            appContext?.let { MCPKeepAliveService.start(it) }
        }
        return ok
    }

    fun stop() {
        stopRequested = true
        supervisorJob?.cancel()
        supervisorJob = null
        appContext?.let { MCPKeepAliveService.stop(it) }
        server?.stop()
        server = null
        running = false
        Log.i(TAG, "MCP server stopped")
    }

    /** DEBUG-only approve path used by [DebugRPCHandler] for E2E on devices
     *  whose notification actions are collapsed by the OEM (e.g. HyperOS).
     *  Returns the ConfirmQueue Result name, or null when no server is up. */
    fun debugApproveConfirm(confirmId: String, method: String): String? =
        server?.approveConfirm(confirmId, method)?.name

    /** Starts a fresh [MCPServer] on [PORT]; updates [server]/[running]. */
    private fun startServerInstance(): Boolean {
        val ctx = appContext
        if (ctx == null) {
            Log.e(TAG, "start() before init()")
            return false
        }
        val s = MCPServer(ctx, PORT)
        s.start()
        server = s
        running = s.isRunning
        Log.i(TAG, "MCP server started: running=$running port=$PORT")
        return running
    }

    /**
     * Crash supervisor (07 §7): every [SUPERVISOR_POLL_MS] checks that
     * [server] is alive while [running] says it should be. On crash it swaps
     * in a fresh instance, at most [MAX_CONSECUTIVE_RESTARTS] times in a row;
     * beyond that it gives up ([running] = false) to avoid a restart storm.
     * [stop] cancels this job, so a user stop is never resurrected.
     */
    private fun startSupervisor() {
        supervisorJob?.cancel()
        supervisorJob = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            var consecutiveRestarts = 0
            while (isActive && !stopRequested) {
                delay(SUPERVISOR_POLL_MS)
                val s = server ?: continue
                if (s.isRunning) {
                    consecutiveRestarts = 0 // healthy observation resets the storm counter
                    continue
                }
                if (!running) continue // expected-down (start refused) ⇒ no restart
                if (!shouldRestart(consecutiveRestarts)) {
                    running = false
                    Log.e(TAG, "MCP server crashed $MAX_CONSECUTIVE_RESTARTS times in a row; giving up (restart-storm guard)")
                    break
                }
                consecutiveRestarts++
                Log.w(TAG, "MCP server died; restarting ($consecutiveRestarts/$MAX_CONSECUTIVE_RESTARTS)")
                delay(RESTART_DELAY_MS)
                // stale loop after a stop()/start() in the meantime ⇒ bail out
                if (stopRequested || supervisorJob !== currentCoroutineContext()[Job]) break
                startServerInstance()
            }
        }
    }

    /** Pure restart gate: true while [consecutiveRestarts] is under the storm
     * guard. Extracted from the supervisor for JVM-testable counting logic. */
    internal fun shouldRestart(consecutiveRestarts: Int): Boolean =
        consecutiveRestarts in 0 until MAX_CONSECUTIVE_RESTARTS

    data class Status(val running: Boolean, val configured: Boolean, val port: Int)

    fun status(): Status = Status(running, configured, PORT)

    // -- boot opt-in pref -----------------------------------------------------

    fun isEnabled(): Boolean =
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.getBoolean(KEY_ENABLED, false) ?: false

    /** Settings page hook; also the only way boot auto-start turns on. */
    fun setEnabled(enabled: Boolean) {
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
        Log.i(TAG, "mcp_server_enabled=$enabled")
    }

    /**
     * 存活语义 (liveness): linux.* tools may only be dispatched while the
     * Ubuntu Runtime is actually running. W2 calls this before dispatch;
     * when it returns false the tool answers `ubuntu_runtime_unavailable`.
     * Dispatch lives in W2's tool layer — this file only exposes the signal.
     */
    fun linuxToolsAvailable(): Boolean = UbuntuRuntime.snapshot.value.running
}
