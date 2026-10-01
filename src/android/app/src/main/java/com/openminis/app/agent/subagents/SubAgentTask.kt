package com.openminis.app.agent.subagents

import com.openminis.app.data.model.SubAgentDefinition
import com.openminis.app.data.model.SubAgentLimits
import org.json.JSONArray
import org.json.JSONObject

/**
 * The pure half of the `subagent` tool: argument parsing, the JSON envelopes the model reads,
 * the callback message a finished background run posts into its conversation, and the prompt
 * wording. Everything here is free of Android and of the runtime, so it is unit-testable.
 *
 * The contract and wording follow OpenMinis 1.14 (`HelperRunner` / `subagent_task`, which this app exposes under its existing name `subagent`), adapted to this
 * app: model pins are model entries instead of groups, and `resume` is not offered (yet).
 */
enum class SubAgentAction(val wire: String) {
    DELEGATE("delegate"), STATUS("status"), STEER("steer"), CANCEL("cancel"), RESUME("resume");

    companion object {
        fun parse(raw: String?): SubAgentAction? =
            if (raw.isNullOrBlank()) DELEGATE else entries.firstOrNull { it.wire == raw.trim().lowercase() }
    }
}

/** How an Auto sub agent picks its model. */
enum class SubAgentModelChoice(val wire: String) {
    SAME_AS_ME("same_as_me"), DEFAULT_MODEL("default_model"), SUB_MODEL("sub_model");

    companion object {
        /** Unknown or absent parses to the model the user already chose for this conversation. */
        fun parse(raw: String?): SubAgentModelChoice =
            entries.firstOrNull { it.wire == raw?.trim()?.lowercase() } ?: SAME_AS_ME
    }
}

data class SubAgentTaskArgs(
    val action: SubAgentAction,
    val title: String,
    val task: String,
    val context: String,
    val agent: String?,
    val modelChoice: SubAgentModelChoice,
    val maxMinutes: Int,
    val wait: Boolean,
    /** none | frequent | moderate; background runs only. */
    val progressReport: String,
    val jobId: String?,
    val message: String?,
)

sealed class SubAgentArgsResult {
    data class Ok(val args: SubAgentTaskArgs) : SubAgentArgsResult()
    data class Invalid(val error: String, val message: String) : SubAgentArgsResult()
}

object SubAgentTask {
    const val TOOL_NAME = SubAgentDefinition.TOOL_NAME
    const val DEFAULT_MINUTES = 10
    const val MAX_MINUTES = 60
    val PROGRESS_LEVELS = listOf("none", "frequent", "moderate")

    /** How long a run gets, after its budget expires, to answer the wrap-up prompt. */
    const val WRAP_UP_GRACE_MS = 90_000L

    /** The most of the child's latest message a progress report carries. */
    const val PROGRESS_LAST_MESSAGE_MAX_CHARS = 800

    /** A child's answer is capped so an unbounded response cannot flood the parent's context. */
    const val MAX_RESULT_CHARS = 60_000

    /** Results echoed by action=status are shorter: it lists every job at once. */
    const val STATUS_RESULT_CHARS = 2_000

