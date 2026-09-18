package com.openminis.app.xposed

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-eta-xposed-entry] Ported from Eta `config/Prefs.kt` (Mangi-11/Eta @ c15de97). Two properties
 * are the point: an installed-but-unconfigured module behaves like an uninstalled one for every
 * switch that would replace a button, and a settings read that fails leaves the system alone.
 */
class ModulePrefsTest {

    /** A settings view that only carries booleans, the way a hooked process sees it. */
    private fun reader(booleans: Map<String, Boolean>) = object : ModulePrefReader {
        override fun getBoolean(key: String): Boolean? = booleans[key]

        override fun getString(key: String): String? = null
    }

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
        ModulePrefs.attach(
            reader(
                mapOf(
                    ModulePrefs.Keys.POWER_KEY_TAKEOVER to true,
                    ModulePrefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH to false,
                ),
            ),
        )

        assertTrue(ModulePrefs.isEnabled(ModulePrefs.Keys.POWER_KEY_TAKEOVER))
        assertFalse(ModulePrefs.isEnabled(ModulePrefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH))
        assertFalse(
            "a key the settings view does not carry falls back per switch",
            ModulePrefs.isEnabled(ModulePrefs.Keys.HOTWORD_SELF_HEAL),
        )
    }

    @Test
    fun `a settings read that throws leaves the default in place`() {
        ModulePrefs.attach(object : ModulePrefReader {
            override fun getBoolean(key: String): Boolean = error("settings backend is gone")

            override fun getString(key: String): String = error("settings backend is gone")
        })

        assertFalse(ModulePrefs.isEnabled(ModulePrefs.Keys.POWER_KEY_TAKEOVER))
        assertTrue(ModulePrefs.isEnabled(ModulePrefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH))
    }

    @Test
    fun `detaching settings returns every switch to its default`() {
        ModulePrefs.attach(object : ModulePrefReader {
            override fun getBoolean(key: String): Boolean = true

            override fun getString(key: String): String = "value"
        })
        assertTrue(ModulePrefs.isEnabled(ModulePrefs.Keys.POWER_KEY_TAKEOVER))

        ModulePrefs.attach(null)

        assertFalse(
            "a process that never got settings must not keep a stale answer",
            ModulePrefs.isEnabled(ModulePrefs.Keys.POWER_KEY_TAKEOVER),
        )
    }

    @Test
    fun `a string setting falls back when settings are absent or blank`() {
        assertEquals(
            "oem",
            ModulePrefs.string(ModulePrefs.Keys.POWER_KEY_ASSISTANT_TARGET, "oem"),
        )

        ModulePrefs.attach(object : ModulePrefReader {
            override fun getBoolean(key: String): Boolean? = null

            override fun getString(key: String): String = "  "
        })

        assertEquals(
            "a blank value is not a choice",
            "oem",
            ModulePrefs.string(ModulePrefs.Keys.POWER_KEY_ASSISTANT_TARGET, "oem"),
        )
    }

    @Test
    fun `a string setting is returned when the settings view carries it`() {
        ModulePrefs.attach(object : ModulePrefReader {
            override fun getBoolean(key: String): Boolean? = null

            override fun getString(key: String): String = "minis"
        })

        assertEquals(
            "minis",
            ModulePrefs.string(ModulePrefs.Keys.POWER_KEY_ASSISTANT_TARGET, "oem"),
        )
    }
}
