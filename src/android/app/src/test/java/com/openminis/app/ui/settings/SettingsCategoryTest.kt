package com.openminis.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Settings home: the board's four, then Files management, then runtime, system and data. */
class SettingsCategoryTest {
    @Test fun rootListsTheCategoriesInOrder() {
        val root = SettingsCategory.rootGroups.flatten()
        assertEquals(
            listOf("models", "agent", "chat", "appearance", "files", "runtime", "system", "data"),
            root.map { it.key },
        )
        assertEquals(listOf(4, 4), SettingsCategory.rootGroups.map { it.size })
    }

    @Test fun filesManagementIsACategoryOnTheHome() {
        assertTrue(SettingsCategory.FILES.inRoot)
        assertTrue(SettingsCategory.rootGroups.flatten().contains(SettingsCategory.FILES))
        assertSame(SettingsCategory.FILES, SettingsCategory.parse("files"))
    }

    @Test fun everyRootCategoryIsMarkedInRoot() {
        assertEquals(
            SettingsCategory.entries.filter { it.inRoot }.toSet(),
            SettingsCategory.rootGroups.flatten().toSet(),
        )
    }

    @Test fun keysAreUniqueAndRoundTrip() {
        val keys = SettingsCategory.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        for (category in SettingsCategory.entries) assertSame(category, SettingsCategory.parse(category.key))
    }

    @Test fun legacyKeysStillResolveAndUnknownKeysDoNot() {
        assertSame(SettingsCategory.AGENT, SettingsCategory.parse("assistant"))
        assertSame(SettingsCategory.DATA, SettingsCategory.parse("about"))
        assertNull(SettingsCategory.parse("nonsense"))
        assertNull(SettingsCategory.parse(null))
        assertNull(SettingsCategory.parse(""))
        assertTrue(SettingsCategory.parse("Models") == null)
    }
}
