package com.openminis.app.xposed

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-eta-xposed-groups] The store that writes the module's settings.
 *
 * The property under test is the one this repo got wrong: the switches live in the framework's
 * store, not in the app's own preferences file, so a write that the framework did not accept must
 * never be mirrored as if it had been - and a value a user set before the framework was connected
 * must not be lost when it arrives.
 */
class ModuleSettingsStoreTest {

    /** The smallest thing that behaves like SharedPreferences and can refuse a commit. */
    private class FakePrefs(
        private val values: MutableMap<String, Any?> = mutableMapOf(),
        private val commitResult: Boolean = true,
    ) : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = values.toMutableMap()

        override fun getString(key: String, defValue: String?): String? =
            values[key] as? String ?: defValue

        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST")
            (values[key] as? MutableSet<String>) ?: defValues

        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue

        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue

        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue

        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            values[key] as? Boolean ?: defValue

        override fun contains(key: String): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor(values, commitResult)

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        fun value(key: String): Any? = values[key]
    }

    private class FakeEditor(
        private val values: MutableMap<String, Any?>,
        private val commitResult: Boolean,
    ) : SharedPreferences.Editor {
        private val staged = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()

        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
            staged[key] = value
        }

        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor =
            apply { staged[key] = values }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { staged[key] = value }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { staged[key] = value }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { staged[key] = value }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { staged[key] = value }

        override fun remove(key: String): SharedPreferences.Editor = apply { removals.add(key) }

        override fun clear(): SharedPreferences.Editor = apply {
            values.keys.toList().forEach(removals::add)
        }

        override fun commit(): Boolean {
            if (!commitResult) return false
            removals.forEach(values::remove)
            values.putAll(staged)
            return true
        }

        override fun apply() {
            if (commitResult) commit()
        }
    }

    @Test
    fun `the framework store is what is read while it is connected`() {
        val remote = FakePrefs(mutableMapOf(ModulePrefs.Keys.HOTWORD_SELF_HEAL to true))
        val mirror = FakePrefs(mutableMapOf(ModulePrefs.Keys.HOTWORD_SELF_HEAL to false))

        assertTrue(
            ModuleSettingsStore.readBoolean(
                remote,
                mirror,
                ModulePrefs.Keys.HOTWORD_SELF_HEAL,
                default = false,
            ),
        )
        assertTrue(
            "a key only the mirror carries is not reported while the framework is connected",
            ModuleSettingsStore.readBoolean(
                remote,
                mirror,
                ModulePrefs.Keys.POWER_KEY_TAKEOVER,
                default = false,
            ).not(),
        )
    }

    @Test
    fun `without the framework the mirror keeps the screen honest`() {
        val mirror = FakePrefs(mutableMapOf(ModulePrefs.Keys.POWER_KEY_ASSISTANT_TARGET to "minis"))

        assertEquals(
            "minis",
            ModuleSettingsStore.readString(null, mirror, ModulePrefs.Keys.POWER_KEY_ASSISTANT_TARGET),
        )
        assertFalse(
            ModuleSettingsStore.readBoolean(null, mirror, ModulePrefs.Keys.HOTWORD_SELF_HEAL, false),
        )
        assertTrue(
            "with neither store the switch reports the module's own default",
            ModuleSettingsStore.readBoolean(null, null, ModulePrefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH, true),
        )
        assertNull(ModuleSettingsStore.readString(null, null, ModulePrefs.Keys.POWER_KEY_ASSISTANT_TARGET))
    }

    @Test
    fun `a write without the framework is refused instead of pretended`() {
        val mirror = FakePrefs()

        assertFalse(
            ModuleSettingsStore.writeBoolean(null, mirror, ModulePrefs.Keys.HOTWORD_SELF_HEAL, true),
        )
        assertNull("nothing may be mirrored for a write that never happened", mirror.value(ModulePrefs.Keys.HOTWORD_SELF_HEAL))
    }

    @Test
    fun `an accepted write lands in the framework store and in the mirror`() {
        val remote = FakePrefs()
        val mirror = FakePrefs()

        assertTrue(
            ModuleSettingsStore.writeBoolean(remote, mirror, ModulePrefs.Keys.HOTWORD_SELF_HEAL, true),
        )
        assertEquals(true, remote.value(ModulePrefs.Keys.HOTWORD_SELF_HEAL))
        assertEquals(true, mirror.value(ModulePrefs.Keys.HOTWORD_SELF_HEAL))
    }

    @Test
    fun `a refused commit leaves both stores untouched`() {
        val remote = FakePrefs(commitResult = false)
        val mirror = FakePrefs()

        assertFalse(
            ModuleSettingsStore.writeString(
                remote,
                mirror,
                ModulePrefs.Keys.POWER_KEY_ASSISTANT_TARGET,
                "minis",
            ),
        )
        assertNull(remote.value(ModulePrefs.Keys.POWER_KEY_ASSISTANT_TARGET))
        assertNull(mirror.value(ModulePrefs.Keys.POWER_KEY_ASSISTANT_TARGET))
    }

    @Test
    fun `the first bind carries local values into the framework and copies back the rest`() {
        val mirror = FakePrefs(
            mutableMapOf(
                ModulePrefs.Keys.POWER_KEY_ASSISTANT_TARGET to "minis",
                ModulePrefs.Keys.DOUBLE_FINGER_CIRCLE_TO_SEARCH to true,
            ),
        )
        val remote = FakePrefs(mutableMapOf(ModulePrefs.Keys.HOTWORD_SELF_HEAL to true))

        ModuleSettingsStore.reconcile(
            mirror = mirror,
            remote = remote,
            booleanKeys = ModuleSettingsStore.SWITCHES,
            stringKeys = ModuleSettingsStore.STRING_KEYS,
        )

        assertEquals(
            "a value the user chose before the framework was connected must survive",
            "minis",
            remote.value(ModulePrefs.Keys.POWER_KEY_ASSISTANT_TARGET),
        )
        assertEquals(true, remote.value(ModulePrefs.Keys.DOUBLE_FINGER_CIRCLE_TO_SEARCH))
        assertEquals(
            "a value only the framework knows is copied back so the screen agrees with the hooks",
            true,
            mirror.value(ModulePrefs.Keys.HOTWORD_SELF_HEAL),
        )
    }

    @Test
    fun `reconcile tolerates either store being absent`() {
        ModuleSettingsStore.reconcile(null, FakePrefs(), ModuleSettingsStore.SWITCHES, ModuleSettingsStore.STRING_KEYS)
        ModuleSettingsStore.reconcile(FakePrefs(), null, ModuleSettingsStore.SWITCHES, ModuleSettingsStore.STRING_KEYS)
    }
}

