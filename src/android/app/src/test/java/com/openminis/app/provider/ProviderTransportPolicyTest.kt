package com.openminis.app.provider

import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProviderTransportPolicyTest {

    @Test
    fun `https provider base is allowed by default`() {
        val instance = instance(customBase = null)
        val url = ProviderTransportPolicy.requireAllowedInstanceBase(
            instance,
            "https://api.openai.com/v1",
        )
        assertEquals("https", url?.scheme)
    }

    @Test
    fun `explicit approved custom http api-key base is allowed`() {
        val instance = instance(
            customBase = "http://192.168.1.20:11434",
            approvedOrigin = "http://192.168.1.20:11434",
        )
        val url = ProviderTransportPolicy.requireAllowedInstanceBase(
            instance,
            "http://192.168.1.20:11434/v1",
        )
        assertEquals("192.168.1.20", url?.host)
        assertEquals(11434, url?.port)
    }

    @Test
    fun `local http without persisted approval is rejected`() {
        expectViolation {
            ProviderTransportPolicy.requireAllowedInstanceBase(
                instance(customBase = "http://192.168.1.20:11434"),
                "http://192.168.1.20:11434/v1",
            )
        }
    }

    @Test
    fun `http without explicit custom base is rejected`() {
        expectViolation {
            ProviderTransportPolicy.requireAllowedInstanceBase(
                instance(customBase = null),
                "http://api.openai.com/v1",
            )
        }
    }

    @Test
    fun `oauth custom http base is rejected`() {
        expectViolation {
            ProviderTransportPolicy.requireAllowedInstanceBase(
                instance(
                    customBase = "http://127.0.0.1:8080",
                    credential = ProviderCredential.oauth,
                ),
                "http://127.0.0.1:8080/v1",
            )
        }
    }

    @Test
    fun `cleartext approval cannot be reused for another origin`() {
        expectViolation {
            ProviderTransportPolicy.requireAllowedInstanceBase(
                instance(
                    customBase = "http://192.168.1.20:11434",
                    approvedOrigin = "http://192.168.1.20:11434",
                ),
                "http://192.168.1.21:11434/v1",
            )
        }
    }

    @Test
    fun `https client follows redirects but never scheme redirects`() {
        val client = ProviderTransportPolicy.configureClient(
            OkHttpClient.Builder(),
            "https://example.com/v1",
        ).build()
        assertTrue(client.followRedirects)
        assertFalse(client.followSslRedirects)
    }

    @Test
    fun `approved http client never auto-follows redirects`() {
        val client = ProviderTransportPolicy.configureClient(
            OkHttpClient.Builder(),
            "http://127.0.0.1:11434/v1",
        ).build()
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
    }

    @Test
    fun `http secondary url is limited to approved origin`() {
        val allowed = ProviderTransportPolicy.requireAllowedSecondaryUrl(
            "http://10.0.0.2:8080/v1",
            "http://10.0.0.2:8080/generated/image.png",
        )
        assertEquals("10.0.0.2", allowed.host)

        expectViolation {
            ProviderTransportPolicy.requireAllowedSecondaryUrl(
                "http://10.0.0.2:8080/v1",
                "http://10.0.0.3:8080/generated/image.png",
            )
        }
    }

    @Test
    fun `https provider cannot return cleartext secondary url`() {
        expectViolation {
            ProviderTransportPolicy.requireAllowedSecondaryUrl(
                "https://api.example.com/v1",
                "http://cdn.example.com/image.png",
            )
        }
    }

    private fun instance(
        customBase: String?,
        credential: ProviderCredential = ProviderCredential.apiKey,
        approvedOrigin: String? = null,
    ) = ProviderInstance(
        id = "test",
        label = "test",
        providerType = ProviderType.openAI,
        credentialType = credential,
        customBaseURL = customBase,
        cleartextHttpApprovedOrigin = approvedOrigin,
    )

    private inline fun expectViolation(block: () -> Unit) {
        try {
            block()
            fail("Expected ProviderTransportPolicy.Violation")
        } catch (_: ProviderTransportPolicy.Violation) {
            // expected
        }
    }
}
