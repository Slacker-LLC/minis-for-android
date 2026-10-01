package com.openminis.app.agent.subagents

import kotlinx.coroutines.flow.MutableStateFlow
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
enum class SubAgentJobState {
    QUEUED, RUNNING, DONE, CANCELLED, FAILED,

    /** The wall-clock budget elapsed and the run was cut short. */
    TIMEOUT;

    val isTerminal: Boolean get() = this == DONE || this == CANCELLED || this == FAILED || this == TIMEOUT

    /** `done` is reported as `completed`, the word the tool contract uses. */
    val wire: String get() = if (this == DONE) "completed" else name.lowercase()
}

/** One delegation. An immutable snapshot; the registry replaces entries. */
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

/**
 * In-process registry of every sub agent run.
 *
 * Deliberately NOT persisted: a delegation's whole life is inside the app process, and the parent
 * message's tool block is the durable record of what happened.
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
) {
    private val lock = Any()
    private val _jobs = MutableStateFlow<Map<String, SubAgentJob>>(emptyMap())
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
        val done = job.copy(state = state, finishedAtMs = clock(), resultText = resultText)
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

    /** Test/maintenance hook: forget everything. */
    fun clear() = synchronized(lock) {
        queue.clear()
        _jobs.value = emptyMap()
    }

    private fun put(job: SubAgentJob) {
        _jobs.value = _jobs.value + (job.id to job)
    }

    companion object {
        /** Hard cap on concurrently running child sessions. */
        const val MAX_CONCURRENT = 3

        /** Delegations beyond the cap wait here instead of being refused. */
        const val MAX_QUEUED = 10
    }
}
