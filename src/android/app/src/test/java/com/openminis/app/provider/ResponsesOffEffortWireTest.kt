package com.openminis.app.provider

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.openai.OpenAIProvider
import com.openminis.app.provider.openai.buildResponsesAPIBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [OpenMinis#377] On the Responses path with thinking OFF against official OpenAI, the off tier
 * is "none" — which a model declaring only low…max (gpt-6-astra) rejects with HTTP 400. The
 * request must carry the lowest tier the model declares instead.
 */
class ResponsesOffEffortWireTest {

    private fun body(declared: List<String>?): JSONObject {
        val model = LLMModel(
            id = "gpt-6-astra",
            displayName = "GPT-6 Astra",
            provider = "OpenAI",
            supportsReasoning = true,
            reasoningEffortValues = declared,
        )
        val provider = OpenAIProvider(
            apiKey = "k",
            model = model,
            basePath = "https://api.openai.com/v1",
            useResponsesAPI = true,
        )
        return provider.buildResponsesAPIBody(
            messages = listOf(LLMMessage(LLMMessage.Role.USER, "hi")),
            systemPrompt = null,
            maxTokens = 1024,
            stream = false,
            thinkingLevel = ThinkingLevel.OFF,
        )
    }

    @Test
    fun `a model declaring only low to max gets low instead of none`() {
        val effort = body(listOf("low", "medium", "high", "xhigh", "max"))
            .getJSONObject("reasoning").getString("effort")
        assertEquals("low", effort)
    }

    @Test
    fun `a model that declares none keeps none`() {
        val effort = body(listOf("none", "low", "high")).getJSONObject("reasoning").getString("effort")
        assertEquals("none", effort)
    }

    @Test
    fun `a model with no declared tiers keeps the vendor off tier`() {
        val effort = body(null).getJSONObject("reasoning").getString("effort")
        assertEquals("none", effort)
    }
}
