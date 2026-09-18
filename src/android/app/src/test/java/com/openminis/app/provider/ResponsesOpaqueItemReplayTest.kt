package com.openminis.app.provider

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-eta-responses-opaque-items] Regression coverage for the opaque output items
 * ported from Eta `agent/model/ResponsesEphemeralState.kt` (Mangi-11/Eta @
 * c15de97).
 *
 * The bug: on a `store:false` Responses conversation the model's reasoning items
 * arrive once (encrypted), and the app rebuilt every following request from the
 * transcript alone — so the encrypted chain of thought was lost between the tool
 * call and the answer. The wire body is asserted, because the wire body is what
 * was wrong.
 */
class ResponsesOpaqueItemReplayTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun model() = LLMModel(id = "gpt-5.6-luna", displayName = "Codex", provider = "OpenAI")

    private fun provider() = OpenAIProvider(
        apiKey = "test-key",
        model = model(),
        basePath = server.loopbackUrl("/v1").toString().trimEnd('/'),
        useResponsesAPI = true,
    )

    private fun reasoningItem(id: String = "rs_1", encrypted: String? = "gAAAA-encrypted"): JSONObject =
        JSONObject()
            .put("type", "reasoning")
            .put("id", id)
            .put("summary", JSONArray())
            .apply { if (encrypted != null) put("encrypted_content", encrypted) }

    private fun callPart(id: String = "call_1|fc_1") = AgentContentPart.ToolUse(
        id = id,
        name = "shell",
        input = JSONObject().put("cmd", "ls"),
    )

    private fun historyWithCapturedItem(items: List<String>) = listOf(
        LLMMessage(LLMMessage.Role.USER, "run it"),
        LLMMessage(
            role = LLMMessage.Role.ASSISTANT,
            content = "",
            contentParts = listOf(callPart()),
            providerOutputItems = items,
        ),
        LLMMessage(
            role = LLMMessage.Role.USER,
            content = "",
            contentParts = listOf(
                AgentContentPart.ToolResult(id = "call_1|fc_1", name = "shell", content = "ok"),
            ),
        ),
    )

    private fun typesIn(input: JSONArray): List<String> =
        (0 until input.length()).map { index ->
            val item = input.getJSONObject(index)
            item.optString("type").ifEmpty { "message:" + item.optString("role") }
        }

    @Test
    fun `captured reasoning item is replayed verbatim ahead of the function call`() {
        val raw = reasoningItem().toString()

        val body = provider().buildResponsesAPIBody(
            messages = historyWithCapturedItem(listOf(raw)),
            systemPrompt = null,
            maxTokens = 256,
            stream = true,
        )
        val input = body.getJSONArray("input")

        assertEquals(
            listOf("message:user", "reasoning", "function_call", "function_call_output"),
            typesIn(input),
        )
        val replayed = input.getJSONObject(1)
        assertEquals("rs_1", replayed.getString("id"))
        assertEquals("gAAAA-encrypted", replayed.getString("encrypted_content"))
        assertEquals("fc_1", input.getJSONObject(2).getString("id"))
        assertEquals("call_1", input.getJSONObject(2).getString("call_id"))
    }

    @Test
    fun `a turn without captured items keeps the reconstructed shape`() {
        val body = provider().buildResponsesAPIBody(
            messages = historyWithCapturedItem(emptyList()),
            systemPrompt = null,
            maxTokens = 256,
            stream = true,
        )

        assertEquals(
            listOf("message:user", "function_call", "function_call_output"),
            typesIn(body.getJSONArray("input")),
        )
    }

    @Test
    fun `an unparsable captured item is skipped instead of breaking the request`() {
        val body = provider().buildResponsesAPIBody(
            messages = historyWithCapturedItem(listOf("not json at all")),
            systemPrompt = null,
            maxTokens = 256,
            stream = true,
        )

        assertEquals(
            listOf("message:user", "function_call", "function_call_output"),
            typesIn(body.getJSONArray("input")),
        )
    }

    @Test
    fun `the stream hands encrypted reasoning items to the agent loop`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: ${JSONObject()
                        .put("type", "response.output_item.done")
                        .put("item", reasoningItem(id = "rs_9"))}\n\n" +
                        "data: {\"type\":\"response.output_text.delta\",\"delta\":\"ok\"}\n\n" +
                        "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n",
                ),
        )

        val chunks = runBlocking {
            provider().streamMessageClamped(
                messages = listOf(LLMMessage(LLMMessage.Role.USER, "hi")),
                systemPrompt = null,
                maxTokens = 256,
                temperature = null,
                imageParts = emptyList(),
                tools = emptyList(),
                thinkingLevel = ThinkingLevel.OFF,
            ).toList()
        }

        val items = chunks.filterIsInstance<LLMStreamChunk.ProviderOutputItem>()
        assertEquals(1, items.size)
        val replayed = JSONObject(items.single().json)
        assertEquals("rs_9", replayed.getString("id"))
        assertEquals("gAAAA-encrypted", replayed.getString("encrypted_content"))
    }

    @Test
    fun `a reasoning item without encrypted content is not captured`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: ${JSONObject()
                        .put("type", "response.output_item.done")
                        .put("item", reasoningItem(id = "rs_plain", encrypted = null))}\n\n" +
                        "data: {\"type\":\"response.output_text.delta\",\"delta\":\"ok\"}\n\n" +
                        "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n",
                ),
        )

        val chunks = runBlocking {
            provider().streamMessageClamped(
                messages = listOf(LLMMessage(LLMMessage.Role.USER, "hi")),
                systemPrompt = null,
                maxTokens = 256,
                temperature = null,
                imageParts = emptyList(),
                tools = emptyList(),
                thinkingLevel = ThinkingLevel.OFF,
            ).toList()
        }

        assertTrue(
            "a reasoning item without encrypted content must keep the previous request shape",
            chunks.none { it is LLMStreamChunk.ProviderOutputItem },
        )
    }
}
