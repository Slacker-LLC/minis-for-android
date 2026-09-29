package com.openminis.app.scheduled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledTaskTierMutationPolicyTest {
    @Test
    fun `agent CLI and RPC tier parsers fail closed with stable FULL denial code`() {
        assertEquals(ScheduledTaskPermissionTier.READ_ONLY, ScheduledTaskTierMutationPolicy.parseCliTier(null))
        assertEquals(ScheduledTaskPermissionTier.READ_ONLY, ScheduledTaskTierMutationPolicy.parseCliTier("readonly"))
        assertEquals(ScheduledTaskPermissionTier.READ_ONLY, ScheduledTaskTierMutationPolicy.parseAgentTier("invalid"))

        assertEquals(
            ScheduledTaskTierMutationPolicy.FULL_CONFIRMATION_REQUIRED,
            runCatching { ScheduledTaskTierMutationPolicy.parseCliTier("full") }.exceptionOrNull()?.message,
        )
        assertEquals(
            ScheduledTaskTierMutationPolicy.FULL_CONFIRMATION_REQUIRED,
            runCatching { ScheduledTaskTierMutationPolicy.parseAgentTier("FULL") }.exceptionOrNull()?.message,
        )
    }

    @Test
    fun `new routine defaults are read-only and only confirmed editor can create full`() {
        assertFalse(
            ScheduledTaskTierMutationPolicy.canCreate(
                ScheduledTaskPermissionTier.FULL,
                editorConfirmedFull = false,
            ),
        )
        assertTrue(
            ScheduledTaskTierMutationPolicy.canCreate(
                ScheduledTaskPermissionTier.FULL,
                editorConfirmedFull = true,
            ),
        )
        assertTrue(
            ScheduledTaskTierMutationPolicy.canCreate(
                ScheduledTaskPermissionTier.READ_ONLY,
                editorConfirmedFull = false,
            ),
        )
    }

    @Test
    fun `full routine save requires explicit confirmation while read-only does not`() {
        assertTrue(
            ScheduledTaskTierMutationPolicy.requiresFullConfirmation(
                ScheduledTaskPermissionTier.FULL,
                editorConfirmedFull = false,
            ),
        )
        assertFalse(
            ScheduledTaskTierMutationPolicy.requiresFullConfirmation(
                ScheduledTaskPermissionTier.READ_ONLY,
                editorConfirmedFull = false,
            ),
        )
        assertFalse(
            ScheduledTaskTierMutationPolicy.requiresFullConfirmation(
                ScheduledTaskPermissionTier.FULL,
                editorConfirmedFull = true,
            ),
        )
    }

    @Test
    fun `full config updates require editor confirmation`() {
        assertFalse(
            ScheduledTaskTierMutationPolicy.canUpdate(
                ScheduledTaskPermissionTier.READ_ONLY,
                ScheduledTaskPermissionTier.FULL,
                editorConfirmedFull = false,
            ),
        )
        assertFalse(
            ScheduledTaskTierMutationPolicy.canUpdate(
                ScheduledTaskPermissionTier.FULL,
                ScheduledTaskPermissionTier.FULL,
                editorConfirmedFull = false,
            ),
        )
        assertTrue(
            ScheduledTaskTierMutationPolicy.canUpdate(
                ScheduledTaskPermissionTier.FULL,
                ScheduledTaskPermissionTier.READ_ONLY,
                editorConfirmedFull = false,
            ),
        )
        assertFalse(
            ScheduledTaskTierMutationPolicy.canUpdate(
                null,
                ScheduledTaskPermissionTier.FULL,
                editorConfirmedFull = true,
            ),
        )
        assertTrue(
            ScheduledTaskTierMutationPolicy.canUpdate(
                ScheduledTaskPermissionTier.READ_ONLY,
                ScheduledTaskPermissionTier.FULL,
                editorConfirmedFull = true,
            ),
        )
        assertTrue(
            ScheduledTaskTierMutationPolicy.canUpdate(
                ScheduledTaskPermissionTier.FULL,
                ScheduledTaskPermissionTier.FULL,
                editorConfirmedFull = true,
            ),
        )
    }
}
