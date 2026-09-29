package com.openminis.app.data

import com.openminis.app.data.repository.MemberAvailability
import com.openminis.app.data.repository.availableMembersInDeclarationOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class MemberAvailabilityTest {
    @Test
    fun `unavailable and missing entries are skipped without changing declared order`() {
        val entries = mapOf(
            "hidden" to MemberAvailability("hidden", hidden = true, providerEnabled = true, credentialed = true),
            "disabled" to MemberAvailability("disabled", hidden = false, providerEnabled = false, credentialed = true),
            "no-credential" to MemberAvailability("no-credential", hidden = false, providerEnabled = true, credentialed = false),
            "last" to MemberAvailability("last", hidden = false, providerEnabled = true, credentialed = true),
            "first" to MemberAvailability("first", hidden = false, providerEnabled = true, credentialed = true),
        )
        assertEquals(
            listOf("last", "first"),
            availableMembersInDeclarationOrder(
                listOf("hidden", "disabled", "no-credential", "gone", "last", "first"),
                entries,
            ),
        )
    }
}
