package com.openminis.app.ui.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalKeysTest {
    private fun b(s: String) = s.toByteArray()

    @Test fun ctrlTurnsLettersIntoControlBytes() {
        val r = applyTerminalModifiers(b("c"), ctrl = true, alt = false)
        assertArrayEquals(byteArrayOf(3), r.bytes)
        assertTrue(r.ctrlUsed)
        assertArrayEquals(byteArrayOf(4), applyTerminalModifiers(b("D"), true, false).bytes)
    }

    @Test fun ctrlHandlesTheSymbolsTerminalsUse() {
        assertArrayEquals(byteArrayOf(27), applyTerminalModifiers(b("["), true, false).bytes)
        assertArrayEquals(byteArrayOf(31), applyTerminalModifiers(b("_"), true, false).bytes)
    }

    @Test fun ctrlLeavesOtherInputAloneAndStaysArmed() {
        val digit = applyTerminalModifiers(b("5"), ctrl = true, alt = false)
        assertArrayEquals(b("5"), digit.bytes)
        assertFalse("Ctrl did nothing, so it must not be reported as used", digit.ctrlUsed)
        val word = applyTerminalModifiers(b("ls"), ctrl = true, alt = false)
        assertArrayEquals(b("ls"), word.bytes)
        assertFalse(word.ctrlUsed)
    }

    @Test fun altPrefixesEscape() {
        val r = applyTerminalModifiers(b("b"), ctrl = false, alt = true)
        assertArrayEquals(byteArrayOf(0x1B, 'b'.code.toByte()), r.bytes)
        assertTrue(r.altUsed)
    }

    @Test fun ctrlAndAltCombine() {
        val r = applyTerminalModifiers(b("h"), ctrl = true, alt = true)
        assertArrayEquals(byteArrayOf(0x1B, 8), r.bytes)
        assertTrue(r.ctrlUsed && r.altUsed)
    }

    @Test fun noModifiersAndEmptyInputAreUntouched() {
        assertArrayEquals(b("x"), applyTerminalModifiers(b("x"), false, false).bytes)
        val empty = applyTerminalModifiers(ByteArray(0), ctrl = true, alt = true)
        assertArrayEquals(ByteArray(0), empty.bytes)
        assertFalse(empty.altUsed)
    }
}
