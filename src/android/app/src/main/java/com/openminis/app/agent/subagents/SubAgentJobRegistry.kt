package com.openminis.app.agent.subagents

import com.openminis.app.data.model.ThinkingLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Lifecycle of one delegation. [wire] is the word the model and the cards see.
 *
 * Shaped after OpenMinis 1.14's `AgentJobRegistry` (`AgentJobState`), reduced to what a delegation
 * needs here: no triggers or follow-up targets, a delegation is always "run this brief in a child
 * session now (or when a slot frees)".
 */
@Serializable
enum class SubAgentJobState {
    QUEUED, RUNNING, DONE, CANCELLED, FAILED,

    /** The wall-clock budget elapsed and the run was cut short. */
    TIMEOUT,

    /**
     * The app was killed while the run was queued or running. Not resumed automatically; the model (or the
     * card's Resume button) restarts it with action=resume.
     */
    INTERRUPTED;

    val isTerminal: Boolean
        get() = this == DONE || this == CANCELLED || this == FAILED || this == TIMEOUT || this == INTERRUPTED

    /** `done` is reported as `completed`, the word the tool contract uses. */
    val wire: String get() = if (this == DONE) "completed" else name.lowercase()
}

/** One delegation. An immutable snapshot; the registry replaces entries. */
@Serializable
data class SubAgentJob(
    val id: String,
    /** The conversation that delegated it. Every lookup is scoped to this. */
    val parentSessionId: String,
    val agentName: String,
    val title: String,
    /** True when the parent's tool call is blocked on the result. */
    val wait: Boolean,
    val maxMinutes: Int,
    val state: SubAgentJobState,
    /** Where the run's model came from: "agent" (pinned), "same_as_me", "default_model", "sub_model". */
    val modelOrigin: String? = null,
    /** Human-readable model label for status output. */
    val modelLabel: String? = null,
    val childSessionId: String? = null,
    val createdAtMs: Long,
    val startedAtMs: Long? = null,
    val finishedAtMs: Long? = null,
    val resultText: String? = null,
    /** The model entry the run uses (so a run that never got a child can start one on resume). */
    val modelEntryId: String? = null,
    val thinking: ThinkingLevel? = null,
    /**
     * The complete brief sent to the child. Kept only while the job is not finished (a queued job needs it
     * to start; an interrupted one may need it to start over) and dropped once it is.
     */
    val brief: String? = null,
    /** True when this run is a resume of one the app lost. */
    val resumed: Boolean = false,
    /** none | frequent | moderate: how often a background run posts progress into its conversation. */
    val progress: String = "none",
) {
    val isActive: Boolean get() = !state.isTerminal
    fun elapsedMs(now: Long): Long? = startedAtMs?.let { (finishedAtMs ?: now) - it }
}

/** Why a delegation was not accepted. */
enum class SubAgentRejection { QUEUE_FULL }

sealed class SubAgentAdmission {
    /** A slot was free; the run may start now. */
    data class Started(val job: SubAgentJob) : SubAgentAdmission()

    /** All slots are busy; the job waits (1-based [position]) and starts when one frees. */
    data class Queued(val job: SubAgentJob, val position: Int) : SubAgentAdmission()

    data class Rejected(val reason: SubAgentRejection) : SubAgentAdmission()
}

/** Result of looking a job up by (a prefix of) its id within one conversation. */
sealed class SubAgentLookup {
    data class Found(val job: SubAgentJob) : SubAgentLookup()
    object NotFound : SubAgentLookup()
    data class Ambiguous(val matches: List<String>) : SubAgentLookup()
}

/** Where the registry keeps its last [SubAgentJobRegistry.MAX_KEPT] jobs across app restarts. */
interface SubAgentJobStore {
    fun load(): List<SubAgentJob>
    fun save(jobs: List<SubAgentJob>)
}

