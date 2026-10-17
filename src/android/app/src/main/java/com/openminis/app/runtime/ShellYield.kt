package com.openminis.app.runtime

import com.openminis.app.tools.JobRegistry
import com.openminis.app.tools.ToolFailureKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * A foreground shell command that gives control back to the agent while it is still running.
 *
 * Without this, a foreground command holds the tool call (and the agent) until it ends or hits its deadline, which
 * is 15 minutes by default: a command that hangs looks like a frozen app. Here the call waits at most
 * [DEFAULT_YIELD_MS]; if the command is not done by then, the tool call returns what it printed so far together
 * with a job id, and the command keeps running in the same persistent shell. The agent then polls it with
 * `job_output`, or stops it with `job_kill`. This is the yield/poll shape of Codex's unified exec
 * (`yield_time_ms`, `core/src/tools/handlers/unified_exec.rs`), applied to the one persistent shell so `cd` / `export`
 * state is kept and no new process is started.
 *
 * The persistent shell runs one command at a time. While a yielded command is still running, [busyJob] names it so
 * the next foreground call can refuse at once instead of queueing behind it without a word.
 */
object ShellYield {
    const val DEFAULT_YIELD_MS = 30_000L
    const val MIN_YIELD_MS = 1_000L
    const val MAX_YIELD_MS = 60_000L

    /** How much output the yield reply carries at most. */
    const val REPLY_CHARS = 4_000

    sealed interface Outcome {
        /** The command ended within the yield window; its result is exactly what a plain foreground call returns. */
        data class Finished(val result: ExecutionCoordinator.CommandResult) : Outcome

        /** Still running after the window; it carries on as the registry job [jobId]. */
        data class Yielded(val jobId: String) : Outcome
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Session id -> the yielded command that still occupies its persistent shell. */
    private val active = ConcurrentHashMap<String, String>()

    /** The wait in milliseconds for a caller's `yield_time_ms`: absent means the default, anything else is bounded. */
    fun clampYield(requestedMs: Long?): Long = (requestedMs ?: DEFAULT_YIELD_MS).coerceIn(MIN_YIELD_MS, MAX_YIELD_MS)

    /** The still-running yielded command of [sessionId], or null when its shell is free. */
    fun busyJob(sessionId: String): String? {
        val id = active[sessionId] ?: return null
        if (JobRegistry.get(id)?.status == JobRegistry.JobStatus.RUNNING) return id
        active.remove(sessionId, id)
        return null
    }

    /**
     * Runs [exec] and waits up to [yieldMs] for it. [exec] must stream every output line to the callback it is given
     * and end when the command does; cancelling it must stop the command.
     *
     * Cancelling the caller before the window ends stops the command, as a plain foreground call would. Once
     * [Outcome.Yielded] has been returned the command belongs to the registry job instead: `job_kill`, the end of
     * the session or a runtime-wide stop end it.
     */
    suspend fun run(
        sessionId: String,
        command: String,
        timeoutMs: Long,
        yieldMs: Long,
        runId: String? = null,
        taskId: String? = null,
        executionIntentId: String? = null,
        exec: suspend (onLine: (String) -> Unit) -> ExecutionCoordinator.CommandResult,
    ): Outcome {
        val lock = Any()
        val early = StringBuilder()
        var jobId: String? = null
        var finished: ExecutionCoordinator.CommandResult? = null
        val signal = CompletableDeferred<Unit>()

        val onLine: (String) -> Unit = { line ->
            synchronized(lock) {
                val id = jobId
                if (id != null) {
                    JobRegistry.appendOutput(id, line + "\n")
                } else {
                    early.append(line).append('\n')
                    val over = early.length - JobRegistry.MAX_OUTPUT_CHARS
                    if (over > 0) early.delete(0, over)
                }
            }
        }

        val worker = scope.launch {
            try {
                val result = try {
                    exec(onLine)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    ExecutionCoordinator.CommandResult(error.message ?: error::class.java.simpleName, 1, 0L)
                }
                synchronized(lock) {
                    finished = result
                    jobId?.let { settle(it, result, timeoutMs) }
                }
                signal.complete(Unit)
            } finally {
                synchronized(lock) { jobId }?.let { active.remove(sessionId, it) }
            }
        }

        try {
            withTimeoutOrNull(yieldMs.coerceAtLeast(1L)) { signal.await() }
        } catch (cancelled: CancellationException) {
            worker.cancel()
            throw cancelled
        }

        // The window ending and the command ending can coincide; deciding under the lock gives one answer.
        synchronized(lock) {
            finished?.let { return Outcome.Finished(it) }
            val id = JobRegistry.start(ShellJobs.KIND, command.lineSequence().first().take(120), sessionId, runId, taskId, executionIntentId)
            JobRegistry.appendOutput(id, early.toString())
            early.setLength(0)
            jobId = id
            active[sessionId] = id
            JobRegistry.setCanceller(id) { worker.cancel() }
            return Outcome.Yielded(id)
        }
    }

