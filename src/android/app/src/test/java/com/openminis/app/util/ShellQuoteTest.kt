package com.openminis.app.util

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

class ShellQuoteTest {

    @Test fun `wraps a plain value in single quotes`() {
        assertEquals("'abc'", shellQuote("abc"))
        assertEquals("''", shellQuote(""))
    }

    @Test fun `an embedded single quote cannot close the word`() {
        assertEquals("'it'\"'\"'s'", shellQuote("it's"))
    }

    @Test fun `hostile values reach the shell as one literal argument`() {
        assumeTrue(File("/bin/sh").canExecute())
        val marker = File.createTempFile("minis-shellquote", ".marker").apply { delete() }
        val hostile = listOf(
            "",
            " leading and trailing ",
            "'",
            "''",
            "a'b\"c",
            "\$(touch ${marker.path})",
            "`touch ${marker.path}`",
            "x; touch ${marker.path}",
            "x' ; touch ${marker.path} ; echo '",
            "x && touch ${marker.path} || touch ${marker.path}",
            "\$HOME \${PATH} \$0 \$@ *",
            "line1\nline2\ttab",
            "-n",
            "\\'\\\\",
            "中文 ✓",
        )
        for (value in hostile) {
            assertEquals(value, echoThroughShell(value))
        }
        assertFalse("a quoted value ran a command", marker.exists())
    }

    /** Runs `printf %s <quoted>` under /bin/sh and returns what the shell passed as that one argument. */
    private fun echoThroughShell(value: String): String {
        val process = ProcessBuilder("/bin/sh", "-c", "printf %s ${shellQuote(value)}")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        check(process.waitFor(10, TimeUnit.SECONDS)) { "sh did not exit" }
        return output
    }
}
