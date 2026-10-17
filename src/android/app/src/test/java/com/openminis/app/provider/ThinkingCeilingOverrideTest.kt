package com.openminis.app.provider

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelOverrides
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Batch E (M4): the per-entry thinking ceiling must reach the provider clamp, and gpt-6
 * must take the same OpenAI-native wire path as gpt-5 (upstream ThinkingRuleResolver.kt:243-257, 479-481).
 */
class ThinkingCeilingOverrideTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        ModelRulesTestFixtures.installBundledCatalog()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun model(
        id: String,
        supportsReasoning: Boolean? = true,
        reasoningEffortValues: List<String>? = null,
        ceiling: ThinkingLevel? = null,
    ) = LLMModel(
        id = id,
        displayName = id,
        provider = "TestProvider",
        supportsReasoning = supportsReasoning,
        reasoningEffortValues = reasoningEffortValues,
        thinkingCeilingOverride = ceiling,
    )

    private fun entry(base: LLMModel, max: ThinkingLevel?) = ModelEntry(
        providerInstanceId = "p",
        baseModel = base,
        overrides = ModelOverrides(maxThinkingLevel = max),
    )

    private fun send(model: LLMModel, level: ThinkingLevel): JSONObject {
        val ok = """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
        repeat(4) { server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(ok)) }
        val provider = OpenAIProvider(
            apiKey = "test-key",
            model = model,
            basePath = server.url("/v1").toString().trimEnd('/'),
        )
        // The public entry point is the one that clamps.
        runCatching {
            runBlocking {
                provider.sendMessage(
                    messages = listOf(LLMMessage(LLMMessage.Role.USER, "q")),
                    systemPrompt = null,
                    maxTokens = 4096,
                    thinkingLevel = level,
                )
            }
        }
        return JSONObject(server.takeRequest().body.readUtf8())
    }

    @Test
    fun `entry override lowers the ceiling and the request is clamped to it`() {
        val m = entry(model("unknown-reasoner"), ThinkingLevel.MEDIUM).model
        assertEquals(ThinkingLevel.MEDIUM, m.catalogMaxThinkingLevel)
        assertEquals("medium", send(m, ThinkingLevel.XHIGH).optString("reasoning_effort", null))
    }

    @Test
    fun `entry override can raise the ceiling above the catalog default`() {
        val base = model("unknown-reasoner")
        assertEquals(ThinkingLevel.HIGH, base.catalogMaxThinkingLevel)
        val raised = entry(base, ThinkingLevel.MAX)
        assertEquals(ThinkingLevel.MAX, raised.effectiveMaxThinkingLevel)
        assertEquals(ThinkingLevel.MAX, raised.model.catalogMaxThinkingLevel)
    }

    @Test
    fun `without an override nothing changes`() {
        val e = entry(model("unknown-reasoner"), null)
        assertNull(e.model.thinkingCeilingOverride)
        assertEquals(ThinkingLevel.HIGH, e.effectiveMaxThinkingLevel)
        assertEquals("high", send(e.model, ThinkingLevel.XHIGH).optString("reasoning_effort", null))
    }

    @Test
    fun `override cannot turn on thinking for a model that does not reason`() {
        val e = entry(model("mimo-v2.5-tts", supportsReasoning = false), ThinkingLevel.MAX)
        assertEquals(ThinkingLevel.OFF, e.effectiveMaxThinkingLevel)
        assertEquals(ThinkingLevel.OFF, e.model.catalogMaxThinkingLevel)
    }

    @Test
    fun `override applies on top of a model that declares its own tiers`() {
        val e = entry(model("glm-5.2", reasoningEffortValues = listOf("low", "medium", "high")), ThinkingLevel.LOW)
        assertEquals(ThinkingLevel.LOW, e.model.catalogMaxThinkingLevel)
        assertEquals("low", send(e.model, ThinkingLevel.HIGH).optString("reasoning_effort", null))
    }

    @Test
    fun `gpt-6 follows the same wire path as gpt-5`() {
        val tiers = listOf("low", "medium", "high", "xhigh", "max")
        for (level in listOf(ThinkingLevel.OFF, ThinkingLevel.HIGH)) {
            val five = send(model("gpt-5.9-test", reasoningEffortValues = tiers), level)
            val six = send(model("gpt-6-test", reasoningEffortValues = tiers), level)
            assertEquals(
                "gpt-6 must send what gpt-5 sends at $level",
                five.optString("reasoning_effort", null),
                six.optString("reasoning_effort", null),
            )
        }
    }

    @Test
    fun `gpt-4o still receives no native-reasoning treatment`() {
        // The gpt-6 row is its own pattern, not "gpt-*": a non-reasoning model must not get an effort.
        val body = send(model("gpt-4o", supportsReasoning = false), ThinkingLevel.HIGH)
        assertNull(body.optString("reasoning_effort", null))
    }
}
