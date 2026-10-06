package com.openminis.app.data.repository

import com.openminis.app.data.repository.AppIconRepository.Variant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppIconSwitchTest {
    /** A package manager stand-in: which aliases are enabled, with an optional failing call. */
    private class Fake(start: Variant, val failOnCall: Int = -1) {
        val enabled = Variant.entries.associateWith { it == start }.toMutableMap()
        val calls = mutableListOf<Pair<Variant, Boolean>>()
        fun set(v: Variant, on: Boolean) {
            calls += v to on
            if (calls.size - 1 == failOnCall) error("package manager refused")
            enabled[v] = on
        }
    }

    @Test
    fun `the target is enabled before anything is disabled`() {
        val f = Fake(Variant.Auto)
        assertTrue(AppIconRepository.switchAliases(Variant.Auto, Variant.ClassicDark, f::set))
        assertEquals(Variant.ClassicDark to true, f.calls.first())
        assertEquals(setOf(Variant.ClassicDark), f.enabled.filterValues { it }.keys)
    }

    @Test
    fun `a failure part-way restores the previous arrangement and never leaves no entry point`() {
        // Call 0 enables the target, call 1 (disabling Auto) fails.
        val f = Fake(Variant.Auto, failOnCall = 1)
        assertFalse(AppIconRepository.switchAliases(Variant.Auto, Variant.ClassicDark, f::set))
        assertEquals("Auto is back, the others off", setOf(Variant.Auto), f.enabled.filterValues { it }.keys)
    }

    @Test
    fun `a failure on the first call changes nothing that matters`() {
        val f = Fake(Variant.ClassicLight, failOnCall = 0)
        assertFalse(AppIconRepository.switchAliases(Variant.ClassicLight, Variant.Auto, f::set))
        assertEquals(setOf(Variant.ClassicLight), f.enabled.filterValues { it }.keys)
    }
}
