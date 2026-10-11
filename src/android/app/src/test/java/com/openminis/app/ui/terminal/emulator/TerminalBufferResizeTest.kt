package com.openminis.app.ui.terminal.emulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a resize does to the text, and what soft-wrapped lines look like when copied. */
class TerminalBufferResizeTest {
    private fun term(cols: Int, rows: Int, input: String) =
        TerminalEmulator(cols, rows).also { it.feed(input.toByteArray()) }

    private fun allText(em: TerminalEmulator): String = em.screenText(scrollbackLines = 1000)

    // ── Height ─────────────────────────────────────────────────────────────

    @Test
    fun shrinkingKeepsTheCursorLineAndMovesTopRowsIntoTheHistory() {
        // The keyboard opens: fewer rows. The prompt at the bottom must stay; the top lines go to the history.
        val em = term(20, 6, "one\r\ntwo\r\nthree\r\nfour\r\nfive\r\n\$ ")
        em.resize(20, 3)
        assertEquals("four\nfive\n$", em.screenText())
        assertEquals(2, em.cursorPos().second)
        assertEquals("one\ntwo\nthree\nfour\nfive\n$", allText(em))
    }

    @Test
    fun blankRowsBelowTheCursorGoBeforeAnyTextIsPushedOut() {
        val em = term(20, 6, "a\r\nb\r\n\$ ")
        em.resize(20, 4)
        // Two blank rows below the prompt are dropped; nothing had to move into the history.
        assertEquals("a\nb\n$", em.screenText())
        assertEquals("a\nb\n$", allText(em))
    }

    @Test
    fun growingPullsRowsBackFromTheHistoryAboveTheCursor() {
        val em = term(20, 3, "one\r\ntwo\r\nthree\r\nfour\r\n\$ ")
        assertEquals("three\nfour\n$", em.screenText())
        em.resize(20, 5)
        assertEquals("one\ntwo\nthree\nfour\n$", em.screenText())
        assertEquals(4, em.cursorPos().second)
    }

    @Test
    fun growingWithNoHistoryAddsBlankRowsBelow() {
        val em = term(20, 3, "a\r\nb")
        em.resize(20, 5)
        assertEquals("a\nb", em.screenText())
        assertEquals(1, em.cursorPos().second)
    }

    @Test
    fun theAlternateScreenKeepsItsTopLeftAndLeavesTheHistoryAlone() {
        val em = term(20, 5, "one\r\ntwo\r\n\u001B[?1049hmenu\r\nitem")
        em.resize(10, 3)
        assertEquals("menu\nitem", em.screenText())
        em.feed("\u001B[?1049l".toByteArray())
        assertEquals("one\ntwo", em.screenText().lines().take(2).joinToString("\n"))
    }

    // ── Width ──────────────────────────────────────────────────────────────

    @Test
    fun aNarrowerScreenReflowsALongLine() {
        val em = term(20, 4, "abcdefghijklmnopqrst\r\n\$ ")
        em.resize(10, 4)
        assertEquals("abcdefghij\nklmnopqrst\n$", em.screenText())
    }

    @Test
    fun aWiderScreenJoinsTheRowsOfASoftWrappedLine() {
        val em = term(10, 4, "abcdefghijklmnopqrst\r\n\$ ")
        assertEquals("abcdefghij\nklmnopqrst\n$", em.screenText())
        em.resize(20, 4)
        assertEquals("abcdefghijklmnopqrst\n$", em.screenText())
    }

    @Test
    fun aHardLineBreakIsNeverJoined() {
        val em = term(10, 4, "abcde\r\nfghij\r\n")
        em.resize(20, 4)
        assertEquals("abcde\nfghij", em.screenText())
    }

    @Test
    fun theCursorFollowsItsTextThroughAReflow() {
        val em = term(10, 4, "abcdefghijklmn")
        // The cursor sits after "n" on the second row; at width 20 the text is one row and the cursor is after it.
        em.resize(20, 4)
        assertEquals(14 to 0, em.cursorPos())
        em.resize(5, 4)
        assertEquals("abcde\nfghij\nklmn", em.screenText())
        assertEquals(4 to 2, em.cursorPos())
    }

