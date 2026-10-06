package com.openminis.app.speech

/** How a wait for the speech engine ended. */
internal enum class TtsInit { READY, FAILED, NOT_READY }

/**
 * Waits for [isInitialized] up to [budgetMs]. An engine that reported an init error ([initFailed]) ends
 * the wait at once as [TtsInit.FAILED]; running out of budget with neither is [TtsInit.NOT_READY]: a
 * slow engine that is still binding, which is not the same thing as "no engine installed".
 */
internal fun awaitTtsInit(
    budgetMs: Long,
    isInitialized: () -> Boolean,
    initFailed: () -> Boolean,
    now: () -> Long = System::currentTimeMillis,
    sleep: (Long) -> Unit = { Thread.sleep(it) },
): TtsInit {
    val deadline = now() + budgetMs
    while (true) {
        if (isInitialized()) return TtsInit.READY
        if (initFailed()) return TtsInit.FAILED
        if (now() >= deadline) return TtsInit.NOT_READY
        try {
            sleep(50)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return if (isInitialized()) TtsInit.READY else TtsInit.NOT_READY
        }
    }
}
