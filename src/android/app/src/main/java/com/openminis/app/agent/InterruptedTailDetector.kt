package com.openminis.app.agent

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * Which "the agent loop stopped early" shape a session's tail matches.
 *
 * Extracted from `ChatViewModel.loadSession` so the rule can be tested
 * directly: it decides whether the user gets a Resume affordance at all, and
 * a wrong answer is invisible in code review — either the banner never
 * appears or it appears over a turn that is simply still waiting.
 */
enum class InterruptedTailShape {
    /** Tools completed, but the follow-up model call never fired. */
    TOOL_RESULT_TAIL,

    /** The model asked for tools that never executed. */
    ASSISTANT_TOOL_USE,

    /** The synthetic "user stopped the previous response" reminder was
     * committed, but `resume()` never re-entered the loop. */
    CONTINUE_REMINDER,

    /** A user turn with no reply after it. */
    UNANSWERED_USER_TURN,

    /** Not interrupted. */
    NONE,
}

object InterruptedTailDetector {

    /** Marker text of the synthetic continue reminder (see `resume()`). */
    const val CONTINUE_REMINDER_MARKER = "The user stopped the previous response"

    /**
     * Classify [lastEntry] — the final entry of `agentHistory`.
     *
     * Callers MUST additionally gate on "nothing is currently streaming"
     * (`!isStreaming && !SessionActivityTracker.isActive(sid)`). This function
     * deliberately knows nothing about liveness: it answers "what shape is
     * this tail", not "is it safe to offer Resume".
     */
    fun classify(lastEntry: LLMMessage?): InterruptedTailShape {
        if (lastEntry == null) return InterruptedTailShape.NONE
        return when (lastEntry.role) {
            LLMMessage.Role.USER -> {
                val parts = lastEntry.contentParts
                val allToolResults = parts.isNotEmpty() &&
                    parts.all { it is AgentContentPart.ToolResult }
                val isContinueReminder = parts.size == 1 &&
                    (parts.first() as? AgentContentPart.Text)?.text
                        ?.contains(CONTINUE_REMINDER_MARKER) == true
                when {
                    // Order matters: the first two describe a turn that was
                    // mid-flight; the third describes one that never started.
                    allToolResults -> InterruptedTailShape.TOOL_RESULT_TAIL
                    isContinueReminder -> InterruptedTailShape.CONTINUE_REMINDER
                    // An empty user turn is not recoverable: there is nothing
                    // to answer, and re-sending it would post empty content.
                    parts.isEmpty() -> InterruptedTailShape.NONE
                    else -> InterruptedTailShape.UNANSWERED_USER_TURN
                }
            }
            LLMMessage.Role.ASSISTANT ->
                if (lastEntry.contentParts.any { it is AgentContentPart.ToolUse }) {
                    InterruptedTailShape.ASSISTANT_TOOL_USE
                } else {
                    InterruptedTailShape.NONE
                }
            else -> InterruptedTailShape.NONE
        }
    }

    /** True when the tail is any recoverable shape. */
    fun isInterrupted(lastEntry: LLMMessage?): Boolean =
        classify(lastEntry) != InterruptedTailShape.NONE
}
