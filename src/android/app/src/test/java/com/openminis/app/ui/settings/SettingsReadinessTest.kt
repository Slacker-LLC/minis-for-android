package com.openminis.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsReadinessTest {
    private fun items(
        root: Boolean = true,
        files: Boolean = true,
        a11y: Boolean = true,
        bg: Boolean = true,
        notif: Boolean = true,
    ) = SettingsReadiness.assemble(root, files, a11y, bg, notif)

    @Test fun checklistKeepsTheDesignOrder() {
        assertEquals(
            listOf(
                ReadinessId.ROOT,
                ReadinessId.ALL_FILES,
                ReadinessId.ACCESSIBILITY,
                ReadinessId.BACKGROUND,
                ReadinessId.NOTIFICATIONS,
            ),
            items().map { it.id },
        )
    }

    @Test fun nothingNeedsAttentionWhenEverythingIsReady() {
        assertTrue(SettingsReadiness.attention(items()).isEmpty())
    }

    @Test fun designExampleCountsAllFilesAndBackgroundOnly() {
        val attention = SettingsReadiness.attention(items(files = false, bg = false))
        assertEquals(listOf(ReadinessId.ALL_FILES, ReadinessId.BACKGROUND), attention.map { it.id })
    }

    @Test fun missingRootAndBlockedNotificationsAreCounted() {
        val attention = SettingsReadiness.attention(items(root = false, notif = false))
        assertEquals(listOf(ReadinessId.ROOT, ReadinessId.NOTIFICATIONS), attention.map { it.id })
    }

    @Test fun optionalAccessibilityIsNeverCounted() {
        assertTrue(SettingsReadiness.attention(items(a11y = false)).isEmpty())
        // ...but it is still in the checklist, marked not ready, so the page can offer the action.
        val a11y = items(a11y = false).single { it.id == ReadinessId.ACCESSIBILITY }
        assertTrue(!a11y.ok && a11y.optional)
    }

    @Test fun onlyAccessibilityIsOptional() {
        assertEquals(listOf(ReadinessId.ACCESSIBILITY), items().filter { it.optional }.map { it.id })
    }
}
