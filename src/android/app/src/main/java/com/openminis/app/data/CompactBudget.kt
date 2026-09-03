package com.openminis.app.data

/**
 * Hard limits for one context-compaction run.
 *
 * The recursive split path is intentionally bounded in two independent ways:
 * the call ceiling limits fan-out, while the timeout limits slow calls that do
 * not fail quickly. Keeping the arithmetic here makes it reusable from the
 * ViewModel and from JVM tests without constructing Android dependencies.
 */
internal object CompactBudget {
    /** Maximum number of summary requests, including the initial attempt. */
    const val MAX_LLM_CALLS = 6

    /** Base wall-clock budget for a short transcript. */
    const val TIMEOUT_BASE_MS = 90_000L

    /** Extra budget granted for every complete 10k characters. */
    const val TIMEOUT_PER_10K_CHARS_MS = 30_000L

    /** Absolute ceiling, kept below provider socket read timeouts. */
    const val TIMEOUT_MAX_MS = 300_000L

    fun timeoutMsFor(transcriptChars: Int): Long {
        val nonNegativeChars = transcriptChars.coerceAtLeast(0)
        val growth = (nonNegativeChars / 10_000L) * TIMEOUT_PER_10K_CHARS_MS
        return (TIMEOUT_BASE_MS + growth).coerceAtMost(TIMEOUT_MAX_MS)
    }
}
