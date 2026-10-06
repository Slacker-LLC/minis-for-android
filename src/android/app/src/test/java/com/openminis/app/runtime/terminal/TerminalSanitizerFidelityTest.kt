package com.openminis.app.runtime.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

/** The sanitizer removes terminal control sequences, nothing else. */
class TerminalSanitizerFidelityTest {
    private val esc = "\u001B"

    @Test
    fun theTextNullIsOrdinaryOutput() {
        assertEquals("null", TerminalSanitizer.sanitize("null"))
        assertEquals("a\nnull\nb", TerminalSanitizer.sanitize("a\nnull\nb"))
        assertEquals("nullnull", TerminalSanitizer.sanitize("nullnull"))
        assertEquals("val x = nullnullnull", TerminalSanitizer.sanitize("val x = nullnullnull"))
    }

    @Test
    fun aRealNulByteIsStillRemoved() {
        assertEquals("ab", TerminalSanitizer.sanitize("a\u0000b"))
    }

    @Test
    fun aColouredLongLineKeepsAllItsText() {
        val plain = "A".repeat(5_000)
        assertEquals(plain, TerminalSanitizer.sanitize(plain))
        assertEquals(plain, TerminalSanitizer.sanitize("$esc[31m$plain$esc[0m"))
        assertEquals(5_000, TerminalSanitizer.sanitize("$esc[31m$plain$esc[0m").length)
    }

    @Test
    fun theBoundaryColumnsAreNotSpecial() {
        for (n in listOf(4095, 4096, 4097, 20_000)) {
            val text = "x".repeat(n)
            assertEquals("n=$n", n, TerminalSanitizer.sanitize("$esc[1m$text").length)
        }
    }

    @Test
    fun anUnknownEscapeDoesNotSwallowTheTextAfterIt() {
        // "!" is not a supported escape; the text up to the next real sequence must survive.
        assertEquals("!KEEPRED", TerminalSanitizer.sanitize("$esc!KEEP$esc[31mRED"))
    }

    @Test
    fun aLoneTrailingEscapeIsDroppedAndTextIsKept() {
        assertEquals("abc", TerminalSanitizer.sanitize("abc$esc"))
        assertEquals("-keep", TerminalSanitizer.sanitize("${esc}${esc}${esc}-keep"))
    }

    @Test
    fun carriageReturnOverwriteAndColourStillWork() {
        assertEquals("BBAA", TerminalSanitizer.sanitize("AAAA\rBB"))
        assertEquals("RED", TerminalSanitizer.sanitize("$esc[31mRED$esc[0m"))
        assertEquals("done   ", TerminalSanitizer.sanitize("working\rdone$esc[K").trimEnd('\n').let { it.take(7) })
    }
}
