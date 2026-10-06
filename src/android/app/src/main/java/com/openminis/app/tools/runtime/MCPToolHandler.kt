package com.openminis.app.tools.runtime

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.ContextOffload
import com.openminis.app.mcp.client.MCPClientCodec
import com.openminis.app.mcp.client.MCPClientSession
import com.openminis.app.mcp.client.MCPTransportException
import com.openminis.app.mcp.client.MCPTransportFailureKind
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.tools.ToolFailureKind
import com.openminis.app.tools.ToolTimeoutPolicy
import com.openminis.app.tools.internal.ToolResultPruner
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * A remote MCP tool exposed as `mcp.<server>.<tool>`.
 *
 * @param serverId the tool-name-safe server id used in the canonical name.
 * @param configId the server's configured id, which session overrides are keyed on.
 * @param enabledInSession whether this server is switched on for a session; checked on every call so
 *   a server the user switched off for a session cannot be reached through a stale tool list or an
 *   earlier tool call.
 */
class MCPToolHandler(
    val serverId: String,
    val remoteTool: MCPClientCodec.RemoteTool,
    private val session: MCPClientSession,
    val configId: String = serverId,
    private val enabledInSession: (String) -> Boolean = { sessionId ->
        com.openminis.app.mcp.client.MCPProvider.isServerEnabledForSession(configId, sessionId)
    },
) : ToolHandler {

    private val canonicalName = "mcp.$serverId.${remoteTool.name}"
    override val isMcpTool: Boolean = true

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
        if (!enabledInSession(sessionId)) {
            return ToolExecutionResult(
                "Error: MCP server $configId is switched off for this session; tool ${remoteTool.name} was not called",
                false,
            )
        }
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
        // [T-mcp-result-bounds-android] A remote tool's result is untrusted text that
        // goes straight into the transcript, and the HTTP reply cap (4 MiB) is far more
        // than any context can afford. Oversized results spill to the session's
        // offloads directory through the helper the log tools already use, so the model
        // gets a head/tail preview plus a path it can re-read instead of megabytes of
        // body; if the spill cannot be written, a pruned preview is used rather than the
        // raw text.
        val spill = runCatching {
            ContextOffload.spillIfOversized(
                sessionId = sessionId,
                text = result.content,
                baseName = "mcp_${serverId}_${remoteTool.name}",
            )
        }.getOrNull()
        return ToolExecutionResult(boundedMcpResult(result.content, spill), !result.isError)
    }

    companion object {
        /**
         * The text handed to the model for one remote result: the spill's preview when
         * the result was spilled, otherwise the result itself — pruned with an explicit
         * omission marker when it is oversized and the spill failed.
         */
        internal fun boundedMcpResult(raw: String, spill: ContextOffload.SpillResult?): String =
            spill?.takeIf { it.spilled }?.inline
                ?: ToolResultPruner.prune(raw)
                ?: raw

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
