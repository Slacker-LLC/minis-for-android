package com.openminis.app.data

import com.openminis.app.data.repository.MemberAvailability
import com.openminis.app.data.repository.availableMembersInDeclarationOrder
import com.openminis.app.ui.chat.fallbackEntryIdsInAttemptOrder
import org.junit.Assert.assertEquals
import org.junit.Test

/** Regression coverage for ordered slot resolution and stable failover. */
class SlotResolveCredentialFilterTest {
    private fun available(
        declared: List<String>,
        vararg entries: MemberAvailability<String>,
    ): List<String> = availableMembersInDeclarationOrder(
        declared,
        entries.associateBy { it.value },
    )

    private fun entry(
        id: String,
        hidden: Boolean = false,
        providerEnabled: Boolean = true,
        credentialed: Boolean = true,
    ) = MemberAvailability(id, hidden, providerEnabled, credentialed)

    @Test
    fun `hidden disabled uncredentialed and missing slot entries are skipped in order`() {
        assertEquals(
            listOf("backup-a", "backup-b"),
            available(
                listOf("hidden", "disabled", "uncredentialed", "missing", "backup-a", "backup-b"),
                entry("hidden", hidden = true),
                entry("disabled", providerEnabled = false),
                entry("uncredentialed", credentialed = false),
                entry("backup-b"),
                entry("backup-a"),
            ),
        )
    }

    @Test
    fun `slot resolves to empty only when every declared entry is unavailable`() {
        assertEquals(
            emptyList<String>(),
            available(
                listOf("no-credential", "disabled", "hidden"),
                entry("no-credential", credentialed = false),
                entry("disabled", providerEnabled = false),
                entry("hidden", hidden = true),
            ),
        )
    }

    @Test
    fun `fallback candidates follow slot order after current entry and wrap`() {
        val slot = available(
            listOf("primary", "hidden", "backup-a", "disabled", "backup-b"),
            entry("primary"),
            entry("hidden", hidden = true),
            entry("backup-a"),
            entry("disabled", providerEnabled = false),
            entry("backup-b"),
        )
        assertEquals(listOf("backup-a", "backup-b"), fallbackEntryIdsInAttemptOrder(slot, "primary"))
        assertEquals(listOf("primary", "backup-a"), fallbackEntryIdsInAttemptOrder(slot, "backup-b"))
        repeat(5) {
            assertEquals(listOf("backup-a", "backup-b"), fallbackEntryIdsInAttemptOrder(slot, "primary"))
        }
    }
}
