package com.openminis.app.provider

import com.openminis.app.data.model.ProviderType
import com.openminis.app.provider.antigravity.AntigravityWire
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AntigravityWireTest {
    private fun inner() = JSONObject(
        """
        {
          "contents": [{"role":"user","parts":[{"text":"hi"}]}],
          "safetySettings": [{"category":"x","threshold":"y"}],
          "generationConfig": {"maxOutputTokens": 8192, "temperature": 0.7,
                               "thinkingConfig": {"thinkingBudget": 1024}},
          "tools": [{"function_declarations": []}]
        }
        """.trimIndent(),
    )

    @Test
    fun envelopeCarriesModelProjectAndAFreshRequestId() {
        val a = AntigravityWire.wrap("gemini-3-pro-high", "proj-1", "-123", inner())
        val b = AntigravityWire.wrap("gemini-3-pro-high", "proj-1", "-123", inner())
        assertEquals("gemini-3-pro-high", a.getString("model"))
        assertEquals("antigravity", a.getString("userAgent"))
        assertEquals("agent", a.getString("requestType"))
        assertEquals("proj-1", a.getString("project"))
        assertTrue(a.getString("requestId").startsWith("agent-"))
        assertNotEquals(a.getString("requestId"), b.getString("requestId"))
        assertEquals("-123", a.getJSONObject("request").getString("sessionId"))
    }

    @Test
    fun safetySettingsAreAlwaysDropped() {
        val wrapped = AntigravityWire.wrap("gemini-3-pro-high", "p", "s", inner())
        assertFalse(wrapped.getJSONObject("request").has("safetySettings"))
    }

    @Test
    fun geminiModelsLoseMaxOutputTokensButKeepThinking() {
        val config = AntigravityWire.wrap("gemini-3-pro-high", "p", "s", inner())
            .getJSONObject("request").getJSONObject("generationConfig")
        assertFalse(config.has("maxOutputTokens"))
        assertTrue(config.has("thinkingConfig"))
        assertEquals(0.7, config.getDouble("temperature"), 0.0)
    }

    @Test
    fun claudeModelsKeepMaxOutputTokensDropThinkingAndValidateToolCalls() {
        val request = AntigravityWire.wrap("claude-opus-4-6-thinking", "p", "s", inner())
            .getJSONObject("request")
        val config = request.getJSONObject("generationConfig")
        assertEquals(8192, config.getInt("maxOutputTokens"))
        assertFalse(config.has("thinkingConfig"))
        assertEquals(
            "VALIDATED",
            request.getJSONObject("toolConfig").getJSONObject("functionCallingConfig").getString("mode"),
        )
    }

    @Test
    fun claudeWithoutToolsGetsNoToolConfig() {
        val body = inner().also { it.remove("tools") }
        val request = AntigravityWire.wrap("claude-sonnet-4-6", "p", "s", body).getJSONObject("request")
        assertFalse(request.has("toolConfig"))
    }

    @Test
    fun wrapToleratesABodyWithoutGenerationConfig() {
        val body = JSONObject("""{"contents":[]}""")
        val wrapped = AntigravityWire.wrap("gemini-3-pro-high", "p", "s", body)
        assertFalse(wrapped.getJSONObject("request").has("generationConfig"))
    }

    @Test
    fun unwrapReturnsTheInnerResponseAndPassesPlainBodiesThrough() {
        val inner = JSONObject("""{"candidates":[]}""")
        assertEquals(inner.toString(), AntigravityWire.unwrap(JSONObject().put("response", inner)).toString())
        assertEquals(inner.toString(), AntigravityWire.unwrap(inner).toString())
    }

    @Test
    fun interleavedThinkingHeaderOnlyForClaudeThinking() {
        assertTrue(AntigravityWire.needsInterleavedThinking("claude-opus-4-6-thinking", false))
        assertTrue(AntigravityWire.needsInterleavedThinking("claude-sonnet-4-6", true))
        assertFalse(AntigravityWire.needsInterleavedThinking("claude-sonnet-4-6", false))
        assertFalse(AntigravityWire.needsInterleavedThinking("gemini-3-pro-high", true))
    }

    @Test
    fun projectIdIsReadFromEveryShapeTheEndpointUses() {
        assertEquals("a", AntigravityWire.projectIdFrom(JSONObject("""{"cloudaicompanionProject":"a"}""")))
        assertEquals("b", AntigravityWire.projectIdFrom(JSONObject("""{"cloudaicompanionProject":{"id":"b"}}""")))
        assertEquals("c", AntigravityWire.projectIdFrom(JSONObject("""{"projectId":"c"}""")))
        assertEquals("d", AntigravityWire.projectIdFrom(JSONObject("""{"project":"projects/d"}""")))
    }

    @Test
    fun noProjectMeansNullNotAnEmptyString() {
        assertNull(AntigravityWire.projectIdFrom(null))
        assertNull(AntigravityWire.projectIdFrom(JSONObject("{}")))
        assertNull(AntigravityWire.projectIdFrom(JSONObject("""{"cloudaicompanionProject":"  "}""")))
        assertNull(AntigravityWire.projectIdFrom(JSONObject("""{"cloudaicompanionProject":{"id":""}}""")))
    }

    @Test
    fun onboardingTierPrefersTheDefaultThenCurrentThenFree() {
        val withDefault = JSONObject(
            """{"allowedTiers":[{"id":"legacy-tier"},{"id":"standard-tier","isDefault":true}],"currentTier":{"id":"x"}}""",
        )
        assertEquals("standard-tier", AntigravityWire.defaultTierId(withDefault))
        assertEquals("cur", AntigravityWire.defaultTierId(JSONObject("""{"currentTier":{"id":"cur"}}""")))
        assertEquals("free-tier", AntigravityWire.defaultTierId(JSONObject("{}")))
    }

    @Test
    fun loadCodeAssistAsksAsTheAntigravityIde() {
        assertEquals(
            "ANTIGRAVITY",
            AntigravityWire.loadCodeAssistBody().getJSONObject("metadata").getString("ideType"),
        )
        val onboard = AntigravityWire.onboardUserBody("free-tier")
        assertEquals("free-tier", onboard.getString("tierId"))
        assertEquals(AntigravityWire.CLIENT_VERSION, onboard.getJSONObject("metadata").getString("ide_version"))
    }

    @Test
    fun reportedClientVersionStaysAtOrAboveTheNewModelFloor() {
        val (major, minor) = AntigravityWire.CLIENT_VERSION.split('.').map { it.toInt() }
        assertTrue("Cloud Code refuses clients below 2.9.0", major > 2 || (major == 2 && minor >= 9))
    }

    @Test
    fun modelCatalogKeepsNamedModelsAndSkipsInternalOnes() {
        val models = com.openminis.app.provider.antigravity.AntigravityModelsApi.parseModels(
            JSONObject(
                """
                {"models":{
                  "gemini-3-pro-high":{"displayName":"Gemini 3 Pro (High)"},
                  "claude-sonnet-4-6":{"displayName":"Claude Sonnet 4.6"},
                  "chat_20706":{"isInternal":true,"displayName":"internal"},
                  "tab_flash_lite":{}
                }}
                """.trimIndent(),
            ),
        )
        assertEquals(setOf("gemini-3-pro-high", "claude-sonnet-4-6"), models.map { it.id }.toSet())
    }

    @Test
    fun modelCatalogWithoutAModelsFieldIsEmpty() {
        val api = com.openminis.app.provider.antigravity.AntigravityModelsApi
        assertTrue(api.parseModels(JSONObject("{}")).isEmpty())
        assertTrue(api.parseModels(JSONObject("""{"models":[]}""")).isEmpty())
    }

    @Test
    fun antigravityIsADrivableProviderType() {
        assertTrue(ProviderType.antigravity.isUsable)
        assertTrue(ProviderType.antigravity.isGoogleAccountOAuth)
        assertTrue(ProviderType.gemini.isGoogleAccountOAuth)
        assertFalse(ProviderType.unsupported.isUsable)
    }
}
