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
     * job tools. Correlated jobs remain scoped to their owning session; legacy
     * unbound jobs and explicit app-level diagnostics keep their global context.
     */
    suspend fun execute(name: String, argsJson: String, sessionId: String?, context: Context): ToolExecutionResult =
        when (name) {
            NAME_OUTPUT -> jobOutput(argsJson, sessionId)
            NAME_LIST -> jobList(sessionId)
            NAME_KILL -> jobKill(argsJson, sessionId)
            NAME_INPUT -> jobInput(argsJson, sessionId)
            else -> ToolExecutionResult("job: unknown action " + name, false)
        }

    fun jobOutputDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME_OUTPUT,
        description = "Read the output of a background job. The result is the output so far. " +
            "It always ends with a [status: STATUS] line and a [next_offset: N] line. " +
            "STATUS is RUNNING, COMPLETED, KILLED, or FAILED, with the reason if there is one.\n\n" +
            "To read only the new output, pass offset=N from the previous call. " +
            "Without offset, you get all the output that the app still keeps. " +
            "With wait=true, the call blocks until the job ends or timeout_ms passes. " +
            "After a timeout, the call returns the current output with [status: RUNNING] and the job stays alive.",
        parameters = mapOf(
            "tool_title" to ToolText.TOOL_TITLE,
            "job_id" to AgentToolParam("string", "The ID of the background job."),
            "wait" to AgentToolParam("boolean", "If true, wait until the job ends. The default is false."),
            "timeout_ms" to AgentToolParam("integer", "The maximum wait time in milliseconds when wait=true. The default is 30000."),
            "offset" to AgentToolParam("integer", "The output position to read from, which is the next_offset of the previous call. Omit it to read all the output that the app still keeps."),
        ),
        required = listOf("tool_title", "job_id"),
        propertyOrdering = listOf("tool_title", "job_id", "wait", "timeout_ms", "offset"),
        // Generous budget so wait=true has room to complete inside the
        // executor's per-tool cooperative timeout.
        timeoutMs = 120_000L,
    )

    fun jobListDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME_LIST,
        description = "List all background jobs, newest first. Each line is '<id> [<kind>] <STATUS> \u2014 <label>'.",
        parameters = mapOf(
            "tool_title" to ToolText.TOOL_TITLE,
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title"),
        timeoutMs = 10_000L,
    )

    fun jobKillDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME_KILL,
        description = "Request the cancellation of a running background job. The call marks the job KILLED and records the reason. " +
            "It returns 'cancellation-requested' if the job was running, or 'already-finished' if the job had ended.",
        parameters = mapOf(
            "tool_title" to ToolText.TOOL_TITLE,
            "job_id" to AgentToolParam("string", "The ID of the job to cancel."),
            "reason" to AgentToolParam("string", "An optional short reason. The app records it in the job detail."),
        ),
        required = listOf("tool_title", "job_id"),
        propertyOrdering = listOf("tool_title", "job_id", "reason"),
        timeoutMs = 10_000L,
    )

    fun jobInputDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME_INPUT,
        description = "Send text to the stdin of a running background shell job. " +
            "A background shell job is a job that you started with shell_execute and background=true. " +
            "The job gets the text as typed input. The call adds nothing to the text, so end a line with \\n.\n\n" +
            "To close the stdin after the text, set eof=true. You can also set eof=true alone, with no input. " +
            "Use it for a program that reads until the end of the file. Read the answer of the job with job_output.",
        parameters = mapOf(
            "tool_title" to ToolText.TOOL_TITLE,
            "job_id" to AgentToolParam("string", "The ID of the job that gets the input."),
            "input" to AgentToolParam("string", "The text to send. The app sends it as it is."),
            "eof" to AgentToolParam("boolean", "If true, close the stdin of the job after the text. The default is false."),
        ),
        required = listOf("tool_title", "job_id"),
        propertyOrdering = listOf("tool_title", "job_id", "input", "eof"),
        timeoutMs = 30_000L,
    )

    // ── Executors ───────────────────────────────────────────────────────────

    internal suspend fun jobOutput(argsJson: String, sessionId: String? = null): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return ToolExecutionResult("job_output: invalid arguments JSON", false)
        val jobId = args.optString("job_id").trim()
        if (jobId.isEmpty()) return ToolExecutionResult("job_output: missing 'job_id'", false)
        if (JobRegistry.get(jobId) == null) return ToolExecutionResult("job_output: no such job: " + jobId, false)
        if (!belongsToSession(jobId, sessionId)) return ToolExecutionResult("job_output: job belongs to another session", false,
            failureKind = ToolFailureKind.PERMISSION_DENIED, executionStarted = false)

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

    private fun jobList(sessionId: String?): ToolExecutionResult {
        val jobs = JobRegistry.list().filter { belongsToSession(it.id, sessionId) }
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

    private fun belongsToSession(jobId: String, sessionId: String?): Boolean {
        val owner = JobRegistry.get(jobId)?.sessionId
        return sessionId.isNullOrBlank() || owner == null || owner == sessionId
    }

    private fun jobKill(argsJson: String, sessionId: String?): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return ToolExecutionResult("job_kill: invalid arguments JSON", false)
        val jobId = args.optString("job_id").trim()
        if (jobId.isEmpty()) return ToolExecutionResult("job_kill: missing 'job_id'", false)
        if (JobRegistry.get(jobId) == null) return ToolExecutionResult("job_kill: no such job: " + jobId, false)
        if (!belongsToSession(jobId, sessionId)) return ToolExecutionResult("job_kill: job belongs to another session", false,
            failureKind = ToolFailureKind.PERMISSION_DENIED, executionStarted = false)
        val reason = args.optString("reason", "")
        return if (JobRegistry.kill(jobId, reason)) {
            ToolExecutionResult("cancellation-requested", true)
        } else {
            ToolExecutionResult("already-finished", true)
        }
    }
}