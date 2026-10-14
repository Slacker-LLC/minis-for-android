package com.openminis.app.agent

import androidx.annotation.MainThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** One session's execution lifetime, including preparation, children and cancellation cleanup. */
internal class AgentExecution {
    val conversation = AgentConversation()
    private val lifetime = SupervisorJob()
    internal val scope = CoroutineScope(lifetime + Dispatchers.Main.immediate)
    @Volatile var currentJob: Job? = null
        private set
    @Volatile private var currentRequest: AgentTurnHandle? = null
    val isClosed: Boolean get() = !lifetime.isActive

    @MainThread
    fun launchTurn(
        request: AgentTurnHandle? = null,
        onStarted: () -> Unit,
        onSettled: () -> Unit,
        block: suspend CoroutineScope.() -> Unit,
    ): Job {
        val previous = currentJob
        val job = scope.launch(start = CoroutineStart.LAZY) {
            // Cancel can expose the composer before old tool/persistence cleanup finishes.
            // Even a cancelled successor must retain this fence until its predecessor exits.
            withContext(NonCancellable) { previous?.join() }
            ensureActive()
            block()
        }
        synchronized(this) {
            currentJob = job
            currentRequest = request
            onStarted()
        }
        job.invokeOnCompletion {
            synchronized(this) {
                if (currentJob === job) {
                    currentJob = null
                    currentRequest = null
                    onSettled()
                }
            }
        }
        request?.bind(job)
        job.start()
        return job
    }

    fun cancelTurn() { currentJob?.cancel() }

    @Synchronized
    fun cancelRequest(requestId: String): Boolean {
        val request = currentRequest?.takeIf { it.requestId == requestId } ?: return false
        request.cancel()
        return true
    }

    suspend fun awaitExit() { currentJob?.join() }

    suspend fun awaitExit(timeoutMs: Long): Boolean {
        val job = currentJob ?: return true
        if (job.isCompleted) return true
        return withTimeoutOrNull(timeoutMs.coerceAtLeast(0)) { job.join(); true } ?: false
    }

    /** Called when the session owner is released, never when a chat screen is dismissed. */
    fun close() { lifetime.cancel() }

    suspend fun awaitClosed() { lifetime.join() }

    fun invokeOnClosed(block: () -> Unit) { lifetime.invokeOnCompletion { block() } }
}
