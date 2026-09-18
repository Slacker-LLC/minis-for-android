package com.openminis.app.mcp.client

import android.util.Log
import org.json.JSONObject

/**
 * One connected MCP server session: initialize handshake (protocol version
 * check), paginated tools/list, and tools/call dispatch. Owns the transport.
 */
class MCPClientSession(
    private val config: com.openminis.app.data.repository.MCPRepository.MCPServerConfig,
    private val bearerToken: String? = null,
) {

    companion object {
        private const val TAG = "MCPClientSession"

        /** [T-android-mcp-tool-bounds] A server advertises a bounded tool set.
         *  Pagination is already guarded by [MAX_LIST_PAGES]; this bounds the
         *  total a client will hold, so one server cannot turn the local tool
         *  registry and every provider request into an unbounded payload. */
        const val MAX_DISCOVERED_TOOLS = 256

        /** Hard stop for a server whose `nextCursor` never ends. */
        const val MAX_LIST_PAGES = 50
    }

    private var transport: Transport? = null
    private var nextId: Long = 100

    @Volatile
    var serverName: String? = null
        private set

    @Volatile
    var serverVersion: String? = null
        private set

    interface Transport {
        /**
         * [extraHeaders] carries the tool parameters the server asked to receive as
         * headers (`x-mcp-header`); transports that have no headers ignore them.
         */
        suspend fun send(frame: JSONObject, extraHeaders: Map<String, String> = emptyMap()): JSONObject
        /** Records the negotiated protocol version for transports that must send it. */
        fun setProtocolVersion(version: String) {}
        fun close()
    }

    private class HttpAdapter(private val http: MCPHttpTransport) : Transport {
        override suspend fun send(frame: JSONObject, extraHeaders: Map<String, String>): JSONObject =
            http.send(frame, extraHeaders)
        override fun setProtocolVersion(version: String) = http.setProtocolVersion(version)
        override fun close() = http.close()
    }

    private class StdioAdapter(private val stdio: MCPStdioTransport) : Transport {
        override suspend fun send(frame: JSONObject, extraHeaders: Map<String, String>): JSONObject =
            stdio.send(frame)
        override fun close() = stdio.close()
    }

    suspend fun connect() {
        val t: Transport = if (config.isStdio) {
            val s = MCPStdioTransport(config.command!!, config.args, config.env)
            s.start()
            StdioAdapter(s)
        } else {
            HttpAdapter(MCPHttpTransport(config.url!!, config.headers, bearerToken))
        }
        transport = t
        val initReply = t.send(MCPClientCodec.buildInitialize(clientName = "minis-android"))
        val info = MCPClientCodec.parseInitializeResult(initReply)
            ?: throw MCPTransportException("initialize rejected: $initReply")
        if (info.protocolVersion != MCPClientCodec.PROTOCOL_VERSION) {
            throw MCPTransportException(
                "protocol mismatch: server=${info.protocolVersion} " +
                    "client=${MCPClientCodec.PROTOCOL_VERSION}",
            )
        }
        serverName = info.serverName
        serverVersion = info.serverVersion
        // [T-mcp-protocol-headers-android] Every request after the handshake names the
        // negotiated version; stdio has no headers and ignores this.
        t.setProtocolVersion(info.protocolVersion)
        // Spec: client must send notifications/initialized after initialize.
        t.send(MCPClientCodec.buildNotificationsInitialized())
        Log.i(TAG, "connected ${config.id} -> ${info.serverName}@${info.serverVersion}")
    }

    /** Paginated tools/list: follows nextCursor until exhausted. */
    suspend fun listTools(): List<MCPClientCodec.RemoteTool> {
        val t = transport ?: throw MCPTransportException("session not connected")
        val out = mutableListOf<MCPClientCodec.RemoteTool>()
        val schemaBudget = MCPClientCodec.SchemaBudget()
        var cursor: String? = null
        var guard = 0
        do {
            if (++guard > MAX_LIST_PAGES) {
                throw MCPTransportException("tools/list pagination runaway")
            }
            val reply = t.send(MCPClientCodec.buildToolsList(cursor))
            val page = MCPClientCodec.parseToolsList(reply)
                ?: throw MCPTransportException("tools/list rejected: $reply")
            // [T-android-mcp-schema-bounds] Per-tool bounds are in the codec; this one is
            // the sum. A tool whose schema does not fit the budget is kept without it —
            // callable, just untyped — rather than dropped from the list.
            page.tools.forEach { tool ->
                val schema = tool.inputSchema
                val chars = schema?.toString()?.length ?: 0
                out += if (schema == null || schemaBudget.accept(chars)) {
                    tool
                } else {
                    tool.copy(inputSchema = null)
                }
            }
            // [T-mcp-param-headers-android] Derive the parameter-to-header bindings while
            // the schema is in hand; a tool whose schema declares none simply gets none.
            page.tools.forEach { tool ->
                val schema = tool.inputSchema ?: return@forEach
                toolHeaders[tool.name] = McpToolHeaders.fromSchema(schema)
            }
            if (page.skippedTools > 0) {
                Log.w(TAG, "${config.id}: skipped ${page.skippedTools} unusable tool entries")
            }
            if (out.size > MAX_DISCOVERED_TOOLS) {
                throw MCPTransportException(
                    "${config.id}: server advertises more than $MAX_DISCOVERED_TOOLS tools; " +
                        "refusing the list",
                )
            }
            cursor = page.nextCursor
        } while (cursor != null)
        return out
    }

    suspend fun callTool(name: String, arguments: JSONObject): MCPClientCodec.CallResult {
        val t = transport ?: throw MCPTransportException("session not connected")
        val extraHeaders = toolHeaders[name]?.extract(arguments).orEmpty()
        val reply = t.send(MCPClientCodec.buildToolsCall(name, arguments, nextId++), extraHeaders)
        return MCPClientCodec.parseCallResult(reply)
            ?: throw MCPTransportException("tools/call rejected: $reply")
    }

    /**
     * [T-mcp-param-headers-android] The parameter-to-header bindings of every tool this
     * session lists, derived from the schema's `x-mcp-header` annotations. Ported from Eta
     * `agent/mcp/McpHttpClient.kt` (Mangi-11/Eta @ c15de97), which caches the same map per
     * tool name.
     */
    private val toolHeaders = mutableMapOf<String, McpToolHeaders>()

    fun close() {
        transport?.close()
        transport = null
    }
}
