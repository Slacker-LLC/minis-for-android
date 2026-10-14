package com.openminis.app.tools

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Keeps overlapping local requests for the same chat from running concurrently. */
object SessionTurnLockRegistry {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withLock(sessionId: String, block: suspend () -> T): T =
        locks.getOrPut(sessionId) { Mutex() }.withLock { block() }
}
