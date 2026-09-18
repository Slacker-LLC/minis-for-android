package com.openminis.app.mcp.client

import android.content.Context
import android.util.Log
import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.tools.runtime.MCPToolHandler
import com.openminis.app.tools.runtime.ToolRegistry
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

/**
 * MCPProvider — Minis as an MCP **client**: connects every enabled server in
 * `servers.json` (MCPRepository), performs the 2025-06-18 initialize
 * handshake, pulls tools and registers them in ToolRegistry as
 * `mcp.<serverId>.<tool>` (policy table `mcp.*` governs them, LOCAL_ONLY).
 *
 * Reload (`reload()`) tears all sessions down and reconnects with bounded
 * concurrency so one unreachable server cannot serialize the entire reload.
 * MCPRepository binds its global-config change callback here during [init], so
 * UI/debug/import/disk-refresh mutations share this same reload path.
 */
object MCPProvider {

    private const val TAG = "MCPProvider"
    private const val MAX_CONCURRENT_CONNECTIONS = 4
    private const val SERVER_LOAD_TIMEOUT_MS = 45_000L

    data class ServerStatus(
        val serverId: String,
        val connected: Boolean,
        val toolCount: Int,
        val error: String? = null,
    )

    private data class ConnectedServer(
        val session: MCPClientSession,
        val tools: List<MCPClientCodec.RemoteTool>,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val registryLock = Any()

    private val _status = MutableStateFlow<List<ServerStatus>>(emptyList())
    val status: StateFlow<List<ServerStatus>> = _status.asStateFlow()

    private val sessions = ConcurrentHashMap<String, MCPClientSession>()
    private val registeredTools = ConcurrentHashMap<String, MCPToolHandler>()

    @Volatile
    private var repository: MCPRepository? = null

    @Volatile
    private var reloadGeneration: Long = 0

    @Volatile
    private var reloadJob: Job? = null

    /** App context placeholder for handler dispatch (handlers ignore it). */
    @Volatile
    var context: Context? = null

    /**
     * Attaches the config source and its hot-update callback; call [reload] once
     * after init for the initial connection set. Re-init detaches the previous
     * Repository so a stale object cannot trigger reloads against the new one.
     */
    fun init(repository: MCPRepository, context: Context? = null) {
        this.repository?.onServerConfigsChanged = null
        this.repository = repository
        this.context = context
        repository.onServerConfigsChanged = ::reload
    }

    /** Reconnects all enabled servers and re-registers their tools. */
    fun reload() {
        val repo = repository ?: return
        val servers = repo.servers.value.filter { it.enabled }
        synchronized(registryLock) {
            reloadGeneration += 1
            val generation = reloadGeneration
            reloadJob?.cancel()
            unregisterAllLocked()
            _status.value = emptyList()
            reloadJob = scope.launch {
                val outcomes = mapConcurrentBounded(
                    items = servers,
                    maxConcurrency = MAX_CONCURRENT_CONNECTIONS,
                    timeoutMs = SERVER_LOAD_TIMEOUT_MS,
                ) { cfg ->
                    val connected = connectOne(cfg)
                    if (!commitConnected(generation, cfg, connected)) {
                        connected.session.close()
                        throw CancellationException("superseded MCP reload")
                    }
                    ServerStatus(cfg.id, connected = true, toolCount = connected.tools.size)
                }

                if (!isActive || generation != reloadGeneration) return@launch
                val statuses = servers.zip(outcomes).map { (cfg, outcome) ->
                    outcome.getOrElse { error -> failureStatus(cfg.id, error) }
                }
                synchronized(registryLock) {
                    if (generation == reloadGeneration) {
                        _status.value = statuses
                    }
                }
                Log.i(
                    TAG,
                    "reload done: ${statuses.count { it.connected }}/${servers.size} connected, " +
                        "${statuses.sumOf { it.toolCount }} tools registered",
                )
            }
        }
    }

    private suspend fun connectOne(cfg: MCPRepository.MCPServerConfig): ConnectedServer {
        val session = MCPClientSession(cfg)
        try {
            session.connect()
            return ConnectedServer(session, session.listTools())
        } catch (t: Throwable) {
            session.close()
            if (t is CancellationException) throw t
            Log.w(TAG, "connect ${cfg.id} failed: ${t.message}")
            throw t
        }
    }

    private fun commitConnected(
        generation: Long,
        cfg: MCPRepository.MCPServerConfig,
        connected: ConnectedServer,
    ): Boolean = synchronized(registryLock) {
        if (generation != reloadGeneration) return@synchronized false
        val sanitizedId = sanitizeId(cfg.id)
        val added = mutableListOf<String>()
        try {
            connected.tools.forEach { tool ->
                val handler = MCPToolHandler(sanitizedId, tool, connected.session)
                val fullName = "mcp.$sanitizedId.${tool.name}"
                ToolRegistry.register(handler)
                registeredTools[fullName] = handler
                added += fullName
            }
            sessions[cfg.id] = connected.session
            val status = ServerStatus(cfg.id, connected = true, toolCount = connected.tools.size)
            _status.value = _status.value.filter { it.serverId != cfg.id } + status
            true
        } catch (t: Throwable) {
            added.forEach { name ->
                ToolRegistry.unregister(name)
                registeredTools.remove(name)
            }
            connected.session.close()
            throw t
        }
    }

    private fun failureStatus(serverId: String, error: Throwable): ServerStatus {
        val detail = if (error is TimeoutCancellationException) {
            "server load timed out after ${SERVER_LOAD_TIMEOUT_MS}ms"
        } else {
            error.message ?: error::class.java.simpleName
        }
        Log.w(TAG, "connect $serverId failed: $detail")
        return ServerStatus(serverId, connected = false, toolCount = 0, error = detail)
    }

    /** Drops every registered remote tool and closes sessions. */
    fun unregisterAll() {
        synchronized(registryLock) {
            reloadGeneration += 1
            reloadJob?.cancel()
            reloadJob = null
            unregisterAllLocked()
            _status.value = emptyList()
        }
    }

    private fun unregisterAllLocked() {
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
            "mcp provider not initialized",
            false,
        )
        return handler.execute(arguments.toString(), "", ctx, "")
    }

    /** Tool-name-safe server id (ToolRegistry names are dot-separated). */
    private fun sanitizeId(id: String): String =
        id.replace(Regex("[^a-zA-Z0-9_.-]"), "_")

    internal suspend fun <T, R> mapConcurrentBounded(
        items: List<T>,
        maxConcurrency: Int,
        timeoutMs: Long,
        block: suspend (T) -> R,
    ): List<Result<R>> {
        require(maxConcurrency > 0) { "maxConcurrency must be positive" }
        require(timeoutMs > 0) { "timeoutMs must be positive" }
        val semaphore = Semaphore(maxConcurrency)
        return kotlinx.coroutines.supervisorScope {
            items.map { item ->
                async {
                    try {
                        Result.success(
                            semaphore.withPermit {
                                withTimeout(timeoutMs) { block(item) }
                            },
                        )
                    } catch (timeout: TimeoutCancellationException) {
                        Result.failure(timeout)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (t: Throwable) {
                        Result.failure(t)
                    }
                }
            }.awaitAll()
        }
    }
}
