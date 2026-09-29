package com.openminis.app.tools.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Per-display mutual exclusion for physical display 0 and the single active virtual display. */
class DeviceScreenLease(
    private val clock: () -> Long = System::currentTimeMillis,
    private val waitTimeoutMs: Long = 30_000L,
    private val idleTimeoutMs: Long = 120_000L,
    private val pollIntervalMs: Long = 50L,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val watchdogEnabled: Boolean = true,
) {
    data class Owner(
        val displayId: Int,
        val sessionId: String,
        val displayName: String,
        val unattended: Boolean,
        val lastActivityMs: Long,
    )

    sealed interface Acquisition {
        data class Granted(val owner: Owner) : Acquisition
        data class Busy(val holderName: String) : Acquisition
        data object Preempted : Acquisition
        data object InvalidSession : Acquisition
    }

    private data class PreemptedKey(val displayId: Int, val sessionId: String)
    private val lock = Any()
    private val owners = HashMap<Int, Owner>()
    private val preempted = HashSet<PreemptedKey>()
    private val watchdogJobs = HashMap<Int, Job>()
    private val watchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun acquire(
        displayId: Int,
        sessionId: String,
        displayName: String,
        unattended: Boolean,
    ): Acquisition {
        if (displayId < 0 || sessionId.isBlank()) return Acquisition.InvalidSession
        val deadline = clock() + waitTimeoutMs
        while (true) {
            val now = clock()
            val outcome = synchronized(lock) {
                val key = PreemptedKey(displayId, sessionId)
                if (preempted.remove(key)) return@synchronized Acquisition.Preempted
                expireIdleLocked(now)
                val current = owners[displayId]
                when {
                    current == null || current.sessionId == sessionId -> {
                        val owner = Owner(displayId, sessionId, sanitizeName(displayName), unattended, now)
                        owners[displayId] = owner
                        owner
                    }
                    displayId == 0 && !unattended && current.unattended -> {
                        preempted += PreemptedKey(displayId, current.sessionId)
                        val owner = Owner(displayId, sessionId, sanitizeName(displayName), unattended, now)
                        owners[displayId] = owner
                        owner
                    }
                    else -> null
                }
            }
            when (outcome) {
                is Acquisition.Preempted -> return outcome
                is Owner -> {
                    scheduleWatchdog(displayId, sessionId)
                    return Acquisition.Granted(outcome)
                }
                else -> Unit
            }
            val current = synchronized(lock) { owners[displayId] }
            if (current == null) continue
            val remaining = deadline - clock()
            if (remaining <= 0L) return Acquisition.Busy(current.displayName)
            sleep(minOf(pollIntervalMs, remaining).coerceAtLeast(1L))
        }
    }

    fun touch(displayId: Int, sessionId: String): Boolean {
        val changed = synchronized(lock) {
            val current = owners[displayId]
            if (current?.sessionId != sessionId) return@synchronized false
            owners[displayId] = current.copy(lastActivityMs = clock())
            true
        }
        if (changed) scheduleWatchdog(displayId, sessionId)
        return changed
    }

    fun owner(displayId: Int): Owner? = synchronized(lock) {
        expireIdleLocked(clock())
        owners[displayId]
    }

    fun release(displayId: Int, sessionId: String): Boolean {
        val removed = synchronized(lock) {
            val current = owners[displayId]
            if (current?.sessionId != sessionId) return@synchronized false
            owners.remove(displayId)
            watchdogJobs.remove(displayId)?.cancel()
            true
        }
        synchronized(lock) {
            preempted.removeAll { it.displayId == displayId && it.sessionId == sessionId }
        }
        return removed
    }

    /** Called at every turn's finally, including cancellation, timeout, and failure. */
    fun releaseSession(sessionId: String) {
        synchronized(lock) {
            owners.entries.removeAll { (_, owner) ->
                if (owner.sessionId == sessionId) {
                    watchdogJobs.remove(owner.displayId)?.cancel()
                    true
                } else false
            }
            preempted.removeAll { it.sessionId == sessionId }
        }
    }

    /** Exposed for deterministic tests; production also schedules an idle watchdog. */
    fun expireIdle(): List<Owner> = synchronized(lock) { expireIdleLocked(clock()) }

    fun close() {
        synchronized(lock) {
            watchdogJobs.values.forEach(Job::cancel)
            watchdogJobs.clear()
            owners.clear()
            preempted.clear()
        }
        watchdogScope.cancel()
    }

    private fun expireIdleLocked(now: Long): List<Owner> {
        val expired = owners.values.filter { now - it.lastActivityMs >= idleTimeoutMs }
        expired.forEach { owner ->
            owners.remove(owner.displayId)
            watchdogJobs.remove(owner.displayId)?.cancel()
        }
        return expired
    }

    private fun scheduleWatchdog(displayId: Int, sessionId: String) {
        if (!watchdogEnabled) return
        synchronized(lock) {
            watchdogJobs.remove(displayId)?.cancel()
            watchdogJobs[displayId] = watchdogScope.launch {
                delay(idleTimeoutMs)
                synchronized(lock) {
                    val current = owners[displayId]
                    if (current?.sessionId == sessionId && clock() - current.lastActivityMs >= idleTimeoutMs) {
                        owners.remove(displayId)
                    }
                    watchdogJobs.remove(displayId)
                }
            }
        }
    }

    private fun sanitizeName(raw: String): String = raw
        .map { if (it.isISOControl()) ' ' else it }
        .joinToString("")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(80)
        .ifBlank { "another session" }

    companion object {
        val shared: DeviceScreenLease by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { DeviceScreenLease() }
    }
}
