package io.github.slackerllc.minis.tools.runtime

import android.content.Context
import io.github.slackerllc.minis.data.model.AgentToolDefinition
import io.github.slackerllc.minis.data.model.AgentToolParam
import io.github.slackerllc.minis.mcp.client.MCPClientCodec
import io.github.slackerllc.minis.mcp.client.MCPClientSession
import io.github.slackerllc.minis.tools.ToolExecutionResult
import org.json.JSONObject

/**
 * A remote MCP tool exposed through the local ToolRegistry under the D12
 * `mcp.<server>.<tool>` name. Definition is derived from the remote
 * input_schema; execution forwards to the live [MCPClientSession].
 */
class MCPToolHandler(
    val serverId: String,
    val remoteTool: MCPClientCodec.RemoteTool,
    private val session: MCPClientSession,
) : ToolHandler {

    override val definition: AgentToolDefinition by lazy {
        AgentToolDefinition(
            name = "mcp.$serverId.${remoteTool.name}",
            description = remoteTool.description ?: "Remote MCP tool ${remoteTool.name} (server $serverId)",
            parameters = schemaToParams(remoteTool.inputSchema),
        )
    }

    override suspend fun execute(
        argsJson: String,
        sessionId: String,
        context: Context,
        toolId: String,
    ): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrElse { JSONObject() }
        val result = try {
            session.callTool(remoteTool.name, args)
        } catch (t: Throwable) {
            return ToolExecutionResult(
                "mcp tool ${remoteTool.name} failed: ${t.message}",
                false,
            )
        }
        return ToolExecutionResult(result.content, !result.isError)
    }

    companion object {
        /** Converts a remote JSON schema {type,properties,required} to AgentToolParam map. */
        fun schemaToParams(schema: JSONObject?): Map<String, AgentToolParam> {
            if (schema == null) return emptyMap()
            val props = schema.optJSONObject("properties") ?: return emptyMap()
            val out = mutableMapOf<String, AgentToolParam>()
            for (key in props.keys()) {
                val p = props.optJSONObject(key) ?: continue
                out[key] = AgentToolParam(
                    type = p.optString("type", "string"),
                    description = p.optString("description", ""),
                    enumValues = p.optJSONArray("enum")?.let { arr ->
                        (0 until arr.length()).map { arr.optString(it) }
                    },
                )
            }
            return out
        }
    }
}
