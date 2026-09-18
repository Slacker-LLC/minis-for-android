package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.tools.android.CommandRisk
import com.openminis.app.tools.android.PrivilegedCommandRunner
import com.openminis.app.tools.runtime.ToolHandler
import com.openminis.app.xposed.aimemory.ColorOsMemoryBridgeProtocol
import org.json.JSONObject

/**
 * [T-eta-xposed-groups] ColorOS system memories, read through the module that sits inside the memory
 * app's own process.
 *
 * Ported from Eta `agent/tool/AgentColorOsMemoryTools.kt` and the three tool entries in
 * `agent/model/AgentDeviceToolCatalog.kt` (Mangi-11/Eta @ c15de97); attribution in
 * THIRD_PARTY_LICENSES.md. The vendor database is never opened from here: an encoded request goes
 * through this project's structured privileged path as one argv, the hook inside the memory app
 * answers it read-only, and the answer is decoded from the shell envelope. Both sides of that
 * exchange are bounded by the protocol.
 *
 * Eta also carries a fallback for devices without the module: it copies the database out with a
 * compound shell script. That one is deliberately not ported, because this project's privileged
 * surface is argv-based (a trusted tool basename plus arguments, never a raw command) and a compound
 * script is exactly what it refuses. Without the module the tools say which piece is missing instead
 * of looking like an empty result.
 */
object ColorOsMemoryTools {
    const val MEMORIES = "android.coloros.memory"
    const val ORDERS = "android.coloros.orders"
    const val PLACES = "android.coloros.places"

    val aliases: Map<String, List<String>> = mapOf(
        MEMORIES to listOf("search_coloros_memories"),
        ORDERS to listOf("search_personal_orders"),
        PLACES to listOf("search_saved_places"),
    )

    fun handlers(): List<ToolHandler> = listOf(
        ColorOsMemoriesHandler(),
        ColorOsOrdersHandler(),
        ColorOsPlacesHandler(),
    )

    internal suspend fun query(
        operation: String,
        argsJson: String,
        sessionId: String,
        context: Context,
    ): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrElse { JSONObject() }
        val requestedQuery = args.optString("query").takeIf { it.isNotBlank() }
        if (ColorOsMemoryQueryPolicy.isQueryTooLong(requestedQuery)) {
            return ToolExecutionResult(
                failure(
                    "COLOROS_MEMORY_QUERY_TOO_LONG",
                    "query is limited to ${ColorOsMemoryQueryPolicy.MAX_QUERY_CHARS} characters",
                ),
                false,
            )
        }
        val request = JSONObject().apply {
            ColorOsMemoryQueryPolicy.query(requestedQuery)?.let { put("query", it) }
            put(
                "limit",
                ColorOsMemoryQueryPolicy.clampLimit(
                    if (args.has("limit")) args.optInt("limit") else null,
                ),
            )
        }
        val encoded = runCatching {
            ColorOsMemoryBridgeProtocol.encodeRequest(operation, request)
        }.getOrNull() ?: return ToolExecutionResult(
            failure("COLOROS_MEMORY_REQUEST_INVALID", "the query could not be encoded"),
            false,
        )

