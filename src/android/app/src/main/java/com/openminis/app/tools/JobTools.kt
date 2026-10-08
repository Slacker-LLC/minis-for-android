package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Model-facing job tools (DeepSeek Harness `dsh-tool-jobs` contract, minimal
 * port): `job_output` / `job_list` / `job_kill`.
 *
 * The three definitions are registered in [AgentTools.makeAgentTools] and
 * dispatched from ChatViewModel.dispatchTool. All state lives in the
 * process-wide [JobRegistry] singleton, so jobs survive across tool calls
 * within the app process and are also observable through the Web Remote RPC
 * (`agent.jobs.*`, see AgentRpcMethods).
 */
object JobTools {

    const val NAME_OUTPUT = "job_output"
    const val NAME_LIST = "job_list"
    const val NAME_KILL = "job_kill"
    const val NAME_INPUT = "job_input"

    /** Default wait budget for `job_output` with wait=true (milliseconds). */
    private const val DEFAULT_WAIT_TIMEOUT_MS = 30_000L

    /** Most output one `job_output` call returns. */
    private const val READ_CHARS = 20_000

    /** Poll interval used while waiting for a job to finish. */
    private const val WAIT_POLL_MS = 100L

    /**
     * Dispatch entry point, called by ChatViewModel.dispatchTool for all
     * three job tool names. [sessionId] and [context] are part of the uniform
     * tool-executor signature but unused — the registry is process-global.
     */
    suspend fun execute(name: String, argsJson: String, sessionId: String?, context: Context): ToolExecutionResult =
        when (name) {
            NAME_OUTPUT -> jobOutput(argsJson)
            NAME_LIST -> jobList()
            NAME_KILL -> jobKill(argsJson)
            NAME_INPUT -> jobInput(argsJson, sessionId)
            else -> ToolExecutionResult("job: unknown action " + name, false)
        }

