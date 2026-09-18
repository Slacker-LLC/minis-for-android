package com.openminis.app.xposed

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-eta-xposed-entry] Ported from Eta `config/Prefs.kt` (Mangi-11/Eta @ c15de97). Two properties
 * are the point: an installed-but-unconfigured module behaves like an uninstalled one for every
 * switch that would replace a button, and a settings read that fails leaves the system alone.
 */
class ModulePrefsTest {

    @After
    fun tearDown() = ModulePrefs.attach(null)

    @Test
    fun `with no settings every switch reports its own default`() {
        assertFalse(ModulePrefs.isEnabled(ModulePrefs.Keys.POWER_KEY_TAKEOVER))
        assertFalse(ModulePrefs.isEnabled(ModulePrefs.Keys.HOTWORD_SELF_HEAL))
        assertFalse(ModulePrefs.isEnabled(ModulePrefs.Keys.ASSISTANT_AUTO_CONFIG))
        assertTrue(
            "the gesture-bar search routing is what the module is for, so it is on",
            ModulePrefs.isEnabled(ModulePrefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH),
        )
    }

    @Test
    fun `an unknown switch is off rather than assumed`() {
        assertFalse(ModulePrefs.isEnabled("something.that.does.not.exist"))
    }

    @Test
    fun `a settings value wins over the default`() {
        ModulePrefs.attach { key ->
            when (key) {
                ModulePrefs.Keys.POWER_KEY_TAKEOVER -> true
                ModulePrefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH -> false
                else -> null
            }
        }

        assertTrue(ModulePrefs.isEnabled(ModulePrefs.Keys.POWER_KEY_TAKEOVER))
        assertFalse(ModulePrefs.isEnabled(ModulePrefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH))
        assertFalse(
            "a key the settings view does not carry falls back per switch",
            ModulePrefs.isEnabled(ModulePrefs.Keys.HOTWORD_SELF_HEAL),
        )
    }

    @Test
    fun `a settings read that throws leaves the default in place`() {
        ModulePrefs.attach { error("settings backend is gone") }

        assertFalse(ModulePrefs.isEnabled(ModulePrefs.Keys.POWER_KEY_TAKEOVER))
        assertTrue(ModulePrefs.isEnabled(ModulePrefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH))
    }

    @Test
    fun `detaching settings returns every switch to its default`() {
        ModulePrefs.attach { true }
        assertTrue(ModulePrefs.isEnabled(ModulePrefs.Keys.POWER_KEY_TAKEOVER))

        ModulePrefs.attach(null)

        assertFalse(
            "a process that never got settings must not keep a stale answer",
            ModulePrefs.isEnabled(ModulePrefs.Keys.POWER_KEY_TAKEOVER),
        )
    }
}
