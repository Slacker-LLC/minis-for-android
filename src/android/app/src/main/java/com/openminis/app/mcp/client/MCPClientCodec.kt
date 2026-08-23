package com.openminis.app.mcp.client

import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP client-side protocol construction/parsing (spec 2025-06-18).
 * Pure functions — no I/O — so the wire shapes are unit-testable in JVM.
 * Mirrors [com.openminis.app.mcp.server.MCPCodec] on the server side.
 */
object MCPClientCodec {

    const val PROTOCOL_VERSION = "2025-06-18"

    fun buildInitialize(clientName: String, clientVersion: String = "1.0"): JSONObject =
        JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 0)
            put("method", "initialize")
            put(
                "params",
                JSONObject().apply {
                    put("protocolVersion", PROTOCOL_VERSION)
                    put("capabilities", JSONObject())
                    put(
                        "clientInfo",
                        JSONObject().apply {
                            put("name", clientName)
                            put("version", clientVersion)
                        },
                    )
                },
            )
        }

    fun buildNotificationsInitialized(): JSONObject =
        JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", "notifications/initialized")
        }

    fun buildToolsList(cursor: String? = null): JSONObject =
        JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "tools/list")
            put("params", JSONObject().apply { cursor?.let { put("cursor", it) } })
        }

    fun buildToolsCall(name: String, arguments: JSONObject, id: Long): JSONObject =
        JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", "tools/call")
            put(
                "params",
                JSONObject().apply {
                    put("name", name)
                    put("arguments", arguments)
                },
            )
        }

    fun buildPing(id: Long = 2): JSONObject =
        JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", "ping")
        }

    /** Parsed remote tool entry from tools/list result. */
    data class RemoteTool(
        val name: String,
        val description: String?,
        val inputSchema: JSONObject?,
    )

    data class ToolsPage(val tools: List<RemoteTool>, val nextCursor: String?)

    data class CallResult(val content: String, val isError: Boolean)

    /**
     * Extracts `{protocolVersion, capabilities, serverInfo}` from an initialize
     * response. Returns null when the frame is an error.
     */
    fun parseInitializeResult(frame: JSONObject): InitializeInfo? {
        val err = frame.optJSONObject("error")
        if (err != null) return null
        val result = frame.optJSONObject("result") ?: return null
        return InitializeInfo(
            protocolVersion = result.optString("protocolVersion"),
            serverName = result.optJSONObject("serverInfo")?.optString("name"),
            serverVersion = result.optJSONObject("serverInfo")?.optString("version"),
        )
    }

    data class InitializeInfo(
        val protocolVersion: String,
        val serverName: String?,
        val serverVersion: String?,
    )

    /** Parses one tools/list page; returns null on error frames. */
    fun parseToolsList(frame: JSONObject): ToolsPage? {
        if (frame.optJSONObject("error") != null) return null
        val result = frame.optJSONObject("result") ?: return null
        val arr = result.optJSONArray("tools") ?: JSONArray()
        val tools = mutableListOf<RemoteTool>()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            val name = t.optString("name")
            if (name.isBlank()) continue
            tools.add(
                RemoteTool(
                    name = name,
                    description = t.optString("description").ifBlank { null },
                    inputSchema = t.optJSONObject("input_schema"),
                ),
            )
        }
        val cursor = result.optString("nextCursor").ifBlank { null }
        return ToolsPage(tools, cursor)
    }

    /**
     * Extracts call result content as concatenated text. Handles the three
     * content shapes: [{type:"text",text}] (preferred), {content:[...]},
     * or a bare object/string payload.
     */
    fun parseCallResult(frame: JSONObject): CallResult? {
        if (frame.has("error")) return null
        val result = frame.optJSONObject("result") ?: return null
        val isError = result.optBoolean("isError", false)
        val content = buildString {
            val items = result.optJSONArray("content")
            if (items != null) {
                for (i in 0 until items.length()) {
                    val it = items.optJSONObject(i) ?: continue
                    when (it.optString("type")) {
                        "text" -> append(it.optString("text"))
                        else -> append(it.toString())
                    }
                }
            } else if (result.has("structuredContent")) {
                append(result.opt("structuredContent").toString())
            } else {
                append(result.toString())
            }
        }
        return CallResult(content, isError)
    }

    /** Builds the JSON-RPC request body of [frame] with CRLF-free compact JSON. */
    fun encodeFrame(frame: JSONObject): String = frame.toString()
}
