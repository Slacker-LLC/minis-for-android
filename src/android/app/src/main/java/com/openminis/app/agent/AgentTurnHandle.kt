package com.openminis.app.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job

internal sealed interface AgentTurnOutcome {
    data object Completed : AgentTurnOutcome
    data class Failed(val reason: String) : AgentTurnOutcome
    data object Cancelled : AgentTurnOutcome
    data class NeedsAttention(val reason: String) : AgentTurnOutcome
    data class Rejected(val reason: String) : AgentTurnOutcome
}

/** Completion belongs to one accepted request, never to a shared UI busy flag. */
internal class AgentTurnHandle(internal val taskId: String? = null,
                               internal val requestId: String = java.util.UUID.randomUUID().toString(),
                               internal val userInput: Boolean = false) {
    private val completion = CompletableDeferred<AgentTurnOutcome>()
    val result: Deferred<AgentTurnOutcome> = completion
    internal val accepted = CompletableDeferred<String?>()
    @Volatile internal var runId: String? = null
    @Volatile var userMessageId: String? = null
        internal set
    @Volatile private var outcome: AgentTurnOutcome = AgentTurnOutcome.Failed("turn_did_not_finish")
    @Volatile private var job: Job? = null

    fun record(outcome: AgentTurnOutcome) { this.outcome = outcome }
    internal val recordedOutcome get() = outcome
    fun markAccepted(messageId: String) { userMessageId = messageId; accepted.complete(messageId) }

    fun reject(reason: String) { accepted.complete(null); completion.complete(AgentTurnOutcome.Rejected(reason)) }

    fun bind(job: Job) {
        this.job = job
        job.invokeOnCompletion { cause ->
            accepted.complete(null)
            completion.complete(when (cause) {
                is kotlinx.coroutines.CancellationException -> AgentTurnOutcome.Cancelled
                null -> outcome
                else -> AgentTurnOutcome.Failed(cause.message ?: cause.javaClass.simpleName)
            })
        }
    }

    fun cancel() { job?.cancel() }
}
