package com.openminis.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Settings home must stay the seven categories of the design board, in the board's order. */
class SettingsCategoryTest {
    @Test fun rootListsTheSevenDesignCategoriesInOrder() {
        val root = SettingsCategory.rootGroups.flatten()
        assertEquals(
            listOf("models", "agent", "chat", "appearance", "runtime", "system", "data"),
            root.map { it.key },
        )
        assertEquals(listOf(4, 3), SettingsCategory.rootGroups.map { it.size })
    }

    @Test fun filesIsNotASettingsCategoryOnTheHome() {
        assertFalse(SettingsCategory.FILES.inRoot)
        assertFalse(SettingsCategory.rootGroups.flatten().contains(SettingsCategory.FILES))
        // The drawer's Files entry still opens it by key, so it must keep resolving.
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
