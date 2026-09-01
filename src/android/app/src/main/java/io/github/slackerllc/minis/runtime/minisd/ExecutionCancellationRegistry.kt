package io.github.slackerllc.minis.runtime.minisd

import java.util.concurrent.ConcurrentHashMap

/** Tracks active session executions and transport cancellations without owning I/O. */
internal class ExecutionCancellationRegistry {

    data class Target(val sessionId: String, val executionId: String)

    private val activeSessionExecutions = ConcurrentHashMap<String, String>()
    private val cancelledExecutions = ConcurrentHashMap.newKeySet<String>()

    fun register(sessionId: String, executionId: String) {
        activeSessionExecutions[sessionId] = executionId
    }

    fun unregister(sessionId: String, executionId: String) {
        activeSessionExecutions.remove(sessionId, executionId)
        cancelledExecutions.remove(executionId)
    }

    fun requestSessionCancellation(sessionId: String): Target? {
        val executionId = activeSessionExecutions[sessionId] ?: return null
        cancelledExecutions += executionId
        return Target(sessionId, executionId)
    }

    fun requestAllSessionCancellations(): List<Target> =
        activeSessionExecutions.entries.map { (sessionId, executionId) ->
            cancelledExecutions += executionId
            Target(sessionId, executionId)
        }

    fun requestExecutionCancellation(executionId: String) {
        cancelledExecutions += executionId
    }

    fun isCancellationRequested(executionId: String): Boolean =
        executionId in cancelledExecutions

    fun clearExecution(executionId: String) {
        cancelledExecutions.remove(executionId)
    }
}
