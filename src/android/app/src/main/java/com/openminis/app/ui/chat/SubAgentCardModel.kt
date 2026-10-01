package com.openminis.app.ui.chat

import org.json.JSONObject

/**
 * What a `subagent` tool block says about the delegation it started, read from the block's arguments and
 * the tool's JSON reply. Parsing is total (null for anything else): a block from the older single-shot
 * tool, a status/steer/cancel call, or an error reply gets no card.
 */
internal data class SubAgentCardRef(
    val jobId: String,
    val agent: String?,
    val title: String?,
    val model: String?,
    val task: String?,
    /** The status the call itself returned (running / queued / completed …). */
    val statusAtCall: String?,
    /** The result text, when the call blocked for it (wait=true). */
    val resultAtCall: String?,
)

internal fun parseSubAgentCardRef(toolArgs: String, content: String): SubAgentCardRef? {
    val reply = runCatching { JSONObject(content) }.getOrNull() ?: return null
    val jobId = reply.optString("job_id", "").ifEmpty { return null }
    if (reply.optString("status") == "error") return null
    val args = runCatching { JSONObject(toolArgs) }.getOrNull()
    // Only the call that started the run owns the card; the later steer/cancel/status/resume calls
    // are ordinary tool rows.
    val action = args?.optString("action", "")?.ifBlank { "delegate" } ?: "delegate"
    if (action != "delegate") return null
    return SubAgentCardRef(
        jobId = jobId,
        agent = reply.optString("agent", "").ifEmpty { null },
        title = reply.optString("title", "").ifEmpty { null },
        model = reply.optString("model", "").ifEmpty { null },
        task = (args?.optString("task", "") ?: "").ifEmpty { args?.optString("prompt", "") ?: "" }.ifEmpty { null },
        statusAtCall = reply.optString("status", "").ifEmpty { null },
        resultAtCall = reply.optString("result", "").ifEmpty { null },
    )
}

/** Whether a status word means the run has ended (so the card shows a result and no Stop). */
internal fun isFinishedSubAgentStatus(wire: String?): Boolean =
    wire in setOf("completed", "cancelled", "failed", "timeout", "interrupted")
