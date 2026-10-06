package com.openminis.app.speech

internal enum class UtteranceEnd { DONE, TIMED_OUT }

/**
 * Waits for the utterance now playing to finish. A PAUSED utterance has not finished, however quiet the
 * engine is: the queue must not move on to the next sentence while the user has it paused, and the
 * time spent paused must not count against [budgetMs] (the budget exists to escape an engine whose
 * progress callbacks never arrive).
 */
internal suspend fun awaitUtteranceEnd(
    budgetMs: Long,
    isSpeaking: () -> Boolean,
    isPaused: () -> Boolean,
    now: () -> Long = System::currentTimeMillis,
    pollMs: Long,
    pause: suspend (Long) -> Unit,
): UtteranceEnd {
    var deadline = now() + budgetMs
    var last = now()
    while (isSpeaking() || isPaused()) {
        val t = now()
        if (isPaused()) deadline += t - last
        last = t
        if (t > deadline) return UtteranceEnd.TIMED_OUT
        pause(pollMs)
    }
    return UtteranceEnd.DONE
}
