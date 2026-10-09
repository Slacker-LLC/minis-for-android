package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortFileNameTest {
    @Test
    fun aShortNameIsLeftAlone() {
        assertEquals("data.csv", shortFileName("data.csv"))
    }

    @Test
    fun aLongNameKeepsItsExtension() {
        val shown = shortFileName("ssd_256gb_market_comparison_full_list_oct2026.csv")
        assertTrue(shown, shown.endsWith(".csv"))
        assertTrue(shown, shown.length <= 22)
        assertTrue(shown, shown.contains("…"))
    }

    @Test
    fun aLongNameWithoutExtensionIsCut() {
        val shown = shortFileName("a".repeat(60))
        assertTrue(shown.length <= 22)
    }
}
