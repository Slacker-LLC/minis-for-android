package io.github.slackerllc.minis.mcp.client

import android.util.Log
import org.json.JSONObject

/**
 * One connected MCP server session: initialize handshake (protocol version
 * check), paginated tools/list, and tools/call dispatch. Owns the transport.
 */
class MCPClientSession(
    private val config: io.github.slackerllc.minis.data.repository.MCPRepository.MCPServerConfig,
    private val bearerToken: String? = null,
) {

    companion object {
        private const val TAG = "MCPClientSession"
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
        suspend fun send(frame: JSONObject): JSONObject
        fun close()
    }

    private class HttpAdapter(private val http: MCPHttpTransport) : Transport {
        override suspend fun send(frame: JSONObject): JSONObject = http.send(frame)
        override fun close() = http.close()
    }

    private class StdioAdapter(private val stdio: MCPStdioTransport) : Transport {
        override suspend fun send(frame: JSONObject): JSONObject = stdio.send(frame)
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
        // Spec: client must send notifications/initialized after initialize.
        t.send(MCPClientCodec.buildNotificationsInitialized())
        Log.i(TAG, "connected ${config.id} -> ${info.serverName}@${info.serverVersion}")
    }

    /** Paginated tools/list: follows nextCursor until exhausted. */
    suspend fun listTools(): List<MCPClientCodec.RemoteTool> {
        val t = transport ?: throw MCPTransportException("session not connected")
        val out = mutableListOf<MCPClientCodec.RemoteTool>()
        var cursor: String? = null
        var guard = 0
        do {
            if (++guard > 50) throw MCPTransportException("tools/list pagination runaway")
            val reply = t.send(MCPClientCodec.buildToolsList(cursor))
            val page = MCPClientCodec.parseToolsList(reply)
                ?: throw MCPTransportException("tools/list rejected: $reply")
            out.addAll(page.tools)
            cursor = page.nextCursor
        } while (cursor != null)
        return out
    }

    suspend fun callTool(name: String, arguments: JSONObject): MCPClientCodec.CallResult {
        val t = transport ?: throw MCPTransportException("session not connected")
        val reply = t.send(MCPClientCodec.buildToolsCall(name, arguments, nextId++))
        return MCPClientCodec.parseCallResult(reply)
            ?: throw MCPTransportException("tools/call rejected: $reply")
    }

    fun close() {
        transport?.close()
        transport = null
    }
}