        val result = PrivilegedCommandRunner.run(
            context = context,
            sessionId = sessionId.ifBlank { "global" },
            argv = listOf(
                "content",
                "call",
                "--uri",
                ColorOsMemoryBridgeProtocol.PROVIDER_URI,
                "--method",
                ColorOsMemoryBridgeProtocol.METHOD,
                "--arg",
                encoded,
            ),
            operation = "coloros-memory-query",
            risk = CommandRisk.READ_ONLY,
            timeoutMs = QUERY_TIMEOUT_MS,
        )
        result.unavailableReason?.let { reason ->
            return ToolExecutionResult(
                failure(
                    if (reason.contains("trusted Android Root tool")) {
                        "COLOROS_MEMORY_TOOL_UNAVAILABLE"
                    } else {
                        "COLOROS_MEMORY_ROOT_UNAVAILABLE"
                    },
                    reason,
                ),
                false,
            )
        }
        if (result.timedOut) {
            return ToolExecutionResult(
                failure("COLOROS_MEMORY_TIMEOUT", "the memory bridge did not answer in time"),
                false,
            )
        }
        if (!result.success) {
            return ToolExecutionResult(
                failure(
                    "COLOROS_MEMORY_BRIDGE_UNAVAILABLE",
                    "the ColorOS memory app did not answer: the Minis module is not installed or " +
                        "not active for it",
                    exitCode = result.exitCode,
                ),
                false,
            )
        }
        val decoded = ColorOsMemoryBridgeProtocol.decodeShellResponse(result.stdout)
            ?: return ToolExecutionResult(
                failure(
                    "COLOROS_MEMORY_BRIDGE_UNANSWERED",
                    "the call was answered by something that is not this module's bridge",
                ),
                false,
            )
        return ToolExecutionResult(decoded, true)
    }

    private fun failure(code: String, message: String, exitCode: Int? = null): String = JSONObject()
        .put("ok", false)
        .put("code", code)
        .put("message", message)
        .apply { exitCode?.let { put("exit_code", it) } }
        .toString(2)

    private const val QUERY_TIMEOUT_MS = 15_000L

    private fun definition(name: String, description: String) = AgentToolDefinition(
        name = name,
        description = description,
        parameters = mapOf(
            "query" to AgentToolParam(
                "string",
                "Optional keyword, up to ${ColorOsMemoryQueryPolicy.MAX_QUERY_CHARS} characters",
            ),
            "limit" to AgentToolParam(
                "integer",
                "Max rows (default ${ColorOsMemoryQueryPolicy.DEFAULT_LIMIT}, " +
                    "max ${ColorOsMemoryQueryPolicy.MAX_LIMIT})",
            ),
        ),
    )

    class ColorOsMemoriesHandler : AndroidSystemHandler() {
        override val definition = definition(
            MEMORIES,
            "Search the ColorOS system memory: collected notes, bills, schedules, pickup codes, " +
                "deliveries and their related content. Requires the Minis module to be active for " +
                "the ColorOS memory app.",
        )

        override suspend fun execute(
            argsJson: String,
            sessionId: String,
            context: Context,
            toolId: String,
        ): ToolExecutionResult =
            query(ColorOsMemoryBridgeProtocol.OPERATION_SEARCH, argsJson, sessionId, context)
    }

    class ColorOsOrdersHandler : AndroidSystemHandler() {
        override val definition = definition(
            ORDERS,
            "Search food, shopping, delivery, ticket and travel orders the ColorOS system memory " +
                "recognised. Requires the Minis module to be active for the ColorOS memory app.",
        )

        override suspend fun execute(
            argsJson: String,
            sessionId: String,
            context: Context,
            toolId: String,
        ): ToolExecutionResult =
            query(ColorOsMemoryBridgeProtocol.OPERATION_ORDERS, argsJson, sessionId, context)
    }

    class ColorOsPlacesHandler : AndroidSystemHandler() {
        override val definition = definition(
            PLACES,
            "Search places the ColorOS system memory saved or recognised. Requires the Minis " +
                "module to be active for the ColorOS memory app.",
        )

        override suspend fun execute(
            argsJson: String,
            sessionId: String,
            context: Context,
            toolId: String,
        ): ToolExecutionResult =
            query(ColorOsMemoryBridgeProtocol.OPERATION_PLACES, argsJson, sessionId, context)
    }
}

/**
 * [T-eta-xposed-groups] The bounds Eta's catalog declares for these tools, as values.
 *
 * Ported from the `search_coloros_memories` / `search_saved_places` / `search_personal_orders`
 * entries in Eta `agent/model/AgentDeviceToolCatalog.kt` (Mangi-11/Eta @ c15de97): an optional
 * keyword of at most 200 characters and a row count of 1 to 30, defaulting to 10.
 */
object ColorOsMemoryQueryPolicy {
    const val MAX_QUERY_CHARS = 200
    const val DEFAULT_LIMIT = 10
    const val MAX_LIMIT = 30

    /** A longer keyword is a caller error, not something to silently cut. */
    fun isQueryTooLong(raw: String?): Boolean = (raw?.trim()?.length ?: 0) > MAX_QUERY_CHARS

    /** Trimmed keyword, or null when there is none. */
    fun query(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

    fun clampLimit(requested: Int?): Int = (requested ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
}
