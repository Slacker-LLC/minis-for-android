package com.openminis.app.tools.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-eta-app-search] Ported from Eta's `search_apps` (Mangi-11/Eta @ c15de97). The listing
 * Android returns is the input; what matches, in which order and how much survives the cap
 * is this policy's contract.
 */
class AppSearchPolicyTest {

    private val settings = AppSearchPolicy.Entry("com.android.settings", "Settings", "com.android.settings.Settings")
    private val telegram = AppSearchPolicy.Entry("org.telegram.messenger", "Telegram", "org.telegram.ui.LaunchActivity")
    private val telegramX = AppSearchPolicy.Entry("org.thunderdog.challegram", "Telegram X", "org.thunderdog.challegram.MainActivity")
    private val entries = listOf(telegram, settings, telegramX)

    @Test
    fun `limit defaults and clamps`() {
        assertEquals(AppSearchPolicy.DEFAULT_LIMIT, AppSearchPolicy.clampLimit(null))
        assertEquals(1, AppSearchPolicy.clampLimit(0))
        assertEquals(AppSearchPolicy.MAX_LIMIT, AppSearchPolicy.clampLimit(5_000))
        assertEquals(7, AppSearchPolicy.clampLimit(7))
    }

    @Test
    fun `queries match label or package and ignore case`() {
        assertTrue(AppSearchPolicy.matches(telegram, "tele"))
        assertTrue(AppSearchPolicy.matches(telegram, "TELEGRAM"))
        assertTrue(AppSearchPolicy.matches(telegram, "messenger"))
        assertFalse(AppSearchPolicy.matches(telegram, "settings"))
        assertTrue("a blank query keeps everything", AppSearchPolicy.matches(telegram, null))
        assertTrue(AppSearchPolicy.matches(telegram, "   "))
    }

    @Test
    fun `ranking filters sorts by label and caps`() {
        val ranked = AppSearchPolicy.rank(entries, "telegram", 10)

        assertEquals(listOf("Telegram", "Telegram X"), ranked.map { it.label })
    }

    @Test
    fun `the cap keeps the first rows of a stable order`() {
        val ranked = AppSearchPolicy.rank(entries, null, 2)

        assertEquals(listOf("Settings", "Telegram"), ranked.map { it.label })
    }

    @Test
    fun `entries with an identical label fall back to package order`() {
        val alpha = AppSearchPolicy.Entry("com.example.alpha", "Same", "A")
        val beta = AppSearchPolicy.Entry("com.example.beta", "Same", "B")

        assertEquals(
            listOf(alpha, beta),
            AppSearchPolicy.rank(listOf(beta, alpha), null, 10),
        )
    }
}
