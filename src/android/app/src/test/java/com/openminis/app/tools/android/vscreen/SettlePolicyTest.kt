package com.openminis.app.tools.android.vscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettlePolicyTest {
    private val params = SettleParams(graceMs = 400, quietMs = 100, deadlineMs = 1_500)

    private fun eval(now: Long, tracker: SettleTracker, p: SettleParams = params) =
        SettlePolicy.evaluate(now, tracker.snapshot(), p)

    @Test fun pressFeedbackIsNotASemanticChange() {
        // TYPE_VIEW_CLICKED=0x1, FOCUSED=0x8, ACCESSIBILITY_FOCUSED=0x8000, HOVER_ENTER=0x80
        listOf(0x1, 0x2, 0x4, 0x8, 0x80, 0x100, 0x8000).forEach { assertFalse("0x${it.toString(16)}", SettlePolicy.isSemantic(it)) }
        listOf(0x10, 0x20, 0x800, 0x1000, 0x400000).forEach { assertTrue("0x${it.toString(16)}", SettlePolicy.isSemantic(it)) }
    }

    @Test fun aClickThatDoesNothingEndsAtTheGraceWindowNotEarlier() {
        val t = SettleTracker().also { it.arm(1_000) }
        t.onFrame(1_050) // ripple frames must not start the clock
        assertNull(eval(1_399, t))
        assertEquals(SettledBy.GRACE_EXPIRED, eval(1_400, t))
    }

    @Test fun aSlowTransitionThatArrivesInsideTheGraceWindowIsWaitedFor() {
        val t = SettleTracker().also { it.arm(1_000) }
        t.onFrame(1_040) // ripple
        t.onSemantic(1_350) // activity transition begins late, still inside grace
        assertNull(eval(1_400, t)) // grace is over but a change is in flight: must not return the old screen
        t.onFrame(1_420)
        assertNull(eval(1_500, t))
        assertEquals(SettledBy.QUIET, eval(1_520, t))
    }

    @Test fun animationFramesExtendTheQuietWindow() {
        val t = SettleTracker().also { it.arm(0) }
        t.onSemantic(50)
        var now = 60L
        while (now < 600) { t.onFrame(now); assertNull(eval(now + 50, t)); now += 16 }
        assertEquals(SettledBy.QUIET, eval(now + 100, t))
    }

    @Test fun aScreenThatNeverSettlesStopsAtTheHardLimit() {
        val t = SettleTracker().also { it.arm(0) }
        var now = 10L
        while (now < 1_500) { t.onSemantic(now); now += 20 }
        assertEquals(SettledBy.DEADLINE, eval(1_500, t))
    }

    @Test fun signalsFromBeforeTheActionAreIgnored() {
        val t = SettleTracker()
        t.onSemantic(500)
        t.onFrame(510)
        t.arm(1_000)
        assertNull(t.snapshot().firstSemantic.takeIf { it >= 0 })
        assertEquals(-1L, t.snapshot().lastFrame)
        assertEquals(SettledBy.GRACE_EXPIRED, eval(1_400, t))
        assertEquals(1L, t.semanticEventsEver()) // lifetime counter still sees it: proves the stream is alive
    }

    @Test fun grace_isLongerForNavigationThanForTyping() {
        assertTrue(SettlePolicy.paramsFor("back").graceMs > SettlePolicy.paramsFor("click").graceMs)
        assertTrue(SettlePolicy.paramsFor("click").graceMs > SettlePolicy.paramsFor("set_text").graceMs)
        listOf("click", "back", "ime_enter", "set_text", "swipe", "launch", "anything").forEach {
            assertTrue(SettlePolicy.paramsFor(it).deadlineMs <= SettlePolicy.HARD_LIMIT_MS)
        }
    }
}
