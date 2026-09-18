package com.openminis.app.tools

import android.content.Context
import com.openminis.app.tools.android.CommandRisk
import com.openminis.app.tools.android.PrivilegedCommandRunner
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-eta-xposed-groups] The one provider-backed read every personal-data tool shares.
 *
 * Ported from Eta `agent/tool/AgentPersonalDataTools.kt` and its `query` helper (Mangi-11/Eta @
 * c15de97); attribution in THIRD_PARTY_LICENSES.md. Eta builds a `content query` shell string and
 * runs it through root; this project's privileged surface takes argv, so the same command is issued
 * as arguments - uri, projection, where and sort, each its own element. The keyword becomes a LIKE
 * clause through [PersonalDataQueryPolicy], so the wildcards are escaped before they reach another
 * app's SQL, and a provider that refuses is reported as a failure rather than as an empty list.
 */
object PersonalDataQueryTools {
    private const val QUERY_TIMEOUT_MS = 15_000L

    internal suspend fun query(
        tool: String,
        uri: String,
        columns: List<String>,
        sort: String,
        fixedWhere: String?,
        argsJson: String,
        sessionId: String,
        context: Context,
    ): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrElse { JSONObject() }
        val limit = PersonalDataQueryPolicy.clampLimit(
            if (args.has("limit")) args.optInt("limit") else null,
        )
        val keyword = args.optString("query").trim().takeIf { it.isNotEmpty() }
        if (PersonalDataQueryPolicy.isKeywordTooLong(keyword)) {
            return ToolExecutionResult(
                PersonalDataQueryPolicy.failure(
                    "PERSONAL_DATA_QUERY_TOO_LONG",
                    "query is limited to ${PersonalDataQueryPolicy.MAX_KEYWORD_CHARS} characters",
                ),
                false,
            )
        }
        val where = PersonalDataQueryPolicy.combineWhere(
            fixedWhere,
            keyword?.let { PersonalDataQueryPolicy.likeClause(it, columns) },
        )
        val argv = buildList {
            add("content")
            add("query")
            add("--uri")
            add(uri)
            add("--projection")
            add(columns.joinToString(":"))
            where?.let {
                add("--where")
                add(it)
            }
            add("--sort")
            add(sort)
        }
        val result = PrivilegedCommandRunner.run(
            context = context,
            sessionId = sessionId.ifBlank { "global" },
            argv = argv,
            operation = "$tool-query",
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
                PersonalDataQueryPolicy.failure("PERSONAL_DATA_QUERY_TIMEOUT", "the provider did not answer in time"),
                false,
            )
        }
        if (!result.success ||
            PersonalDataContentParser.hasProviderFailure(result.stdout, result.stderr)
        ) {
            return ToolExecutionResult(
                PersonalDataQueryPolicy.failure(
                    "PERSONAL_DATA_UNAVAILABLE",
                    "the provider is unavailable right now",
                    exitCode = result.exitCode,
                ),
                false,
            )
        }
        val items = PersonalDataContentParser.parseRows(result.stdout, columns)
        val bounded = items.take(limit)
        return ToolExecutionResult(
            JSONObject()
                .put("ok", true)
                .put("tool", tool)
                .put("items", JSONArray(bounded))
                .put("count", bounded.size)
                .put("truncated", items.size > bounded.size)
                .toString(2),
            true,
        )
    }}
