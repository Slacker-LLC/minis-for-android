package com.openminis.app.agent

/**
 * Decides whether a turn that ended cleanly with no tool call should be nudged
 * to carry on, because its text only announced the next action ("现在实际执行：").
 *
 * Weaker models (seen with mimo-v2.6-flash) often end a turn right after such a
 * sentence: the stream is complete and `finishReason` is `stop`, so the loop
 * treats it as the final answer and the task silently stops. Codex, Claude Code
 * and dsh treat this as a normal end of turn too; only a user-configured Stop
 * hook or goal state continues there. Here the loop adds a bounded, narrow guard
 * instead: every condition must hold, and a miss only means the turn ends as it
 * always did.
 *
 * Pure so the rule can be tested without a running loop.
 */
object UnfulfilledIntentNudge {

    /** Consecutive nudges allowed before the turn is allowed to end. */
    const val MAX_CONSECUTIVE = 2

    /** Longer text is a real answer, not an announcement. */
    const val MAX_TEXT_CHARS = 150

    const val REMINDER =
        "<system-reminder>Your previous reply only announced an action and made no tool call, " +
            "so nothing was done. If work remains, call the tool(s) now without further " +
            "announcement. If the task is already finished, give the final answer instead. " +
            "Do not reply with another \"now I will…\" sentence.</system-reminder>"

    /**
     * @param text the visible text of the turn that just ended.
     * @param finishReason the stream's terminal reason; only a clean `stop` / `end_turn` counts.
     * @param priorIsToolResult the message before this reply is a tool result, i.e. the model
     *   owed a follow-up. Without it a short opening sentence is not treated as a stall.
     * @param hasTools tools were offered for this turn (false in chat-only turns).
     * @param turn 0-based index of the turn that just ended.
     * @param turnCap loop bound; the last permitted turn is never nudged, because another
     *   iteration would take the "exceeded the turn limit" error path.
     * @param nudgesUsed consecutive nudges already spent since the last tool round.
     * @param promptQueued the user has queued another prompt, which should run instead.
     */
    fun shouldNudge(
        text: String,
        finishReason: String?,
        priorIsToolResult: Boolean,
        hasTools: Boolean,
        turn: Int,
        turnCap: Int,
        nudgesUsed: Int,
        promptQueued: Boolean = false,
    ): Boolean =
        (finishReason == "stop" || finishReason == "end_turn") &&
            priorIsToolResult &&
            hasTools &&
            !promptQueued &&
            nudgesUsed < MAX_CONSECUTIVE &&
            turn + 1 < turnCap &&
            looksUnfulfilled(text)

    internal fun looksUnfulfilled(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > MAX_TEXT_CHARS) return false
        // A question is addressed to the user; the turn is rightly waiting for an answer.
        if (t.endsWith("?") || t.endsWith("？")) return false
        // Waiting on the user (asking for input, a choice, or permission) is a legitimate stop.
        if (USER_ASK.containsMatchIn(t)) return false
        if (t.endsWith(":") || t.endsWith("：")) return true
        if (COMPLETION.containsMatchIn(t)) return false
        return INTENT.containsMatchIn(t)
    }

    private val INTENT = Regex(
        "我先|我来|我再|我这就|我马上|我现在|现在(?:就|来|开始|实际|用|重新|再)|接下来|马上|立刻|重试|再试|再(?:用|去|跑|执行)|重新(?:生成|执行|运行|调用)|开始(?:生成|执行|运行|安装)" +
            "|\\b(?:let me|i'll|i will|i'm going to|i am going to|now i|next,? i)\\b",
        RegexOption.IGNORE_CASE,
    )

    private val USER_ASK = Regex(
        "你(?:直接|可以|先|再)?(?:告诉|提供|确认|选择?|回复|决定|授权)|请你|需要你|等你|让我知道|要不要" +
            "|\\b(?:please|let me know|you can|you could|tell me|would you|do you want)\\b",
        RegexOption.IGNORE_CASE,
    )

    private val COMPLETION = Regex(
        "已完成|完成了|搞定|如下|以上|总结|无需|不需要|没有问题" +
            "|\\b(?:done|completed|finished|here is|here's|summary)\\b",
        RegexOption.IGNORE_CASE,
    )
}
