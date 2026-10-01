package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatReplyUsageTest {
    private fun usage(i: Int = 2, o: Int = 408, cw: Int = 0, cr: Int = 0, ctx: Int = 0) =
        ChatTokenUsage(i, o, cw, cr, ctx)

    private fun json(i: Int, o: Int, cw: Int, cr: Int, ctx: Int) =
        """{"inputTokens":$i,"outputTokens":$o,"cacheCreationTokens":$cw,"cacheReadTokens":$cr,"latestContextTokens":$ctx}"""

    @Test
    fun `the persisted JSON is read back field by field`() {
        assertEquals(usage(2, 408, 3000, 57_000, 57_400), ChatTokenUsage.parse(json(2, 408, 3000, 57_000, 57_400)))
    }

    @Test
    fun `token counts are abbreviated`() {
        assertEquals("0", formatUsageTokens(0))
        assertEquals("999", formatUsageTokens(999))
        assertEquals("1k", formatUsageTokens(1000))
        assertEquals("57.4k", formatUsageTokens(57_400))
    }

    @Test
    fun `the summary lists ctx and cache figures only when they are non-zero`() {
        assertEquals("in:2 out:408", usageSummary(usage()))
        assertEquals("ctx:57k in:2 out:408", usageSummary(usage(ctx = 57_000)))
        assertEquals(
            "ctx:57k in:2.3k out:408 cache:57k (91%) +cache:3k",
            usageSummary(usage(cr = 57_000, cw = 3_000, ctx = 57_000).copy(inputTokens = 2_300)),
        )
    }

    @Test
    fun `the hit rate is the cached share of the whole prompt`() {
        assertEquals(75, cacheHitPercent(usage(i = 1000, cr = 3000)))
        assertEquals(60, cacheHitPercent(usage(i = 1000, cr = 3000, cw = 1000)))
        assertEquals(100, cacheHitPercent(usage(i = 0, cr = 500)))
    }

    @Test
    fun `a reply reports its last turn that has usage`() {
        val rows = listOf(json(10, 20, 0, 0, 100) to 1L, json(30, 40, 0, 0, 200) to 2L, null to 3L)
        val reply = lastReplyUsage(rows)!!
        assertEquals(usage(30, 40, ctx = 200), reply.usage)
        assertEquals(2L, reply.completedAtMs)
    }

    // ── Negative cases: bad data never surfaces as a crash or a fake figure ──

    @Test
    fun `malformed, empty and all-zero rows yield no usage`() {
        assertNull(ChatTokenUsage.parse(null))
        assertNull(ChatTokenUsage.parse(""))
        assertNull(ChatTokenUsage.parse("   "))
        assertNull(ChatTokenUsage.parse("not json"))
        assertNull(ChatTokenUsage.parse(json(0, 0, 0, 0, 0)))
        assertNull(lastReplyUsage(listOf(null to 1L, "{}" to 2L)))
        assertNull(lastReplyUsage(emptyList()))
    }

    @Test
    fun `no hit rate without cache reads or a prompt`() {
        assertNull(cacheHitPercent(usage(i = 5000)))
        assertNull(cacheHitPercent(usage(i = 0, cr = 0, cw = 100)))
    }
}
