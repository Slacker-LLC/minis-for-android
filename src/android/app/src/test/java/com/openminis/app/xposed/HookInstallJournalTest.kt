package com.openminis.app.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-eta-xposed-entry] Ported from Eta `core/HookRegistrar.kt` (Mangi-11/Eta @ c15de97). A vendor ROM
 * integration is debuggable only if "installed", "the target is not there" and "it threw" stay
 * distinguishable.
 */
class HookInstallJournalTest {

    @Test
    fun `the journal counts every state separately`() {
        val journal = HookInstallJournal("XiaoAi")
        journal.installed("voice.entry", "assistant entry point")
        journal.missing("voice.entry.class", "assistant entry point", "class not found on this ROM")
        journal.failed("voice.reply", "reply interception", "InvocationTargetException")
        journal.skipped("voice.extra", "optional hook", "not requested")

        val report = journal.report()

        assertEquals("XiaoAi", report.group)
        assertEquals(1, report.installedCount)
        assertEquals(1, report.missingCount)
        assertEquals(1, report.failedCount)
        assertEquals(1, report.skippedCount)
        assertTrue(report.summary().contains("installed=1"))
        assertTrue(report.summary().contains("XiaoAi"))
    }

    @Test
    fun `a throw during installation becomes a failure instead of a silent pass`() {
        val journal = HookInstallJournal("System")
        var observed: Exception? = null

        journal.capture(
            block = { error("boom") },
            onFailure = { observed = it },
        )

        val report = journal.report()
        assertEquals(1, report.failedCount)
        assertEquals(0, report.installedCount)
        assertTrue(observed is IllegalStateException)
        assertEquals("IllegalStateException", report.entries.single().detail)
    }

    @Test
    fun `a clean block records nothing by itself`() {
        val journal = HookInstallJournal("System")

        journal.capture(block = {})

        assertTrue(journal.report().entries.isEmpty())
        assertTrue("an empty report still summarises", journal.report().summary().contains("installed=0"))
    }

    @Test
    fun `combining groups keeps every entry`() {
        val first = HookInstallJournal("Google").apply { installed("a", "first") }.report()
        val second = HookInstallJournal("Google").apply { skipped("b", "second", "not requested") }.report()

        val combined = HookInstallReport.combine("Google", listOf(first, second))

        assertEquals(2, combined.entries.size)
        assertEquals(1, combined.installedCount)
        assertEquals(1, combined.skippedCount)
    }

    @Test
    fun `an applied change keeps its detail in the ledger`() {
        val journal = HookInstallJournal("GoogleEligibility")

        journal.installed("google.identity.model", "Build.MODEL", "SM-S928B")

        val entry = journal.report().entries.single()
        assertEquals(HookInstallStatus.INSTALLED, entry.status)
        assertEquals("SM-S928B", entry.detail)
    }
}