/**
 * Registry of every sub agent run.
 *
 * Kept in memory and mirrored to a [SubAgentJobStore] so that a run lost to a process kill shows up
 * afterwards as `interrupted` (and can be resumed) instead of vanishing, and so cards keep their
 * history. Only the most recent [MAX_KEPT] jobs are kept.
 *
 * Concurrency is bounded globally ([maxConcurrent] running at once) with a bounded wait queue
 * ([maxQueued]); extras are queued instead of refused so a model that delegates more than the cap
 * does not have to retry. All state changes happen under one lock; reads through [jobs] are safe
 * from anywhere.
 */
class SubAgentJobRegistry(
    val maxConcurrent: Int = MAX_CONCURRENT,
    val maxQueued: Int = MAX_QUEUED,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val store: SubAgentJobStore? = null,
) {
    private val lock = Any()
    private val _jobs = MutableStateFlow<Map<String, SubAgentJob>>(loadStored())
    val jobs: StateFlow<Map<String, SubAgentJob>> = _jobs.asStateFlow()

    /** Insertion order of queued job ids. */
    private val queue = ArrayDeque<String>()

    fun submit(
        parentSessionId: String,
        agentName: String,
        title: String,
        wait: Boolean,
        maxMinutes: Int,
        modelOrigin: String? = null,
        modelLabel: String? = null,
        modelEntryId: String? = null,
        thinking: ThinkingLevel? = null,
        brief: String? = null,
        progress: String = "none",
    ): SubAgentAdmission = synchronized(lock) {
        val running = _jobs.value.values.count { it.state == SubAgentJobState.RUNNING }
        val now = clock()
        val base = SubAgentJob(
            id = idFactory(),
            parentSessionId = parentSessionId,
            agentName = agentName,
            title = title,
            wait = wait,
            maxMinutes = maxMinutes,
            state = SubAgentJobState.QUEUED,
            modelOrigin = modelOrigin,
            modelLabel = modelLabel,
            modelEntryId = modelEntryId,
            thinking = thinking,
            brief = brief,
            progress = progress,
            createdAtMs = now,
        )
        when {
            running < maxConcurrent -> {
                val job = base.copy(state = SubAgentJobState.RUNNING, startedAtMs = now)
                put(job)
                SubAgentAdmission.Started(job)
            }
            queue.size < maxQueued -> {
                put(base)
                queue.addLast(base.id)
                SubAgentAdmission.Queued(base, queue.size)
            }
            else -> SubAgentAdmission.Rejected(SubAgentRejection.QUEUE_FULL)
        }
    }

    /** Records the child session the run executes in. No-op for a job that is already finished. */
    fun attachChild(jobId: String, childSessionId: String) = synchronized(lock) {
        val job = _jobs.value[jobId] ?: return@synchronized
        if (!job.state.isTerminal) put(job.copy(childSessionId = childSessionId))
    }

    /**
     * Moves a job to a terminal [state]. Returns the finished job, or null when it was unknown or had
     * already finished (the first terminal state wins — a cancelled run that later reports "done"
     * must not flip back).
     */
    fun finish(jobId: String, state: SubAgentJobState, resultText: String?): SubAgentJob? = synchronized(lock) {
        require(state.isTerminal) { "finish needs a terminal state" }
        val job = _jobs.value[jobId] ?: return@synchronized null
        if (job.state.isTerminal) return@synchronized null
        queue.remove(jobId)
        val done = job.copy(state = state, finishedAtMs = clock(), resultText = resultText, brief = null)
        put(done)
        done
    }

    /**
     * Takes the oldest queued job and marks it running, or null when none waits or no slot is free.
     * Call after [finish] to start the next one.
     */
    fun promoteNext(): SubAgentJob? = synchronized(lock) {
        val running = _jobs.value.values.count { it.state == SubAgentJobState.RUNNING }
        if (running >= maxConcurrent) return@synchronized null
        val id = queue.removeFirstOrNull() ?: return@synchronized null
        val job = _jobs.value[id] ?: return@synchronized null
        if (job.state != SubAgentJobState.QUEUED) return@synchronized null
        val started = job.copy(state = SubAgentJobState.RUNNING, startedAtMs = clock())
        put(started)
        started
    }

    fun get(jobId: String): SubAgentJob? = _jobs.value[jobId]

    /** 1-based queue position of [jobId], or null when it is not waiting. */
    fun queuePosition(jobId: String): Int? = synchronized(lock) {
        queue.indexOf(jobId).takeIf { it >= 0 }?.plus(1)
    }

    /** Every job of one conversation, oldest first. */
    fun jobsOf(parentSessionId: String): List<SubAgentJob> =
        _jobs.value.values.filter { it.parentSessionId == parentSessionId }.sortedBy { it.createdAtMs }

    /**
     * Finds a job of [parentSessionId] by its id or an unambiguous prefix of it. The lookup never
     * crosses conversations: one chat must not be able to inspect or stop another chat's runs.
     */
    fun lookup(parentSessionId: String, idOrPrefix: String): SubAgentLookup {
        val needle = idOrPrefix.trim()
        if (needle.isEmpty()) return SubAgentLookup.NotFound
        val mine = _jobs.value.values.filter { it.parentSessionId == parentSessionId }
        mine.firstOrNull { it.id == needle }?.let { return SubAgentLookup.Found(it) }
        val byPrefix = mine.filter { it.id.startsWith(needle) }
        return when (byPrefix.size) {
            0 -> SubAgentLookup.NotFound
            1 -> SubAgentLookup.Found(byPrefix.single())
            else -> SubAgentLookup.Ambiguous(byPrefix.map { it.id })
        }
    }

    /**
     * Restarts an interrupted job: running when a slot is free, otherwise queued. Returns null when the
     * job is unknown or is not interrupted. The result text of the lost run is kept until the new run
     * finishes.
     */
    fun resume(jobId: String): SubAgentAdmission? = synchronized(lock) {
        val job = _jobs.value[jobId] ?: return@synchronized null
        if (job.state != SubAgentJobState.INTERRUPTED) return@synchronized null
        val running = _jobs.value.values.count { it.state == SubAgentJobState.RUNNING }
        val now = clock()
        if (running < maxConcurrent) {
            val started = job.copy(state = SubAgentJobState.RUNNING, startedAtMs = now, finishedAtMs = null, resumed = true)
            put(started)
            SubAgentAdmission.Started(started)
        } else if (queue.size < maxQueued) {
            val queued = job.copy(state = SubAgentJobState.QUEUED, finishedAtMs = null, resumed = true)
            put(queued)
            queue.addLast(queued.id)
            SubAgentAdmission.Queued(queued, queue.size)
        } else {
            SubAgentAdmission.Rejected(SubAgentRejection.QUEUE_FULL)
        }
    }

    /** Test/maintenance hook: forget everything. */
    fun clear() = synchronized(lock) {
        queue.clear()
        _jobs.value = emptyMap()
    }

    private fun put(job: SubAgentJob) {
        var next = _jobs.value + (job.id to job)
        if (next.size > MAX_KEPT) {
            // Forget the oldest finished jobs first; an active job is never dropped.
            val drop = next.values.filter { it.state.isTerminal }.sortedBy { it.createdAtMs }
                .take(next.size - MAX_KEPT).map { it.id }.toSet()
            next = next - drop
        }
        _jobs.value = next
        store?.let { runCatching { it.save(next.values.sortedBy { j -> j.createdAtMs }) } }
    }

    /**
     * Jobs from before the last start. Whatever was queued or running is gone (its coroutine died with
     * the process), so it becomes `interrupted`; finished jobs are kept as they were.
     */
    private fun loadStored(): Map<String, SubAgentJob> {
        val stored = runCatching { store?.load() }.getOrNull().orEmpty()
        return stored.associate { job ->
            val loaded = if (job.state == SubAgentJobState.QUEUED || job.state == SubAgentJobState.RUNNING) {
                job.copy(state = SubAgentJobState.INTERRUPTED, finishedAtMs = clock())
            } else job
            loaded.id to loaded
        }
    }

    companion object {
        /** Hard cap on concurrently running child sessions. */
        const val MAX_CONCURRENT = 3

        /** Delegations beyond the cap wait here instead of being refused. */
        const val MAX_QUEUED = 10

        /** Jobs remembered across restarts (and in memory); the oldest finished ones go first. */
        const val MAX_KEPT = 30
    }
}
