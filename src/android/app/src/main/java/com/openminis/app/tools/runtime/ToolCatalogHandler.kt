package com.openminis.app.tools.runtime

import android.content.Context
import com.openminis.app.agent.AgentSessionStore
import com.openminis.app.mcp.server.MCPServer
import com.openminis.app.mcp.server.TokenStore
import com.openminis.app.tools.AgentTools
import com.openminis.app.tools.ToolDeferral
import com.openminis.app.tools.ToolExecutionResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read the existing directory without constructing an execution owner. For a local caller with a live session
 * it also loads the deferred tools a query names (see [com.openminis.app.tools.ToolDeferral]); that only
 * changes which of the session's own pool tools the next request carries, never what the session may run.
 */
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
            owner != null -> owner.agentToolPool
            local -> ToolRegistry.definitionsForCaller(caller)
            else -> ToolRegistry.definitionsForCaller(caller).filter { MCPServer.scopeAllows(token!!.scope, it.name) }
        }
        val query = args.optString("query")
        val matches = ToolDeferral.search(offered, query)
        val page = matches.drop(offset.coerceAtMost(matches.size.toLong()).toInt()).take(limit.toInt()).map { it.tool }
        // A query that names deferred tools loads them; a plain listing (no query) loads nothing.
        val deferredNames = if (owner != null) ToolDeferral.split(offered).deferred.map { it.name }.toSet() else emptySet()
        val toLoad = if (owner != null && query.isNotBlank()) {
            ToolDeferral.selectToLoad(matches) { it.name in deferredNames }
        } else emptyList()
        val loadedNow = owner?.loadDeferredTools(toLoad.map { it.name }).orEmpty()
        val nowOffered = owner?.agentTools?.map { it.name }?.toSet().orEmpty()
        val tools = JSONArray()
        for (tool in page) {
            val item = JSONObject().put("name", tool.apiName)
                .put("canonical_name", ToolRegistry.canonicalName(tool.name) ?: tool.name)
                .put("description", if (args.optBoolean("include_schema")) tool.description else tool.description.take(200))
            if (owner != null) item.put("loaded", tool.name in nowOffered)
            if (args.optBoolean("include_schema")) item.put("input_schema", tool.toMcpJson().getJSONObject("inputSchema"))
            tools.put(item)
        }
        val next = offset + tools.length()
        val result = JSONObject()
            .put("scope", when { owner != null -> "current_session_configuration"; local -> "registered_local_interfaces"; else -> "mcp_token" })
            .put("device_availability_checked", false)
            .put("total", matches.size).put("offset", offset)
            .put("next_offset", if (next < matches.size) next else JSONObject.NULL)
        if (loadedNow.isNotEmpty()) {
            result.put("loaded_now", JSONArray(loadedNow.map { name -> offered.first { it.name == name }.apiName }))
            result.put("note", "Loaded tools are callable from your next step.")
        } else if (owner != null && query.isNotBlank() && matches.any { it.tool.name in deferredNames && it.tool.name !in nowOffered }) {
            result.put("note", "Several deferred tools match and none was loaded: query with a tool name (or a few names separated by commas) to load them.")
        }
        return ToolExecutionResult(result.put("tools", tools).toString(), true)
    }
}