    @Test
    fun reflowedHistoryGrowsOrShrinksWithTheScreen() {
        val em = term(10, 2, "abcdefghijklmnopqrst\r\nuvwxyz\r\n\$ ")
        em.resize(20, 2)
        assertEquals("uvwxyz\n$", em.screenText())
        assertEquals("abcdefghijklmnopqrst\nuvwxyz\n$", allText(em))
    }

    @Test
    fun aWideCharacterIsNeverSplitFromItsTrailingHalf() {
        val em = term(6, 4, "ab你好cd")
        em.resize(5, 4)
        // "ab你好cd" is 8 cells; at 5 columns 好 does not fit after ab你 and moves down whole.
        val rows = em.screenText().lines()
        assertEquals("ab你", rows[0])
        assertEquals("好cd", rows[1])
    }

    @Test
    fun resizingToTheSameSizeDoesNothing() {
        val em = term(10, 3, "abc")
        em.resize(10, 3)
        assertEquals("abc", em.screenText())
        assertEquals(3 to 0, em.cursorPos())
    }

    // ── Copying wrapped lines ──────────────────────────────────────────────

    @Test
    fun aSoftWrappedLineCopiesAsOneLine() {
        val em = term(10, 6, "https://example.com/a/very/long/path\r\n\$ ")
        val text = em.getSelectedText(0, 0, 9, 4)
        assertEquals("https://example.com/a/very/long/path\n$", text)
    }

    @Test
    fun aHardBreakStaysABreakWhenCopied() {
        val em = term(10, 4, "abc\r\ndef")
        assertEquals("abc\ndef", em.getSelectedText(0, 0, 9, 1))
    }

    @Test
    fun wrappedFlagsSurviveScrolling() {
        val em = term(5, 3, "aaaaabbbbbccccc\r\nx\r\ny\r\nz")
        // Everything above has scrolled into the history; scrolling back and copying still joins the long line.
        em.scrollOffset = 3
        val wrapped = em.visibleWrapped()
        assertTrue(wrapped.any { it })
        assertEquals("aaaaabbbbbccccc", em.getSelectedText(0, 0, 4, 2))
    }

    // ── Width of characters ────────────────────────────────────────────────

    @Test
    fun combiningMarksJoinTheCharacterBeforeThem() {
        // e + combining acute, and an a with a ring: one cell each, so the columns after them line up.
        val em = term(10, 2, "éåx")
        assertEquals("éåx", em.screenText())
        assertEquals(3 to 0, em.cursorPos())
    }

    @Test
    fun aZeroWidthJoinerTakesNoCell() {
        val em = term(10, 2, "a‍b")
        assertEquals(2 to 0, em.cursorPos())
    }

    @Test
    fun aCombiningMarkAtTheStartOfALineHasNothingToAttachToAndIsDropped() {
        val em = term(10, 2, "́a")
        assertEquals("a", em.screenText())
        assertEquals(1 to 0, em.cursorPos())
    }

    @Test
    fun emojiAndCjkTakeTwoCellsButMiscSymbolsTakeOne() {
        assertEquals(2, TerminalBuffer.characterWidth('你'.code))
        assertEquals(2, TerminalBuffer.characterWidth(0x1F600))
        assertEquals(1, TerminalBuffer.characterWidth('a'.code))
        // A "misc symbol" such as a heart or a check mark is one cell in wcwidth, not two.
        assertEquals(1, TerminalBuffer.characterWidth(0x2665))
        assertEquals(1, TerminalBuffer.characterWidth(0x2713))
        assertEquals(2, TerminalBuffer.characterWidth(0x2705))
        assertEquals(0, TerminalBuffer.characterWidth(0x0301))
    }

    @Test
    fun aWideCharacterAtTheLastColumnWrapsWholeAndTheLineStaysOne() {
        val em = term(5, 3, "abcd你e")
        assertEquals("abcd\n你e", em.screenText())
        assertFalse(em.activeBuffer.gridWrapped[1])
        assertTrue(em.activeBuffer.gridWrapped[0])
        // Copied back it is one line without the empty cell the wide character left behind.
        assertEquals("abcd你e", em.getSelectedText(0, 0, 4, 1))
    }
}
