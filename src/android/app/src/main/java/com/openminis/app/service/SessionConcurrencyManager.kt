package com.openminis.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.LinkedList
import kotlin.coroutines.resume

/**
 * Limits concurrent agent loop sessions to [maxConcurrent].
 * Excess sessions are suspended in a FIFO queue until a slot frees up.
 */
object SessionConcurrencyManager {
    const val MAX_CONCURRENT = 5

    private val _runningSessions = MutableStateFlow<Set<String>>(emptySet())
    val runningSessions: StateFlow<Set<String>> = _runningSessions.asStateFlow()

    private val _suspendedSessions = MutableStateFlow<List<String>>(emptyList())
    val suspendedSessions: StateFlow<List<String>> = _suspendedSessions.asStateFlow()

    private data class Waiter(val sessionId: String, val continuation: CancellableContinuation<Unit>)
    private val stateLock = Any()
    private val waitQueue = LinkedList<Waiter>()

    suspend fun acquireSlot(sessionId: String) {
        suspendCancellableCoroutine { cont ->
            var grantImmediately = false
            synchronized(stateLock) {
                if (sessionId !in _runningSessions.value &&
                    _runningSessions.value.size < MAX_CONCURRENT
                ) {
                    _runningSessions.value = _runningSessions.value + sessionId
                    grantImmediately = true
                } else {
                    waitQueue.add(Waiter(sessionId, cont))
                    _suspendedSessions.value = _suspendedSessions.value + sessionId
                }
            }

            if (grantImmediately) cont.resume(Unit)
            cont.invokeOnCancellation {
                synchronized(stateLock) {
                    val removed = waitQueue.removeAll { it.continuation === cont }
                    if (removed) {
                        val suspended = _suspendedSessions.value.toMutableList()
                        suspended.remove(sessionId)
                        _suspendedSessions.value = suspended
                    }
                }
            }
        }
    }

    fun releaseSlot(sessionId: String) {
        synchronized(stateLock) {
            if (sessionId !in _runningSessions.value) return
            _runningSessions.value = _runningSessions.value - sessionId

            while (waitQueue.isNotEmpty()) {
                val candidate = waitQueue.pollFirst() ?: continue
                _suspendedSessions.value =
                    _suspendedSessions.value - candidate.sessionId
                if (candidate.continuation.isCancelled) continue
                _runningSessions.value = _runningSessions.value + candidate.sessionId
                try {
                    candidate.continuation.resume(Unit)
                    return
                } catch (_: IllegalStateException) {
                    _runningSessions.value =
                        _runningSessions.value - candidate.sessionId
                }
            }
        }
    }

    fun isSuspended(sessionId: String): Boolean = synchronized(stateLock) {
        sessionId in _suspendedSessions.value
    }
}
