package com.openminis.app.ui.terminal.emulator

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** OSC 7501 (Program Status Protocol 0.3): reports are untrusted; anything out of bounds is dropped whole. */
class ProgramStatusTest {

    private fun b64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    private fun osc(body: String, st: String = "\u001B\\") = "\u001B]7501;$body$st"

    private fun TerminalEmulator.feedText(s: String) = feed(s.toByteArray(Charsets.ISO_8859_1))

    private fun update(body: String) = (ProgramStatusParser.parse(body) as? ProgramStatusReport.Update)?.record

    // ── parser ───────────────────────────────────────────────────────────────

    @Test
    fun specExampleParses() {
        val r = update("state=blocked:kind=permission:app=terraform:msg=QXBwbHkgMyB0byBhZGQsIDEgdG8gY2hhbmdlLCAwIHRvIGRlc3Ryb3k/")!!
        assertEquals(ProgramState.BLOCKED, r.state)
        assertEquals(BlockedKind.PERMISSION, r.kind)
        assertEquals("terraform", r.app)
        assertEquals("Apply 3 to add, 1 to change, 0 to destroy?", r.msg)
        assertEquals(ProgramStatusParser.ROOT_ID, r.id)
    }

    @Test
    fun missingOrUnknownStateIsIgnored() {
        assertNull(ProgramStatusParser.parse("app=cargo"))
        assertNull(ProgramStatusParser.parse("state=sleeping"))
        assertNull(ProgramStatusParser.parse(""))
    }

    @Test
    fun malformedPairsAreSkippedButTheRestApplies() {
        val r = update("junk:=x:Bad=1:state=working:app=has space:progress=40")!!
        assertEquals(ProgramState.WORKING, r.state)
        assertEquals(40, r.progress)
        assertNull(r.app)
    }

    @Test
    fun unknownKeysAreIgnoredAndLastDuplicateWins() {
        val r = update("state=idle:future=1:state=working")!!
        assertEquals(ProgramState.WORKING, r.state)
    }

    @Test
    fun kindAndProgressOnlyApplyToTheirStates() {
        assertNull(update("state=working:kind=auth")!!.kind)
        assertNull(update("state=done:progress=50")!!.progress)
        assertNull(update("state=blocked:kind=bogus")!!.kind)
        assertNull(update("state=working:progress=101")!!.progress)
        assertNull(update("state=working:progress=-3")!!.progress)
        assertNull(update("state=working:progress=1x")!!.progress)
        assertEquals(0, update("state=working:progress=0")!!.progress)
    }

    @Test
    fun aPairWithBytesOutsideTheValueSetIsSkippedNotDecoded() {
        assertNull(update("state=done:msg=!!!")!!.msg)
    }

    @Test
    fun paddingIsOptional() {
        assertEquals("a", update("state=done:msg=YQ")!!.msg)
        assertEquals("a", update("state=done:msg=YQ==")!!.msg)
    }

