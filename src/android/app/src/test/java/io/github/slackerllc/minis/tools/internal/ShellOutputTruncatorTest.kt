package io.github.slackerllc.minis.tools.internal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class ShellOutputTruncatorTest {
    @Test fun leavesSmallOutputUntouched() {
        val r = ShellOutputTruncator.truncateTail("a\nb", maxLines = 10, maxBytes = 100)
        assertFalse(r.truncated)
        assertEquals("a\nb", r.output)
    }

    @Test fun keepsTailByLineCount() {
        val r = ShellOutputTruncator.truncateTail((1..20).joinToString("\n"), maxLines = 3, maxBytes = 1000)
        assertTrue(r.truncated)
        assertEquals("18\n19\n20", r.output)
    }

    @Test fun enforcesByteLimit() {
        val r = ShellOutputTruncator.truncateTail("head\n" + "x".repeat(200), maxLines = 20, maxBytes = 32)
        assertTrue(r.truncated)
        assertTrue(r.output.toByteArray(Charsets.UTF_8).size <= 32)
    }
    @Test fun doesNotSplitUtf8Characters() {
        val r = ShellOutputTruncator.truncateTail("head\n" + "你".repeat(200), maxLines = 20, maxBytes = 32)
        assertTrue(r.truncated)
        assertTrue(r.output.toByteArray(Charsets.UTF_8).size <= 32)
        assertFalse(r.output.contains('\uFFFD'))
    }

}
