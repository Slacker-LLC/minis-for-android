package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.tools.android.CommandRisk
import com.openminis.app.tools.android.PrivilegedCommandRunner
import com.openminis.app.tools.runtime.ToolHandler
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-eta-xposed-groups] The chat apps' image caches, newest first.
 *
 * Ported from Eta `agent/tool/AgentPersonalDataTools.kt` (Mangi-11/Eta @ c15de97); attribution in
 * THIRD_PARTY_LICENSES.md. Eta scans the two cache directories through one root pipeline; here the
 * same scan is one argv through the structured privileged path, with the sorting done on the parsed
 * rows. Only files past the encoder's size ceiling are excluded, and the answer carries the cache
 * path, its kind and its time - reading the picture itself is the runtime's [ReadImageTool] problem,
 * not this tool's.
 */
object ChatImageTools {
    const val QQ = "android.chat_images.qq"
    const val WECHAT = "android.chat_images.wechat"

    val aliases: Map<String, List<String>> = mapOf(
        QQ to listOf("search_qq_chat_images"),
        WECHAT to listOf("search_wechat_chat_images"),
    )

    fun handlers(): List<ToolHandler> = listOf(QqChatImagesHandler(), WechatChatImagesHandler())

    internal suspend fun searchQq(context: Context, sessionId: String, argsJson: String) = search(
        tool = QQ,
        directory = ChatImagePolicy.QQ_DIRECTORY,
        pathFilter = ChatImagePolicy.QQ_PATH_FILTER,
        kind = ChatImagePolicy::qqKind,
        unavailableCode = "QQ_CHAT_IMAGES_UNAVAILABLE",
        unavailableMessage = "the QQ chat image cache is not reachable right now",
        argsJson = argsJson,
        sessionId = sessionId,
        context = context,
    )

    internal suspend fun searchWechat(context: Context, sessionId: String, argsJson: String) = search(
        tool = WECHAT,
        directory = ChatImagePolicy.WECHAT_DIRECTORY,
        pathFilter = ChatImagePolicy.WECHAT_PATH_FILTER,
        kind = ChatImagePolicy::wechatKind,
        unavailableCode = "WECHAT_CHAT_IMAGES_UNAVAILABLE",
        unavailableMessage = "the WeChat chat image cache is not reachable right now",
        argsJson = argsJson,
        sessionId = sessionId,
        context = context,
    )

    private suspend fun search(
        tool: String,
        directory: String,
        pathFilter: List<String>,
        kind: (String) -> String,
        unavailableCode: String,
        unavailableMessage: String,
        argsJson: String,
        sessionId: String,
        context: Context,
    ): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrElse { JSONObject() }
        val limit = PersonalDataQueryPolicy.clampLimit(
            if (args.has("limit")) args.optInt("limit") else null,
        )
        val keyword = args.optString("query").trim().lowercase(Locale.ROOT)
        val result = PrivilegedCommandRunner.run(
            context = context,
            sessionId = sessionId.ifBlank { "global" },
            argv = ChatImagePolicy.findArgv(directory, pathFilter),
            operation = "$tool-scan",
            risk = CommandRisk.READ_ONLY,
            timeoutMs = QUERY_TIMEOUT_MS,
        )
        result.unavailableReason?.let { reason ->
            return ToolExecutionResult(
                PersonalDataQueryPolicy.failure(
                    if (reason.contains("trusted Android Root tool")) {
                        "PERSONAL_DATA_TOOL_UNAVAILABLE"
                    } else {
                        "PERSONAL_DATA_ROOT_UNAVAILABLE"
                    },
                    reason,
                ),
                false,
            )
        }
        if (result.timedOut) {
            return ToolExecutionResult(
                PersonalDataQueryPolicy.failure(
                    "PERSONAL_DATA_QUERY_TIMEOUT",
                    "the scan did not finish in time",
                ),
                false,
            )
        }
        if (!result.success) {
            return ToolExecutionResult(
                PersonalDataQueryPolicy.failure(unavailableCode, unavailableMessage), false,
            )
        }
        // Eta sorts the scan with "sort -rn"; the mtime is the first field, so sorting the parsed
        // rows by it is the same order.
        val candidates = result.stdout.lineSequence()
            .mapNotNull { ChatImagePolicy.row(it, directory, kind) }
            .sortedByDescending { row -> row.optLong("modified_at_epoch_seconds") }
            .toList()
        val items = candidates
            .filter { keyword.isBlank() || it.optString("path").lowercase(Locale.ROOT).contains(keyword) }
            .take(limit)
        return ToolExecutionResult(
            JSONObject()
                .put("ok", true)
                .put("tool", tool)
                .put("items", JSONArray(items))
                .put("count", items.size)
                .put(
                    "truncated",
                    candidates.size >= ChatImagePolicy.MAX_CANDIDATES || items.size == limit,
                )
                .toString(),
            true,
        )
    }

    private const val QUERY_TIMEOUT_MS = 15_000L

}

class QqChatImagesHandler : ToolHandler {
    override val definition = AgentToolDefinition(
        name = ChatImageTools.QQ,
        description = "List the newest images in QQ's own chat image cache, with cache path, kind " +
            "(original/image/thumbnail), time and size. Only available where QQ is installed and " +
            "its cache still exists; reading a picture needs the runtime to reach that path.",
        parameters = mapOf(
            "query" to AgentToolParam("string", "Optional keyword matched against the cache path"),
            "limit" to AgentToolParam(
                "integer",
                "Max rows (default " + PersonalDataQueryPolicy.DEFAULT_LIMIT + ", max " +
                    PersonalDataQueryPolicy.MAX_LIMIT + ")",
            ),
        ),
        required = emptyList(),
    )

    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String) =
        ChatImageTools.searchQq(context, sessionId, argsJson)
}

class WechatChatImagesHandler : ToolHandler {
    override val definition = AgentToolDefinition(
        name = ChatImageTools.WECHAT,
        description = "List the newest images in WeChat's own chat image cache, with cache path, " +
            "time and size. Only available where WeChat is installed and its cache still exists; " +
            "reading a picture needs the runtime to reach that path.",
        parameters = mapOf(
            "query" to AgentToolParam("string", "Optional keyword matched against the cache path"),
            "limit" to AgentToolParam(
                "integer",
                "Max rows (default " + PersonalDataQueryPolicy.DEFAULT_LIMIT + ", max " +
                    PersonalDataQueryPolicy.MAX_LIMIT + ")",
            ),
        ),
        required = emptyList(),
    )

    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String) =
        ChatImageTools.searchWechat(context, sessionId, argsJson)
}
