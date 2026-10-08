package com.openminis.app.runtime

import com.openminis.app.tools.JobRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Commands that run on their own, apart from a session's one persistent shell.
 *
 * The persistent shell keeps `cd` / `export` state and runs one command at a time, so a long command (a build, a
 * server, a download) blocks everything behind it and a timeout kills the shell with its state. A job is the other
 * shape: its own guest shell process, started and left running, whose output the agent reads later by id
 * (`job_output`, `job_list`, `job_kill` - the job tools that already exist, backed by [JobRegistry]) - the way
 * Codex's unified exec keeps several command sessions alive at once. Any number of jobs run in parallel with each
 * other and with the persistent shell.
 *
 * This object owns what [JobRegistry] does not: the guest process and its lifetime. A few jobs per session and in
 * total, a deadline per job, and no job outlives its session or a runtime-wide stop. Output and status live in the
 * registry (bounded there). Jobs are in-memory only: when the app process dies they die with it.
 */
object ShellJobs {
    /** What a job runs on. The real one is a dedicated guest shell; tests use a fake. */
    interface JobProcess {
        data class Result(val exitCode: Int, val timedOut: Boolean = false)

        /** Runs the command to its end, feeding every output line to [onLine]; may throw on startup failure. */
        suspend fun run(command: String, timeoutMs: Long, onLine: (String) -> Unit): Result

        /** Kills the process (and what it started). Safe to call at any time, more than once. */
        fun stop()

        /** Sends [data] to the process's stdin, then closes it when [eof]. Null on success, otherwise the reason it failed. */
        suspend fun writeInput(data: String, eof: Boolean): String? = "this job cannot take input"
    }

    class LimitExceeded(message: String) : Exception(message)

    const val KIND = "shell"
    const val MAX_RUNNING_PER_SESSION = 6
    const val MAX_RUNNING_TOTAL = 16
    const val MAX_TIMEOUT_MS = 24L * 60 * 60 * 1000
    const val DEFAULT_TIMEOUT_MS = 2L * 60 * 60 * 1000

    /** How much output the start call's reply carries at most. */
    const val START_REPLY_CHARS = 4_000

    private class Entry(val sessionId: String, val process: JobProcess) {
        @Volatile var coroutine: kotlinx.coroutines.Job? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Registry job id -> the process behind it, for jobs started here. */
    private val entries = ConcurrentHashMap<String, Entry>()
    private val admission = Any()

    private fun running(sessionId: String?): Int = entries.entries.count { (id, e) ->
        (sessionId == null || e.sessionId == sessionId) && JobRegistry.get(id)?.status == JobRegistry.JobStatus.RUNNING
    }

    /**
     * Starts [command] as a job of [sessionId] and returns its [JobRegistry] id. Throws [LimitExceeded] when the
     * session or the app already runs as many jobs as allowed; the caller says so to the agent instead of queueing.
     */
    fun start(
        sessionId: String,
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        newProcess: () -> JobProcess,
    ): String {
        val deadline = timeoutMs.coerceIn(1_000L, MAX_TIMEOUT_MS)
        val id: String
        val entry: Entry
        synchronized(admission) {
            // Finished entries are dropped as they are noticed, so the table cannot grow without bound.
            entries.keys.filter { JobRegistry.get(it)?.status != JobRegistry.JobStatus.RUNNING }.forEach { entries.remove(it) }
            if (running(sessionId) >= MAX_RUNNING_PER_SESSION) {
                throw LimitExceeded("this session already runs $MAX_RUNNING_PER_SESSION background jobs; wait for or kill one first")
            }
            if (running(null) >= MAX_RUNNING_TOTAL) {
                throw LimitExceeded("the app already runs $MAX_RUNNING_TOTAL background jobs; wait for or kill one first")
            }
            id = JobRegistry.start(KIND, command.lineSequence().first().take(120))
            entry = Entry(sessionId, newProcess())
            entries[id] = entry
        }
        // A generic job_kill / RPC cancel stops the guest process, not only the label.
        JobRegistry.setCanceller(id) {
            runCatching { entry.process.stop() }
            entry.coroutine?.cancel()
        }
        entry.coroutine = scope.launch {
            try {
                val result = entry.process.run(command, deadline) { line -> JobRegistry.appendOutput(id, line + "\n") }
                when {
                    result.timedOut -> JobRegistry.finish(id, JobRegistry.JobStatus.FAILED, "timed out after ${deadline / 1000}s")
                    result.exitCode == 0 -> JobRegistry.finish(id, JobRegistry.JobStatus.COMPLETED, "exit code 0")
                    else -> JobRegistry.finish(id, JobRegistry.JobStatus.FAILED, "exit code ${result.exitCode}")
                }
            } catch (cancelled: CancellationException) {
                // A kill marks the job itself; a cancellation that reaches here without one is a shutdown.
                JobRegistry.finish(id, JobRegistry.JobStatus.KILLED, "stopped")
                throw cancelled
            } catch (error: Exception) {
                JobRegistry.appendOutput(id, "[job failed to run: ${error.message ?: error::class.java.simpleName}]\n")
                JobRegistry.finish(id, JobRegistry.JobStatus.FAILED, "could not run")
            } finally {
                runCatching { entry.process.stop() }
                entries.remove(id)
            }
        }
        return id
    }

    /** Sends input to a running job of [sessionId]. Null on success, otherwise what to tell the agent. */
    suspend fun writeInput(sessionId: String?, jobId: String, data: String, eof: Boolean): String? {
        val entry = entries[jobId]
        val job = JobRegistry.get(jobId)
        if (entry == null || job == null) {
            return if (job == null) "no such job: $jobId" else "job $jobId is not a shell job started in this session"
        }
        if (sessionId != null && entry.sessionId != sessionId) return "job $jobId belongs to another session"
        if (job.status != JobRegistry.JobStatus.RUNNING) return "job $jobId is not running (${job.status.name})"
        return entry.process.writeInput(data, eof)
    }

    /** The session ended: nothing of it may keep running. */
    fun killSession(sessionId: String) {
        entries.entries.filter { it.value.sessionId == sessionId }.forEach { (id, _) ->
            JobRegistry.kill(id, "session ended")
        }
    }

    /** A runtime-wide stop (rootfs or mount change): every job of every session goes. */
    fun killAll() {
        entries.keys.toList().forEach { JobRegistry.kill(it, "runtime stopped") }
    }
}
