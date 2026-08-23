package com.openminis.app.mcp.client

import android.content.Context
import android.util.Log
import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.tools.runtime.MCPToolHandler
import com.openminis.app.tools.runtime.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * MCPProvider — Minis as an MCP **client**: connects every enabled server in
 * `mcp_servers.json` (MCPRepository), performs the 2025-06-18 initialize
 * handshake, pulls tools and registers them in ToolRegistry as
 * `mcp.<serverId>.<tool>` (policy table `mcp.*` governs them, LOCAL_ONLY).
 *
 * Reload (`reload()`) tears all sessions down and reconnects — the same
 * hot-update contract as MiClaw's reload_mcp_config.
 */
object MCPProvider {

    private const val TAG = "MCPProvider"

    data class ServerStatus(
        val serverId: String,
        val connected: Boolean,
        val toolCount: Int,
        val error: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow<List<ServerStatus>>(emptyList())
    val status: StateFlow<List<ServerStatus>> = _status.asStateFlow()

    private val sessions = java.util.concurrent.ConcurrentHashMap<String, MCPClientSession>()
    private val registeredTools = java.util.concurrent.ConcurrentHashMap<String, MCPToolHandler>()

    @Volatile
    private var repository: MCPRepository? = null

    /** App context placeholder for handler dispatch (handlers ignore it). */
    @Volatile
    var context: Context? = null

    /** Attaches the config source; call [reload] to connect. */
    fun init(repository: MCPRepository, context: Context? = null) {
        this.repository = repository
        this.context = context
    }

    /** Reconnects all enabled servers and re-registers their tools. */
    fun reload() {
        val repo = repository ?: return
        unregisterAll()
        val servers = repo.servers.value.filter { it.enabled }
        scope.launch {
            val statuses = servers.map { cfg ->
                val st = connectOne(cfg)
                _status.value = _status.value.filter { it.serverId != cfg.id } + st
                st
            }
            Log.i(
                TAG,
                "reload done: ${statuses.count { it.connected }}/${servers.size} connected, " +
                    "${statuses.sumOf { it.toolCount }} tools registered",
            )
        }
    }

    private fun connectOne(cfg: MCPRepository.MCPServerConfig): ServerStatus {
        val session = MCPClientSession(cfg)
        try {
            kotlinx.coroutines.runBlocking { session.connect() }
            val tools = kotlinx.coroutines.runBlocking { session.listTools() }
            val sanitizedId = sanitizeId(cfg.id)
            tools.forEach { tool ->
                val handler = MCPToolHandler(sanitizedId, tool, session)
                ToolRegistry.register(handler)
                registeredTools["mcp.$sanitizedId.${tool.name}"] = handler
            }
            sessions[cfg.id] = session
            return ServerStatus(cfg.id, connected = true, toolCount = tools.size)
        } catch (t: Throwable) {
            session.close()
            Log.w(TAG, "connect ${cfg.id} failed: ${t.message}")
            return ServerStatus(cfg.id, connected = false, toolCount = 0, error = t.message)
        }
    }

    /** Drops every registered remote tool and closes sessions. */
    fun unregisterAll() {
        registeredTools.keys.forEach { ToolRegistry.unregister(it) }
        registeredTools.clear()
        sessions.values.forEach { it.close() }
        sessions.clear()
    }

    /** Dispatches a call to a connected remote tool; null when unknown. */
    suspend fun callRemoteTool(
        fullName: String,
        arguments: org.json.JSONObject,
    ): com.openminis.app.tools.ToolExecutionResult? {
        val handler = registeredTools[fullName] ?: return null
        // MCPToolHandler ignores context; only needs a non-null placeholder.
        val ctx = MCPProvider.context ?: return com.openminis.app.tools.ToolExecutionResult(
            "mcp provider not initialized", false,
        )
        return handler.execute(arguments.toString(), "", ctx, "")
    }

    /** Tool-name-safe server id (ToolRegistry names are dot-separated). */
    private fun sanitizeId(id: String): String =
        id.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
}
