package com.openminis.app.ui.terminal.emulator

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The terminal follows the app theme (docs/design/UI-DESIGN-LANGUAGE.md §3). */
class TerminalPaletteTest {

    @After
    fun reset() {
        TerminalPalette.light = false
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    @Test
    fun `dark theme keeps the original defaults`() {
        TerminalPalette.light = false
        assertEquals(Color.Black, TerminalPalette.defaultBackground)
        assertEquals(Color(204, 204, 204), TerminalPalette.defaultForeground)
        assertEquals(
            Color(205, 0, 0),
            TerminalPalette.resolve(TerminalColor.Indexed(1), isForeground = true),
        )
    }

    @Test
    fun `light theme is black on white`() {
        TerminalPalette.light = true
        assertEquals(Color.White, TerminalPalette.defaultBackground)
        assertEquals(Color.Black, TerminalPalette.defaultForeground)
        assertEquals(
            TerminalPalette.defaultBackground,
            TerminalPalette.resolve(TerminalColor.Default, isForeground = false),
        )
    }

    @Test
    fun `every light ANSI colour is readable on white`() {
        val failures = (0 until 16).filter { contrast(TerminalPalette.ansiForTest(it, light = true), Color.White) < 4.5 }
        assertTrue("ANSI colours below 4.5:1 on white: $failures", failures.isEmpty())
    }

    @Test
    fun `the dark ANSI colours would not be, so the check can fail`() {
        // ANSI 7 (light grey) and 11 (bright yellow) are unreadable on white.
        assertTrue(contrast(TerminalPalette.ansiForTest(7, light = false), Color.White) < 4.5)
        assertTrue(contrast(TerminalPalette.ansiForTest(11, light = false), Color.White) < 4.5)
    }

    @Test
    fun `bold promotes standard colours to the bright slot in both themes`() {
        TerminalPalette.light = true
        val boldRed = TerminalPalette.resolve(TerminalColor.Indexed(1), isForeground = true, bold = true)
        assertEquals(TerminalPalette.ansiForTest(9, light = true), boldRed)
        TerminalPalette.light = false
        val darkBoldRed = TerminalPalette.resolve(TerminalColor.Indexed(1), isForeground = true, bold = true)
        assertNotEquals(boldRed, darkBoldRed)
    }

    @Test
    fun `explicit RGB colours are never remapped`() {
        TerminalPalette.light = true
        assertEquals(
            Color(10, 20, 30),
            TerminalPalette.resolve(TerminalColor.Rgb(10, 20, 30), isForeground = true),
        )
    }
}
