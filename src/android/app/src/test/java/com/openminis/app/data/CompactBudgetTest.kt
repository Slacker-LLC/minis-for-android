package com.openminis.app.data

import com.openminis.app.ui.chat.ChatViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for the two ceilings that bound one compaction run. */
class CompactBudgetTest {

    @Test
    fun `short transcript gets the base timeout`() {
        assertEquals(
            ChatViewModel.COMPACT_TIMEOUT_BASE_MS,
            ChatViewModel.compactTimeoutMsFor(0),
        )
        assertEquals(
            ChatViewModel.COMPACT_TIMEOUT_BASE_MS,
            ChatViewModel.compactTimeoutMsFor(9_999),
        )
    }

    @Test
    fun `timeout grows with transcript length`() {
        val short = ChatViewModel.compactTimeoutMsFor(5_000)
        val medium = ChatViewModel.compactTimeoutMsFor(50_000)
        val long = ChatViewModel.compactTimeoutMsFor(120_000)
        assertTrue("longer transcript must get more time", medium > short)
        assertTrue("longer transcript must get more time", long > medium)
    }

    @Test
    fun `growth is one step per 10k characters`() {
        val base = ChatViewModel.COMPACT_TIMEOUT_BASE_MS
        val step = ChatViewModel.COMPACT_TIMEOUT_PER_10K_CHARS_MS
        assertEquals(base + step, ChatViewModel.compactTimeoutMsFor(10_000))
        assertEquals(base + 3 * step, ChatViewModel.compactTimeoutMsFor(35_000))
    }

    @Test
    fun `timeout is capped`() {
        assertEquals(
            ChatViewModel.COMPACT_TIMEOUT_MAX_MS,
            ChatViewModel.compactTimeoutMsFor(10_000_000),
        )
    }

    @Test
    fun `cap stays below provider read timeout`() {
        val providerReadTimeoutMs = 10 * 60 * 1000L
        assertTrue(
            ChatViewModel.COMPACT_TIMEOUT_MAX_MS < providerReadTimeoutMs,
        )
    }

    @Test
    fun `call budget is below depth-only fanout and still allows rescue`() {
        val depthCapWorstCase = 1 + 2 + 4 + 8
        assertTrue(ChatViewModel.MAX_COMPACT_LLM_CALLS < depthCapWorstCase)
        assertTrue(ChatViewModel.MAX_COMPACT_LLM_CALLS >= 5)
    }

    @Test
    fun `worst-case wall clock stays under six minutes`() {
        assertTrue(ChatViewModel.COMPACT_TIMEOUT_MAX_MS <= 6 * 60 * 1000L)
    }
}
