package com.openminis.app.ui.chat

import com.openminis.app.ui.chat.InLoopContextPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/** The mid-loop context guard's decisions, including every way it refuses to compact. */
class InLoopContextPolicyTest {
    private val window = 200_000

    @Test
    fun `an unknown reading or window proceeds`() {
        assertEquals(Decision.PROCEED, InLoopContextPolicy.decide(0, window, 0))
        assertEquals(Decision.PROCEED, InLoopContextPolicy.decide(-5, window, 0))
        assertEquals(Decision.PROCEED, InLoopContextPolicy.decide(150_000, null, 0))
    }

    @Test
    fun `a small reading proceeds`() {
        assertEquals(Decision.PROCEED, InLoopContextPolicy.decide(10_000, window, 0))
    }

    @Test
    fun `a nearly full window compacts while budget remains`() {
        assertEquals(Decision.COMPACT, InLoopContextPolicy.decide(190_000, window, 0))
        assertEquals(Decision.COMPACT, InLoopContextPolicy.decide(190_000, window, InLoopContextPolicy.MAX_COMPACTIONS - 1))
    }

    @Test
    fun `a nearly full window stops once the compaction budget is spent`() {
        assertEquals(
            Decision.STOP_COMPACTION_BUDGET,
            InLoopContextPolicy.decide(190_000, window, InLoopContextPolicy.MAX_COMPACTIONS),
        )
    }

    @Test
    fun `a small window never rescues, it stops`() {
        assertEquals(Decision.STOP_EXHAUSTED, InLoopContextPolicy.decide(30_000, 32_000, 0))
    }
}
