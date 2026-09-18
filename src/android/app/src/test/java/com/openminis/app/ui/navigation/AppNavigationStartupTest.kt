package com.openminis.app.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class AppNavigationStartupTest {
    @Test
    fun `auto launch opens new draft when provider is ready and sessions are empty`() {
        // AppNavigation calls this only after the no-provider onboarding gate has passed.
        val targetSessionId = resolveAutoLaunchSessionId(
            latestSessionId = null,
            latestSessionUpdatedAt = null,
            nowMillis = 1_000_000L,
            autoThresholdMs = 15L * 60 * 1000,
            newDraftSessionId = { "__new__regression" },
        )

        assertEquals("__new__regression", targetSessionId)
    }
}
