package com.openminis.app.debug

import android.content.Context
import com.openminis.app.scheduled.ScheduledAgentRunner
import com.openminis.app.scheduled.ScheduledTaskManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * `scheduled.*` RPC handlers for the Web Remote frontend.
 * Listing, toggle, delete, and immediate run — creation stays on-device.
 */
internal object ScheduledTaskRpcMethods {

    /** Fire-and-forget scope for run-now invocations (mirrors ScheduledAgentRunner.bgScope). */
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun manager(context: Context): ScheduledTaskManager =
        ScheduledTaskManager(context)

    fun list(context: Context): JSONObject {
        val tasks = manager(context).list()
        val arr = JSONArray()
        for (t in tasks) {
            // Reuse the task's own toJson() which includes all fields,
            // so the frontend gets the full picture without manual mapping.
            arr.put(t.toJson())
        }
        return JSONObject().put("tasks", arr)
    }

    fun toggle(context: Context, params: JSONObject): JSONObject {
        val taskId = params.optString("taskId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'taskId' param")
        }
        if (!params.has("enabled")) {
            throw RPCException(-32602, "Missing 'enabled' param")
        }
        val enabled = params.optBoolean("enabled", true)
        val mgr = manager(context)
        if (mgr.get(taskId) == null) {
            throw RPCException(-32602, "Scheduled task not found: $taskId")
        }
        mgr.setEnabled(taskId, enabled)
        return JSONObject().put("ok", true)
    }

    fun delete(context: Context, params: JSONObject): JSONObject {
        val taskId = params.optString("taskId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'taskId' param")
        }
        val mgr = manager(context)
        if (mgr.get(taskId) == null) {
            throw RPCException(-32602, "Scheduled task not found: $taskId")
        }
        mgr.delete(taskId)
        return JSONObject().put("ok", true)
    }

    /**
     * Fire a scheduled task immediately (mirrors the "Run now" button in the UI).
     * The agent runner is launched in a background scope so the RPC returns
     * instantly with {ok:true} — the actual run may take up to 10 minutes.
     */
    fun run(context: Context, params: JSONObject): JSONObject {
        val taskId = params.optString("taskId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'taskId' param")
        }
        val mgr = manager(context)
        val task = mgr.get(taskId)
            ?: throw RPCException(-32602, "Scheduled task not found: $taskId")
        // Fire-and-forget: launch in background scope so the RPC returns
        // immediately, matching the UI "Run now" behaviour.
        bgScope.launch {
            ScheduledAgentRunner.run(context, task, waitForCompletion = false)
        }
        return JSONObject().put("ok", true)
    }
}
