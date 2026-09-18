package com.openminis.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported from Eta `agent/model/CustomHeaderFilter.kt` (Mangi-11/Eta @ c15de97);
 * each case mirrors the rule the port has to keep. Negative cases are the point:
 * a forbidden or malformed header must disappear, never reach the wire in a
 * rewritten form.
 */
class CustomHeaderPolicyTest {

    @Test
    fun `protocol managed header names are dropped with a warning`() {
        val result = CustomHeaderPolicy.sanitizeWithWarnings(
            linkedMapOf(
                "Host" to "evil.example",
                "Content-Length" to "1",
                "Connection" to "close",
                "Transfer-Encoding" to "chunked",
                "Accept-Encoding" to "identity",
                "X-Trace" to "keep-me",
            ),
        )

        assertEquals(linkedMapOf("X-Trace" to "keep-me"), result.headers)
        assertEquals(5, result.warnings.size)
        assertTrue(result.warnings.all { it.contains("managed by the system") })
    }

    @Test
    fun `credential header names are dropped even when the caller supplies a value`() {
        val result = CustomHeaderPolicy.sanitizeWithWarnings(
            linkedMapOf(
                "Authorization" to "Bearer sk-caller",
                "x-api-key" to "caller-key",
                "anthropic-version" to "2023-06-01",
            ),
        )

        assertTrue(result.headers.isEmpty())
        assertEquals(3, result.warnings.size)
    }

    @Test
    fun `ordinary provider headers keep their original name and value`() {
        val result = CustomHeaderPolicy.sanitizeWithWarnings(
            linkedMapOf(
                "X-Custom" to "1",
                "HTTP-Referer" to "https://example.com/app",
                "X-Title" to "Minis App",
            ),
        )

        assertEquals(
            linkedMapOf(
                "X-Custom" to "1",
                "HTTP-Referer" to "https://example.com/app",
                "X-Title" to "Minis App",
            ),
            result.headers,
        )
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `header names with characters outside the token charset are dropped`() {
        val result = CustomHeaderPolicy.sanitizeWithWarnings(
            linkedMapOf(
                "X Header" to "space-in-name",
                "X:Header" to "colon-in-name",
                "X-\"quoted\"" to "quote-in-name",
                "X-Ok" to "fine",
            ),
        )

        assertEquals(linkedMapOf("X-Ok" to "fine"), result.headers)
        assertEquals(3, result.warnings.size)
        assertTrue(result.warnings.all { it.contains("not ") })
    }

    @Test
    fun `blank name is dropped`() {
        val result = CustomHeaderPolicy.sanitizeWithWarnings(linkedMapOf("   " to "value"))

        assertTrue(result.headers.isEmpty())
        assertEquals(1, result.warnings.size)
    }

    @Test
    fun `values outside printable ascii are dropped and tabs are allowed`() {
        val result = CustomHeaderPolicy.sanitizeWithWarnings(
            linkedMapOf(
                "X-Line-Break" to "a\nb",
                "X-Carriage" to "a\rb",
                "X-Non-Ascii" to "中文",
                "X-Tab" to "a\tb",
            ),
        )

        assertEquals(linkedMapOf("X-Tab" to "a\tb"), result.headers)
        assertEquals(3, result.warnings.size)
        assertTrue(result.warnings.all { it.contains("printable ASCII") })
    }

    @Test
    fun `case insensitive duplicates collapse to the last one`() {
        val result = CustomHeaderPolicy.sanitizeWithWarnings(
            linkedMapOf(
                "X-Foo" to "first",
                "x-foo" to "last",
                "X-Bar" to "kept",
            ),
        )

        assertEquals(linkedMapOf("x-foo" to "last", "X-Bar" to "kept"), result.headers)
        assertEquals(1, result.warnings.size)
        assertTrue(result.warnings.single().contains("case-insensitive"))
    }

    @Test
    fun `sanitize returns the same map as sanitizeWithWarnings`() {
        val input = linkedMapOf("Authorization" to "Bearer x", "X-Ok" to "1")

        assertEquals(
            CustomHeaderPolicy.sanitizeWithWarnings(input).headers,
            CustomHeaderPolicy.sanitize(input),
        )
    }

    @Test
    fun `isForbidden is case insensitive and rejects blank names`() {
        assertTrue(CustomHeaderPolicy.isForbidden("HOST"))
        assertTrue(CustomHeaderPolicy.isForbidden(" authorization "))
        assertTrue(CustomHeaderPolicy.isForbidden(""))
        assertFalse(CustomHeaderPolicy.isForbidden("X-Custom"))
    }

    @Test
    fun `redactForLog masks credential values and keeps the rest`() {
        val redacted = CustomHeaderPolicy.redactForLog(
            linkedMapOf(
                "Authorization" to "Bearer sk-secret",
                "X-Api-Key" to "sk-secret",
                "api-key" to "sk-secret",
                "X-Custom" to "visible",
            ),
        )

        assertEquals("***", redacted["Authorization"])
        assertEquals("***", redacted["X-Api-Key"])
        assertEquals("***", redacted["api-key"])
        assertEquals("visible", redacted["X-Custom"])
    }
}
