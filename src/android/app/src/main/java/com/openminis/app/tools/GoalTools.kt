package com.openminis.app.tools

import android.content.Context
import org.json.JSONObject

/**
 * Model-facing goal tools (DeepSeek Harness `get_goal` / `create_goal` /
 * `update_goal`). The goal lives in [AgentStateStore] and is also rendered
 * as the Web Remote goal bar, so both the model and the user see the same
 * current target.
 */
object GoalTools {

    suspend fun execute(name: String, argsJson: String, sessionId: String?, context: Context): ToolExecutionResult {
        val sid = sessionId ?: return ToolExecutionResult("goal: no active session", false)
        val args = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return ToolExecutionResult("goal: invalid arguments JSON", false)
        return when (name) {
            "get_goal" -> {
                val g = AgentStateStore.goalGet(sid)
                ToolExecutionResult(
                    if (g.text.isBlank()) "No goal is set."
                    else "Current goal (${if (g.active) "active" else "paused"}): ${g.text}",
                    true,
                )
            }
            "create_goal", "update_goal" -> {
                val text = args.optString("goal").trim()
                if (text.isBlank()) {
                    // Empty text clears the goal.
                    AgentStateStore.goalSet(sid, "")
                    return ToolExecutionResult("Goal cleared", true)
                }
                if (name == "create_goal" && AgentStateStore.goalGet(sid).text.isNotBlank()) {
                    return ToolExecutionResult("goal already exists", false)
                }
                if (name == "update_goal" && AgentStateStore.goalGet(sid).text.isBlank()) {
                    return ToolExecutionResult("no goal exists", false)
                }
                val g = AgentStateStore.goalSet(sid, text)
                ToolExecutionResult("Goal set (${if (g.active) "active" else "paused"}): ${g.text}", true)
            }
            else -> ToolExecutionResult("goal: unknown action $name", false)
        }
    }
}
