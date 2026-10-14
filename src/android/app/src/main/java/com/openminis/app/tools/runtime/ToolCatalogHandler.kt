package com.openminis.app.tools.runtime

import android.content.Context
import com.openminis.app.agent.AgentSessionStore
import com.openminis.app.mcp.server.MCPServer
import com.openminis.app.mcp.server.TokenStore
import com.openminis.app.tools.AgentTools
import com.openminis.app.tools.ToolExecutionResult
import org.json.JSONArray
import org.json.JSONObject

/** Read the existing directory without enabling capabilities or constructing an execution owner. */
class ToolCatalogHandler : ToolHandler {
    override val definition = AgentTools.catalogDefinition().copy(name = "agent.tools")

    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult =
        executeForCaller(argsJson, sessionId, context, toolId, ToolPermissionManager.CALLER_LOCAL)

    override suspend fun executeForCaller(argsJson: String, sessionId: String, context: Context, toolId: String, caller: String): ToolExecutionResult {
        val args = JSONObject(argsJson)
        val offset = args.optLong("offset", 0)
        val limit = args.optLong("limit", 20)
        if (offset < 0 || limit !in 1L..100L) return ToolExecutionResult("Error: invalid_arguments: offset must be nonnegative and limit must be 1..100", false)
        val local = caller == ToolPermissionManager.CALLER_LOCAL
        val owner = if (local) AgentSessionStore.existing(sessionId) else null
        val token = if (!local) TokenStore.findById(caller.removePrefix("mcp:")) else null
        if (!local && (!caller.startsWith("mcp:") || token == null)) return ToolExecutionResult("Error: permission_denied: catalog caller", false)
        val offered = when {
            owner != null -> owner.agentTools
            local -> ToolRegistry.definitionsForCaller(caller)
            else -> ToolRegistry.definitionsForCaller(caller).filter { MCPServer.scopeAllows(token!!.scope, it.name) }
        }
        val words = args.optString("query").lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val matches = offered.distinctBy { it.apiName }.filter { tool ->
            val text = "${tool.name} ${tool.apiName} ${ToolRegistry.canonicalName(tool.name).orEmpty()} ${tool.description}".lowercase()
            words.all(text::contains)
        }.sortedBy { it.apiName }
        val tools = JSONArray()
        for (tool in matches.drop(offset.coerceAtMost(matches.size.toLong()).toInt()).take(limit.toInt())) {
            val item = JSONObject().put("name", tool.apiName)
                .put("canonical_name", ToolRegistry.canonicalName(tool.name) ?: tool.name)
                .put("description", if (args.optBoolean("include_schema")) tool.description else tool.description.take(200))
            if (args.optBoolean("include_schema")) item.put("input_schema", tool.toMcpJson().getJSONObject("inputSchema"))
            tools.put(item)
        }
        val next = offset + tools.length()
        return ToolExecutionResult(JSONObject()
            .put("scope", when { owner != null -> "current_session_configuration"; local -> "registered_local_interfaces"; else -> "mcp_token" })
            .put("device_availability_checked", false)
            .put("total", matches.size).put("offset", offset)
            .put("next_offset", if (next < matches.size) next else JSONObject.NULL)
            .put("tools", tools).toString(), true)
    }
}
