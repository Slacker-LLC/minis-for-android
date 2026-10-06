package com.openminis.app.data.repository

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** A write to the settings file by something other than the repository (minis-config) reaches the flows. */
@RunWith(AndroidJUnit4::class)
class BackgroundSettingsPrefsSyncTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = context.getSharedPreferences("background_settings", Context.MODE_PRIVATE)
    private var saved: Map<String, *> = emptyMap<String, Any>()

    @Before
    fun setUp() {
        saved = prefs.all
        prefs.edit().clear().commit()
    }

    @After
    fun tearDown() {
        val e = prefs.edit().clear()
        saved.forEach { (k, v) ->
            when (v) {
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
            }
        }
        e.commit()
    }

    /** Listeners are called on the main thread after the write, so poll briefly. */
    private fun <T> awaitValue(expected: T, read: () -> T) {
        val deadline = System.currentTimeMillis() + 3_000
        while (read() != expected && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(expected, read())
    }

    @Test
    fun aCliWriteOfTheNotificationKeyChangesTheLiveFlow() {
        val repo = BackgroundSettingsRepository(context)
        assertEquals(true, repo.taskNotificationsEnabled.value)

        prefs.edit().putBoolean("taskNotificationsEnabled", false).commit()

        awaitValue(false) { repo.taskNotificationsEnabled.value }
    }

    @Test
    fun aCliWriteOfLiveUpdatesChangesTheLiveFlow() {
        val repo = BackgroundSettingsRepository(context)
        assertEquals(false, repo.dynamicIslandEnabled.value)

        prefs.edit().putBoolean("dynamicIslandEnabled", true).commit()
        awaitValue(true) { repo.dynamicIslandEnabled.value }

        prefs.edit().putBoolean("dynamicIslandEnabled", false).commit()
        awaitValue(false) { repo.dynamicIslandEnabled.value }
    }

    @Test
    fun theRepositorysOwnSetterStillWorks() {
        val repo = BackgroundSettingsRepository(context)
        repo.setBackgroundOverlayEnabled(true)
        assertEquals(true, repo.backgroundOverlayEnabled.value)
        assertEquals(true, prefs.getBoolean("backgroundOverlayEnabled", false))
    }
}
