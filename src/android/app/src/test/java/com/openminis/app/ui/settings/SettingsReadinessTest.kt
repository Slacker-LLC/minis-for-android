package com.openminis.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsReadinessTest {
    /** Every item that applies on a device, all ready unless [not] lists it. */
    private fun items(vararg not: ReadinessId, without: Set<ReadinessId> = emptySet()) =
        SettingsReadiness.assemble(
            ReadinessId.entries.filter { it !in without }.associateWith { it !in not },
        )

    @Test fun checklistKeepsTheDeclaredOrder() {
        assertEquals(ReadinessId.entries.toList(), items().map { it.id })
    }

    @Test fun checklistCoversEverythingSystemAndPermissionsManages() {
        val ids = items().map { it.id }.toSet()
        // Root & Shizuku, all files, accessibility, overlay, assistant role, battery, notifications
        // and the Android grants behind the Agent's tools.
        assertTrue(
            ids.containsAll(
                listOf(
                    ReadinessId.ROOT, ReadinessId.SHIZUKU, ReadinessId.ALL_FILES, ReadinessId.ACCESSIBILITY,
                    ReadinessId.OVERLAY, ReadinessId.ASSISTANT_ROLE, ReadinessId.BACKGROUND,
                    ReadinessId.NOTIFICATIONS, ReadinessId.CALENDAR, ReadinessId.LOCATION,
                    ReadinessId.CONTACTS, ReadinessId.PHOTOS,
                ),
            ),
        )
    }

    @Test fun nothingNeedsAttentionWhenEverythingIsReady() {
        assertTrue(SettingsReadiness.attention(items()).isEmpty())
    }

    @Test fun designExampleCountsAllFilesAndBackgroundOnly() {
        val attention = SettingsReadiness.attention(items(ReadinessId.ALL_FILES, ReadinessId.BACKGROUND))
        assertEquals(listOf(ReadinessId.ALL_FILES, ReadinessId.BACKGROUND), attention.map { it.id })
    }

    @Test fun missingRootAndBlockedNotificationsAreCounted() {
        val attention = SettingsReadiness.attention(items(ReadinessId.ROOT, ReadinessId.NOTIFICATIONS))
        assertEquals(listOf(ReadinessId.ROOT, ReadinessId.NOTIFICATIONS), attention.map { it.id })
    }

    @Test fun optionalItemsAreNeverCountedButStayInTheChecklist() {
        val optional = listOf(
            ReadinessId.SHIZUKU, ReadinessId.ACCESSIBILITY, ReadinessId.OVERLAY, ReadinessId.ASSISTANT_ROLE,
            ReadinessId.CALENDAR, ReadinessId.LOCATION, ReadinessId.CONTACTS, ReadinessId.PHOTOS,
        )
        val all = items(*optional.toTypedArray())
        assertTrue(SettingsReadiness.attention(all).isEmpty())
        // ...but each is still listed as not ready, so the dialog can offer its action.
        optional.forEach { id ->
            val item = all.single { it.id == id }
            assertTrue("$id", !item.ok && item.optional)
        }
    }

    @Test fun onlyFourItemsAreRequired() {
        assertEquals(
            listOf(ReadinessId.ROOT, ReadinessId.ALL_FILES, ReadinessId.BACKGROUND, ReadinessId.NOTIFICATIONS),
            items().filter { !it.optional }.map { it.id },
        )
    }

    @Test fun itemsThatDoNotApplyAreLeftOut() {
        // A device without the assistant role, and an Agent tool the user switched off.
        val list = items(without = setOf(ReadinessId.ASSISTANT_ROLE, ReadinessId.CALENDAR))
        assertFalse(list.any { it.id == ReadinessId.ASSISTANT_ROLE || it.id == ReadinessId.CALENDAR })
    }
}
