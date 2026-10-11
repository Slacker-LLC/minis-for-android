package com.openminis.app.ui.terminal.emulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A full-screen program that asks the terminal something at start-up waits for the answer. A terminal that stays
 * silent stalls it (a first screen that only appears after a key is pressed). These pin what the emulator answers,
 * and that it does NOT claim features it lacks (the kitty keyboard protocol would make programs send keys it cannot encode).
 */
class TerminalQueryResponseTest {
    private fun reply(vararg inputs: String, rows: Int = 24, cols: Int = 80, setup: (TerminalEmulator) -> Unit = {}): String {
        val em = TerminalEmulator(cols, rows)
        setup(em)
        val out = StringBuilder()
        em.onResponse = { out.append(String(it, Charsets.ISO_8859_1)) }
        for (input in inputs) em.feed(input.toByteArray())
        return out.toString()
    }

    @Test
    fun cursorPositionReportsAreAnsweredInBothForms() {
        assertEquals("\u001B[3;5R", reply("\u001B[3;5H\u001B[6n"))
        assertEquals("\u001B[?3;5R", reply("\u001B[3;5H\u001B[?6n"))
    }

    @Test
    fun theDecPrivateCursorReportDoesNotTurnOnOriginMode() {
        // CSI ? 6 n used to fall into the DEC private mode handler as "reset mode 6".
        val em = TerminalEmulator(80, 24)
        em.feed("\u001B[?6h".toByteArray())
        assertTrue(em.originMode)
        em.feed("\u001B[?6n".toByteArray())
        assertTrue("a status query must not change a mode", em.originMode)
    }

    @Test
    fun windowSizeQueriesAreAnswered() {
        assertEquals("\u001B[8;30;100t", reply("\u001B[18t", rows = 30, cols = 100))
        assertEquals("\u001B[9;30;100t", reply("\u001B[19t", rows = 30, cols = 100))
        assertEquals("\u001B[4;480;800t", reply("\u001B[14t", rows = 30, cols = 100))
        assertEquals("\u001B[6;16;8t", reply("\u001B[16t", rows = 30, cols = 100))
    }

    @Test
    fun theRealCellSizeIsUsedWhenTheViewKnowsIt() {
        val text = reply("\u001B[16t") { it.cellWidthPx = 18; it.cellHeightPx = 40 }
        assertEquals("\u001B[6;40;18t", text)
    }

    @Test
    fun modeQueriesReportSetResetAndUnknown() {
        assertEquals("\u001B[?2004;2\$y", reply("\u001B[?2004\$p"))
        assertEquals("\u001B[?2004;1\$y", reply("\u001B[?2004h", "\u001B[?2004\$p"))
        assertEquals("\u001B[?1049;1\$y", reply("\u001B[?1049h", "\u001B[?1049\$p"))
        assertEquals("\u001B[?1000;1\$y", reply("\u001B[?1000h", "\u001B[?1000\$p"))
        assertEquals("\u001B[?2026;0\$y", reply("\u001B[?2026\$p"))
    }

    @Test
    fun defaultColoursAreReportedForColourQueries() {
        val fg = reply("\u001B]10;?\u0007")
        val bg = reply("\u001B]11;?\u0007")
        assertTrue(fg, Regex("""\u001B\]10;rgb:[0-9a-f]{4}/[0-9a-f]{4}/[0-9a-f]{4}\u001B\\""").matches(fg))
        assertTrue(bg, Regex("""\u001B\]11;rgb:[0-9a-f]{4}/[0-9a-f]{4}/[0-9a-f]{4}\u001B\\""").matches(bg))
        assertTrue("foreground and background differ", fg.substringAfter(';') != bg.substringAfter(';'))
    }

    @Test
    fun settingAColourIsNotAQuery() {
        assertEquals("", reply("\u001B]11;rgb:00/00/00\u0007"))
    }

    @Test
    fun theTerminalNamesItselfForVersionQueries() {
        assertEquals("\u001BP>|Minis\u001B\\", reply("\u001B[>q"))
    }

    @Test
    fun theKittyKeyboardProtocolIsNotClaimed() {
        // Answering would make a program turn it on and send keys this terminal cannot read.
        assertEquals("", reply("\u001B[?u"))
        assertFalse(reply("\u001B[?u").contains("?0u"))
    }

    @Test
    fun deviceAttributesStillAnswer() {
        assertEquals("\u001B[?62;22c", reply("\u001B[c"))
        assertEquals("\u001B[>0;0;0c", reply("\u001B[>c"))
    }
}
