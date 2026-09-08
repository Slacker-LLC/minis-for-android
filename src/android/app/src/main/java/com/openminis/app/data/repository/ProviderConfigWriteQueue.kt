package com.openminis.app.data.repository

import com.openminis.app.data.model.ProviderConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Serializes provider-config writes without making callers wait for disk I/O.
 *
 * A single worker consumes the channel in FIFO order. The queue is deliberately
 * unbounded: every mutation has already produced a complete snapshot, so
 * dropping an intermediate request could lose a user's earlier mutation when
 * a later snapshot is built from a different thread.
 */
internal class ProviderConfigWriteQueue(
    scope: CoroutineScope,
    private val persist: suspend (ProviderConfig) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {

    private sealed class Request {
        data class Write(val config: ProviderConfig) : Request()
        data class Barrier(val completed: CompletableDeferred<Unit>) : Request()
    }

    private val requests = Channel<Request>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (request in requests) {
                when (request) {
                    is Request.Write -> {
                        try {
                            persist(request.config)
                        } catch (error: Throwable) {
                            // Do not let one failed write kill the worker: a
                            // later successful mutation must be able to
                            // resynchronize the DB and JSON mirror.
                            if (error is CancellationException) throw error
                            runCatching { onFailure(error) }
                        }
                    }

                    is Request.Barrier -> request.completed.complete(Unit)
                }
            }
        }
    }

    /** Enqueue a complete snapshot. Returns false if the queue is closed. */
    fun enqueue(config: ProviderConfig): Boolean =
        requests.trySend(Request.Write(config)).isSuccess

    /** Suspend until all writes enqueued before this call have been handled. */
    suspend fun awaitIdle() {
        val completed = CompletableDeferred<Unit>()
        requests.send(Request.Barrier(completed))
        completed.await()
    }

    /** Close the queue after callers have stopped submitting mutations. */
    fun close() {
        requests.close()
    }
}
