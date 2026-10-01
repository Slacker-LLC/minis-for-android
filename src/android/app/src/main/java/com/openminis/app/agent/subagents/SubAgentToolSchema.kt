package com.openminis.app.agent.subagents

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.model.SubAgentDefinition

/**
 * The `subagent` tool schema the model sees while sub agents are allowed. One tool: delegate, and inspect, steer or stop what
 * was delegated. The wording follows OpenMinis 1.14's contract (adapted: model pins are model
 * entries, no `resume`/`progress_report`); `agent`'s enum is rebuilt from the live roster every
 * turn, so a rename takes effect on the next request and the model cannot emit a name that would
 * fail to resolve.
 */
object SubAgentToolSchema {
    /** The registry's canonical handler name (it also answers to the model-facing [SubAgentDefinition.TOOL_NAME]). */
    const val HANDLER_NAME = "agent.subagent"

    fun definition(
        rosterNames: List<String> = listOf(SubAgentDefinition.BUILT_IN_NAME),
        name: String = SubAgentDefinition.TOOL_NAME,
    ): AgentToolDefinition = AgentToolDefinition(
        name = name,
        description = "Delegate a self-contained task to a sub agent — its own isolated context and tool loop, in a separate child session running concurrently with you — and inspect, steer or stop the ones you started. `action` defaults to `delegate`.\n\n" +
            "DELEGATE work needing many rounds of exploration (reading lots of files or pages, trial-and-error), producing bulk output you only need a conclusion from, or splitting into independent sub-problems you can run in parallel (several calls in one turn). DO NOT delegate what you can finish in one or two tool calls, what needs the user's confirmation mid-way, or work depending on nuances of this conversation you cannot restate. A sub agent cannot see this conversation and has no memory: write `task` as a complete brief for a capable colleague who just walked in — goal, constraints, where things are, what exactly to return. It costs a full model run, so nothing trivial. Only ${SubAgentJobRegistry.MAX_CONCURRENT} run at once, but delegate everything you need anyway: extras return status=queued and start as slots free, so never re-delegate a queued task or wait for a slot.\n\n" +
            "wait=false (default) returns at once with status=running and a job_id; the result arrives later as a NEW MESSAGE prefixed [Background task finished …] (also on cancel, timeout or failure). End your turn when you have nothing else to do — never poll in a loop, never promise to report back. You do NOT need action=status to receive results.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user on the block in the tool bar and in the transcript (e.g. 'Survey repo test layout', 'Check on the research agent'). Use the same language as the user."),
            "action" to AgentToolParam("string", "\"delegate\" (default): start a sub agent on `task`. \"status\": report this conversation's sub agents — state (queued/running/completed/cancelled/failed/timeout), elapsed, model, finished results; `job_id` for one, omit for all. \"steer\": course-correct a RUNNING one without stopping it (see `message`). \"cancel\": stop the one named by `job_id`; its partial result is still posted back.", enumValues = SubAgentAction.entries.map { it.wire }),
            "task" to AgentToolParam("string", "action=delegate only, required. The complete, self-contained brief: goal, success criteria, relevant paths/URLs, constraints, and exactly what to return. The sub agent sees nothing else."),
            "agent" to AgentToolParam("string", "action=delegate only. Which sub agent runs this task. Pick the one whose description matches the work; omit it to use the general one.", enumValues = rosterNames),
            "model_choice" to AgentToolParam("string", "action=delegate only, and only when the chosen sub agent is set to Auto — one the user pinned to a model ignores it. DEFAULT TO \"same_as_me\". The user picked the model this conversation runs on, and that choice covers the work you delegate from it: a sub agent on a different model can cost far more, or be far weaker, than what they chose, and they never see it happen. Only depart from it when the task itself gives you a specific reason, judged by what the task demands and not by how long it will take. \"same_as_me\" (default): this conversation's model — anything continuing the work at hand, and every case where you are unsure. \"default_model\": the user's Main model — only when this task clearly needs more capability than the current model, e.g. multi-step reasoning, design judgment, ambiguous requirements where a wrong answer is expensive. \"sub_model\": the user's Light model — only when the task is clearly mechanical and well-bounded, verifiable at a glance (collecting files against a list, format conversion, fixed commands, lookups).", enumValues = SubAgentModelChoice.entries.map { it.wire }),
            "context" to AgentToolParam("string", "action=delegate only. Optional raw material to hand over verbatim (file excerpts, error output, a list of paths). Appended to the task."),
            "max_minutes" to AgentToolParam("integer", "action=delegate only. Wall-clock budget in minutes (default ${SubAgentTask.DEFAULT_MINUTES}, maximum ${SubAgentTask.MAX_MINUTES}). The sub agent is stopped when it runs out and whatever it produced so far is returned with status=timeout."),
            "wait" to AgentToolParam("boolean", "action=delegate only. false (default): return at once with status=running; the result is posted here as a new message when done. true: block until it finishes and return the result here — only when the next step cannot proceed without it. Stopping your turn while it waits stops the sub agent."),
            "job_id" to AgentToolParam("string", "action=status/steer/cancel. The job_id this tool returned when it started the sub agent (a prefix is accepted). Required for steer and cancel; omit on status to list every sub agent of this conversation."),
            "message" to AgentToolParam("string", "action=steer only, required. The correction, phrased as an instruction to the running sub agent (e.g. 'focus on pricing, skip the migration notes'). Use when new information changes what it should do — it keeps the work already done, unlike cancelling and re-delegating. Read at its next turn, so a running tool call is not interrupted; if the run finishes first the correction is missed."),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "action", "task", "agent", "model_choice", "context", "max_minutes", "wait", "job_id", "message"),
    )
}
