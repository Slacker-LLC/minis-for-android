package com.openminis.app.provider

import com.openminis.app.data.FastModePrefs
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.openai.OpenAIProvider
import com.openminis.app.provider.openai.buildRequestBody
import com.openminis.app.provider.openai.buildRequestBody
import com.openminis.app.provider.openai.buildResponsesAPIBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [T-codex-fast-mode] Fast Mode on the Responses path for GPT models.
 *
 * The Responses builder sends `service_tier: "priority"` when the app-level toggle is on and
 * the model is a gpt-family model (OpenMinis 1.14 behaviour). It had been replaced by the
 * xAI per-instance call alone, so the toggle did nothing for GPT. xAI's own gate is covered
 * by [XAIPriorityProcessingTest].
 */
class CodexFastModeTest {

    @After
    fun tearDown() {
        FastModePrefs.setCachedEnabledForTest(false)
    }

    private val history = listOf(LLMMessage(LLMMessage.Role.USER, "hello"))

    private fun provider(modelId: String, useResponsesAPI: Boolean = true) = OpenAIProvider(
        apiKey = "test-key",
        model = LLMModel(id = modelId, displayName = modelId, provider = "OpenAI"),
        basePath = "https://example.invalid/v1",
        useResponsesAPI = useResponsesAPI,
    )

    private fun responsesBody(modelId: String, fastMode: Boolean): JSONObject {
        FastModePrefs.setCachedEnabledForTest(fastMode)
        return provider(modelId).buildResponsesAPIBody(
            messages = history,
            systemPrompt = null,
            maxTokens = 1024,
            stream = false,
        )
    }

    @Test
    fun `a gpt model on the Responses path carries priority when Fast Mode is on`() {
        assertEquals("priority", responsesBody("gpt-6-luna", fastMode = true).getString("service_tier"))
    }

    @Test
    fun `the tier is absent when Fast Mode is off`() {
        assertFalse(responsesBody("gpt-6-luna", fastMode = false).has("service_tier"))
    }

    @Test
    fun `a model outside the gpt family never carries the tier`() {
        assertFalse(
            "service_tier leaked into a non-GPT model's Responses body",
            responsesBody("deepseek-v4-pro", fastMode = true).has("service_tier"),
        )
    }

    @Test
    fun `Chat Completions for a gpt model does not carry the tier`() {
        FastModePrefs.setCachedEnabledForTest(true)
        val body = provider("gpt-6-luna", useResponsesAPI = false).buildRequestBody(
            messages = history,
            systemPrompt = null,
            maxTokens = 1024,
            stream = false,
            temperature = null,
            imageParts = emptyList(),
        )
        assertFalse(
            "the Chat Completions body only carries the tier for an eligible (xAI) instance",
            body.has("service_tier"),
        )
    }
}
