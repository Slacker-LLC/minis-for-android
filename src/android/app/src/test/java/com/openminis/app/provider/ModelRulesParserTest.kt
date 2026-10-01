package com.openminis.app.provider

import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.rules.MODEL_RULES_MAX_BYTES
import com.openminis.app.provider.rules.ModelRulesParser
import com.openminis.app.provider.rules.ModelRulesProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRulesParserTest {
    private fun envelope(rules: String = "[]", version: Int = 1, topExtra: String = "") =
        """{"schemaVersion":$version,"rules":$rules,"staticModels":{},"pickerFilters":{}$topExtra}"""

    @Test
    fun `bundled rules asset parses and every submitted rule is valid`() {
        val warnings = mutableListOf<String>()
        val document = ModelRulesParser.parse(ModelRulesTestFixtures.bundledDocumentText(), warnings::add)
        assertTrue("bad built-in rules: $warnings", warnings.isEmpty())
        assertEquals(1, document?.schemaVersion)
        assertEquals(21, document?.rules?.size)
        assertEquals(setOf("anthropic", "gemini", "openAI", "openRouter", "xAI", "kimi", "codexOAuth"), document?.staticModels?.keys)
        assertTrue(document?.staticModels?.values?.flatten()?.isNotEmpty() == true)
    }

    @Test
    fun `first matching rule wins independently for each property`() {
        val rules = """
          [
            {"id":"specific","match":{"idExact":["vendor/model"]},"set":{"maxThinkingLevel":"MAX"}},
            {"id":"family","match":{"idPrefix":["vendor/"]},"set":{"maxThinkingLevel":"HIGH","supportsReasoning":true}}
          ]
        """.trimIndent()
        val document = requireNotNull(ModelRulesParser.parse(envelope(rules)))
        assertEquals(ThinkingLevel.MAX, document.capabilitiesFor("vendor/model").maxThinkingLevel)
        assertEquals(true, document.capabilitiesFor("vendor/model").supportsReasoning)

        assertTrue("rule order must remain data-defined", document.rules.first().id == "specific")
        val reversedRules = """[{"id":"family","match":{"idPrefix":["vendor/"]},"set":{"maxThinkingLevel":"HIGH"}},{"id":"specific","match":{"idExact":["vendor/model"]},"set":{"maxThinkingLevel":"MAX"}}]"""
        val reversedDocument = requireNotNull(ModelRulesParser.parse(envelope(reversedRules)))
        assertEquals(ThinkingLevel.HIGH, reversedDocument.capabilitiesFor("vendor/model").maxThinkingLevel)
    }

    @Test
    fun `predicate arrays are OR within a kind and kinds combine with AND`() {
        val rules = """[{"id":"compound","match":{"idPrefix":["org/", "vendor/"],"idContains":["chat", "assistant"],"idSuffix":["-stable"]},"set":{"maxThinkingLevel":"HIGH"}}]"""
        val document = requireNotNull(ModelRulesParser.parse(envelope(rules)))
        assertEquals(ThinkingLevel.HIGH, document.capabilitiesFor("org/chat-model-stable").maxThinkingLevel)
        assertEquals(ThinkingLevel.HIGH, document.capabilitiesFor("vendor/assistant-stable").maxThinkingLevel)
        assertNull(document.capabilitiesFor("org/chat-model-preview").maxThinkingLevel)
        assertNull(document.capabilitiesFor("other/chat-model-stable").maxThinkingLevel)
    }

    @Test
    fun `normalizeDots and stripPath are explicit independent match options`() {
        val rules = """
          [
            {"id":"claude-dots","match":{"idPrefix":["claude-opus-4"],"normalizeDots":true,"stripPath":true},"set":{"maxThinkingLevel":"MAX"}},
            {"id":"astra-tail","match":{"idExact":["gpt-6-astra"],"stripPath":true},"set":{"contextWindow":1050000}}
          ]
        """.trimIndent()
        val document = requireNotNull(ModelRulesParser.parse(envelope(rules)))
        assertEquals(ThinkingLevel.MAX, document.capabilitiesFor("proxy/claude-opus-4.8-preview").maxThinkingLevel)
        assertEquals(ThinkingLevel.MAX, document.capabilitiesFor("claude-opus-4-8-preview").maxThinkingLevel)
        assertEquals(1050000, document.capabilitiesFor("openai/gpt-6-astra").contextWindow)
        assertNull(document.capabilitiesFor("openai/gpt-6-astra-preview").contextWindow)
    }

    @Test
    fun `OpenAI official picker keeps legacy substring exclusions in rule data`() {
        val filter = requireNotNull(ModelRulesTestFixtures.bundledDocument().pickerFilters["openAI"])
        assertTrue(filter.accepts("gpt-4o-mini"))
        assertFalse(filter.accepts("gpt-4o-mini-audio-preview"))
        assertFalse(filter.accepts("gpt-4o-mini-embedding-v2"))
        assertFalse(filter.accepts("gpt-4o-mini:ft-42"))
        assertFalse(filter.accepts("vendor-chat-model"))
    }

    @Test
    fun `malformed oversized future schema and unknown top-level properties reject whole document`() {
        assertNull(ModelRulesParser.parse("{"))
        assertNull(ModelRulesParser.parse(envelope() + " ".repeat(MODEL_RULES_MAX_BYTES)))
        assertNull(ModelRulesParser.parse(envelope(version = 2)))
        assertNull(ModelRulesParser.parse(envelope(topExtra = ",\"execute\":\"no\"")))
    }

    @Test
    fun `unknown set properties and enum values drop only their unsafe rule`() {
        val warnings = mutableListOf<String>()
        val rules = """
          [
            {"id":"unknown-field","match":{"idExact":["unsafe"]},"set":{"maxThinkingLevel":"MAX","baseURL":"https://evil.invalid"}},
            {"id":"unknown-enum","match":{"idExact":["future"]},"set":{"maxThinkingLevel":"ULTRA_PLUS"}},
            {"id":"known","match":{"idExact":["safe"]},"set":{"maxThinkingLevel":"HIGH"}}
          ]
        """.trimIndent()
        val document = requireNotNull(ModelRulesParser.parse(envelope(rules), warnings::add))
        assertEquals(listOf("known"), document.rules.map { it.id })
        assertNull(document.capabilitiesFor("unsafe").maxThinkingLevel)
        assertNull(document.capabilitiesFor("future").maxThinkingLevel)
        assertEquals(ThinkingLevel.HIGH, document.capabilitiesFor("safe").maxThinkingLevel)
        assertEquals(2, warnings.size)
    }

    @Test
    fun `invalid remote document cannot be accepted and non HTTPS URLs are rejected`() {
        val existing = ModelRulesTestFixtures.bundledDocument()
        val invalid = ModelRulesParser.parse(envelope(version = 99))
        assertNull(invalid)
        // Refresh installs only a non-null validated candidate; rejected input leaves this snapshot intact.
        val active = invalid ?: existing
        assertEquals(existing.rules, active.rules)
        assertTrue(ModelRulesProvider.isAllowedRemoteUrl("https://example.invalid/model-rules.json"))
        assertFalse(ModelRulesProvider.isAllowedRemoteUrl("http://example.invalid/model-rules.json"))
        assertFalse(ModelRulesProvider.isAllowedRemoteUrl("file:///tmp/model-rules.json"))
    }
}
