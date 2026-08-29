package io.github.slackerllc.minis.tools

import io.github.slackerllc.minis.data.model.LLMMessage

/**
 * Fixed-density heuristic token pricing for LLM messages, ported from the
 * DeepSeek Harness `dsh-token-meter` contract ([@deepseek-ai/dsh-token-meter]
 * `estimate.ts`).
 *
 * The estimator has no configuration: it deliberately prices every token as
 * four UTF-16 characters, plus a per-message structural overhead for role /
 * block framing. Exact provider tokenization is intentionally out of scope —
 * this is a cheap, deterministic estimate used for pressure advisories, not
 * for billing or request gating.
 *
 * Overhead kinds mirror the original contract:
 *  - [MESSAGE_OVERHEAD] (`+8`) — role framing + one content block, applied to
 *    every ordinary user / assistant turn (the only roles [LLMMessage] models);
 *  - [SYSTEM_OVERHEAD] (`+16`) — system-prompt framing, exposed via
 *    [estimatePrompt] (equivalent to the contract's `estimateSystemTokens`);
 *  - [TOOL_RESULT_OVERHEAD] (`+12`) — tool-result framing, exposed via
 *    [estimateToolResult] (equivalent to the contract's `tool-result` block).
 *
 * All functions are pure and side-effect free, so the object is trivially
 * unit-testable.
 */
object TokenMeter {
    /** Fixed text-density estimate: one token per four UTF-16 characters. */
    const val CHARS_PER_TOKEN: Long = 4L

    /** Role + block framing overhead for a regular user / assistant message. */
    const val MESSAGE_OVERHEAD: Long = 8L

    /** Structural overhead for a system prompt (role + block + envelope). */
    const val SYSTEM_OVERHEAD: Long = 16L

    /** Structural overhead for a tool-result block. */
    const val TOOL_RESULT_OVERHEAD: Long = 12L

    /**
     * Heuristically price one model-visible message: `ceil(content chars / 4)`
     * plus the per-message role/block overhead.
     *
     * [LLMMessage.role] only models user / assistant turns, so every message
     * carries [MESSAGE_OVERHEAD]. System prompts and tool results are priced
     * through [estimatePrompt] / [estimateToolResult] respectively.
     *
     * @param message the message to price (not mutated).
     * @return estimated tokens, including structural overhead.
     */
    fun estimateMessage(message: LLMMessage): Long =
        ceilDiv(message.content.length.toLong(), CHARS_PER_TOKEN) + MESSAGE_OVERHEAD

    /**
     * Heuristically price a system prompt: `ceil(text chars / 4)` plus the
     * system-prompt structural overhead ([SYSTEM_OVERHEAD]).
     *
     * @param text the system-prompt text to price (not mutated).
     * @return estimated tokens, including structural overhead.
     */
    fun estimatePrompt(text: String): Long =
        ceilDiv(text.length.toLong(), CHARS_PER_TOKEN) + SYSTEM_OVERHEAD

    /**
     * Heuristically price a tool result: `ceil(text chars / 4)` plus the
     * tool-result structural overhead ([TOOL_RESULT_OVERHEAD]).
     *
     * @param text the tool-result text to price (not mutated).
     * @return estimated tokens, including structural overhead.
     */
    fun estimateToolResult(text: String): Long =
        ceilDiv(text.length.toLong(), CHARS_PER_TOKEN) + TOOL_RESULT_OVERHEAD

    /**
     * Sum the heuristic token estimate across a session's messages.
     *
     * @param messages the full message list to price (not mutated).
     * @return cumulative estimated tokens.
     */
    fun estimateSessionTokens(messages: List<LLMMessage>): Long =
        messages.sumOf { estimateMessage(it) }

    /** Ceiling integer division for non-negative [n] and positive [d]. */
    private fun ceilDiv(n: Long, d: Long): Long = (n + d - 1) / d
}
