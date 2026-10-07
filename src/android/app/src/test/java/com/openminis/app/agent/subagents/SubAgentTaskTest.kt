package com.openminis.app.agent.subagents

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.SubAgentDefinition
import com.openminis.app.data.model.SubAgentRoster
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentTaskTest {
    private fun ok(raw: String) = (SubAgentTask.parseArgs(raw) as SubAgentArgsResult.Ok).args
    private fun invalid(raw: String) = (SubAgentTask.parseArgs(raw) as SubAgentArgsResult.Invalid).error

    private fun job(state: SubAgentJobState, result: String? = "r") = SubAgentJob(
        id = "abcdef12-0000", parentSessionId = "p", agentName = "General Sub Agent", title = "t",
        wait = false, maxMinutes = 10, state = state, createdAtMs = 0, startedAtMs = 1_000, finishedAtMs = 6_000, resultText = result,
    )

    @Test
    fun `a delegation defaults to the background, the built-in agent and ten minutes`() {
        val a = ok("""{"tool_title":"t","task":"  do it  "}""")
        assertEquals(SubAgentAction.DELEGATE, a.action)
        assertEquals("do it", a.task)
        assertNull(a.agent)
        assertFalse(a.wait)
        assertEquals(SubAgentTask.DEFAULT_MINUTES, a.maxMinutes)
        assertEquals(SubAgentModelChoice.SAME_AS_ME, a.modelChoice)
    }

    @Test
    fun `the time budget is clamped and an unknown model choice means the conversation's own model`() {
        assertEquals(SubAgentTask.MAX_MINUTES, ok("""{"task":"x","max_minutes":9999}""").maxMinutes)
        assertEquals(1, ok("""{"task":"x","max_minutes":-5}""").maxMinutes)
        assertEquals(SubAgentModelChoice.SAME_AS_ME, ok("""{"task":"x","model_choice":"strongest"}""").modelChoice)
        assertEquals(SubAgentModelChoice.SUB_MODEL, ok("""{"task":"x","model_choice":"SUB_MODEL"}""").modelChoice)
    }

    @Test
    fun `the callback message says it is system-written and carries status and result`() {
        val text = SubAgentTask.callbackText(job(SubAgentJobState.DONE, "the answer"), now = 9_000)
        assertTrue(text.startsWith("[Background task finished"))
        assertTrue(text.contains("job_id=abcdef12"))
        assertTrue(text.contains("status=completed"))
        assertTrue(text.contains("· 5s"))
        assertTrue(text.endsWith("the answer"))
    }

    @Test
    fun `an empty result is labelled instead of left blank`() {
        assertTrue(SubAgentTask.callbackText(job(SubAgentJobState.DONE, ""), 9_000).endsWith("(no result text)"))
    }

    @Test
    fun `the final payload is JSON with the wire status`() {
        val p = SubAgentTask.finalPayload(job(SubAgentJobState.TIMEOUT, "partial"), 9_000)
        assertEquals("timeout", p.getString("status"))
        assertEquals("partial", p.getString("result"))
        assertEquals(5L, p.getLong("elapsed_s"))
    }

    @Test
    fun `the brief carries the user's instructions, the task and the context`() {
        val def = SubAgentDefinition(name = "r", description = "d", instructions = "Cite sources.")
        val brief = SubAgentTask.childBrief(def, "find X", "log excerpt")
        assertTrue(brief.contains("Cite sources."))
        assertTrue(brief.contains("Task:\nfind X"))
        assertTrue(brief.contains("log excerpt"))
        assertFalse("no instructions block without instructions", SubAgentTask.childBrief(def.copy(instructions = ""), "t", "").contains("Sub agent instructions"))
    }

    @Test
    fun `the roster section is built from the roster and notes each model`() {
        val roster = SubAgentRoster.normalize(listOf(SubAgentDefinition(id = "a", name = "researcher", description = "digs deep")))
        val section = SubAgentTask.rosterSection(roster) { if (it.name == "researcher") "fixed — GPT-6" else "Auto — you choose with model_choice" }
        assertTrue(section.startsWith("Available sub agents (pass the name as subagent.agent):"))
        assertTrue(section.contains("- General Sub Agent — "))
        assertTrue(section.contains("- researcher — digs deep Model: fixed — GPT-6."))
        assertEquals("", SubAgentTask.rosterSection(emptyList()) { "" })
    }

    @Test
    fun `the tool schema's agent enum is the live roster and resume is offered`() {
        val def: AgentToolDefinition = SubAgentToolSchema.definition(listOf("General Sub Agent", "researcher"))
        assertEquals("subagent", def.name)
        assertEquals(listOf("General Sub Agent", "researcher"), def.parameters.getValue("agent").enumValues)
        assertEquals(listOf("delegate", "status", "steer", "cancel", "resume"), def.parameters.getValue("action").enumValues)
        assertTrue(def.parameters.keys.none { it == "child_session_id" })
        assertEquals(listOf("none", "frequent", "moderate"), def.parameters.getValue("progress_report").enumValues)
    }

    // ── Negative cases ──────────────────────────────────────────────────────

    @Test
    fun `malformed or incomplete calls are rejected with a specific code`() {
        assertEquals("invalid_arguments", invalid("{oops"))
        assertEquals("unknown_action", invalid("""{"action":"explode"}"""))
        assertEquals("task_required", invalid("""{"tool_title":"t","task":"   "}"""))
        assertEquals("job_id_required", invalid("""{"action":"cancel"}"""))
        assertEquals("message_required", invalid("""{"action":"steer","job_id":"x"}"""))
    }

    @Test
    fun `an oversized result is cut and says so`() {
        val big = "x".repeat(SubAgentTask.MAX_RESULT_CHARS + 5_000)
        val capped = SubAgentTask.capResult(big)
        assertTrue(capped.length < big.length)
        assertTrue(capped.contains("[truncated"))
        assertEquals("short", SubAgentTask.capResult("short"))
    }

    @Test
    fun `the progress level defaults to none and an unknown level means none`() {
        assertEquals("none", ok("""{"task":"x"}""").progressReport)
        assertEquals("none", ok("""{"task":"x","progress_report":"chatty"}""").progressReport)
        assertEquals("moderate", ok("""{"task":"x","progress_report":"MODERATE"}""").progressReport)
    }

    @Test
    fun `a progress report names the status, the current tool and the tail of the latest message`() {
        val text = SubAgentTask.progressText(job(SubAgentJobState.RUNNING), ChildProgress("shell_execute", "x".repeat(2000)), now = 9_000)
        assertTrue(text.startsWith("[Background task progress"))
        assertTrue(text.contains("Current tool: shell_execute"))
        assertTrue(text.length < 1_000)
    }

    @Test
    fun `the older tool's prompt argument is accepted as the task`() {
        val a = ok("""{"tool_title":"t","prompt":"do the thing"}""")
        assertEquals("do the thing", a.task)
        assertEquals("task wins when both are given", "mine", ok("""{"task":"mine","prompt":"other"}""").task)
    }

    @Test
    fun `an error envelope is not a success`() {
        val j = JSONObject(SubAgentTask.error("unknown_agent", "No such agent."))
        assertEquals("error", j.getString("status"))
        assertEquals("unknown_agent", j.getString("error"))
    }

    @Test
    fun `a named model is parsed and blank means none`() {
        val ok = SubAgentTask.parseArgs("""{"task":"x","model":" gpt-5 "}""") as SubAgentArgsResult.Ok
        assertEquals("gpt-5", ok.args.model)
        val none = SubAgentTask.parseArgs("""{"task":"x","model":"  "}""") as SubAgentArgsResult.Ok
        assertEquals(null, none.args.model)
    }

    @Test
    fun `the model parameter is offered only with a list, and its enum is that list`() {
        val without = SubAgentToolSchema.definition(listOf("General Sub Agent"))
        assertFalse(without.parameters.containsKey("model"))
        assertFalse("model" in without.propertyOrdering.orEmpty())
        val with = SubAgentToolSchema.definition(listOf("General Sub Agent"), modelHandles = listOf("a", "P/b"))
        assertEquals(listOf("a", "P/b"), with.parameters.getValue("model").enumValues)
        assertTrue("model" in with.propertyOrdering.orEmpty())
    }

    @Test
    fun `the models section lists every name and is empty without models`() {
        assertEquals("", SubAgentTask.callableModelsSection(emptyList()))
        val text = SubAgentTask.callableModelsSection(
            listOf(CallableModel("a", "e1", "A", "A · 128K context"), CallableModel("P/b", "e2", "B", "B")),
        )
        assertTrue(text, text.contains("subagent.model"))
        assertTrue(text, text.contains("- a — A · 128K context"))
        assertTrue(text, text.contains("- P/b — B"))
    }
}
