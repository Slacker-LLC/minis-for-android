package com.openminis.app.runtime.ubuntu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class RootPersistentShellOutputTest {
    @Test
    fun `shell exit classifier reports signal deaths without guessing normal exits`() {
        assertEquals("SIGKILL", ShellExitClassifier.signalName(137))
        assertEquals("SIGSYS", ShellExitClassifier.signalName(159))
        assertNull(ShellExitClassifier.signalName(0))
        assertNull(ShellExitClassifier.signalName(127))
    }

    @Test
    fun `bounded output preserves head and tail`() {
        val output = BoundedCommandOutput(20)
        output.appendLine("abcdefghij")
        output.appendLine("klmnopqrst")
        output.appendLine("uvwxyz")

        val value = output.value()
        assertTrue(value.startsWith("abcdefghij"))
        assertTrue(value.contains("characters omitted"))
        assertTrue(value.endsWith("uvwxyz\n"))
        assertTrue(value.length < 100)
    }

    @Test
    fun `line reader caps a line while continuing to next line`() {
        val reader = BoundedLineReader(
            StringReader("0123456789abcdef\nnext\r\n"),
            maxChars = 8,
        )

        val first = reader.readLine()!!
        assertEquals("01234567", first.text)
        assertEquals(8L, first.omittedChars)
        assertTrue(first.truncated)

        val second = reader.readLine()!!
        assertEquals("next", second.text)
        assertEquals(0L, second.omittedChars)
        assertFalse(second.truncated)
        assertNull(reader.readLine())
    }

    @Test
    fun `line reader returns final unterminated line`() {
        val reader = BoundedLineReader(StringReader("final"), maxChars = 16)
        val line = reader.readLine()!!
        assertEquals("final", line.text)
        assertFalse(line.truncated)
        assertNull(reader.readLine())
    }
}
