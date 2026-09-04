package com.openminis.app.provider

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/** Regression coverage for #35's session temperature override on Responses. */
class ResponsesApiTemperatureOverrideTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun captureBody(model: LLMModel): JSONObject {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"type\":\"response.output_text.delta\",\"delta\":\"ok\"}\n\n" +
                        "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n",
                ),
        )
        val provider = OpenAIProvider(
            apiKey = "test-key",
            model = model,
            basePath = server.loopbackUrl("/v1").toString().trimEnd('/'),
            useResponsesAPI = true,
        )
        runBlocking {
            provider.streamMessageClamped(
                messages = listOf(LLMMessage(LLMMessage.Role.USER, "hi")),
                systemPrompt = null,
                maxTokens = 256,
                temperature = 0.35,
                imageParts = emptyList(),
                tools = emptyList(),
                thinkingLevel = ThinkingLevel.OFF,
            ).toList()
        }
        return JSONObject(server.takeRequest().body.readUtf8())
    }

    @Test
    fun `session temperature reaches non reasoning Responses request`() {
        val body = captureBody(LLMModel.gpt4oMini)

        assertEquals(0.35, body.getDouble("temperature"), 0.001)
    }

    @Test
    fun `reasoning Responses request omits unsupported temperature`() {
        val body = captureBody(
            LLMModel(
                id = "gpt-5.5",
                displayName = "GPT-5.5",
                provider = "OpenAI",
                supportsReasoning = true,
            ),
        )

        assertFalse(body.has("temperature"))
    }
}
