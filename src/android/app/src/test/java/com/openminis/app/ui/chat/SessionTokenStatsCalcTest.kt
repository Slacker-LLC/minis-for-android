package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionTokenStatsCalcTest {
    private fun msg(role: String, vararg kinds: String) = ChatMessage(
        id = role + kinds.joinToString(), role = role, content = "",
        toolBlocks = kinds.mapIndexed { i, k -> AssistantBlock(id = "b$i", kind = k) },
    )

    @Test
    fun `rows are summed and the last positive context reading wins`() {
        val s = SessionTokenStatsCalc.aggregate(
            listOf(
                """{"inputTokens":10,"outputTokens":2,"cacheReadTokens":5,"cacheCreationTokens":1,"latestContextTokens":100}""",
                """{"inputTokens":20,"outputTokens":3,"latestContextTokens":0}""",
                """{"inputTokens":1,"outputTokens":1,"latestContextTokens":300}""",
            ),
            emptyList(),
        )
        assertEquals(31L, s.input)
        assertEquals(6L, s.output)
        assertEquals(5L, s.cacheRead)
        assertEquals(1L, s.cacheWrite)
        assertEquals(300, s.context)
    }

    @Test
    fun `a malformed row is skipped and does not lose the others`() {
        val s = SessionTokenStatsCalc.aggregate(listOf("not json", """{"inputTokens":7}""", ""), emptyList())
        assertEquals(7L, s.input)
        assertEquals(0, s.context)
    }

    @Test
    fun `the loop count is the larger of tool blocks and assistant messages, text and info blocks excluded`() {
        val messages = listOf(
            msg("user"),
            msg("assistant", "text", "tool_use", "tool_use", "info", "thinking"),
            msg("assistant", "text"),
        )
        // tool-ish blocks: 2 tool_use + 1 thinking = 3, assistants = 2
        assertEquals(3, SessionTokenStatsCalc.aggregate(emptyList(), messages).loopCount)
        assertEquals(2, SessionTokenStatsCalc.aggregate(emptyList(), listOf(msg("assistant", "text"), msg("assistant", "text"))).loopCount)
        assertEquals(0, SessionTokenStatsCalc.aggregate(emptyList(), emptyList()).loopCount)
    }
}
