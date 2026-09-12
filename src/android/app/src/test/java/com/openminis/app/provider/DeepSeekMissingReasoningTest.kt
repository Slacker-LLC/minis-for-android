package com.openminis.app.provider

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeepSeekMissingReasoningTest {
    @Test fun `missing tool reasoning is rejected before HTTP`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val provider = OpenAIProvider(
                apiKey = "test", model = LLMModel("deepseek-reasoner", "DeepSeek", "DeepSeek", supportsReasoning = true),
                basePath = server.loopbackUrl("/v1").toString().trimEnd('/'),
            )
            val failure = runCatching {
                provider.sendMessageClamped(
                    messages = listOf(LLMMessage(LLMMessage.Role.ASSISTANT, "", contentParts = listOf(
                        AgentContentPart.ToolUse("call-1", "test", JSONObject()),
                    ))), systemPrompt = null, maxTokens = 1024, temperature = null,
                    imageParts = emptyList(), tools = emptyList(), thinkingLevel = ThinkingLevel.MEDIUM,
                )
            }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(failure!!.message.orEmpty().contains("reasoning is missing"))
            assertEquals(0, server.requestCount)
        } finally { server.shutdown() }
    }
}
