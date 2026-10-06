package com.openminis.app.ui.terminal.emulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Terminal output is untrusted: a short byte string must not hang the UI thread or crash the renderer. */
class TerminalEmulatorRobustnessTest {

    private fun TerminalEmulator.feedText(s: String) = feed(s.toByteArray(Charsets.ISO_8859_1))

    private fun TerminalEmulator.allChars(): List<Int> =
        activeBuffer.grid.flatMap { row -> row.map { it.char } }


    @Test
    fun hugeScrollCountsFinishQuicklyAndLeaveABlankScreen() {
        val emulator = TerminalEmulator(80, 24)
        emulator.feedText("hello")
        val started = System.nanoTime()
        emulator.feedText("\u001B[2147483647S\u001B[2147483647T\u001B[99999999999999999999S")
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("took ${elapsedMs}ms", elapsedMs < 1_000)
        assertTrue(emulator.allChars().all { it == ' '.code })
    }

    @Test
    fun hugeForwardTabCountIsBoundedByTheWidth() {
        val emulator = TerminalEmulator(80, 24)
        val started = System.nanoTime()
        emulator.feedText("\u001B[2147483647I")
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000)
        assertEquals(79, emulator.activeBuffer.cursorCol)
    }

    @Test
    fun anOverflowingParameterDoesNotWrapNegative() {
        val emulator = TerminalEmulator(80, 24)
        emulator.feedText("\u001B[99999999999999999999;99999999999999999999H")
        assertEquals(79, emulator.activeBuffer.cursorCol)
        assertEquals(23, emulator.activeBuffer.cursorRow)
    }

    @Test
    fun normalScrollStillScrolls() {
        val emulator = TerminalEmulator(10, 3)
        emulator.feedText("a\r\nb\r\nc\u001B[1S")
        assertEquals("b", emulator.activeBuffer.grid[0][0].char.toChar().toString())
    }

    @Test
    fun invalidUtf8CodePointsBecomeTheReplacementCharacter() {
        val emulator = TerminalEmulator(80, 24)
        // F4 90 80 80 = U+110000; ED A0 80 = a surrogate; C0 80 = overlong NUL.
        emulator.feed(byteArrayOf(0xF4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()))
        emulator.feed(byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()))
        emulator.feed(byteArrayOf(0xC0.toByte(), 0x80.toByte()))
        val printed = emulator.allChars().filter { it != ' '.code }
        assertEquals(listOf(0xFFFD, 0xFFFD, 0xFFFD), printed)
        // The renderer builds a String from each code point; none of them may throw.
        printed.forEach { String(intArrayOf(it), 0, 1) }
    }

    @Test
    fun validMultiByteCharactersAreUntouched() {
        val emulator = TerminalEmulator(80, 24)
        emulator.feed("é€😀".toByteArray(Charsets.UTF_8))
        val printed = emulator.allChars().filter { it != ' '.code && it != 0 }
        assertTrue(printed.containsAll(listOf(0xE9, 0x20AC, 0x1F600)))
    }
}
