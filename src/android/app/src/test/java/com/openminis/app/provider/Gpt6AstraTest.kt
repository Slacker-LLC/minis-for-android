package com.openminis.app.provider

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.model.inferContextWindowTokens
import com.openminis.app.provider.openai.OpenAIModelsApi
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Gpt6AstraTest {
    private val messages = listOf(LLMMessage(LLMMessage.Role.USER, "hello"))
    private val tools = listOf(AgentToolDefinition("fixture_tool", "Fixture", emptyMap()))
    private val sse = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"ok\"}\n\n" +
        "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n"

    @Test
    fun `OAuth catalog includes Astra with documented capabilities`() {
        val model = OpenAIModelsApi.fetchModelsOAuth().single { it.id == "gpt-6-astra" }
        assertEquals(1_050_000, model.contextWindowTokens)
        assertEquals(128_000, model.maxOutputTokens)
        assertEquals(true, model.supportsReasoning)
        assertTrue(model.inputModalities!!.contains("image"))
        assertEquals(ThinkingLevel.MAX, model.catalogMaxThinkingLevel)
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), model.reasoningEffortValues)
        assertTrue(LLMModel.allOpenAI.contains(LLMModel.gpt6Astra))
    }

    @Test
    fun `API discovery fills new model capabilities without models dev`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"gpt-6-astra"}]}"""))
            val model = OpenAIModelsApi.fetchModels("fixture-key", server.loopbackUrl("/v1").toString()).single()
            assertEquals("/v1/models", server.takeRequest().path)
            assertEquals(1_050_000, model.contextWindowTokens)
            assertEquals(128_000, model.maxOutputTokens)
            assertEquals(true, model.supportsReasoning)
            assertEquals(listOf("text", "image"), model.inputModalities)
        }
    }

    @Test
    fun `API key automatically sends Responses tools and removes stale parameters`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse))
            // A saved/custom entry can predate the built-in capability metadata.
            val provider = OpenAIProvider("fixture-key", LLMModel("gpt-6-astra", "Astra", "OpenAI"),
                server.loopbackUrl("/v1").toString())
            provider.chatExtraBody = mapOf(
                "temperature" to 0.5, "top_p" to 0.8, "top_logprobs" to 1, "logprobs" to true,
                "reasoning" to JSONObject().put("effort", "none"),
                "include" to JSONArray().put("message.output_text.logprobs"),
                "prompt_cache_retention" to "24h",
            )
            assertFalse(provider.supportsTemperatureOverride)
            assertFalse(provider.streamTextIsMonolithic)
            val result = provider.sendMessage(messages, "fixture instructions", 2048, temperature = 0.4, tools = tools)
            assertEquals("ok", result.text)
            val request = server.takeRequest()
            assertEquals("/v1/responses", request.path)
            assertEquals("Bearer fixture-key", request.getHeader("Authorization"))
            val body = JSONObject(request.body.readUtf8())
            assertEquals("gpt-6-astra", body.getString("model"))
            assertEquals("low", body.getJSONObject("reasoning").getString("effort"))
            assertEquals("fixture_tool", body.getJSONArray("tools").getJSONObject(0).getString("name"))
            assertEquals(2048, body.getInt("max_output_tokens"))
            assertFalse(body.has("messages"))
            assertEquals(0, body.getJSONArray("include").length())
            assertEquals("30m", body.getJSONObject("prompt_cache_options").getString("ttl"))
            for (key in listOf("temperature", "top_p", "top_logprobs", "logprobs", "prompt_cache_retention")) {
                assertFalse(key, body.has(key))
            }
        }
    }

    @Test
    fun `OAuth retains login route account headers tools and all supported effort tiers`() = runBlocking {
        val provider = OpenAIProvider({ "fixture-token" }, LLMModel.gpt6Astra, "fixture-account")
        val captured = mutableListOf<Request>()
        interceptRequests(provider, captured)
        val expected = listOf("low", "low", "medium", "high", "xhigh", "max", "max")
        for ((index, level) in ThinkingLevel.entries.withIndex()) {
            assertEquals("ok", provider.sendMessage(messages, "fixture", 2048, tools = tools, thinkingLevel = level).text)
            val request = captured.last()
            assertEquals("https://chatgpt.com/backend-api/codex/responses", request.url.toString())
            assertEquals("Bearer fixture-token", request.header("Authorization"))
            assertEquals("fixture-account", request.header("Chatgpt-Account-Id"))
            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            val body = JSONObject(buffer.readUtf8())
            assertEquals("gpt-6-astra", body.getString("model"))
            assertEquals(expected[index], body.getJSONObject("reasoning").getString("effort"))
            assertEquals("fixture_tool", body.getJSONArray("tools").getJSONObject(0).getString("name"))
            assertEquals("reasoning.encrypted_content", body.getJSONArray("include").getString(0))
            assertFalse(body.has("max_output_tokens"))
            assertFalse(body.has("temperature"))
        }
    }

    @Test
    fun `saved and namespaced Astra metadata retains context and max effort`() {
        for (id in listOf("gpt-6-astra", "openai/gpt-6-astra")) {
            val model = LLMModel(id, id, "Custom")
            assertEquals(1_050_000, model.contextWindowTokens)
            assertEquals(1_050_000, inferContextWindowTokens(model))
            assertEquals(ThinkingLevel.MAX, model.catalogMaxThinkingLevel)
        }
        assertFalse(LLMModel("gpt-6-astra-unrelated", "Fixture", "Custom").isGpt6Astra)
        assertEquals(50_000, inferContextWindowTokens(LLMModel.gpt6Astra.copy(contextWindow = 50_000)))
    }

    @Test
    fun `namespaced relay preserves configured Chat Completions transport`() {
        val provider = OpenAIProvider("fixture", LLMModel.gpt6Astra.copy(id = "openai/gpt-6-astra"), "https://openrouter.ai/api/v1")
        assertTrue(provider.streamTextIsMonolithic)
        assertTrue(OpenAIProvider("fixture", LLMModel.gpt4oMini).streamTextIsMonolithic)
    }

    @Test
    fun `direct DeepSeek endpoint honors configured Responses flag`() {
        // Route selection is controlled by the provider setting, even for the
        // direct DeepSeek endpoint; the app must not override it by hostname.
        val provider = OpenAIProvider(
            apiKey = "fixture",
            model = LLMModel("deepseek-chat", "DeepSeek", "DeepSeek"),
            basePath = "https://api.deepseek.com/v1",
            useResponsesAPI = true,
        )
        assertFalse(provider.streamTextIsMonolithic)
    }

    private fun interceptRequests(provider: OpenAIProvider, requests: MutableList<Request>) {
        // Exercise the complete OAuth request builder and SSE parser with an
        // in-memory transport. No token or request reaches a live endpoint.
        val field = OpenAIProvider::class.java.getDeclaredField("client").apply { isAccessible = true }
        val client = field.get(provider) as OkHttpClient
        field.set(provider, client.newBuilder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(sse.toResponseBody("text/event-stream".toMediaType())).build()
        }.build())
    }
}