    /** What the agent is told when a command is handed over: the output so far and how to follow it. */
    fun reply(jobId: String, waitedMs: Long): String {
        val read = JobRegistry.read(jobId, 0L, REPLY_CHARS)
        val job = JobRegistry.get(jobId)
        val running = job?.status == JobRegistry.JobStatus.RUNNING
        return buildString {
            appendLine("job_id: $jobId")
            val detail = job?.detail?.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty()
            if (running) {
                appendLine("status: RUNNING (not finished after ${waitedMs / 1000}s; it keeps running and this shell is busy until it ends)")
            } else {
                appendLine("status: ${job?.status?.name ?: "UNKNOWN"}$detail")
            }
            if (read != null && read.text.isNotEmpty()) {
                appendLine("---")
                if (read.missed > 0) appendLine("[earlier output not shown]")
                appendLine(read.text.trimEnd('\n'))
            }
            if (running) {
                append("Read more with job_output {job_id: \"$jobId\", offset: ${read?.nextOffset ?: 0}, wait: true}; stop it with job_kill. ")
                append("A new foreground shell_execute is refused until it ends; use background=true to run something alongside it.")
            }
        }.trimEnd()
    }

    /** Refusal for a foreground call that arrives while a yielded command still holds the shell. */
    fun busyMessage(jobId: String): String =
        "Error: this shell is still running job $jobId, a command that had not finished when it was handed back. " +
            "Read it with job_output {job_id: \"$jobId\", wait: true}, stop it with job_kill, " +
            "or run this command with background=true."

    /** A user Stop or the end of a session: the yielded command of [sessionId] must not keep running. */
    fun stopSession(sessionId: String, reason: String) {
        active.remove(sessionId)?.let { JobRegistry.kill(it, reason) }
    }

    /** A runtime-wide stop: every yielded command goes. */
    fun stopAll(reason: String) {
        active.keys.toList().forEach { stopSession(it, reason) }
    }

    private fun settle(jobId: String, result: ExecutionCoordinator.CommandResult, timeoutMs: Long) {
        // The streamed lines are the output; only a command that printed nothing (a failure before it ran) has its text here.
        if (JobRegistry.output(jobId).isNullOrBlank() && result.output.isNotBlank()) {
            JobRegistry.appendOutput(jobId, result.output + "\n")
        }
        val timedOut = result.exitCode == 124 && result.output.startsWith("command timed out after")
        when {
            timedOut -> JobRegistry.finish(jobId, JobRegistry.JobStatus.FAILED, "timed out after ${timeoutMs / 1000}s", ToolFailureKind.TOOL_TIMEOUT)
            result.exitCode == 0 -> JobRegistry.finish(jobId, JobRegistry.JobStatus.COMPLETED, "exit code 0")
            result.exitCode == 130 -> JobRegistry.finish(jobId, JobRegistry.JobStatus.KILLED, "stopped")
            else -> JobRegistry.finish(jobId, JobRegistry.JobStatus.FAILED, "exit code ${result.exitCode}")
        }
    }
}
