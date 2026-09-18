package com.openminis.app.tools.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-eta-alarm-tools] Ported from Eta `agent/tool/AgentStructuredDeviceTools.kt`
 * (Mangi-11/Eta @ c15de97). The refusals matter as much as the argv: an alarm at an
 * impossible time, or a timer longer than the advertised bound, must be reported rather than
 * silently adjusted.
 */
class AlarmToolPolicyTest {

    private fun ok(decision: AlarmToolPolicy.Decision): List<String> {
        assertTrue("expected an argv, got $decision", decision is AlarmToolPolicy.Decision.Ok)
        return (decision as AlarmToolPolicy.Decision.Ok).argv
    }

    private fun refused(decision: AlarmToolPolicy.Decision): String {
        assertTrue("expected a refusal, got $decision", decision is AlarmToolPolicy.Decision.Refused)
        return (decision as AlarmToolPolicy.Decision.Refused).reason
    }

    @Test
    fun `an alarm without repeat days becomes a one-shot`() {
        val argv = ok(AlarmToolPolicy.alarmArgv(hour = 7, minute = 5, label = "Standup", repeatDays = null))

        assertEquals(listOf("set", "--time", "07:05", "--repeat", "ONCE", "--label", "Standup"), argv)
    }

    @Test
    fun `a full week is daily and monday to friday is weekdays`() {
        val daily = ok(
            AlarmToolPolicy.alarmArgv(7, 0, null, listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")),
        )
        assertEquals("DAILY", daily[daily.indexOf("--repeat") + 1])

        val weekdays = ok(AlarmToolPolicy.alarmArgv(7, 0, null, listOf("MON", "tue", "wed", "thu", "fri")))
        assertEquals("WEEKDAYS", weekdays[weekdays.indexOf("--repeat") + 1])
    }

    @Test
    fun `a custom day set is refused instead of scheduled on the wrong days`() {
        val reason = refused(AlarmToolPolicy.alarmArgv(7, 0, null, listOf("mon", "wed")))

        assertTrue(reason.contains("Clock app"))
    }

    @Test
    fun `unknown day spellings are refused with the accepted ones`() {
        val reason = refused(AlarmToolPolicy.alarmArgv(7, 0, null, listOf("monday")))

        assertTrue(reason.contains("mon"))
    }

    @Test
    fun `impossible clock values are refused`() {
        assertTrue(refused(AlarmToolPolicy.alarmArgv(24, 0, null, null)).contains("hour"))
        assertTrue(refused(AlarmToolPolicy.alarmArgv(-1, 0, null, null)).contains("hour"))
        assertTrue(refused(AlarmToolPolicy.alarmArgv(null, 0, null, null)).contains("hour"))
        assertTrue(refused(AlarmToolPolicy.alarmArgv(7, 60, null, null)).contains("minute"))
    }

    @Test
    fun `labels are trimmed bounded and omitted when blank`() {
        val plain = ok(AlarmToolPolicy.alarmArgv(7, 0, "   ", null))
        assertTrue(!plain.contains("--label"))

        val long = ok(AlarmToolPolicy.alarmArgv(7, 0, "x".repeat(500), null))
        val label = long[long.indexOf("--label") + 1]
        assertEquals(AlarmToolPolicy.MAX_LABEL_CHARS, label.length)
    }

    @Test
    fun `a timer takes seconds and refuses what the bound excludes`() {
        assertEquals(
            listOf("timer", "--duration", "300", "--label", "Tea"),
            ok(AlarmToolPolicy.timerArgv(300, "Tea")),
        )
        assertTrue(refused(AlarmToolPolicy.timerArgv(0, null)).contains("positive"))
        assertTrue(refused(AlarmToolPolicy.timerArgv(null, null)).contains("positive"))
        assertTrue(
            refused(AlarmToolPolicy.timerArgv(AlarmToolPolicy.MAX_TIMER_SECONDS + 1, null))
                .contains("24 hours"),
        )
        assertEquals(
            listOf("timer", "--duration", AlarmToolPolicy.MAX_TIMER_SECONDS.toString()),
            ok(AlarmToolPolicy.timerArgv(AlarmToolPolicy.MAX_TIMER_SECONDS, null)),
        )
    }

    @Test
    fun `opening the clock app is its own argv`() {
        assertEquals(listOf("open"), AlarmToolPolicy.openArgv())
    }
}
