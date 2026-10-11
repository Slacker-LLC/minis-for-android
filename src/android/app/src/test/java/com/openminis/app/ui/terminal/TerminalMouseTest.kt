package com.openminis.app.ui.terminal

import com.openminis.app.ui.terminal.emulator.MouseTracking
import com.openminis.app.ui.terminal.emulator.TerminalEmulator
import com.openminis.app.ui.terminal.emulator.TerminalFontScale
import com.openminis.app.ui.terminal.emulator.TerminalMouse
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalMouseTest {
    private fun s(b: ByteArray?) = b?.toString(Charsets.ISO_8859_1)

    // ---- SGR encoding: coordinates and button values ----

    @Test fun `sgr press and release carry one-based cells and M or m`() {
        assertEquals("\u001B[<0;1;1M", s(TerminalMouse.press(0, 0, sgr = true)))
        assertEquals("\u001B[<0;1;1m", s(TerminalMouse.release(0, 0, sgr = true)))
        assertEquals("\u001B[<0;80;24M", s(TerminalMouse.press(79, 23, sgr = true)))
        assertEquals("\u001B[<0;5;7m", s(TerminalMouse.release(4, 6, sgr = true)))
    }

    @Test fun `sgr drag adds the motion bit and wheel uses 64 and 65`() {
        assertEquals("\u001B[<32;10;3M", s(TerminalMouse.drag(9, 2, sgr = true)))
        assertEquals("\u001B[<64;2;2M", s(TerminalMouse.wheel(up = true, col = 1, row = 1, sgr = true)))
        assertEquals("\u001B[<65;2;2M", s(TerminalMouse.wheel(up = false, col = 1, row = 1, sgr = true)))
    }

    @Test fun `sgr has no coordinate limit`() {
        assertEquals("\u001B[<0;301;999M", s(TerminalMouse.press(300, 998, sgr = true)))
    }

    @Test fun `legacy encoding offsets by 32 and reports release as button 3`() {
        assertArrayEquals(byteArrayOf(0x1B, '['.code.toByte(), 'M'.code.toByte(), 32, 33, 33), TerminalMouse.press(0, 0, sgr = false))
        assertArrayEquals(byteArrayOf(0x1B, '['.code.toByte(), 'M'.code.toByte(), 35, 33, 33), TerminalMouse.release(0, 0, sgr = false))
    }

    @Test fun `legacy encoding drops what it cannot write instead of corrupting the stream`() {
        assertNull(TerminalMouse.press(223, 0, sgr = false))
        assertNull(TerminalMouse.press(0, 300, sgr = false))
        assertEquals(6, TerminalMouse.press(221, 0, sgr = false)!!.size)
    }

    // ---- swipe -> arrow keys honours DECCKM ----

    @Test fun `swipe sends CSI arrows in normal cursor mode`() {
        assertEquals("\u001B[B\u001B[B\u001B[B", s(TerminalMouse.scrollKeys(3, applicationCursorKeys = false)))
        assertEquals("\u001B[A\u001B[A", s(TerminalMouse.scrollKeys(-2, applicationCursorKeys = false)))
    }

    @Test fun `swipe sends SS3 arrows in application cursor mode`() {
        assertEquals("\u001BOB\u001BOB", s(TerminalMouse.scrollKeys(2, applicationCursorKeys = true)))
        assertEquals("\u001BOA", s(TerminalMouse.scrollKeys(-1, applicationCursorKeys = true)))
    }

    @Test fun `a swipe never floods the program`() {
        assertEquals(TerminalMouse.MAX_SCROLL_KEYS * 3, TerminalMouse.scrollKeys(500, false).size)
        assertEquals(0, TerminalMouse.scrollKeys(0, false).size)
    }

    @Test fun `wheel events for a swipe go up for negative lines`() {
        assertEquals("\u001B[<64;1;1M\u001B[<64;1;1M", s(TerminalMouse.wheelEvents(-2, 0, 0, sgr = true)))
        assertEquals("\u001B[<65;1;1M", s(TerminalMouse.wheelEvents(1, 0, 0, sgr = true)))
    }

    // ---- paste ----

    @Test fun `paste is bracketed when the program asked for it`() {
        assertEquals("\u001B[200~echo a\recho b\u001B[201~", s(TerminalMouse.paste("echo a\necho b", bracketed = true)))
    }

    @Test fun `plain paste turns newlines into Enter`() {
        assertEquals("a\rb\rc", s(TerminalMouse.paste("a\nb\r\nc", bracketed = false)))
    }

    @Test fun `pasted text cannot close the bracket early`() {
        val hostile = "x\u001B[201~rm -rf /"
        val out = s(TerminalMouse.paste(hostile, bracketed = true))!!
        assertEquals(1, Regex("\u001B\\[201~").findAll(out).count())
        assertTrue(out.endsWith("\u001B[201~"))
    }

    // ---- emulator mode tracking ----

    private fun emulator() = TerminalEmulator(80, 24)

    @Test fun `decset 1000 1002 1003 and 1006 are tracked and reset`() {
        val e = emulator()
        assertEquals(MouseTracking.OFF, e.mouseTracking)
        e.feed("\u001B[?1000h".toByteArray()); assertEquals(MouseTracking.BUTTON, e.mouseTracking)
        e.feed("\u001B[?1002h".toByteArray()); assertEquals(MouseTracking.DRAG, e.mouseTracking)
        e.feed("\u001B[?1003h".toByteArray()); assertEquals(MouseTracking.ANY, e.mouseTracking)
        e.feed("\u001B[?1006h".toByteArray()); assertTrue(e.mouseSgr)
        e.feed("\u001B[?1003l".toByteArray()); assertEquals(MouseTracking.OFF, e.mouseTracking)
        e.feed("\u001B[?1006l".toByteArray()); assertFalse(e.mouseSgr)
    }

    @Test fun `a full reset and a dead program both release the mouse`() {
        val e = emulator()
        e.feed("\u001B[?1002h\u001B[?1006h".toByteArray())
        e.feed("\u001Bc".toByteArray())
        assertEquals(MouseTracking.OFF, e.mouseTracking)
        assertFalse(e.mouseSgr)
        e.feed("\u001B[?1000h".toByteArray())
        e.onProcessExit()
        assertEquals(MouseTracking.OFF, e.mouseTracking)
    }

    @Test fun `several modes in one sequence are all applied`() {
        val e = emulator()
        e.feed("\u001B[?1002;1006;2004h".toByteArray())
        assertEquals(MouseTracking.DRAG, e.mouseTracking)
        assertTrue(e.mouseSgr)
        assertTrue(e.bracketedPaste)
    }

    // ---- font scale ----

    @Test fun `pinch scales within the allowed range`() {
        assertEquals(26f, TerminalFontScale.scaled(13f, 2f), 0.001f)
        assertEquals(TerminalFontScale.MAX_SP, TerminalFontScale.scaled(30f, 2f), 0.001f)
        assertEquals(TerminalFontScale.MIN_SP, TerminalFontScale.scaled(9f, 0.1f), 0.001f)
        assertEquals(13f, TerminalFontScale.scaled(13f, Float.NaN), 0.001f)
        assertEquals(13f, TerminalFontScale.scaled(13f, -1f), 0.001f)
    }
}
