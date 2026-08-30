package com.openminis.app.runtime.minisd

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Content-free transport counters for Issue #28.
 *
 * This intentionally records only method/transport/size/timing/outcome. It never
 * stores request JSON, argv, environment values, tokens, stdout or stderr.
 */
object MinisdTransportStats {
    enum class Transport { LOCAL_SOCKET, SU_CALL, NONE }

    data class Key(
        val method: String,
        val transport: Transport,
        val outcome: String,
        val fallback: Boolean,
    )

    data class Snapshot(
        val method: String,
        val transport: Transport,
        val outcome: String,
        val fallback: Boolean,
        val calls: Long,
        val requestBytes: Long,
        val responseBytes: Long,
        val totalDurationMs: Long,
        val maxDurationMs: Long,
    )

    private class Counters {
        val calls = AtomicLong()
        val requestBytes = AtomicLong()
        val responseBytes = AtomicLong()
        val totalDurationMs = AtomicLong()
        val maxDurationMs = AtomicLong()
    }

    private val counters = ConcurrentHashMap<Key, Counters>()

    fun record(
        method: String,
        transport: Transport,
        requestBytes: Int,
        responseBytes: Int,
        durationMs: Long,
        outcome: String,
        fallback: Boolean,
    ) {
        val key = Key(
            method = method.take(80),
            transport = transport,
            outcome = outcome.take(80),
            fallback = fallback,
        )
        val value = counters.computeIfAbsent(key) { Counters() }
        value.calls.incrementAndGet()
        value.requestBytes.addAndGet(requestBytes.coerceAtLeast(0).toLong())
        value.responseBytes.addAndGet(responseBytes.coerceAtLeast(0).toLong())
        value.totalDurationMs.addAndGet(durationMs.coerceAtLeast(0))
        updateMax(value.maxDurationMs, durationMs.coerceAtLeast(0))
    }

    fun snapshot(): List<Snapshot> = counters.entries
        .map { (key, value) ->
            Snapshot(
                method = key.method,
                transport = key.transport,
                outcome = key.outcome,
                fallback = key.fallback,
                calls = value.calls.get(),
                requestBytes = value.requestBytes.get(),
                responseBytes = value.responseBytes.get(),
                totalDurationMs = value.totalDurationMs.get(),
                maxDurationMs = value.maxDurationMs.get(),
            )
        }
        .sortedWith(compareByDescending<Snapshot> { it.calls }.thenBy { it.method })

    internal fun resetForTests() {
        counters.clear()
    }

    private fun updateMax(target: AtomicLong, candidate: Long) {
        var current = target.get()
        while (candidate > current && !target.compareAndSet(current, candidate)) {
            current = target.get()
        }
    }
}
