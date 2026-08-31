package com.openminis.app.tools.android

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivilegedAccessModeTest {
    @Test
    fun `missing preference defaults to standard mode`() {
        assertEquals(PrivilegedAccessMode.STANDARD, PrivilegedAccessModeStore.parse(null))
    }

    @Test
    fun `invalid preference fails closed to standard mode`() {
        for (raw in listOf("", "full-access", "FULL", "agent", "unrestricted")) {
            assertEquals(PrivilegedAccessMode.STANDARD, PrivilegedAccessModeStore.parse(raw))
        }
    }

    @Test
    fun `only persisted full wire value selects full access`() {
        assertEquals(
            PrivilegedAccessMode.FULL_ACCESS,
            PrivilegedAccessModeStore.parse(PrivilegedAccessMode.FULL_ACCESS.wireValue),
        )
    }
}
