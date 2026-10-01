package com.openminis.app.tools

import com.openminis.app.agent.subagents.SubAgentToolSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which delegation schema a session gets under the one tool name `subagent`: the roster schema while
 * sub agents are allowed, the older single-shot schema when they are switched off, and none at all in a
 * sub agent's own child session.
 */
class SubAgentToolExposureTest {
    private fun tools(roster: List<String>? = null, child: Boolean = false) =
        AgentTools.makeAgentTools(subAgentRosterNames = roster, subAgentChild = child)

    private fun subagent(list: List<com.openminis.app.data.model.AgentToolDefinition>) =
        list.filter { it.name == "subagent" || it.name == SubAgentToolSchema.HANDLER_NAME }

    @Test
    fun `sub agents allowed - one subagent tool with the roster schema`() {
        val found = subagent(tools(roster = listOf("General Sub Agent", "researcher")))
        assertEquals(1, found.size)
        assertEquals("subagent", found.single().name)
        assertEquals(listOf("General Sub Agent", "researcher"), found.single().parameters.getValue("agent").enumValues)
    }

    @Test
    fun `sub agents switched off - the older single-shot schema under the same name`() {
        val found = subagent(tools(roster = null))
        assertEquals(1, found.size)
        assertEquals("subagent", found.single().name)
        assertNotNull(found.single().parameters["prompt"])
        assertFalse(found.single().parameters.containsKey("action"))
    }

    // ── Negative case ───────────────────────────────────────────────────────

    @Test
    fun `a sub agent's own session is offered no delegation tool in any form`() {
        for (roster in listOf(null, listOf("General Sub Agent"))) {
            assertTrue("roster=$roster", subagent(tools(roster = roster, child = true)).isEmpty())
        }
    }
}
