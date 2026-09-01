package io.github.slackerllc.minis.tools.runtime

import android.content.Context
import io.github.slackerllc.minis.data.model.AgentToolDefinition
import io.github.slackerllc.minis.data.model.AgentToolParam
import io.github.slackerllc.minis.mcp.client.MCPClientCodec
import io.github.slackerllc.minis.mcp.client.MCPClientSession
import io.github.slackerllc.minis.mcp.client.MCPTransportException
import io.github.slackerllc.minis.mcp.client.MCPTransportFailureKind
import io.github.slackerllc.minis.tools.ToolExecutionResult
import io.github.slackerllc.minis.tools.ToolFailureKind
import io.github.slackerllc.minis.tools.ToolTimeoutPolicy
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** A remote MCP tool exposed as `mcp.<server>.<tool>`. */
class MCPToolHandler(
    val serverId: String,
    val remoteTool: MCPClientCodec.RemoteTool,
    private val session: MCPClientSession,
) : ToolHandler {

    private val canonicalName = "mcp.$serverId.${remoteTool.name}"

    override val definition: AgentToolDefinition by lazy {
        AgentToolDefinition(
            name = canonicalName,
            description = remoteTool.description ?: "Remote MCP tool ${remoteTool.name} (server $serverId)",
            parameters = schemaToParams(remoteTool.inputSchema),
            timeoutMs = ToolTimeoutPolicy.resolve(canonicalName).timeoutMs,
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
        } catch (cancelled: CancellationException) {
            // withTimeout/user Stop reaches the actual transport; HTTP cancels
            // the Call and stdio closes pipes + kills its process.
            throw cancelled
        } catch (transport: MCPTransportException) {
            val kind = when (transport.kind) {
                MCPTransportFailureKind.TRANSPORT_TIMEOUT -> ToolFailureKind.TRANSPORT_TIMEOUT
                MCPTransportFailureKind.PROCESS_KILLED -> ToolFailureKind.PROCESS_KILLED
                MCPTransportFailureKind.TRANSPORT_FAILURE -> null
            }
            return ToolExecutionResult(
                output = "mcp tool ${remoteTool.name} failed: ${transport.message}",
                success = false,
                failureKind = kind,
            )
        } catch (t: Throwable) {
            return ToolExecutionResult(
                "mcp tool ${remoteTool.name} failed: ${t.message}",
                false,
            )
        }
        return ToolExecutionResult(result.content, !result.isError)
    }

    companion object {
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
