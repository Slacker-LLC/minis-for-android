package com.openminis.app.xposed.colordirect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-eta-xposed-groups] Ported from Eta `hook/colordirect/ColorDirectHooks.kt` (Mangi-11/Eta @
 * c15de97). One activity carries every direct-service gesture, so the cases that matter are the
 * near misses: a finger trigger with a different count, a payload that does not parse, and the
 * second report of one gesture.
 */
class ColorDirectTriggerPolicyTest {

    private fun payload(fingerTrigger: Boolean, fingerCount: Int): String =
        """{"fingerTrigger":$fingerTrigger,"touchInfo":{"fingerCount":$fingerCount}}"""

    @Test
    fun `only a two-finger trigger is this gesture`() {
        assertTrue(ColorDirectTriggerPolicy.isDoubleFingerCollect(payload(true, 2)))
        assertFalse(
            "another finger count is another gesture",
            ColorDirectTriggerPolicy.isDoubleFingerCollect(payload(true, 3)),
        )
        assertFalse(
            "a payload without the trigger flag is not a gesture report at all",
            ColorDirectTriggerPolicy.isDoubleFingerCollect(payload(false, 2)),
        )
    }

    @Test
    fun `a payload that cannot be read is not claimed`() {
        assertFalse(ColorDirectTriggerPolicy.isDoubleFingerCollect(null))
        assertFalse(ColorDirectTriggerPolicy.isDoubleFingerCollect(""))
        assertFalse(ColorDirectTriggerPolicy.isDoubleFingerCollect("not json"))
        assertFalse(
            "no touch info means no finger count to match",
            ColorDirectTriggerPolicy.isDoubleFingerCollect("""{"fingerTrigger":true}"""),
        )
    }

    @Test
    fun `one gesture is not turned into two searches`() {
        val first = 5_000L

        assertTrue(
            ColorDirectTriggerPolicy.withinDedupWindow(
                nowMs = first + ColorDirectTriggerPolicy.DEDUP_WINDOW_MS,
                lastHandledMs = first,
            ),
        )
        assertFalse(
            ColorDirectTriggerPolicy.withinDedupWindow(
                nowMs = first + ColorDirectTriggerPolicy.DEDUP_WINDOW_MS + 1,
                lastHandledMs = first,
            ),
        )
        assertFalse(
            "nothing handled yet is not a dedup window",
            ColorDirectTriggerPolicy.withinDedupWindow(nowMs = first, lastHandledMs = 0L),
        )
    }
}
