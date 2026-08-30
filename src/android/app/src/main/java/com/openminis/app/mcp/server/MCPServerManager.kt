package com.openminis.app.mcp.server

import android.content.Context
import android.util.Log
import com.openminis.app.runtime.ubuntu.UbuntuRuntime
import com.openminis.app.tools.runtime.ToolPermissionManager
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Lifecycle gatekeeper for the on-device MCP server.
 *
 * Production exposure is deliberately local-only: Streamable HTTP on
 * 127.0.0.1. Network/LAN exposure is a separate security contract and must not
 * be enabled by changing a UI preference alone.
 */
object MCPServerManager {

    private const val TAG = "MCPServerManager"

    const val HOST = "127.0.0.1"
    const val PORT = 18789
    const val PATH = "/mcp"
    internal const val MANAGED_TOKEN_ID = "android-settings"

    private const val PREFS = "minis_mcp_prefs"
    private const val KEY_ENABLED = "mcp_server_enabled"
    private const val SUPERVISOR_POLL_MS = 3_000L
    private const val RESTART_DELAY_MS = 1_000L
    internal const val MAX_CONSECUTIVE_RESTARTS = 5

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var configured: Boolean = false
        private set

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var server: MCPServer? = null

    @Volatile
    private var stopRequested: Boolean = false

