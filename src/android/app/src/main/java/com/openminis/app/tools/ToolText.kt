package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolParam

/**
 * Model-facing wording shared by more than one tool definition.
 *
 * The resident tools (see [ToolDeferral.RESIDENT_NAMES]) are written in the style of ASD-STE100
 * Simplified Technical English: one idea per sentence, instruction sentences of at most 20 words and
 * other sentences of at most 25, active voice, simple present tense, articles kept, and one word for one
 * concept (tool call, task, goal, job, session, user). Tool names, parameter names and enum values stay
 * exactly as they are. ToolDefinitionTextTest checks the parts a machine can check.
 */
object ToolText {
    /** The `tool_title` parameter. One wording for every tool, so each definition pays for it once. */
    val TOOL_TITLE = AgentToolParam(
        "string",
        "Write a title of 5 to 10 words for this tool call, in the language of the user.",
    )

    /**
     * What each goal action does. The five model-facing aliases (`get_goal` ...) each carry only their own
     * line; the combined `agent.goal` handler lists all five, so a rule is written in one place only.
     */
    val GOAL_ACTIONS: Map<String, String> = linkedMapOf(
        "get_goal" to "Get the current goal of this session. " +
            "The result has the task identity (task_id, revision, and stop_generation), the status, the progress, " +
            "and the unknown outcomes. Call it before you update the goal.",
        "create_goal" to "Create the goal of this session. " +
            "The goal text is required and must not be blank. Use acceptance_criteria to state how to verify completion. " +
            "The call fails if the session already has a goal.",
        "update_goal" to "Change the goal text, or settle the status of the goal. Do not do both in one call. " +
            "An empty goal text clears the goal. " +
            "To settle the status, send the task identity from get_goal and send progress. " +
            "To set the status to completed, also send evidence. A reply alone never completes the goal.",
        "verify_outcome" to "Verify one unknown tool outcome that get_goal lists. " +
            "Send outcome with the exact execution_id and run_id, and send the task identity. " +
            "Do not send status or evidence.\n\n" +
            "With verification=automatic, the check uses the fingerprint of a file write that does not append. " +
            "With verification=user, the actual user inspects other effects. If the result is uncertain, the outcome stays pending. " +
            "The call keeps the task state and never runs the action again.",
        "review_restored_task" to "Ask the actual user to inspect a task that the app restored. " +
            "Send the task identity from get_goal. The task stays paused.",
    )

    /** The `agent.goal` description: one line per action. */
    fun goalActionsSummary(): String = buildString {
        append("Manage the durable goal of the session. Set action to one of the actions below.")
        for ((action, text) in GOAL_ACTIONS) append("\n").append(action).append(": ").append(text)
    }
}