    @Test
    fun badBase64InvalidUtf8AndControlCharactersDiscardTheWholeReport() {
        assertNull(ProgramStatusParser.parse("state=done:msg=a"))                           // one stray base64 char cannot decode
        assertNull(ProgramStatusParser.parse("state=done:msg=a-b_"))                       // URL-safe alphabet is not standard base64
        assertNull(ProgramStatusParser.parse("state=done:msg=${Base64.getEncoder().encodeToString(byteArrayOf(0xC3.toByte()))}"))
        assertNull(ProgramStatusParser.parse("state=done:msg=${b64("line\nbreak")}"))
        assertNull(ProgramStatusParser.parse("state=done:msg=${b64("esc\u001B[31m")}"))
        assertNull(ProgramStatusParser.parse("state=done:msg=${b64("c1\u009B")}"))
        assertNull(ProgramStatusParser.parse("state=done:title=${b64("del\u007F")}"))
    }

    @Test
    fun limitsDiscardTheWholeReport() {
        assertNotNull(ProgramStatusParser.parse("state=done:msg=${b64("x".repeat(2048))}"))
        assertNull(ProgramStatusParser.parse("state=done:msg=${b64("x".repeat(2049))}"))
        assertNotNull(ProgramStatusParser.parse("state=done:title=${b64("x".repeat(192))}"))
        assertNull(ProgramStatusParser.parse("state=done:title=${b64("x".repeat(193))}"))
        assertNull(ProgramStatusParser.parse("state=done:app=${"a".repeat(33)}"))
        assertNull(ProgramStatusParser.parse("state=done:${"k".repeat(17)}=1"))
        assertNull(ProgramStatusParser.parse("state=done:msg=${"A".repeat(5000)}"))
    }

    @Test
    fun idMustMatchTheGrammarOrTheReportIsIgnored() {
        assertEquals("build/test", update("state=working:id=build/test")!!.id)
        assertNull(ProgramStatusParser.parse("state=working:id="))                          // must not fall back to the root
        assertNull(ProgramStatusParser.parse("state=working:id=a//b"))
        assertNull(ProgramStatusParser.parse("state=working:id=/a"))
        assertNull(ProgramStatusParser.parse("state=working:id=${"s".repeat(33)}"))
        assertNull(ProgramStatusParser.parse("state=working:id=${(1..9).joinToString("/") { "s" }}"))
        assertNotNull(ProgramStatusParser.parse("state=working:id=${(1..8).joinToString("/") { "s" }}"))
    }

    @Test
    fun clearParsesWithAndWithoutId() {
        assertEquals(ProgramStatusReport.Clear(null), ProgramStatusParser.parse("state=clear"))
        assertEquals(ProgramStatusReport.Clear("a/b"), ProgramStatusParser.parse("state=clear:id=a/b"))
        assertNull(ProgramStatusParser.parse("state=clear:id=a b"))
    }

    // ── store ────────────────────────────────────────────────────────────────

    private fun rec(id: String, state: ProgramState = ProgramState.WORKING, app: String? = null) =
        ProgramStatusReport.Update(StatusRecord(id, state, null, null, app, null, null))

    @Test
    fun aReportReplacesItsRecordCompletely() {
        val store = ProgramStatusStore()
        store.apply(ProgramStatusParser.parse("state=working:app=brew:msg=${b64("a")}")!!)
        store.apply(ProgramStatusParser.parse("state=done")!!)
        val only = store.snapshot.single()
        assertEquals(ProgramState.DONE, only.state)
        assertNull(only.app)
        assertNull(only.msg)
    }

    @Test
    fun clearRemovesTheRecordAndEverythingBeneathIt() {
        val store = ProgramStatusStore()
        listOf("", "build", "build/test", "buildx", "other").forEach { store.apply(rec(it)) }
        store.apply(ProgramStatusReport.Clear("build"))
        assertEquals(listOf("", "buildx", "other"), store.snapshot.map { it.id })
        store.apply(ProgramStatusReport.Clear(null))
        assertTrue(store.snapshot.isEmpty())
    }

    @Test
    fun processExitDropsTransientRecordsButKeepsDoneAndError() {
        val store = ProgramStatusStore()
        store.apply(rec("w", ProgramState.WORKING))
        store.apply(rec("b", ProgramState.BLOCKED))
        store.apply(rec("i", ProgramState.IDLE))
        store.apply(rec("d", ProgramState.DONE))
        store.apply(rec("e", ProgramState.ERROR))
        store.dropTransient()
        assertEquals(listOf("d", "e"), store.snapshot.map { it.id })
    }

    @Test
    fun recordCapEvictsTheLeastRecentlyUpdated() {
        val store = ProgramStatusStore(maxRecords = 3)
        store.apply(rec("a")); store.apply(rec("b")); store.apply(rec("c"))
        store.apply(rec("a"))                       // a is now the freshest
        store.apply(rec("d"))
        assertEquals(listOf("c", "a", "d"), store.snapshot.map { it.id })
    }

    @Test
    fun appIsInheritedFromTheNearestAncestor() {
        val store = ProgramStatusStore()
        store.apply(rec("", app = "deploy"))
        store.apply(rec("us", app = null))
        store.apply(rec("us/east", app = null))
        assertEquals("deploy", store.effectiveApp(store.snapshot.first { it.id == "us/east" }))
        store.apply(rec("us", app = "mid"))
        assertEquals("mid", store.effectiveApp(store.snapshot.first { it.id == "us/east" }))
    }

    // ── emulator ─────────────────────────────────────────────────────────────

    @Test
    fun featureDetectionRepliesWithTheFixedBodyBeforeDeviceAttributes() {
        val emulator = TerminalEmulator(80, 24)
        val replies = mutableListOf<String>()
        emulator.onResponse = { replies += String(it, Charsets.UTF_8) }
        emulator.feedText("\u001B]7501;?\u001B\\\u001B[c")
        assertEquals(listOf("\u001B]7501;?\u001B\\", "\u001B[?62;22c"), replies)
    }

    /** Bytes pi writes at startup (tui/src/terminal.ts: Kitty query, OSC 7501 query, DA1 sentinel), then its own reports. */
    @Test
    fun piStartupHandshakeIsRecognisedAndItsReportsAreShown() {
        val emulator = TerminalEmulator(80, 24)
        val replies = mutableListOf<String>()
        emulator.onResponse = { replies += String(it, Charsets.UTF_8) }
        emulator.feedText("\u001B[>7u\u001B[?u\u001B]7501;?\u001B\\\u001B[c")
        // pi counts support only if the 7501 reply arrives before the DA1 reply.
        assertEquals("\u001B]7501;?\u001B\\", replies.first())
        assertTrue(replies.last().matches(Regex("\u001B\\[\\?[0-9;]*c")))

        emulator.feedText("\u001B]7501;state=working:app=pi:msg=${b64("my session")}\u001B\\")
        emulator.feedText("\u001B]7501;state=blocked:app=pi:kind=permission:msg=${b64("Allow bash?")}\u001B\\")
        val blocked = emulator.programStatus.value.single()
        assertEquals(ProgramState.BLOCKED, blocked.state)
        assertEquals("pi", blocked.app)
        assertEquals("Allow bash?", blocked.msg)
        emulator.feedText("\u001B]7501;state=clear\u001B\\")   // pi's exit
        assertTrue(emulator.programStatus.value.isEmpty())
    }

    @Test
    fun reportsNeverWriteAnythingBack() {
        val emulator = TerminalEmulator(80, 24)
        val replies = mutableListOf<ByteArray>()
        emulator.onResponse = { replies += it }
        emulator.feedText(osc("state=working:id=x:msg=${b64("hi")}"))
        assertTrue(replies.isEmpty())
    }

    @Test
    fun belAndStBothTerminateAReport() {
        val emulator = TerminalEmulator(80, 24)
        emulator.feedText(osc("state=working", st = "\u0007"))
        emulator.feedText(osc("state=done:id=x"))
        assertEquals(listOf("", "x"), emulator.programStatus.value.map { it.id })
    }

    @Test
    fun anOversizeSequenceIsDroppedNotTruncatedIntoAValidReport() {
        val emulator = TerminalEmulator(80, 24)
        // The first 4096 bytes alone are a valid report; the tail must not be silently cut off.
        emulator.feedText(osc("state=working:app=x:${"a".repeat(5000)}=1"))
        assertTrue(emulator.programStatus.value.isEmpty())
    }

    @Test
    fun recordsSurviveScreenSwitchesAndSoftResetButNotRis() {
        val emulator = TerminalEmulator(80, 24)
        emulator.feedText(osc("state=done:app=cargo"))
        emulator.feedText("\u001B[?1049h\u001B[?1049l\u001B[!p")
        assertEquals(1, emulator.programStatus.value.size)
        emulator.feedText("\u001Bc")
        assertTrue(emulator.programStatus.value.isEmpty())
    }

    @Test
    fun shellPromptAndProcessExitDropTransientRecords() {
        val emulator = TerminalEmulator(80, 24)
        emulator.feedText(osc("state=working:id=a") + osc("state=done:id=b"))
        emulator.feedText("\u001B]133;A\u001B\\")
        assertEquals(listOf("b"), emulator.programStatus.value.map { it.id })
        emulator.feedText(osc("state=blocked:id=c:kind=auth"))
        emulator.onProcessExit()
        assertEquals(listOf("b"), emulator.programStatus.value.map { it.id })
    }

    @Test
    fun malformedReportLeavesStoredRecordsUntouched() {
        val emulator = TerminalEmulator(80, 24)
        emulator.feedText(osc("state=working:app=cargo:msg=${b64("building")}"))
        emulator.feedText(osc("state=done:msg=${b64("bad\u0007")}"))
        assertEquals(ProgramState.WORKING, emulator.programStatus.value.single().state)
    }

    // ── display text ─────────────────────────────────────────────────────────

    @Test
    fun displayTextDropsDirectionOverridesAndInvisibles() {
        assertEquals("abc", sanitizeStatusText("a‮b​⁦c"))
        assertEquals("héllo 😀", sanitizeStatusText("héllo 😀"))
    }
}
