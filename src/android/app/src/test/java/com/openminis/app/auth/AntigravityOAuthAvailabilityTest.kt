package com.openminis.app.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

/** The Antigravity sign-in fails closed when the build was not given the OAuth client, and its credentials stay out of the source. */
class AntigravityOAuthAvailabilityTest {
    @Test
    fun aBuildWithoutTheClientRefusesBeforeAnythingStarts() {
        assertThrows(ProviderCustomizationUnavailableException::class.java) {
            AntigravityOAuthManager.requireAvailable(available = false)
        }
    }

    @Test
    fun aBuildWithTheClientProceeds() {
        AntigravityOAuthManager.requireAvailable(available = true)
    }

    @Test
    fun theRefusalIsTheSharedNotAvailableMessage() {
        val e = assertThrows(ProviderCustomizationUnavailableException::class.java) {
            AntigravityOAuthManager.requireAvailable(available = false)
        }
        assertEquals(ClaudeOAuthManager.NOT_AVAILABLE_IN_THIS_BUILD, e.message)
    }

    @Test
    fun noGoogleClientSecretIsWrittenInTheSource() {
        // GitHub's push protection blocks such a push; the values belong in the private build configuration.
        val root = File("src/main/java")
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { Regex("GOCSPX-[A-Za-z0-9_-]{20,}").containsMatchIn(it.readText()) }
            .map { it.path }
            .toList()
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun noGoogleClientIdIsWrittenInTheSource() {
        val root = File("src/main/java")
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { Regex("\\d{10,}-[a-z0-9]{20,}\\.apps\\.googleusercontent\\.com").containsMatchIn(it.readText()) }
            .map { it.path }
            .toList()
        assertEquals(emptyList<String>(), offenders)
    }
}