    fun parseArgs(argsJson: String): SubAgentArgsResult {
        val o = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return SubAgentArgsResult.Invalid("invalid_arguments", "The arguments are not valid JSON.")
        val action = SubAgentAction.parse(o.optString("action", ""))
            ?: return SubAgentArgsResult.Invalid(
                "unknown_action",
                "`action` must be one of: ${SubAgentAction.entries.joinToString { it.wire }}.",
            )
        // `prompt` is the older single-shot tool's name for the brief; accept it so a call shaped for
        // that schema still works.
        val task = o.optString("task", "").ifBlank { o.optString("prompt", "") }.trim()
        val jobId = o.optString("job_id", "").trim().ifEmpty { null }
        val message = o.optString("message", "").trim().ifEmpty { null }
        when (action) {
            SubAgentAction.DELEGATE -> if (task.isEmpty()) {
                return SubAgentArgsResult.Invalid(
                    "task_required",
                    "`task` is required for action=delegate and must be a complete brief: the sub agent cannot see this conversation.",
                )
            }
            SubAgentAction.STEER -> {
                if (jobId == null) return SubAgentArgsResult.Invalid("job_id_required", "`job_id` is required for action=steer.")
                if (message == null) return SubAgentArgsResult.Invalid("message_required", "`message` is required for action=steer.")
            }
            SubAgentAction.CANCEL -> if (jobId == null) {
                return SubAgentArgsResult.Invalid("job_id_required", "`job_id` is required for action=cancel.")
            }
            SubAgentAction.STATUS, SubAgentAction.RESUME -> Unit
        }
        val minutes = if (o.has("max_minutes")) o.optInt("max_minutes", DEFAULT_MINUTES) else DEFAULT_MINUTES
        return SubAgentArgsResult.Ok(
            SubAgentTaskArgs(
                action = action,
                title = o.optString("tool_title", "").trim(),
                task = task,
                context = o.optString("context", "").trim(),
                agent = o.optString("agent", "").trim().ifEmpty { null },
                modelChoice = SubAgentModelChoice.parse(o.optString("model_choice", "")),
                maxMinutes = minutes.coerceIn(1, MAX_MINUTES),
                wait = o.optBoolean("wait", false),
                progressReport = o.optString("progress_report", "none").trim().lowercase()
                    .takeIf { it in PROGRESS_LEVELS } ?: "none",
                jobId = jobId,
                message = message,
            ),
        )
    }

    // ── Envelopes ───────────────────────────────────────────────────────────

    fun error(code: String, message: String, extra: JSONObject.() -> Unit = {}): String =
        JSONObject().put("status", "error").put("error", code).put("message", message).apply(extra).toString()

    fun started(job: SubAgentJob): String = JSONObject()
        .put("status", "running")
        .put("job_id", job.id)
        .put("agent", job.agentName)
        .put("title", job.title)
        .apply { job.modelLabel?.let { put("model", it) } }
        .put("note", "Running in the background. Its result will arrive later as a new message starting with [Background task finished …]; end your turn when you have nothing else to do and do not poll.")
        .toString()

    fun queued(job: SubAgentJob, position: Int): String = JSONObject()
        .put("status", "queued")
        .put("job_id", job.id)
        .put("agent", job.agentName)
        .put("title", job.title)
        .put("position", position)
        .put("note", "All sub agent slots are busy. It starts as one frees; do not re-delegate it or wait for a slot.")
        .toString()

    /** The final payload of a finished run: returned by a blocking call, and the body of the callback. */
    fun finalPayload(job: SubAgentJob, now: Long): JSONObject = JSONObject()
        .put("status", job.state.wire)
        .put("job_id", job.id)
        .put("agent", job.agentName)
        .put("title", job.title)
        .apply {
            job.modelLabel?.let { put("model", it) }
            job.elapsedMs(now)?.let { put("elapsed_s", it / 1000) }
            job.childSessionId?.let { put("child_session_id", it) }
            if (job.resumed) put("resumed", true)
            put("result", capResult(job.resultText.orEmpty()))
        }

    fun statusPayload(jobs: List<SubAgentJob>, now: Long, queuePosition: (String) -> Int?): String {
        val arr = JSONArray()
        for (job in jobs) {
            arr.put(
                JSONObject()
                    .put("job_id", job.id)
                    .put("agent", job.agentName)
                    .put("title", job.title)
                    .put("status", job.state.wire)
                    .apply {
                        job.modelLabel?.let { put("model", it) }
                        job.elapsedMs(now)?.let { put("elapsed_s", it / 1000) }
                        job.childSessionId?.let { put("child_session_id", it) }
                        queuePosition(job.id)?.let { put("position", it) }
                        if (job.state.isTerminal) put("result", capResult(job.resultText.orEmpty(), STATUS_RESULT_CHARS))
                    },
            )
        }
        return JSONObject().put("status", "ok").put("jobs", arr).put("count", jobs.size).toString()
    }

    /**
     * The message a finished background run posts into its conversation. It is written by the system,
     * not typed by the user, and says so in its first line.
     */
    fun callbackText(job: SubAgentJob, now: Long): String {
        val elapsed = job.elapsedMs(now)?.let { " · ${it / 1000}s" }.orEmpty()
        val header = "[Background task finished — job_id=${job.id.take(8)} · agent=${job.agentName} · " +
            "status=${job.state.wire}$elapsed]"
        val body = capResult(job.resultText.orEmpty()).ifBlank { "(no result text)" }
        return header + "\n\n" + body
    }

