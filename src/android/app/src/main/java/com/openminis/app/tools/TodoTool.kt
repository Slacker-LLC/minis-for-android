package com.openminis.app.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Model-facing `todo_write` tool (DeepSeek Harness): the agent replaces the
 * whole todo list in one atomic call. The Web Remote todo bar renders the
 * same list.
 */
object TodoTool {

    const val NAME = "todo_write"

    suspend fun execute(argsJson: String, sessionId: String?, context: Context): ToolExecutionResult {
        val sid = sessionId ?: return ToolExecutionResult("todo: no active session", false)
        val args = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return ToolExecutionResult("todo: invalid arguments JSON", false)
        val items = mutableListOf<AgentStateStore.TodoItem>()
        args.optJSONArray("todos")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val title = o.optString("title", "").trim()
                if (title.isEmpty()) continue
                items.add(
                    AgentStateStore.TodoItem(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        title = title,
                        status = o.optString("status", "pending"),
                    )
                )
            }
        }
        val t = AgentStateStore.todoReplace(sid, items)
        return ToolExecutionResult(
            "Todo list updated (${t.items.size} item(s)): " +
                t.items.joinToString("; ") { "${it.title} [${it.status}]" },
            true,
        )
    }
}
