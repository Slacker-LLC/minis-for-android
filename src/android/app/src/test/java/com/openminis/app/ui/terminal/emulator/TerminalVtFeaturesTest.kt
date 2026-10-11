package com.openminis.app.ui.terminal.emulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Escape sequences modern full-screen programs write, and what an old terminal did with the ones it did not know. */
class TerminalVtFeaturesTest {
    private fun term(input: String, cols: Int = 40, rows: Int = 6) =
        TerminalEmulator(cols, rows).also { it.feed(input.toByteArray()) }

    private fun cellAt(em: TerminalEmulator, col: Int, row: Int) = em.activeBuffer.grid[row][col]

    // ── Colon sub-parameters ───────────────────────────────────────────────

    @Test
    fun curlyUnderlineWithColonsIsAnUnderlineNotGarbage() {
        val em = term("\u001B[4:3mab\u001B[4:0mc")
        assertEquals("abc", em.screenText())
        assertTrue(cellAt(em, 0, 0).attributes.has(TextAttributes.UNDERLINE))
        assertFalse(cellAt(em, 2, 0).attributes.has(TextAttributes.UNDERLINE))
    }

    @Test
    fun trueColourInColonFormWithAndWithoutTheColourSpaceId() {
        val withId = term("\u001B[38:2::255:128:0mx")
        assertEquals(TerminalColor.Rgb(255, 128, 0), cellAt(withId, 0, 0).foreground)
        val withoutId = term("\u001B[48:2:10:20:30mx")
        assertEquals(TerminalColor.Rgb(10, 20, 30), cellAt(withoutId, 0, 0).background)
    }

    @Test
    fun indexedColourInColonFormAndSemicolonFormAgree() {
        assertEquals(TerminalColor.Indexed(196), cellAt(term("\u001B[38:5:196mx"), 0, 0).foreground)
        assertEquals(TerminalColor.Indexed(196), cellAt(term("\u001B[38;5;196mx"), 0, 0).foreground)
        assertEquals(TerminalColor.Rgb(1, 2, 3), cellAt(term("\u001B[38;2;1;2;3mx"), 0, 0).foreground)
    }

    @Test
    fun underlineColourIsConsumedAndNeverBecomesAnAttribute() {
        // 58 sets the underline colour; reading its arguments as SGR codes would turn on bold, blink, ...
        val colon = term("\u001B[58:2::1:2:3mx")
        assertEquals("x", colon.screenText())
        assertEquals(TextAttributes(), cellAt(colon, 0, 0).attributes)
        val semi = term("\u001B[58;2;1;2;3mx")
        assertEquals(TextAttributes(), cellAt(semi, 0, 0).attributes)
        assertEquals(TerminalColor.Default, cellAt(semi, 0, 0).foreground)
    }

    @Test
    fun aMixedSgrWithSubParametersKeepsTheRestOfTheList() {
        val em = term("\u001B[1;4:3;38:2::9:8:7;7mx")
        val a = cellAt(em, 0, 0)
        assertTrue(a.attributes.has(TextAttributes.BOLD))
        assertTrue(a.attributes.has(TextAttributes.UNDERLINE))
        assertTrue(a.attributes.has(TextAttributes.INVERSE))
        assertEquals(TerminalColor.Rgb(9, 8, 7), a.foreground)
    }

    // ── Strings the terminal skips ─────────────────────────────────────────

    @Test
    fun aDeviceControlStringIsSkippedWhole() {
        // DECRQSS, sixel and APC graphics: their text must never reach the screen.
        val em = term("a\u001BP1\$r0;1m\u001B\\b\u001B_Gi=1;AAAA\u001B\\c\u001B^privacy\u001B\\d")
        assertEquals("abcd", em.screenText())
    }

    @Test
    fun anOscTitleIsDecodedAsUtf8() {
        val em = term("\u001B]0;终端 ~/项目\u0007")
        assertEquals("终端 ~/项目", em.title)
    }

    @Test
    fun anOscEndedByStAndByBellBothWork() {
        assertEquals("a", term("\u001B]2;a\u001B\\").title)
        assertEquals("b", term("\u001B]2;b\u0007").title)
    }

    // ── Character sets ─────────────────────────────────────────────────────

    @Test
    fun decSpecialGraphicsDrawsBoxes() {
        val em = term("\u001B(0lqqk\u001B(Bq")
        assertEquals("┌──┐q", em.screenText())
    }

    @Test
    fun shiftOutUsesG1AndShiftInGoesBack() {
        val em = term("\u001B)0q\u000Eq\u000Fq")
        assertEquals("q─q", em.screenText())
    }

    @Test
    fun aCharacterOutsideTheGraphicsRangeIsUnchanged() {
        assertEquals("A1 ┌", term("\u001B(0A1 l").screenText())
    }

    // ── Tabs, repeat, insert mode ──────────────────────────────────────────

    @Test
    fun backTabGoesToThePreviousStop() {
        val em = term("\t\t\u001B[Zy")
        // Two tabs land on column 16; CBT goes back to the stop at 8.
        assertEquals('y'.code, cellAt(em, 8, 0).char)
        // From inside a tab cell it goes to the stop at its left.
        val inside = term("\t\tx\u001B[Zy")
        assertEquals('y'.code, cellAt(inside, 16, 0).char)
    }

    @Test
    fun aProgramCanSetAndClearTabStops() {
        val em = term("\u001B[3g\u001B[1;4H\u001BH\u001B[1;1H\tx")
        // All stops cleared, one set at column 4 (index 3): a tab from the left edge stops there.
        assertEquals('x'.code, cellAt(em, 3, 0).char)
    }

    @Test
    fun repeatWritesTheLastCharacterAgain() {
        assertEquals("abbbb", term("ab\u001B[3b").screenText())
    }

    @Test
    fun repeatWithNothingPrintedDoesNothing() {
        assertEquals("", term("\u001B[5b").screenText())
    }

    @Test
    fun horizontalAbsoluteAndRelativeMoves() {
        val em = term("\u001B[5`x\u001B[2ay\u001B[1;1H\u001B[2eZ")
        assertEquals('x'.code, cellAt(em, 4, 0).char)
        assertEquals('y'.code, cellAt(em, 7, 0).char)
        assertEquals('Z'.code, cellAt(em, 0, 2).char)
    }

    @Test
    fun insertModePushesTheRestOfTheLineRight() {
        val em = term("abc\u001B[1;1H\u001B[4hX\u001B[4lY")
        assertEquals("XYbc", em.screenText())
    }

    // ── Resets and tests ───────────────────────────────────────────────────

    @Test
    fun theAlignmentTestFillsTheScreen() {
        val em = term("\u001B#8", cols = 5, rows = 2)
        assertEquals("EEEEE\nEEEEE", em.screenText())
    }

    @Test
    fun aSoftResetClearsModesButKeepsTheScreen() {
        val em = term("hi\u001B[4h\u001B(0\u001B[?25l\u001B[!p")
        assertEquals("hi", em.screenText())
        assertTrue(em.cursorVisible)
        em.feed("l".toByteArray())
        // Insert mode and the graphics set are off again.
        assertEquals("hil", em.screenText())
    }

    @Test
    fun theBellIsReportedOutsideStringsOnly() {
        val em = TerminalEmulator(20, 3)
        var rings = 0
        em.onBell = { rings++ }
        em.feed("a\u0007b\u001B]0;t\u0007c".toByteArray())
        assertEquals("The BEL that ends the title is not a bell", 1, rings)
    }
}
