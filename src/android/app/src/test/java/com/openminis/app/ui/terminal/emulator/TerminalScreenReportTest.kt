package com.openminis.app.ui.terminal.emulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalScreenReportTest {
    private fun term(input: String, cols: Int = 40, rows: Int = 10) =
        TerminalEmulator(cols, rows).also { it.feed(input.toByteArray()) }

    @Test
    fun theSelectedMenuItemIsMarked() {
        val em = term("  one\r\n\u001B[7m> two  \u001B[0m\r\n  three")
        assertEquals("  one\n⟦> two⟧\n  three", em.screenTextMarked(0, marks = true))
        assertEquals("  one\n> two\n  three", em.screenTextMarked(0, marks = false))
    }

    @Test
    fun aColouredBackgroundCountsAsHighlighted() {
        val em = term("\u001B[44m Tab 1 \u001B[0m Tab 2")
        // The highlight's own padding stays outside the marks, so the spacing of the line is kept.
        assertEquals(" ⟦Tab 1⟧  Tab 2", em.screenTextMarked(0, marks = true))
    }

    @Test
    fun plainTextAndTrailingBlankRowsAreUntouched() {
        val em = term("hello\r\n\r\n")
        assertEquals("hello", em.screenTextMarked(0, marks = true))
    }

    @Test
    fun aHighlightRunsToTheEndOfTheLine() {
        val em = term("\u001B[7mstatus line                         \u001B[0m")
        assertEquals("⟦status line⟧", em.screenTextMarked(0, marks = true))
    }

    @Test
    fun cursorPositionIsOneBasedAndHiddenIsSaid() {
        val em = term("\u001B[3;7H")
        assertEquals("cursor row 3, col 7", em.cursorLine())
        em.feed("\u001B[?25l".toByteArray())
        assertEquals("cursor hidden (at row 3, col 7)", em.cursorLine())
    }

    @Test
    fun modesAreNamedAndAPlainShellSaysNothing() {
        assertNull(term("minis$ ").modeLine())
        val em = term("\u001B[?1049h\u001B[?1000h\u001B[?2004h\u001B[?1h")
        assertEquals("full-screen app · mouse reporting · bracketed paste · application cursor keys", em.modeLine())
    }

    @Test
    fun searchFindsLinesInTheHistoryAndTheScreenWithNumbers() {
        val em = TerminalEmulator(20, 3)
        for (i in 1..8) em.feed("line $i\r\n".toByteArray())
        val found = em.findLines(Regex("line [27]"), max = 10)
        assertEquals(listOf("line 2", "line 7"), found.map { it.text })
        // The numbers are stable positions in the kept history, oldest first.
        assertEquals(listOf(2, 7), found.map { it.number })
        assertTrue(em.totalLines() >= 8)
    }

    @Test
    fun searchReturnsOnlyTheNewestWhenThereAreTooMany() {
        val em = TerminalEmulator(20, 3)
        for (i in 1..30) em.feed("hit $i\r\n".toByteArray())
        val found = em.findLines(Regex("hit"), max = 5)
        assertEquals(5, found.size)
        assertEquals("hit 30", found.last().text)
    }

    @Test
    fun searchWithNoMatchIsEmpty() {
        assertTrue(term("abc").findLines(Regex("zzz"), 10).isEmpty())
    }
}