    fun jobOutputDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME_OUTPUT,
        description = "Read the output of a background job. Returns the accumulated output so far, always ending " +
            "with a '[status: <STATUS>]' trailer (RUNNING/COMPLETED/KILLED/FAILED, with the reason when there is one) " +
            "and '[next_offset: N]'. Pass offset=N back to read only what was written since; without offset you get " +
            "everything still kept. With wait=true, blocks until the job reaches a terminal status or timeout_ms elapses; " +
            "a timed-out wait returns the current output with [status: RUNNING] and leaves the job alive.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user. Use the same language as the user."),
            "job_id" to AgentToolParam("string", "The id of the background job to read output from."),
            "wait" to AgentToolParam("boolean", "Block until the job reaches a terminal status (default false)."),
            "timeout_ms" to AgentToolParam("integer", "Maximum time to wait in milliseconds when wait=true (default 30000)."),
            "offset" to AgentToolParam("integer", "Output position to read from: the next_offset of the previous call. Omit to read everything still kept."),
        ),
        required = listOf("tool_title", "job_id"),
        propertyOrdering = listOf("tool_title", "job_id", "wait", "timeout_ms", "offset"),
        // Generous budget so wait=true has room to complete inside the
        // executor's per-tool cooperative timeout.
        timeoutMs = 120_000L,
    )

    fun jobListDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME_LIST,
        description = "List all background jobs, newest first, one per line formatted as '<id> [<kind>] <STATUS> \u2014 <label>'.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user. Use the same language as the user."),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title"),
        timeoutMs = 10_000L,
    )

    fun jobKillDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME_KILL,
        description = "Request cancellation of a running background job: marks it KILLED and records the reason. " +
            "Returns 'cancellation-requested' when the job was still running, or 'already-finished' when it had " +
            "already reached a terminal status.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user. Use the same language as the user."),
            "job_id" to AgentToolParam("string", "The id of the job to cancel."),
            "reason" to AgentToolParam("string", "Optional short reason, recorded in the job detail."),
        ),
        required = listOf("tool_title", "job_id"),
        propertyOrdering = listOf("tool_title", "job_id", "reason"),
        timeoutMs = 10_000L,
    )

    fun jobInputDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME_INPUT,
        description = "Send text to the standard input of a running background shell job (one started with " +
            "shell_execute background=true), the way typing into it would: a program that reads a line gets it and " +
            "waits for the next. Nothing is added to the text, so end a line with \\n. Set eof=true to close its " +
            "input afterwards (or alone, with no input) for programs that read until end-of-file. Read what the job " +
            "answered with job_output.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user. Use the same language as the user."),
            "job_id" to AgentToolParam("string", "The id of the job to send input to."),
            "input" to AgentToolParam("string", "Text to send, sent as it is."),
            "eof" to AgentToolParam("boolean", "Close the job's input after sending (default false)."),
        ),
        required = listOf("tool_title", "job_id"),
        propertyOrdering = listOf("tool_title", "job_id", "input", "eof"),
        timeoutMs = 30_000L,
    )

    // ── Executors ───────────────────────────────────────────────────────────

    internal suspend fun jobOutput(argsJson: String): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return ToolExecutionResult("job_output: invalid arguments JSON", false)
        val jobId = args.optString("job_id").trim()
        if (jobId.isEmpty()) return ToolExecutionResult("job_output: missing 'job_id'", false)
        if (JobRegistry.get(jobId) == null) return ToolExecutionResult("job_output: no such job: " + jobId, false)

        val wait = args.optBoolean("wait", false)
        val timeoutMs = args.optLong("timeout_ms", DEFAULT_WAIT_TIMEOUT_MS).coerceAtLeast(0L)
        val deadline = if (wait && timeoutMs > 0) System.currentTimeMillis() + timeoutMs else 0L

        // Re-read the job each iteration: status transitions replace the map
        // entry with a copy, so a stale reference would never observe a
        // terminal state.
        while (wait) {
            val current = JobRegistry.get(jobId) ?: break
            if (current.status != JobRegistry.JobStatus.RUNNING) break
            if (deadline > 0 && System.currentTimeMillis() >= deadline) break
            delay(WAIT_POLL_MS)
        }

        val finalJob = JobRegistry.get(jobId)
        val offset = if (args.has("offset")) args.optLong("offset", 0L) else 0L
        val read = JobRegistry.read(jobId, offset, READ_CHARS)
        val body = read?.text?.ifEmpty { "(no output yet)" } ?: "(no output yet)"
        val notice = if (read != null && read.missed > 0) {
            "[${read.missed} characters before this point are not shown (kept output is bounded)]\n"
        } else ""
        val detail = finalJob?.detail?.takeIf { it.isNotEmpty() }?.let { " \u2014 $it" }.orEmpty()
        return ToolExecutionResult(
            output = notice + body + "\n[status: " + (finalJob?.status?.name ?: "UNKNOWN") + detail + "]" +
                (read?.let { "\n[next_offset: ${it.nextOffset}]" } ?: ""),
            success = true,
        )
    }

    private fun jobList(): ToolExecutionResult {
        val jobs = JobRegistry.list()
        if (jobs.isEmpty()) return ToolExecutionResult("(no jobs)", true)
        val lines = jobs.joinToString("\n") { job ->
            job.id + " [" + job.kind + "] " + job.status.name + " \u2014 " + job.label
        }
        return ToolExecutionResult(lines, true)
    }

    private suspend fun jobInput(argsJson: String, sessionId: String?): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return ToolExecutionResult("job_input: invalid arguments JSON", false)
        val jobId = args.optString("job_id").trim()
        if (jobId.isEmpty()) return ToolExecutionResult("job_input: missing 'job_id'", false)
        val input = args.optString("input", "")
        val eof = args.optBoolean("eof", false)
        if (input.isEmpty() && !eof) return ToolExecutionResult("job_input: give 'input' or eof=true", false)
        val error = com.openminis.app.runtime.ShellJobs.writeInput(sessionId, jobId, input, eof)
        return if (error == null) {
            ToolExecutionResult(if (eof) "sent; input closed" else "sent", true)
        } else {
            ToolExecutionResult("job_input: $error", false)
        }
    }

    private fun jobKill(argsJson: String): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return ToolExecutionResult("job_kill: invalid arguments JSON", false)
        val jobId = args.optString("job_id").trim()
        if (jobId.isEmpty()) return ToolExecutionResult("job_kill: missing 'job_id'", false)
        if (JobRegistry.get(jobId) == null) return ToolExecutionResult("job_kill: no such job: " + jobId, false)
        val reason = args.optString("reason", "")
        return if (JobRegistry.kill(jobId, reason)) {
            ToolExecutionResult("cancellation-requested", true)
        } else {
            ToolExecutionResult("already-finished", true)
        }
    }
}