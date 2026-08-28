package com.openminis.app.data.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionOverridesTest {
    @Test
    fun `empty overrides serialize as null so globals stay inherited`() {
        assertNull(SessionOverrides().toJsonOrNull())
        assertTrue(SessionOverrides.fromJson(null).isEmpty())
        assertTrue(SessionOverrides.fromJson("  ").isEmpty())
    }

    @Test
    fun `round trip preserves explicit sparse values`() {
        val original = SessionOverrides(
            systemPrompt = "Prefer concise answers.",
            temperature = 0.25,
            topP = 0.8,
            topK = 32,
            maxTokens = 4096,
            enabledTools = setOf("file_read", "shell_execute"),
        )

        val encoded = original.toJsonOrNull()
        val decoded = SessionOverrides.fromJson(encoded)

        assertEquals(original, decoded)
    }

    @Test
    fun `missing tool allow-list inherits while explicit empty list means chat only`() {
        val inherited = SessionOverrides.fromJson("{}")
        val chatOnly = SessionOverrides.fromJson("""{"enabledTools":[]}""")

        assertNull(inherited.enabledTools)
        assertEquals(emptySet<String>(), chatOnly.enabledTools)
        assertTrue(inherited.isEmpty())
        assertFalse(chatOnly.isEmpty())
        assertTrue(JSONObject(chatOnly.toJsonOrNull()!!).getJSONArray("enabledTools").isEmpty)
    }

    @Test
    fun `malformed and out of range fields fall back independently`() {
        val decoded = SessionOverrides.fromJson(
            """{
                "systemPrompt":"  Keep code runnable.  ",
                "temperature":3.5,
                "topP":-0.1,
                "topK":0,
                "maxTokens":-2,
                "enabledTools":[" file_read ","", "shell_execute"]
            }""".trimIndent(),
        )

        assertEquals("Keep code runnable.", decoded.systemPrompt)
        assertNull(decoded.temperature)
        assertNull(decoded.topP)
        assertNull(decoded.topK)
        assertNull(decoded.maxTokens)
        assertEquals(setOf("file_read", "shell_execute"), decoded.enabledTools)

        assertTrue(SessionOverrides.fromJson("not-json").isEmpty())
    }

    @Test
    fun `explicit max token budget can only reduce upstream budget`() {
        assertEquals(4096, SessionOverrides(maxTokens = 4096).effectiveMaxTokens(8192))
        assertEquals(8192, SessionOverrides(maxTokens = 16384).effectiveMaxTokens(8192))
        assertEquals(8192, SessionOverrides().effectiveMaxTokens(8192))
    }
}