    @Volatile
    private var supervisorJob: Job? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        TokenStore.init(context)
        refreshConfigured()
        if (isEnabled()) {
            Log.i(TAG, "mcp_server_enabled=true — auto-starting")
            start()
        } else {
            Log.i(TAG, "init: configured=$configured, auto-start off (mcp_server_enabled=false)")
        }
    }

    /** Re-read TokenStore so credentials created after app startup are live. */
    fun refreshConfigured(): Boolean {
        configured = TokenStore.isConfigured
        return configured
    }

    /** Fail closed on every start attempt, not only at application init. */
    fun start(): Boolean {
        if (!refreshConfigured()) {
            lastError = "Access token required"
            Log.w(TAG, "start() refused: MCP token not configured (fail-closed)")
            return false
        }
        if (running) {
            lastError = null
            return true
        }
        stopRequested = false
        val ok = startServerInstance()
        if (ok) {
            lastError = null
            startSupervisor()
            appContext?.let { MCPKeepAliveService.start(it) }
        } else if (lastError == null) {
            lastError = "Unable to bind ${endpointUrl()}"
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

    /**
     * Settings/boot preference and runtime state are changed together. Enabling
     * with no credential fails closed and leaves boot auto-start disabled.
     */
    fun setEnabled(enabled: Boolean): Boolean {
        val ctx = appContext
        if (enabled && !refreshConfigured()) {
            ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                ?.edit()?.putBoolean(KEY_ENABLED, false)?.apply()
            lastError = "Access token required"
            return false
        }
        ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
        Log.i(TAG, "mcp_server_enabled=$enabled")
        return if (enabled) start() else {
            stop()
            lastError = null
            true
        }
    }

    fun isEnabled(): Boolean =
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.getBoolean(KEY_ENABLED, false) ?: false

    fun endpointUrl(): String = "http://$HOST:$PORT$PATH"

    /**
     * The Android settings-created credential is intentionally narrow by
     * default: only tools classified MCP_ALLOWED (no human-confirm tools).
     * The explicit scope is frozen into the token so newly added tools do not
     * become remotely callable without a later user scope edit.
     */
    fun createOrRotateManagedToken(): TokenStore.Token? {
        val caller = "mcp:$MANAGED_TOKEN_ID"
        val safeScope = ToolPermissionManager.mcpVisibleTools()
            .asSequence()
            .filterNot { '*' in it }
            .filter { ToolPermissionManager.levelFor(it, caller) == ToolPermissionManager.Level.MCP_ALLOWED }
            .toSortedSet()
        if (safeScope.isEmpty()) {
            lastError = "No safe MCP tools are currently available"
            return null
        }
        val token = TokenStore.Token(
            id = MANAGED_TOKEN_ID,
            token = generateTokenValue(),
            scope = safeScope,
        )
        TokenStore.upsert(token)
        refreshConfigured()
        lastError = null
        if (isEnabled() && !running) start()
        return token
    }

    fun managedToken(): TokenStore.Token? = TokenStore.findById(MANAGED_TOKEN_ID)

    fun availableToolsForManagedToken(): List<String> =
        ToolPermissionManager.mcpVisibleTools()
            .filterNot { '*' in it }
            .sorted()

    /** Empty scope has legacy "all visible" semantics, so UI never writes it. */
    fun updateManagedTokenScope(scope: Set<String>): Boolean {
        if (scope.isEmpty()) return false
        val token = managedToken() ?: return false
        val allowedNames = availableToolsForManagedToken().toSet()
        val normalized = scope.intersect(allowedNames)
        if (normalized.isEmpty()) return false
        TokenStore.upsert(token.copy(scope = normalized))
        refreshConfigured()
        return true
    }

    /** Revokes only the credential managed by Android Settings. */
    fun revokeManagedToken(): Boolean {
        val changed = TokenStore.remove(MANAGED_TOKEN_ID)
        refreshConfigured()
        if (!configured) setEnabled(false)
        return changed
    }

    fun connectionConfig(token: TokenStore.Token): String =
        org.json.JSONObject()
            .put(
                "mcpServers",
                org.json.JSONObject().put(
                    "minis-android",
                    org.json.JSONObject()
                        .put("url", endpointUrl())
                        .put(
                            "headers",
                            org.json.JSONObject().put("Authorization", "Bearer ${token.token}"),
                        ),
                ),
            )
            .toString(2)

    internal fun generateTokenValue(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** DEBUG-only approve path used by DebugRPCHandler for E2E. */
    fun debugApproveConfirm(confirmId: String, method: String): String? =
        server?.approveConfirm(confirmId, method)?.name

    private fun startServerInstance(): Boolean {
        val ctx = appContext
        if (ctx == null) {
            lastError = "MCP server is not initialized"
            Log.e(TAG, "start() before init()")
            return false
        }
        val s = MCPServer(ctx, PORT)
        s.start()
        server = s
        running = s.isRunning
        if (!running) lastError = "Unable to bind ${endpointUrl()}"
        Log.i(TAG, "MCP server started: running=$running endpoint=${endpointUrl()}")
        return running
    }

    private fun startSupervisor() {
        supervisorJob?.cancel()
        supervisorJob = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            var consecutiveRestarts = 0
            while (isActive && !stopRequested) {
                delay(SUPERVISOR_POLL_MS)
                val s = server ?: continue
                if (s.isRunning) {
                    consecutiveRestarts = 0
                    continue
                }
                if (!running) continue
                if (!shouldRestart(consecutiveRestarts)) {
                    running = false
                    lastError = "Server stopped after repeated crashes"
                    Log.e(TAG, "MCP server crashed $MAX_CONSECUTIVE_RESTARTS times in a row; giving up")
                    break
                }
                consecutiveRestarts++
                Log.w(TAG, "MCP server died; restarting ($consecutiveRestarts/$MAX_CONSECUTIVE_RESTARTS)")
                delay(RESTART_DELAY_MS)
                if (stopRequested || supervisorJob !== currentCoroutineContext()[Job]) break
                startServerInstance()
            }
        }
    }

    internal fun shouldRestart(consecutiveRestarts: Int): Boolean =
        consecutiveRestarts in 0 until MAX_CONSECUTIVE_RESTARTS

    data class Status(
        val running: Boolean,
        val configured: Boolean,
        val port: Int,
        val enabled: Boolean,
        val endpoint: String,
        val tokenCount: Int,
        val lastError: String?,
    )

    fun status(): Status {
        refreshConfigured()
        return Status(
            running = running,
            configured = configured,
            port = PORT,
            enabled = isEnabled(),
            endpoint = endpointUrl(),
            tokenCount = TokenStore.all().size,
            lastError = lastError,
        )
    }

    fun linuxToolsAvailable(): Boolean = UbuntuRuntime.snapshot.value.running
}
