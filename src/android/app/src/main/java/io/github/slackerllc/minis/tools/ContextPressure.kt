package io.github.slackerllc.minis.tools

import io.github.slackerllc.minis.data.model.LLMMessage

/**
 * Context-pressure advisory for a session, ported from the DeepSeek Harness
 * `dsh-token-meter` context-pressure surface.
 *
 * [compute] prices the given messages with [TokenMeter]'s fixed heuristic and
 * expresses the result as a percentage of the model's context window, so
 * callers (e.g. the chat pre-send check) can warn the user — and suggest
 * /compact — before a request risks exceeding the window. This is an
 * approximate, user-facing reference number, not a billing record and not a
 * gating input: no flow should make decisions off [Pressure] alone.
 *
 * Thresholds:
 *  - `0–79%`  → level `ok`
 *  - `80–94%` → level `warn` ([Pressure.needsCompact] true)
 *  - `>=95%`  → level `critical` (dangerously close to the limit)
 *
 * All functions are pure and side-effect free, so the object is trivially
 * unit-testable.
 */
object ContextPressure {
    /** Percentage of the context window at which compaction is advised. */
    const val COMPACT_THRESHOLD_PERCENT: Int = 80

    /** Percentage at which the pressure is considered critical. */
    const val CRITICAL_THRESHOLD_PERCENT: Int = 95

    /**
     * Snapshot of one pressure read.
     *
     * @property totalTokens heuristic token estimate for the session.
     * @property contextWindow model context window the estimate is measured against.
     * @property percent occupancy of [contextWindow] (`0–100`).
     * @property needsCompact true when [percent] >= [COMPACT_THRESHOLD_PERCENT].
     * @property level one of `ok` / `warn` / `critical` (see [ContextPressure]).
     */
    data class Pressure(
        val totalTokens: Long,
        val contextWindow: Int,
        val percent: Int,
        val needsCompact: Boolean,
        val level: String,
    )

    /**
     * Heuristically price a system prompt (4 characters per token plus
     * system-prompt structural overhead). Delegates to [TokenMeter.estimatePrompt]
     * so the meter and the pressure module always agree on pricing.
     *
     * @param text the system-prompt text to price (not mutated).
     * @return estimated tokens, including structural overhead.
     */
    fun estimatePrompt(text: String): Long = TokenMeter.estimatePrompt(text)

    /**
     * Compute the context pressure of a session against a model's context
     * window using [TokenMeter]'s fixed heuristic.
     *
     * @param modelContextWindow the model's context window in tokens (must be
     *   positive; a non-positive value yields `percent = 0` rather than a
     *   division-by-zero).
     * @param messages the session messages to price (not mutated).
     * @return a [Pressure] snapshot for the given window and messages.
     */
    fun compute(modelContextWindow: Int, messages: List<LLMMessage>): Pressure {
        val totalTokens = TokenMeter.estimateSessionTokens(messages)
        val percent = if (modelContextWindow > 0) {
            ((totalTokens * 100) / modelContextWindow.toLong()).toInt()
        } else {
            0
        }
        val needsCompact = percent >= COMPACT_THRESHOLD_PERCENT
        val level = when {
            percent >= CRITICAL_THRESHOLD_PERCENT -> "critical"
            percent >= COMPACT_THRESHOLD_PERCENT -> "warn"
            else -> "ok"
        }
        return Pressure(
            totalTokens = totalTokens,
            contextWindow = modelContextWindow,
            percent = percent,
            needsCompact = needsCompact,
            level = level,
        )
    }
}
