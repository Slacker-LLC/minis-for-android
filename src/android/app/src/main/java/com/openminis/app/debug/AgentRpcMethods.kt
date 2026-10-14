package com.openminis.app.debug

import android.content.Context
import com.openminis.app.tools.JobRegistry
import com.openminis.app.tools.RalphLimits
import org.json.JSONArray
import org.json.JSONObject

/** Agent execution settings, using the same stored budget as the native Ralph settings. */
internal object AgentRpcMethods {

    fun settingsGet(context: Context): JSONObject = JSONObject().apply {
        put("scope", "ralph_round")
        put("timeoutMinutes", RalphLimits.timeoutMs(context) / 60_000L)
    }

    /**
     * Per-session Agent execution permission (DSH /permission state). This is
     * the Agent-runtime layer — distinct from the browser/MCP surface.
     */
    fun sessionPermissionGet(context: Context, params: JSONObject): JSONObject {
        val sessionId = params.optString("sessionId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'sessionId' param")
        }
        return JSONObject().apply {
            put("sessionId", sessionId)
            com.openminis.app.tools.SessionPermissionStore.preset(context, sessionId)
                ?.let { put("preset", it) }
                ?: put("preset", JSONObject.NULL)
        }
    }

    fun sessionPermissionSet(context: Context, params: JSONObject): JSONObject {
        val sessionId = params.optString("sessionId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'sessionId' param")
        }
        val preset = params.optString("preset", "").ifEmpty { null }
        if (preset != null && !com.openminis.app.tools.SessionPermissionStore.isKnownPreset(preset)) {
            throw RPCException(-32602, "preset must be workspace-write | danger-full-access | null")
        }
        com.openminis.app.tools.SessionPermissionStore.setPreset(context, sessionId, preset)
        // Same three events the /permission command emits, so the DSH
        // permissions projection (App and Web) sees one state.
        val effective = preset ?: com.openminis.app.tools.SessionPermissionStore.WORKSPACE_WRITE
        val sandboxMode = if (effective == com.openminis.app.tools.SessionPermissionStore.DANGER_FULL_ACCESS)
            "danger-full-access" else "workspace-write"
        val approvalPolicy = if (effective == com.openminis.app.tools.SessionPermissionStore.DANGER_FULL_ACCESS)
            "never" else "ask"
        com.openminis.app.ui.chat.SessionEventHub.append(sessionId, "permission/preset", JSONObject().put("preset", effective))
        com.openminis.app.ui.chat.SessionEventHub.append(sessionId, "sandbox/mode", JSONObject().put("mode", sandboxMode))
        com.openminis.app.ui.chat.SessionEventHub.append(sessionId, "approval/policy", JSONObject().put("policy", approvalPolicy))
        return JSONObject().apply {
            put("sessionId", sessionId)
            if (preset != null) put("preset", preset) else put("preset", JSONObject.NULL)
        }
    }

    fun approvalsList(context: Context, params: JSONObject): JSONObject {
        val sessionId = params.optString("sessionId", "").ifEmpty { null }
        val arr = JSONArray()
        for (r in com.openminis.app.tools.ApprovalSeam.pendingFor(sessionId)) {
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("sessionId", r.sessionId)
                put("tool", r.toolName)
                put("summary", r.summary)
                put("createdAt", r.createdAt)
            })
        }
        return JSONObject().put("approvals", arr)
    }

    fun approvalsAnswer(context: Context, params: JSONObject): JSONObject {
        val id = params.optString("approvalId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'approvalId' param")
        }
        if (!params.has("allowed")) throw RPCException(-32602, "Missing 'allowed' param")
        val ok = com.openminis.app.tools.ApprovalSeam.answer(id, params.optBoolean("allowed", false))
        if (!ok) throw RPCException(-32001, "No pending approval with id $id")
        return JSONObject().put("ok", true)
    }

    fun settingsSet(context: Context, params: JSONObject): JSONObject {
        if (params.has("maxDepth")) throw RPCException(-32602, "maxDepth has no runtime effect and is no longer a setting; use timeoutMinutes for Ralph rounds")
        val value = params.opt("timeoutMinutes")
        if (value !is Int && value !is Long) throw RPCException(-32602, "timeoutMinutes must be an integer")
        val timeoutMinutes = (value as Number).toLong()
        if (timeoutMinutes !in RalphLimits.TIMEOUT_MINUTES_RANGE) {
            throw RPCException(-32602, "timeoutMinutes must be in ${RalphLimits.TIMEOUT_MINUTES_RANGE}")
        }
        RalphLimits.save(context, timeoutMinutes)
        return JSONObject().apply {
            put("ok", true)
            put("scope", "ralph_round")
            put("timeoutMinutes", timeoutMinutes)
        }
    }

    // ── Jobs (DeepSeek Harness dsh-tool-jobs, minimal port) ────────────────

    /** agent.jobs.list: all tracked jobs, newest first, as JSON. */
    fun jobsList(context: Context, params: JSONObject): JSONObject {
        val arr = JSONArray()
        for (job in JobRegistry.list()) {
            arr.put(JSONObject().apply {
                put("id", job.id)
                put("kind", job.kind)
                put("label", job.label)
                put("status", job.status.name)
                put("detail", job.detail)
                put("startedAt", job.startedAt)
                put("finishedAt", job.finishedAt ?: JSONObject.NULL)
                put("output", JobRegistry.output(job.id) ?: "")
            })
        }
        return JSONObject().apply {
            put("jobs", arr)
            put("count", arr.length())
        }
    }

    /** agent.jobs.cancel: request cancellation of a running job by id. */
    fun jobsCancel(context: Context, params: JSONObject): JSONObject {
        val id = params.optString("id", "").ifEmpty {
            throw RPCException(-32602, "Missing 'id' param")
        }
        val job = JobRegistry.get(id)
            ?: throw RPCException(-32602, "Job not found: $id")
        val canceled = JobRegistry.kill(id, params.optString("reason", ""))
        return JSONObject().apply {
            put("id", id)
            put("canceled", canceled)
            put("status", (JobRegistry.get(id) ?: job).status.name)
        }
    }
}
