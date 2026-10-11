package com.openminis.app.ui.terminal.emulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class TerminalOscTest {
    private fun b64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray())

    private fun clipboardAfter(input: String): List<String> {
        val em = TerminalEmulator(40, 4)
        val got = ArrayList<String>()
        em.onClipboardSet = { got.add(it) }
        em.feed(input.toByteArray())
        return got
    }

    // ── OSC 52: clipboard ──────────────────────────────────────────────────

    @Test
    fun aProgramCanPutTextOnTheClipboard() {
        assertEquals(listOf("hello 世界"), clipboardAfter("\u001B]52;c;${b64("hello 世界")}\u0007"))
        assertEquals(listOf("x"), clipboardAfter("\u001B]52;;${b64("x")}\u001B\\"))
    }

    @Test
    fun aClipboardQueryIsNeverAnswered() {
        val em = TerminalEmulator(40, 4)
        val replies = StringBuilder()
        em.onResponse = { replies.append(String(it)) }
        val got = ArrayList<String>()
        em.onClipboardSet = { got.add(it) }
        em.feed("\u001B]52;c;?\u0007".toByteArray())
        assertEquals("a terminal that answered would let any program read what was copied", "", replies.toString())
        assertTrue(got.isEmpty())
    }

    @Test
    fun badOrHugeClipboardDataIsDropped() {
        assertTrue(clipboardAfter("\u001B]52;c;!!!not base64!!!\u0007").isEmpty())
        assertTrue(clipboardAfter("\u001B]52;c;\u0007").isEmpty())
        assertTrue(clipboardAfter("\u001B]52;c\u0007").isEmpty())
        // The parser drops an oversize OSC whole; a payload under its limit but over the clipboard limit does not exist,
        // so the limit that matters is the parser's: nothing arrives.
        assertTrue(clipboardAfter("\u001B]52;c;" + "A".repeat(5000) + "\u0007").isEmpty())
    }

    @Test
    fun noCallbackMeansTheRequestIsSimplyIgnored() {
        val em = TerminalEmulator(40, 4)
        em.feed("\u001B]52;c;${b64("x")}\u0007ok".toByteArray())
        assertEquals("ok", em.screenText())
    }

    // ── OSC 8: hyperlinks ──────────────────────────────────────────────────

    private fun linkAt(em: TerminalEmulator, col: Int) = em.activeBuffer.grid[0][col].link

    @Test
    fun linkedTextCarriesItsUrlAndTheTextAroundItDoesNot() {
        val em = TerminalEmulator(40, 4)
        em.feed("a\u001B]8;;https://example.com/x\u001B\\link\u001B]8;;\u001B\\b".toByteArray())
        assertEquals("alinkb", em.screenText())
        assertNull(linkAt(em, 0))
        assertEquals("https://example.com/x", linkAt(em, 1))
        assertEquals("https://example.com/x", linkAt(em, 4))
        assertNull(linkAt(em, 5))
    }

    @Test
    fun anIdInTheParametersIsAccepted() {
        val em = TerminalEmulator(40, 4)
        em.feed("\u001B]8;id=7;http://localhost:3000\u0007go\u001B]8;;\u0007".toByteArray())
        assertEquals("http://localhost:3000", linkAt(em, 0))
    }

    @Test
    fun onlyWebLinksAreKept() {
        for (bad in listOf("javascript:alert(1)", "file:///data/x", "intent://x#Intent;end", "tel:10086", "ftp://x")) {
            val em = TerminalEmulator(40, 4)
            em.feed("\u001B]8;;$bad\u0007t\u001B]8;;\u0007".toByteArray())
            assertNull(bad, linkAt(em, 0))
        }
    }

    @Test
    fun aResetOfTheAttributesDoesNotEndALink() {
        val em = TerminalEmulator(40, 4)
        em.feed("\u001B]8;;https://a.com\u0007\u001B[1mx\u001B[0my".toByteArray())
        assertEquals("https://a.com", linkAt(em, 0))
        assertEquals("https://a.com", linkAt(em, 1))
    }

    @Test
    fun aHardResetEndsTheLink() {
        val em = TerminalEmulator(40, 4)
        em.feed("\u001B]8;;https://a.com\u0007x\u001Bcy".toByteArray())
        // The reset homes the cursor, so y lands on the first cell, no longer inside the link.
        assertEquals('y'.code, em.activeBuffer.grid[0][0].char)
        assertNull(em.activeBuffer.grid[0][0].link)
    }
}
