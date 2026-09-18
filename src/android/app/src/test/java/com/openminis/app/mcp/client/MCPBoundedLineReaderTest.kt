package com.openminis.app.mcp.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

/**
 * [T-mcp-stdio-line-bound-android] The bound the stdio transport's line reads were
 * missing: a length check after `BufferedReader.readLine()` runs too late, because the
 * whole line is already in memory by then. These cases pin the reading behaviour that
 * makes the cap real.
 */
class MCPBoundedLineReaderTest {

    private fun reader(text: String, limit: Int = 64) =
        MCPBoundedLineReader(StringReader(text), limit = limit, source = "test-server")

    @Test
    fun `lines come back one at a time and then the stream ends`() {
        val lines = reader("first\nsecond\n")

        assertEquals("first", lines.readLine())
        assertEquals("second", lines.readLine())
        assertNull(lines.readLine())
    }

    @Test
    fun `a last line without a newline still counts`() {
        val lines = reader("only one")

        assertEquals("only one", lines.readLine())
        assertNull(lines.readLine())
    }

    @Test
    fun `windows line endings do not leak into the payload`() {
        val lines = reader("alpha\r\nbeta\r\n")

        assertEquals("alpha", lines.readLine())
        assertEquals("beta", lines.readLine())
    }

    @Test
    fun `an empty line is a line, not the end of the stream`() {
        val lines = reader("\n\n")

        assertEquals("", lines.readLine())
        assertEquals("", lines.readLine())
        assertNull(lines.readLine())
    }

    @Test
    fun `a line exactly at the limit is accepted`() {
        val line = "a".repeat(64)
        val lines = reader("$line\n")

        assertEquals(line, lines.readLine())
    }

    @Test
    fun `a line past the limit is refused while it is still being read`() {
        val lines = reader("b".repeat(65) + "\n")

        val failure = runCatching { lines.readLine() }.exceptionOrNull()

        assertTrue(failure is MCPTransportException)
        assertTrue(failure!!.message.orEmpty().contains("test-server"))
        assertTrue(failure.message.orEmpty().contains("64"))
    }

    @Test
    fun `a flood with no newline at all is refused too`() {
        val lines = reader("c".repeat(5_000_000), limit = 64)

        val failure = runCatching { lines.readLine() }.exceptionOrNull()

        assertTrue(failure is MCPTransportException)
    }

    @Test
    fun `after an oversized line is skipped, the next line is readable again`() {
        val lines = reader("d".repeat(100) + "\nkept\n", limit = 64)

        runCatching { lines.readLine() }
        lines.skipToEndOfLine()

        assertEquals("kept", lines.readLine())
    }
}
