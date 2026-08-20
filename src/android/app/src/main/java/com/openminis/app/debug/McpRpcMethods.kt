package com.openminis.app.debug

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.data.repository.MCPRepository
import org.json.JSONArray
import org.json.JSONObject

/**
 * `mcp.*` RPC handlers for the Web Remote frontend.
 * Read-only listing plus toggle/delete — creation stays on-device.
 */
internal object McpRpcMethods {

    private fun repo(context: Context): MCPRepository =
        (context.applicationContext as? MinisApp
            ?: throw RPCException(-32000, "MinisApp not initialized")).mcpRepository

    fun list(context: Context): JSONObject {
        val servers = repo(context).servers.value
        val arr = JSONArray()
        for (s in servers) {
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("note", s.note ?: JSONObject.NULL)
                put("enabled", s.enabled)
                put("url", s.url ?: JSONObject.NULL)
                put("command", s.command ?: JSONObject.NULL)
                put("args", JSONArray(s.args))
                put("env", JSONObject(s.env))
                put("headers", JSONObject(s.headers))
                put("startupTimeoutSeconds", s.startupTimeoutSeconds ?: JSONObject.NULL)
                put("createdAt", s.createdAt)
            })
        }
        return JSONObject().put("servers", arr)
    }

    fun toggle(context: Context, params: JSONObject): JSONObject {
        val serverId = params.optString("serverId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'serverId' param")
        }
        if (!params.has("enabled")) {
            throw RPCException(-32602, "Missing 'enabled' param")
        }
        val enabled = params.optBoolean("enabled", true)
        val r = repo(context)
        if (r.servers.value.none { it.id == serverId }) {
            throw RPCException(-32602, "MCP server not found: $serverId")
        }
        r.setEnabled(serverId, enabled)
        return JSONObject().put("ok", true)
    }

    fun delete(context: Context, params: JSONObject): JSONObject {
        val serverId = params.optString("serverId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'serverId' param")
        }
        val r = repo(context)
        if (r.servers.value.none { it.id == serverId }) {
            throw RPCException(-32602, "MCP server not found: $serverId")
        }
        r.delete(serverId)
        return JSONObject().put("ok", true)
    }
}
