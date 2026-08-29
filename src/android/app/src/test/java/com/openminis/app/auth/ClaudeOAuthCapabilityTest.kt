package com.openminis.app.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ClaudeOAuthCapabilityTest {
    @Test
    fun unavailableCapabilityReturnsExplicitBuildResult() {
        val error = assertThrows(ProviderCustomizationUnavailableException::class.java) {
            ClaudeOAuthManager.requireCustomizationAvailable(
                available = false,
                prompt = ClaudeOAuthManager.NOT_AVAILABLE_IN_THIS_BUILD,
            )
        }

        assertEquals(ClaudeOAuthManager.NOT_AVAILABLE_IN_THIS_BUILD, error.message)
    }

    @Test
    fun sentinelCannotBeTreatedAsConfigured() {
        val error = assertThrows(ProviderCustomizationUnavailableException::class.java) {
            ClaudeOAuthManager.requireCustomizationAvailable(
                available = true,
                prompt = ClaudeOAuthManager.NOT_AVAILABLE_IN_THIS_BUILD,
            )
        }

        assertEquals(ClaudeOAuthManager.NOT_AVAILABLE_IN_THIS_BUILD, error.message)
    }

    @Test
    fun configuredCapabilityReturnsPrompt() {
        assertEquals(
            "configured-fixture",
            ClaudeOAuthManager.requireCustomizationAvailable(
                available = true,
                prompt = "configured-fixture",
            ),
        )
    }
}
