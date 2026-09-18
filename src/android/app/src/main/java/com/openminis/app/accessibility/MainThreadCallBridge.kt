package com.openminis.app.accessibility

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Outcome of an accessibility call that had to run on the main thread.
 *
 * [COMPLETED_REFUSED] is the system refusing the action, [NOT_DISPATCHED] is a call
 * that was cancelled before it started (so it provably never ran), and
 * [OUTCOME_UNKNOWN] is a call that started but never returned inside the deadline.
 */
enum class GatedCallOutcome {
    COMPLETED_ACCEPTED,
    COMPLETED_REFUSED,
    NOT_DISPATCHED,
    OUTCOME_UNKNOWN,
}

/**
 * Runs a synchronous accessibility call on the main thread behind a
 * [MainThreadCallGate], so a bridge that already timed out cannot have a queued
 * call run late and change the UI after the caller gave up.
 *
 * Ported from Eta `agent/accessibility/MainThreadCallGate.kt` `callOnMainSync`
 * (Mangi-11/Eta @ c15de97).
 */
class MainThreadCallBridge(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {
    sealed class Result<out T> {
        class Completed<T>(val value: T) : Result<T>()

        /** The block never started: the post was refused or the call was cancelled first. */
        object NotDispatched : Result<Nothing>()

        /** The block started but did not return in time; it may still be running. */
        object OutcomeUnknown : Result<Nothing>()
    }

    fun <T> call(block: () -> T): Result<T> {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return try {
                Result.Completed(block())
            } catch (_: Throwable) {
                // A framework call that threw cannot prove the side effect did not happen.
                Result.OutcomeUnknown
            }
        }
        val gate = MainThreadCallGate()
        val latch = CountDownLatch(1)
        var value: T? = null
        var failed = false
        val posted = handler.post {
            if (gate.tryStart()) {
                try {
                    value = block()
                } catch (_: Throwable) {
                    failed = true
                } finally {
                    gate.finish()
                }
            }
            latch.countDown()
        }
        if (!posted) return Result.NotDispatched
        val completed = try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!completed) {
            if (gate.cancelIfPending()) return Result.NotDispatched
            // Already running: the caller must not replay it, and cannot claim it failed.
            return Result.OutcomeUnknown
        }
        if (failed) return Result.OutcomeUnknown
        @Suppress("UNCHECKED_CAST")
        return Result.Completed(value as T)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 3_000L
    }
}
