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

    /** [T-android-mcp-tool-bounds] Tool names longer than this are not
     *  dispatchable: provider wire names are capped at 64 characters, so a
     *  truncated name would address a tool the server does not have. */
    const val MAX_TOOL_NAME_CHARS = 128

    /** A remote description is model-facing context, so an unbounded one is a
     *  cheap way for a server to inflate every prompt. */
    const val MAX_TOOL_DESCRIPTION_CHARS = 2_000

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

    data class ToolsPage(
        val tools: List<RemoteTool>,
        val nextCursor: String?,
        /** Entries this page could not expose: blank or over-long names. */
        val skippedTools: Int = 0,
    )

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
        var skipped = 0
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            val name = t.optString("name")
            if (name.isBlank() || name.length > MAX_TOOL_NAME_CHARS) {
                skipped += 1
                continue
            }
            tools.add(
                RemoteTool(
                    name = name,
                    description = boundedDescription(t.optString("description")),
                    inputSchema = readInputSchema(t),
                ),
            )
        }
        val cursor = result.optString("nextCursor").ifBlank { null }
        return ToolsPage(tools, cursor, skipped)
    }

    private fun boundedDescription(raw: String): String? {
        if (raw.isBlank()) return null
        if (raw.length <= MAX_TOOL_DESCRIPTION_CHARS) return raw
        return raw.take(MAX_TOOL_DESCRIPTION_CHARS) + "…"
    }

    /**
     * [T-android-mcp-input-schema-casing] Reads a tool's parameter schema from
     * either spelling.
     *
     * The MCP `tools/list` schema names the field `inputSchema` (camelCase);
     * `input_schema` is the Anthropic Messages spelling and appears in some
     * servers and in older Minis fixtures. Reading only the snake_case form
     * silently dropped every parameter of a spec-conformant server, which made
     * those tools untyped and callable with unvalidated arguments. Both are
     * accepted, with the specification spelling taking precedence.
     */
    internal fun readInputSchema(tool: JSONObject): JSONObject? =
        tool.optJSONObject("inputSchema") ?: tool.optJSONObject("input_schema")

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
