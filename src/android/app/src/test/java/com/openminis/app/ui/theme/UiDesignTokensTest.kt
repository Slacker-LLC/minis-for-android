package com.openminis.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the numbers in docs/design/UI-DESIGN-LANGUAGE.md §3 so a colour edit
 * that breaks WCAG AA (4.5:1 for text) fails here instead of shipping.
 */
class UiDesignTokensTest {

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private val white = Color(0xFFFFFFFF)
    private val fill = Color(0xFFF2F2F7)
    private val black = Color(0xFF000000)
    private val darkCard = Color(0xFF1C1C1E)

    @Test
    fun `default accent meets AA on page and fill surfaces`() {
        val light = AccentColor.DEFAULT.light
        val dark = AccentColor.DEFAULT.dark
        assertTrue(contrast(light, white) >= 4.5)
        assertTrue(contrast(light, fill) >= 4.5)
        assertTrue(contrast(dark, black) >= 4.5)
        assertTrue(contrast(dark, darkCard) >= 4.5)
    }

    @Test
    fun `old accent would have failed, so the check can fail`() {
        assertTrue(contrast(Color(0xFF528AD2), white) < 4.5)
    }

    @Test
    fun `secondary and link text meet AA on white and fill`() {
        val p = LightChatPalette
        assertTrue(contrast(p.secondaryText, white) >= 4.5)
        assertTrue(contrast(p.secondaryText, fill) >= 4.5)
        assertTrue(contrast(p.link, white) >= 4.5)
        assertTrue(contrast(p.inlineCodeText, p.inlineCodeBg) >= 4.5)
    }

    @Test
    fun `light palette uses one fill grey and pure white page`() {
        val p = LightChatPalette
        assertEquals(white, p.background)
        assertEquals(fill, p.secondaryBg)
        assertEquals(fill, p.toolBg)
        assertEquals(fill, p.codeBlockBg)
    }

    @Test
    fun `every selectable accent meets AA as a text colour`() {
        // Actions are text buttons, so every accent the picker offers is a label colour.
        val failures = AccentColor.entries.flatMap { a ->
            listOf(
                "${a.name} light/white" to contrast(a.light, white),
                "${a.name} light/fill" to contrast(a.light, fill),
                "${a.name} dark/black" to contrast(a.dark, black),
                "${a.name} dark/card" to contrast(a.dark, darkCard),
            ).filter { it.second < 4.5 }
        }
        assertTrue("accents below 4.5:1: $failures", failures.isEmpty())
    }
}
