package com.openminis.app.agent.subagents

import com.openminis.app.data.model.SubAgentDefinition
import com.openminis.app.data.model.SubAgentRoster
import com.openminis.app.data.model.ThinkingLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** The model a delegation runs on: a model entry (null = the child's own default) and where it came from. */
data class SubAgentModel(val entryId: String?, val label: String, val origin: String)

/** What a finished child run reports. */
data class ChildOutcome(val completed: Boolean, val text: String?, val timedOut: Boolean)

/**
 * Everything the runtime needs from the app: the roster, model resolution, and driving child
 * sessions. A narrow seam so the delegation logic can be tested without Android or a model.
 */
interface SubAgentPort {
    fun roster(): List<SubAgentDefinition>

    /** The model for this delegation, or null when none is usable (pinned entry gone, no provider). */
    suspend fun resolveModel(def: SubAgentDefinition, choice: SubAgentModelChoice, parentSessionId: String): SubAgentModel?

    /** Creates the hidden/labelled child session and returns its id. */
    suspend fun createChild(parentSessionId: String, title: String, model: SubAgentModel): String

    /** Sends [brief] to the child and waits for the run to end (or [timeoutMs]). */
    suspend fun runChild(childSessionId: String, brief: String, thinking: ThinkingLevel?, timeoutMs: Long): ChildOutcome

    suspend fun cancelChild(childSessionId: String): Boolean

    /** Delivers a course-correction to a running child; it is read at the child's next turn. */
    suspend fun steerChild(childSessionId: String, message: String): Boolean

    /** Posts [text] into the delegating conversation (queued behind a running turn, never interrupting it). */
    suspend fun deliverToParent(parentSessionId: String, text: String)
}

/** The tool's reply: the JSON text the model reads, and whether the call counts as successful. */
data class SubAgentReply(val text: String, val ok: Boolean)

/**
 * Runs delegations: validates a `subagent` call, admits it to the registry, drives the child
 * session through [SubAgentPort], and reports back. Pure orchestration — no Android types.
 *
 * Design after OpenMinis 1.14 (background by default, `wait=true` as the blocking opt-in, queued
 * extras, steer/cancel/status), simplified for this app: the registry lives in memory only and a run
 * lost to a process kill is not resumed.
 */
class SubAgentRuntime(
    private val port: SubAgentPort,
    val registry: SubAgentJobRegistry,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val cancelRequested = ConcurrentHashMap.newKeySet<String>()
    private val waiters = ConcurrentHashMap<String, CompletableDeferred<SubAgentJob>>()

    /**
     * @param callerIsSubAgent true when the calling conversation is itself a sub agent's child
     *   session: delegation is one level deep, so it is refused there.
     */
    suspend fun execute(argsJson: String, parentSessionId: String, callerIsSubAgent: Boolean = false): SubAgentReply {
        if (callerIsSubAgent) {
            return fail("depth_limit", "A sub agent cannot delegate further. Do this work yourself.")
        }
        val args = when (val parsed = SubAgentTask.parseArgs(argsJson)) {
            is SubAgentArgsResult.Invalid -> return fail(parsed.error, parsed.message)
            is SubAgentArgsResult.Ok -> parsed.args
        }
        return when (args.action) {
            SubAgentAction.DELEGATE -> delegate(args, parentSessionId)
            SubAgentAction.STATUS -> status(args, parentSessionId)
            SubAgentAction.CANCEL -> cancel(args, parentSessionId)
            SubAgentAction.STEER -> steer(args, parentSessionId)
            SubAgentAction.RESUME -> resume(args, parentSessionId)
        }
    }

    // ── delegate ────────────────────────────────────────────────────────────

    private suspend fun delegate(args: SubAgentTaskArgs, parentSessionId: String): SubAgentReply {
        val roster = port.roster()
        val def = SubAgentRoster.resolve(args.agent, roster)
            ?: return fail("unknown_agent", "No sub agent named '${args.agent}'.") {
                put("available", org.json.JSONArray(roster.map { it.name }))
            }
        val model = port.resolveModel(def, args.modelChoice, parentSessionId)
            ?: return fail(
                "model_unavailable",
                "No usable model for sub agent '${def.name}': its pinned model is gone or no provider is configured.",
            )
        val title = args.title.ifBlank { args.task.take(40) }
        return when (val admission = registry.submit(
            parentSessionId = parentSessionId,
            agentName = def.name,
            title = title,
            wait = args.wait,
            maxMinutes = args.maxMinutes,
            modelOrigin = model.origin,
            modelLabel = model.label,
            modelEntryId = model.entryId,
            thinking = def.thinkingLevelOverride,
            brief = SubAgentTask.childBrief(def, args.task, args.context),
        )) {
            is SubAgentAdmission.Rejected ->
                fail("queue_full", "Too many sub agents are waiting (${registry.maxQueued}). Try again after some finish.")
            is SubAgentAdmission.Started -> {
                val waiter = if (args.wait) waiterFor(admission.job.id) else null
                launch(admission.job)
                if (waiter != null) awaitFinal(admission.job.id, waiter) else SubAgentReply(SubAgentTask.started(admission.job), true)
            }
            is SubAgentAdmission.Queued -> {
                val waiter = if (args.wait) waiterFor(admission.job.id) else null
                if (waiter != null) awaitFinal(admission.job.id, waiter)
                else SubAgentReply(SubAgentTask.queued(admission.job, admission.position), true)
            }
        }
    }

    private fun waiterFor(jobId: String): CompletableDeferred<SubAgentJob> =
        CompletableDeferred<SubAgentJob>().also { waiters[jobId] = it }

    /** Blocks for the run's result. If the waiting call is itself cancelled (the user stopped the turn), the run is stopped too. */
    private suspend fun awaitFinal(jobId: String, waiter: CompletableDeferred<SubAgentJob>): SubAgentReply {
        val job = try {
            waiter.await()
        } catch (e: CancellationException) {
            requestCancel(jobId)
            throw e
        }
        val ok = job.state == SubAgentJobState.DONE
        return SubAgentReply(SubAgentTask.finalPayload(job, clock()).toString(), ok)
    }

    private fun launch(job: SubAgentJob) {
        scope.launch {
            var state = SubAgentJobState.FAILED
            var text: String? = null
            try {
                // A resumed run continues in the child it already has; anything else (and a resumed run
                // that never got a child) starts a fresh child with the original brief.
                val existingChild = job.childSessionId.takeIf { job.resumed }
                val childId = existingChild ?: port.createChild(
                    job.parentSessionId, "↳ " + job.title.take(40),
                    SubAgentModel(job.modelEntryId, job.modelLabel.orEmpty(), job.modelOrigin.orEmpty()),
                ).also { registry.attachChild(job.id, it) }
                if (job.id in cancelRequested) {
                    state = SubAgentJobState.CANCELLED
                    text = "Cancelled before it started."
                } else {
                    val message = if (existingChild != null) SubAgentTask.resumeNotice()
                        else job.brief ?: error("the delegation has no brief")
                    val outcome = port.runChild(childId, message, job.thinking, job.maxMinutes * 60_000L)
                    text = outcome.text?.trim()
                    state = when {
                        job.id in cancelRequested -> SubAgentJobState.CANCELLED
                        outcome.timedOut -> SubAgentJobState.TIMEOUT
                        outcome.completed -> SubAgentJobState.DONE
                        else -> SubAgentJobState.FAILED
                    }
                    if (outcome.timedOut) runCatching { port.cancelChild(childId) }
                }
            } catch (e: CancellationException) {
                // The scope is going away, but the parent still gets told and the next job still starts.
                withContext(NonCancellable) { complete(job.id, SubAgentJobState.CANCELLED, text ?: "Stopped.") }
                throw e
            } catch (t: Throwable) {
                state = SubAgentJobState.FAILED
                text = "Sub agent failed: ${t.message ?: t.javaClass.simpleName}"
            }
            complete(job.id, state, text)
        }
    }

    /** Closes a job, tells the parent (background runs), wakes a blocked call, and starts the next queued job. */
    private suspend fun complete(jobId: String, state: SubAgentJobState, text: String?) {
        val done = registry.finish(jobId, state, text) ?: return
        cancelRequested.remove(jobId)
        if (!done.wait) {
            runCatching { port.deliverToParent(done.parentSessionId, SubAgentTask.callbackText(done, clock())) }
        }
        waiters.remove(jobId)?.complete(done)
        registry.promoteNext()?.let { launch(it) }
    }

    // ── status / cancel / steer ─────────────────────────────────────────────

    private fun status(args: SubAgentTaskArgs, parentSessionId: String): SubAgentReply {
        val jobs = if (args.jobId == null) {
            registry.jobsOf(parentSessionId)
        } else when (val found = registry.lookup(parentSessionId, args.jobId)) {
            is SubAgentLookup.Found -> listOf(found.job)
            SubAgentLookup.NotFound -> return fail("job_not_found", "No sub agent job '${args.jobId}' in this conversation.")
            is SubAgentLookup.Ambiguous -> return fail("ambiguous_job_id", "'${args.jobId}' matches several jobs; use more characters.")
        }
        return SubAgentReply(SubAgentTask.statusPayload(jobs, clock(), registry::queuePosition), true)
    }

    private suspend fun cancel(args: SubAgentTaskArgs, parentSessionId: String): SubAgentReply {
        val job = when (val found = registry.lookup(parentSessionId, args.jobId!!)) {
            is SubAgentLookup.Found -> found.job
            SubAgentLookup.NotFound -> return fail("job_not_found", "No sub agent job '${args.jobId}' in this conversation.")
            is SubAgentLookup.Ambiguous -> return fail("ambiguous_job_id", "'${args.jobId}' matches several jobs; use more characters.")
        }
        if (job.state.isTerminal) {
            return SubAgentReply(SubAgentTask.finalPayload(job, clock()).toString(), true)
        }
        requestCancel(job.id)
        val now = registry.get(job.id)
        return SubAgentReply(
            org.json.JSONObject()
                .put("status", if (now?.state?.isTerminal == true) now.state.wire else "cancelling")
                .put("job_id", job.id)
                .put("note", "Stopping it; any partial result is posted back as a new message.")
                .toString(),
            true,
        )
    }

    /** Stops a queued job at once, or asks a running one to stop. */
    private suspend fun requestCancel(jobId: String) {
        val job = registry.get(jobId) ?: return
        when (job.state) {
            SubAgentJobState.QUEUED -> complete(jobId, SubAgentJobState.CANCELLED, "Cancelled before it started.")
            SubAgentJobState.RUNNING -> {
                cancelRequested.add(jobId)
                job.childSessionId?.let { runCatching { port.cancelChild(it) } }
            }
            else -> Unit
        }
    }

    private suspend fun steer(args: SubAgentTaskArgs, parentSessionId: String): SubAgentReply {
        val job = when (val found = registry.lookup(parentSessionId, args.jobId!!)) {
            is SubAgentLookup.Found -> found.job
            SubAgentLookup.NotFound -> return fail("job_not_found", "No sub agent job '${args.jobId}' in this conversation.")
            is SubAgentLookup.Ambiguous -> return fail("ambiguous_job_id", "'${args.jobId}' matches several jobs; use more characters.")
        }
        val child = job.childSessionId
        if (job.state != SubAgentJobState.RUNNING || child == null) {
            return fail("not_running", "Job ${job.id.take(8)} is ${job.state.wire}; only a running sub agent can be steered.")
        }
        val delivered = runCatching { port.steerChild(child, args.message!!) }.getOrDefault(false)
        if (!delivered) return fail("steer_failed", "The correction could not be delivered to the sub agent.")
        return SubAgentReply(
            org.json.JSONObject().put("status", "steered").put("job_id", job.id)
                .put("note", "Read at its next turn; a running tool call is not interrupted. If it finishes first, the correction is missed.")
                .toString(),
            true,
        )
    }

    private suspend fun resume(args: SubAgentTaskArgs, parentSessionId: String): SubAgentReply {
        val targets = if (args.jobId != null) {
            when (val found = registry.lookup(parentSessionId, args.jobId)) {
                is SubAgentLookup.Found -> listOf(found.job)
                SubAgentLookup.NotFound -> return fail("job_not_found", "No sub agent job '${args.jobId}' in this conversation.")
                is SubAgentLookup.Ambiguous -> return fail("ambiguous_job_id", "'${args.jobId}' matches several jobs; use more characters.")
            }
        } else {
            registry.jobsOf(parentSessionId).filter { it.state == SubAgentJobState.INTERRUPTED }
        }
        if (args.jobId != null && targets.single().state != SubAgentJobState.INTERRUPTED) {
            return fail("not_interrupted", "Job ${targets.single().id.take(8)} is ${targets.single().state.wire}; only an interrupted sub agent can be resumed.")
        }
        val results = org.json.JSONArray()
        for (job in targets) {
            when (val admission = registry.resume(job.id)) {
                is SubAgentAdmission.Started -> { launch(admission.job); results.put(org.json.JSONObject().put("job_id", job.id).put("status", "running")) }
                is SubAgentAdmission.Queued -> results.put(org.json.JSONObject().put("job_id", job.id).put("status", "queued").put("position", admission.position))
                is SubAgentAdmission.Rejected -> results.put(org.json.JSONObject().put("job_id", job.id).put("status", "error").put("error", "queue_full"))
                null -> Unit
            }
        }
        return SubAgentReply(
            org.json.JSONObject().put("status", "ok").put("resumed", results)
                .put("note", "Resumed runs report back as a new message when they finish, like any background run.").toString(),
            true,
        )
    }

    private fun fail(code: String, message: String, extra: org.json.JSONObject.() -> Unit = {}) =
        SubAgentReply(SubAgentTask.error(code, message, extra), false)
}