    fun capResult(text: String, limit: Int = MAX_RESULT_CHARS): String =
        if (text.length > limit) text.take(limit) + "\n\n[truncated: the sub agent's answer exceeded $limit characters]" else text

    // ── Prompts ─────────────────────────────────────────────────────────────

    /** Sent to a child whose time budget ran out, so it hands over what it has instead of nothing. */
    const val WRAP_UP_PROMPT =
        "[Time budget reached] Stop working now. Reply with one final message that contains the result so far: what you " +
            "completed, what you found, and anything left undone. Do not call any more tools."

    /** A mid-run report: status, the tool the child is in, and the tail of its latest message. */
    fun progressText(job: SubAgentJob, progress: ChildProgress, now: Long): String {
        val elapsed = job.elapsedMs(now)?.let { " · ${it / 1000}s" }.orEmpty()
        val lines = mutableListOf("[Background task progress — job_id=${job.id.take(8)} · agent=${job.agentName} · ${job.state.wire}$elapsed]")
        progress.currentTool?.let { lines.add("Current tool: $it") }
        progress.lastText?.takeIf { it.isNotBlank() }?.let {
            lines.add("Latest message: " + it.trim().takeLast(PROGRESS_LAST_MESSAGE_MAX_CHARS))
        }
        return lines.joinToString("\n")
    }

    /**
     * What a resumed child is told on its first turn. States plainly what did and did not survive the
     * restart, because the transcript still shows tool results whose side effects are gone.
     */
    fun resumeNotice(): String =
        "[This run was interrupted and has been resumed. The tool results above are still valid, but all live state is gone: " +
            "browser tabs are closed, shell processes have ended, and anything unsaved is lost. Files in the workspace are still there. " +
            "Continue from what the transcript already establishes — reopen pages or re-run commands when you need them, and do not " +
            "assume anything is still open.]"

    /** What the child session is sent as its first (and only user) message. */
    fun childBrief(def: SubAgentDefinition, task: String, context: String): String = buildString {
        append("You are a sub agent working on a task delegated by another agent. You have no access to ")
        append("that conversation: everything you need is below. Work independently with your tools, ")
        append("then finish with a final message that contains exactly what was asked for — it is handed ")
        append("back to the delegating agent as your result.\n")
        val instructions = def.instructions.trim()
        if (instructions.isNotEmpty()) {
            append("\n--- Sub agent instructions (set by the user) ---\n")
            append(instructions)
            append("\n--- End of instructions ---\n")
        }
        append("\nTask:\n")
        append(task.trim())
        val extra = context.trim()
        if (extra.isNotEmpty()) {
            append("\n\nContext (raw material, verbatim):\n")
            append(extra)
        }
    }

    /**
     * The "which sub agent for which job" section of the system prompt, built from the SAME roster
     * the tool's `agent` enum comes from so a name can never be advertised in one and rejected by the
     * other. [modelNote] describes where each agent's model comes from (information only).
     */
    fun rosterSection(roster: List<SubAgentDefinition>, modelNote: (SubAgentDefinition) -> String): String {
        if (roster.isEmpty()) return ""
        val lines = mutableListOf("Available sub agents (pass the name as $TOOL_NAME.agent):")
        for (def in roster) {
            val desc = def.description.take(SubAgentLimits.DESCRIPTION_MAX_LENGTH).trim()
            lines.add("- ${def.name} — $desc Model: ${modelNote(def)}.")
        }
        lines.add("Prefer a specific sub agent when its description matches; otherwise use the general one.")
        return lines.joinToString("\n") + "\n"
    }

    /** The one-line tool bullet for the system prompt. */
    const val SYSTEM_PROMPT_BULLET =
        "- subagent: Delegate a self-contained task to a sub agent that runs its own tool loop in an isolated " +
            "child session, and inspect, steer or stop the ones you started. Full contract in the tool schema. Two things " +
            "it does not say: the \"[Background task finished …]\" result messages are written by the system, not typed " +
            "by the user; and while a sub agent runs the user can open its child session and watch it."
}
