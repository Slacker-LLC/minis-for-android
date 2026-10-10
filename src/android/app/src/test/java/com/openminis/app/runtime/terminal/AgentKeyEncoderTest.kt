package com.openminis.app.runtime.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentKeyEncoderTest {
    private fun enc(spec: String, app: Boolean = false) = AgentKeyEncoder.encode(spec, app)?.toString(Charsets.ISO_8859_1)

    @Test
    fun `plain keys`() {
        assertEquals("\r", enc("Enter"))
        assertEquals("\t", enc("tab"))
        assertEquals("\u001B", enc("Escape"))
        assertEquals("\u007F", enc("Backspace"))
        assertEquals("\u001B[3~", enc("Delete"))
        assertEquals("\u001B[5~", enc("PageUp"))
        assertEquals("\u001B[Z", enc("Shift-Tab"))
        assertEquals("\u001BOP", enc("F1"))
        assertEquals("\u001B[15~", enc("F5"))
    }

    @Test
    fun `arrows follow the application cursor mode the program asked for`() {
        assertEquals("\u001B[A", enc("Up"))
        assertEquals("\u001BOA", enc("Up", app = true))
        assertEquals("\u001B[1;5D", enc("Ctrl-Left", app = true))
    }

    @Test
    fun `control and alt on single characters`() {
        assertEquals("\u0003", enc("Ctrl-C"))
        assertEquals("\u0004", enc("C-d"))
        assertEquals("\u0003", enc("^c"))
        assertEquals("\u0000", enc("Ctrl-@"))
        assertEquals("\u001Bb", enc("Alt-b"))
        assertEquals("\u001B\u0003", enc("Alt-Ctrl-c"))
        assertEquals("x", enc("x"))
        assertEquals("\u001B\r", enc("Alt-Enter"))
    }

    @Test
    fun `unknown specs are reported and send nothing`() {
        assertNull(enc("Ctrl-Wibble"))
        assertNull(enc("Hyper-x"))
        val result = AgentKeyEncoder.encodeAll("Down Wobble Enter", false)
        assertEquals(listOf("Wobble"), result.unknown)
        assertArrayEquals("\u001B[B\r".toByteArray(), result.bytes)
    }

    @Test
    fun `text newlines are Enter, a pasted block keeps its inner newlines`() {
        assertEquals("ls\r", String(AgentKeyEncoder.encodeText("ls\n", bracketedPaste = false)))
        assertEquals("ls\r", String(AgentKeyEncoder.encodeText("ls\n", bracketedPaste = true)))
        assertEquals("a\rb", String(AgentKeyEncoder.encodeText("a\nb", bracketedPaste = false)))
        assertEquals(
            "\u001B[200~line one\nline two\u001B[201~\r",
            String(AgentKeyEncoder.encodeText("line one\nline two\n", bracketedPaste = true)),
        )
        assertEquals("a\rb", String(AgentKeyEncoder.encodeText("a\r\nb", bracketedPaste = false)))
    }
}
