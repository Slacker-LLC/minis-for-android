package com.openminis.app.debug

import android.content.Context
import com.openminis.app.tools.SubagentLimits
import org.json.JSONObject

/**
 * `agent.settings.*` RPC handlers for the Web Remote frontend.
 *
 * Mirrors the DeepSeek Harness agent knobs that are worth exposing remotely:
 * the main agent is the default (Primary) model group, lightweight tasks /
 * delegated children use the Sub group when configured (otherwise they
 * inherit Primary), and the `subagent` tool has a depth cap and a per-run
 * timeout. Model group selection lives in `provider.groups.*`; this family
 * only covers the subagent limits.
 */
internal object AgentRpcMethods {

    fun settingsGet(context: Context): JSONObject = JSONObject().apply {
        put("maxDepth", SubagentLimits.maxDepth(context))
        put("timeoutMinutes", SubagentLimits.timeoutMs(context) / 60_000L)
    }

    fun settingsSet(context: Context, params: JSONObject): JSONObject {
        val maxDepth = params.optInt("maxDepth", SubagentLimits.DEFAULT_MAX_DEPTH)
        val timeoutMinutes = params.optLong("timeoutMinutes", SubagentLimits.DEFAULT_TIMEOUT_MINUTES)
        if (maxDepth !in SubagentLimits.MAX_DEPTH_RANGE) {
            throw RPCException(-32602, "maxDepth must be in ${SubagentLimits.MAX_DEPTH_RANGE}")
        }
        if (timeoutMinutes !in SubagentLimits.TIMEOUT_MINUTES_RANGE) {
            throw RPCException(-32602, "timeoutMinutes must be in ${SubagentLimits.TIMEOUT_MINUTES_RANGE}")
        }
        SubagentLimits.save(context, maxDepth, timeoutMinutes)
        return JSONObject().apply {
            put("ok", true)
            put("maxDepth", maxDepth)
            put("timeoutMinutes", timeoutMinutes)
        }
    }
}
