package com.openminis.app.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Regression coverage for theme-sensitive KaTeX bitmap cache keys. */
class KaTeXCacheKeyThemeTest {

    @Test
    fun `light and dark keys differ for the same formula`() {
        assertNotEquals(
            KaTeXRendererCache.cacheKey("E = mc^2", displayMode = true, isDark = false),
            KaTeXRendererCache.cacheKey("E = mc^2", displayMode = true, isDark = true),
        )
    }

    @Test
    fun `display and inline keys remain separate`() {
        assertNotEquals(
            KaTeXRendererCache.cacheKey("x^2", displayMode = true, isDark = true),
            KaTeXRendererCache.cacheKey("x^2", displayMode = false, isDark = true),
        )
    }

    @Test
    fun `identical inputs keep a stable key`() {
        assertEquals(
            KaTeXRendererCache.cacheKey("\\sum_{i=1}^{n} i", displayMode = true, isDark = false),
            KaTeXRendererCache.cacheKey("\\sum_{i=1}^{n} i", displayMode = true, isDark = false),
        )
    }
}
