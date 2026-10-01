package com.openminis.app.provider

import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/**
 * gpt-image-2 on a ChatGPT (Codex OAuth) login used to go only through a chat
 * model's hosted image tool. The backend's own image endpoint is now tried
 * first, and the old route stays as the fallback; failures a second route cannot
 * fix (login, rate limit, moderation) must not be retried there.
 */
class CodexImageRouteTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4)
    private val pngB64 = Base64.getEncoder().encodeToString(png)
    private val imageModel = LLMModel(
        "gpt-image-2", "GPT Image 2", "OpenAI",
        inputModalities = listOf("text", "image"), outputModalities = listOf("image"),
    )
    private val messages = listOf(LLMMessage(LLMMessage.Role.USER, "a red fox"))

    private val legacySse = "data: {\"type\":\"response.output_item.done\",\"item\":" +
        "{\"type\":\"image_generation_call\",\"status\":\"completed\",\"result\":\"$pngB64\"}}\n\n" +
        "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n"

    private fun provider(
        requests: MutableList<Request>,
        direct: () -> Pair<Int, String>,
    ): OpenAIProvider {
        val provider = OpenAIProvider({ "fixture-token" }, imageModel, "fixture-account")
        val field = OpenAIProvider::class.java.getDeclaredField("client").apply { isAccessible = true }
        val client = field.get(provider) as OkHttpClient
        field.set(provider, client.newBuilder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val (code, body, type) = if (request.url.encodedPath.endsWith("/images/generations")) {
                direct().let { Triple(it.first, it.second, "application/json") }
            } else {
                Triple(200, legacySse, "text/event-stream")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("m")
                .body(body.toResponseBody(type.toMediaType())).build()
        }.build())
        return provider
    }

    private fun generate(provider: OpenAIProvider) =
        runBlocking { provider.sendMessage(messages, "", 1024, temperature = null, tools = emptyList()) }

    private fun directOk() = 200 to """{"created":1,"data":[{"b64_json":"$pngB64","generation_id":"g"}]}"""

    @Test
    fun `the backend image endpoint is used first and carries the login and the model`() {
        val requests = mutableListOf<Request>()
        val result = generate(provider(requests) { directOk() })

        assertEquals(1, requests.size)
        val request = requests.single()
        assertEquals("https://chatgpt.com/backend-api/codex/images/generations", request.url.toString())
        assertEquals("Bearer fixture-token", request.header("Authorization"))
        assertEquals("fixture-account", request.header("Chatgpt-Account-Id"))
        assertNotNull(request.header("x-codex-image-turn-id"))
        val buffer = okio.Buffer().also { request.body!!.writeTo(it) }
        val body = JSONObject(buffer.readUtf8())
        assertEquals("gpt-image-2", body.getString("model"))
        assertEquals("a red fox", body.getString("prompt"))
        assertArrayEquals(png, result.mediaAttachments.single().data)
        assertEquals("image/png", result.mediaAttachments.single().mimeType)
    }

    @Test
    fun `a backend without the endpoint falls back to the hosted-tool route`() {
        val requests = mutableListOf<Request>()
        val result = generate(provider(requests) { 404 to """{"detail":"Not Found"}""" })

        assertEquals(
            listOf("/backend-api/codex/images/generations", "/backend-api/codex/responses"),
            requests.map { it.url.encodedPath },
        )
        assertArrayEquals(png, result.mediaAttachments.single().data)
    }

    @Test
    fun `a success answer without an image falls back too`() {
        val requests = mutableListOf<Request>()
        val result = generate(provider(requests) { 200 to """{"data":[]}""" })
        assertEquals(2, requests.size)
        assertArrayEquals(png, result.mediaAttachments.single().data)
    }

    // ── Negative cases: no second request when another route cannot help ────

    @Test
    fun `an expired login is reported and the old route is not tried`() {
        val requests = mutableListOf<Request>()
        try {
            generate(provider(requests) { 401 to """{"error":{"message":"bad token"}}""" })
            fail("expected an auth error")
        } catch (e: LLMError.InvalidApiKey) {
            // expected
        }
        assertEquals(1, requests.size)
    }

    @Test
    fun `a rate limit is reported and the old route is not tried`() {
        val requests = mutableListOf<Request>()
        try {
            generate(provider(requests) { 429 to """{"error":{"message":"slow down"}}""" })
            fail("expected a rate-limit error")
        } catch (e: LLMError.RateLimited) {
            // expected
        }
        assertEquals(1, requests.size)
    }

    @Test
    fun `a moderation refusal is reported and not re-sent to the old route`() {
        val requests = mutableListOf<Request>()
        try {
            generate(provider(requests) { 400 to """{"error":{"code":"moderation_blocked","message":"rejected by the safety system"}}""" })
            fail("expected a provider error")
        } catch (e: LLMError.ProviderError) {
            assertTrue(e.message.orEmpty().contains("safety"))
        }
        assertEquals(1, requests.size)
    }

    @Test
    fun `parsing ignores bodies that carry no image`() {
        val p = OpenAIProvider({ "t" }, imageModel, null)
        assertNull(p.parseCodexImagesResponse(""))
        assertNull(p.parseCodexImagesResponse("<html>blocked</html>"))
        assertNull(p.parseCodexImagesResponse("""{"data":[{"url":"https://x"}]}"""))
        assertNull(p.parseCodexImagesResponse("""{"data":[{"b64_json":""}]}"""))
    }
}
