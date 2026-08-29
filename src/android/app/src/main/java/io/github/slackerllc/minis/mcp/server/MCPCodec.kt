package io.github.slackerllc.minis.mcp.server

import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-android-mcp-server] Protocol codec layer for the Minis MCP server:
 * JSON-RPC 2.0 framing + MCP message shapes, per the MCP spec 2025-06-18.
 * Pure org.json, no I/O — transport lives elsewhere. The version is pinned
 * to the handshake both sides speak.
 */
object MCPCodec {

    /** MCP spec version pinned for this server. */
    const val PROTOCOL_VERSION = "2025-06-18"

    const val SERVER_NAME = "minis"
    const val SERVER_VERSION = "0.1.0"

    // JSON-RPC 2.0 error codes.
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32000

    // Supported method constants.
    const val METHOD_INITIALIZE = "initialize"
    const val METHOD_NOTIFICATIONS_INITIALIZED = "notifications/initialized"
    const val METHOD_TOOLS_LIST = "tools/list"
    const val METHOD_TOOLS_CALL = "tools/call"
    const val METHOD_PING = "ping"

    /**
     * A parsed JSON-RPC 2.0 request. [id] is Int / String / null
     * (JSON-RPC allows a null id); [params] defaults to {} when absent.
     */
    data class MCPRequest(val id: Any?, val method: String, val params: JSONObject)

    /**
     * Parses a raw JSON-RPC 2.0 frame. Returns null for malformed JSON,
     * a jsonrpc version other than "2.0", a missing/blank method, or a
     * params value that is not a JSON object (this protocol's params shape).
     */
    fun parseRequest(raw: String): MCPRequest? {
        return try {
            val obj = JSONObject(raw)
            if (obj.optString("jsonrpc") != "2.0") return null
            val method = obj.optString("method", "")
            if (method.isEmpty()) return null
            // In org.json, opt() returns the JSONObject.NULL singleton (not null) for
            // an explicit  "id":null, and null when the key is absent. Both are null id.
            val rawId: Any? = obj.opt("id")
            val id: Any? = if (rawId == JSONObject.NULL) null else rawId
            val params: JSONObject = when {
                !obj.has("params") -> JSONObject()
                obj.optJSONObject("params") != null -> obj.getJSONObject("params")
                else -> return null
            }
            MCPRequest(id, method, params)
        } catch (_: Exception) {
            null
        }
    }

    /** Success frame: {"jsonrpc":"2.0","id":…,"result":…} */
    fun response(id: Any?, result: JSONObject): String = JSONObject()
        .put("jsonrpc", "2.0")
        .also { putId(it, id) }
        .put("result", result)
        .toString()

    /**
     * Error frame: {"jsonrpc":"2.0","id":…,"error":{"code":…,"message":…}}
     * [code] is one of the JSON-RPC 2.0 / MCP codes: -32700 parse,
     * -32600 invalid request, -32601 method not found, -32602 invalid params,
     * -32000 internal.
     */
    fun errorResponse(id: Any?, code: Int, message: String): String = JSONObject()
        .put("jsonrpc", "2.0")
        .also { putId(it, id) }
        .put("error", JSONObject().put("code", code).put("message", message))
        .toString()

    /** Serializes [id]; org.json's put(key, null) DROPS the key, so an explicit null id becomes JSONObject.NULL. */
    private fun putId(obj: JSONObject, id: Any?) {
        obj.put("id", id ?: JSONObject.NULL)
    }

    /** initialize result: pinned protocol version, tools capability, server info. */
    fun initializeResult(): JSONObject = JSONObject()
        .put("protocolVersion", PROTOCOL_VERSION)
        .put(
            "capabilities",
            JSONObject().put("tools", JSONObject().put("listChanged", false)),
        )
        .put("serverInfo", JSONObject().put("name", SERVER_NAME).put("version", SERVER_VERSION))

    /** ping result — empty result object. */
    fun pingResult(): JSONObject = JSONObject()

    /** tools/list result; caller supplies the tool definitions. */
    fun toolsListResult(tools: JSONArray): JSONObject = JSONObject().put("tools", tools)

    /**
     * tools/call result — MCP content shape:
     * {"content":[{"type":"text","text":…}],"isError":…}
     */
    fun toolResult(text: String, isError: Boolean): JSONObject = JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
        .put("isError", isError)

    /** Notification methods (e.g. notifications/initialized) get no response frame. */
    fun isNotification(method: String): Boolean = method.startsWith("notifications/")
}
