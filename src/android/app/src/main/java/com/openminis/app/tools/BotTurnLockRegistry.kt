package com.openminis.app.tools

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

object BotTurnLockRegistry {
    private val locks = ConcurrentHashMap<String, Mutex>()
    /** A dispatcher reservation is scoped to one target session. */
    private val externalReservations = ConcurrentHashMap<String, String>()

    suspend fun <T> withLock(
        botId: String?,
        sessionId: String? = null,
        block: suspend () -> T,
    ): T {
        if (botId.isNullOrBlank()) return block()
        if (sessionId != null && externalReservations[botId] == sessionId) return block()
        return locks.getOrPut(botId) { Mutex() }.withLock { block() }
    }

    suspend fun <T> withLock(botId: String?, block: suspend () -> T): T =
        withLock(botId, sessionId = null, block = block)

    fun tryAcquireExternal(botId: String, sessionId: String): Boolean {
        if (botId.isBlank() || sessionId.isBlank()) return false
        val mutex = locks.getOrPut(botId) { Mutex() }
        if (!mutex.tryLock()) return false
        externalReservations[botId] = sessionId
        return true
    }

    fun releaseExternal(botId: String, sessionId: String) {
        if (externalReservations.remove(botId, sessionId)) locks[botId]?.unlock()
    }

    fun tryLock(botId: String): Boolean = locks.getOrPut(botId) { Mutex() }.tryLock()

    fun isLocked(botId: String): Boolean = locks[botId]?.isLocked == true

    suspend fun awaitAvailable(botId: String) {
        locks.getOrPut(botId) { Mutex() }.withLock { }
    }

    fun unlock(botId: String) {
        locks[botId]?.unlock()
    }
}
