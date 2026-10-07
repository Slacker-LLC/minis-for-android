package com.openminis.app.tools.runtime

import com.openminis.app.runtime.ExecutionCoordinator
import com.openminis.app.runtime.ShellJobs
import com.openminis.app.tools.JobRegistry
import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.delay
import org.json.JSONObject

/** `shell_execute background=true`: starts the command as a job and replies as soon as it has said something. */
internal object ShellBackground {
    /** How long a freshly started job gets to write its first lines (or finish) before the start call replies. */
    internal const val START_GRACE_MS = 1_500L

    suspend fun start(args: JSONObject, command: String, sessionId: String): ToolExecutionResult {
        val title = args.optString("tool_title", "shell_execute")
        val timeoutMs = if (args.has("timeout")) args.optLong("timeout") * 1_000L else ShellJobs.DEFAULT_TIMEOUT_MS
        val jobId = try {
            ShellJobs.start(sessionId, command, timeoutMs) { ExecutionCoordinator.newJobProcess(sessionId) }
        } catch (limit: ShellJobs.LimitExceeded) {
            return ToolExecutionResult("Error: ${limit.message}", false, toolTitle = title)
        }
        var waited = 0L
        while (waited < START_GRACE_MS && JobRegistry.get(jobId)?.status == JobRegistry.JobStatus.RUNNING &&
            JobRegistry.output(jobId).isNullOrEmpty()
        ) {
            delay(50)
            waited += 50
        }
        return ToolExecutionResult(
            output = replyFor(jobId),
            success = true,
            toolTitle = title,
        )
    }

    internal fun replyFor(jobId: String): String {
        val read = JobRegistry.read(jobId, 0L, ShellJobs.START_REPLY_CHARS) ?: return "job_id: $jobId"
        val job = JobRegistry.get(jobId)
        val running = job?.status == JobRegistry.JobStatus.RUNNING
        return buildString {
            appendLine("job_id: $jobId")
            appendLine("status: ${job?.status?.name ?: "UNKNOWN"}${job?.detail?.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty()}")
            if (read.text.isNotEmpty()) {
                appendLine("---")
                appendLine(read.text.trimEnd('\n'))
            }
            if (running) {
                append("Running in the background. Read more with job_output {job_id: \"$jobId\", offset: ${read.nextOffset}} ")
                append("(add wait: true to wait for it to finish); stop it with job_kill.")
            }
        }.trimEnd()
    }
}
