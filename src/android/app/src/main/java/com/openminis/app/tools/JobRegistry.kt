package com.openminis.app.tools

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory registry of background jobs (DeepSeek Harness `dsh-tool-jobs`
 * contract, minimal port).
 *
 * A job is created via [start], receives streamed output via [appendOutput],
 * and is terminated by [finish] (natural completion) or [kill] (cancellation
 * request). All mutating operations are `@Synchronized` on the singleton
 * object monitor, so concurrent writers (background tasks appending output,
 * the agent loop finishing a job, a remote RPC cancelling it) are safe.
 * [get] and [list] are lock-free reads over a [ConcurrentHashMap].
 *
 * The registry is bounded: at most [MAX_ENTRIES] jobs are kept, and when the
 * cap is exceeded the oldest (by [Job.startedAt]) entries are evicted first.
 */
object JobRegistry {

    /** Lifecycle state of a job. */
    enum class JobStatus {
        /** Job is active and may still accumulate output. */
        RUNNING,

        /** Job finished naturally; [Job.finishedAt] is set. */
        COMPLETED,

        /** Job was cancelled via [kill]; the reason is in [Job.detail]. */
        KILLED,

        /** Job failed; the reason is in [Job.detail]. */
        FAILED,
    }

    /**
     * One tracked job. Immutable except for [output], which accumulates
     * streamed text. Status transitions replace the map entry with a
     * [copy] so concurrent readers always see a consistent snapshot.
     */
    data class Job(
        val id: String,
        val kind: String,
        val label: String,
        val status: JobStatus,
        val detail: String = "",
        val startedAt: Long,
        val finishedAt: Long? = null,
        val output: StringBuilder = StringBuilder(),
    )

    /** Upper bound on retained jobs; oldest entries are evicted beyond this. */
    private const val MAX_ENTRIES = 100

    private val jobs = ConcurrentHashMap<String, Job>()

    /**
     * Start a new job and return its id. If the registry is at capacity the
     * oldest jobs are evicted first.
     */
    @Synchronized
    fun start(kind: String, label: String): String {
        val id = UUID.randomUUID().toString()
        jobs[id] = Job(
            id = id,
            kind = kind,
            label = label,
            status = JobStatus.RUNNING,
            startedAt = System.currentTimeMillis(),
        )
        trimOldest()
        return id
    }

    /** Append [text] to the job's accumulated output. No-op for unknown ids. */
    @Synchronized
    fun appendOutput(id: String, text: String) {
        jobs[id]?.output?.append(text)
    }

    /**
     * Mark a running job as finished with [status] and an optional [detail].
     * Jobs that are already terminal are left untouched.
     */
    @Synchronized
    fun finish(id: String, status: JobStatus, detail: String = "") {
        val job = jobs[id] ?: return
        if (job.status != JobStatus.RUNNING) return
        jobs[id] = job.copy(status = status, detail = detail, finishedAt = System.currentTimeMillis())
    }

    /** Snapshot of the job's current output, or null for unknown ids. */
    @Synchronized
    fun output(id: String): String? = jobs[id]?.output?.toString()

    /** Current job, or null if the id is unknown. */
    fun get(id: String): Job? = jobs[id]

    /** All jobs, newest first by [Job.startedAt]. */
    fun list(): List<Job> = jobs.values.sortedByDescending { it.startedAt }

    /**
     * Request cancellation of a running job: transitions it RUNNING -> KILLED
     * and records [reason] in the detail. Returns true when the transition
     * happened, false when the job is unknown or already terminal.
     */
    @Synchronized
    fun kill(id: String, reason: String): Boolean {
        val job = jobs[id] ?: return false
        if (job.status != JobStatus.RUNNING) return false
        jobs[id] = job.copy(status = JobStatus.KILLED, detail = reason, finishedAt = System.currentTimeMillis())
        return true
    }

    /** Evict the oldest jobs when the registry exceeds [MAX_ENTRIES]. */
    @Synchronized
    private fun trimOldest() {
        if (jobs.size <= MAX_ENTRIES) return
        val excess = jobs.size - MAX_ENTRIES
        val oldest = jobs.values.sortedBy { it.startedAt }.take(excess)
        for (job in oldest) jobs.remove(job.id)
    }
}