package com.openminis.app.provider

import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.openai.OpenAIModelsApi
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Base64

/**
 * GPT Image 2.5 on a ChatGPT login goes through the same hosted image tool as
 * gpt-image-2 but names itself in the tool object, and the live model list must
 * present the same client identity as inference and must not hide a refused
 * login behind the bundled list. Request shapes follow OpenMinis 1.14.
 */
class CodexImageAndDiscoveryTest {
    @Before
    fun installRules() = ModelRulesTestFixtures.installBundledCatalog()

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4)
    private val pngB64 = Base64.getEncoder().encodeToString(png)
    private val messages = listOf(LLMMessage(LLMMessage.Role.USER, "a red fox"))
    private val imageSse = "data: {\"type\":\"response.output_item.done\",\"item\":" +
        "{\"type\":\"image_generation_call\",\"status\":\"completed\",\"result\":\"$pngB64\"}}\n\n" +
        "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n"

    private fun imageModel(id: String) = LLMModel(
        id, id, "OpenAI", inputModalities = listOf("text", "image"), outputModalities = listOf("image"),
    )

    private fun clientAnswering(requests: MutableList<Request>, code: Int, body: String, type: String): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("m")
                .body(body.toResponseBody(type.toMediaType())).build()
        }.build()

    private fun generate(id: String, requests: MutableList<Request>): Pair<JSONObject, ByteArray> {
        val provider = OpenAIProvider({ "fixture-token" }, imageModel(id), "fixture-account")
        val field = OpenAIProvider::class.java.getDeclaredField("client").apply { isAccessible = true }
        val base = field.get(provider) as OkHttpClient
        field.set(provider, base.newBuilder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(imageSse.toResponseBody("text/event-stream".toMediaType())).build()
        }.build())
        val result = runBlocking { provider.sendMessage(messages, "", 1024, temperature = null, tools = emptyList()) }
        val buffer = okio.Buffer().also { requests.single().body!!.writeTo(it) }
        return JSONObject(buffer.readUtf8()) to result.mediaAttachments.single().data
    }

    // ── Image requests ──────────────────────────────────────────────────────

    @Test
    fun `the 2_5 variants name themselves in the image tool object`() {
        for (id in listOf("gpt-image-2.5-flare", "gpt-image-2.5-sunburst")) {
            val requests = mutableListOf<Request>()
            val (body, image) = generate(id, requests)
            assertEquals("https://chatgpt.com/backend-api/codex/responses", requests.single().url.toString())
            assertEquals("gpt-5.5", body.getString("model"))
            val tool = body.getJSONArray("tools").getJSONObject(0)
            assertEquals("image_generation", tool.getString("type"))
            assertEquals(id, tool.getString("model"))
            assertArrayEquals(png, image)
        }
    }

    @Test
    fun `gpt-image-2 keeps the bare tool so the backend picks its default`() {
        val requests = mutableListOf<Request>()
        val (body, image) = generate("gpt-image-2", requests)
        val tool = body.getJSONArray("tools").getJSONObject(0)
        assertEquals("image_generation", tool.getString("type"))
        assertFalse(tool.has("model"))
        assertArrayEquals(png, image)
    }

    @Test
    fun `the bundled Codex catalog offers both 2_5 models as image-only routes`() {
        val catalog = com.openminis.app.provider.rules.ModelRulesProvider.staticModels("codexOAuth")
        for (id in listOf("gpt-image-2.5-flare", "gpt-image-2.5-sunburst")) {
            assertEquals(listOf("image"), catalog.single { it.id == id }.outputModalities)
            assertTrue(id in OpenAIProvider.CODEX_IMAGE_MODELS)
        }
    }

    // ── Live model list ─────────────────────────────────────────────────────

    private val modelsBody = """{"models":[{"slug":"gpt-6-sol","display_name":"GPT-6 Sol","visibility":"list","priority":1,""" +
        """"supported_reasoning_levels":[{"effort":"low"},{"effort":"high"}]}]}"""

    @Test
    fun `the model list presents the same client identity as inference`() {
        val requests = mutableListOf<Request>()
        val models = runBlocking {
            OpenAIModelsApi.fetchModelsCodexOAuth(
                "fixture-token", "fixture-account", clientAnswering(requests, 200, modelsBody, "application/json"),
            )
        }
        val request = requests.single()
        assertEquals(listOf("gpt-6-sol"), models.map { it.id })
        assertEquals("/backend-api/codex/models", request.url.encodedPath)
        assertEquals(OpenAIProvider.CODEX_CLIENT_VERSION, request.url.queryParameter("client_version"))
        assertEquals(OpenAIProvider.CODEX_CLIENT_VERSION, request.header("Version"))
        assertEquals("responses=experimental", request.header("Openai-Beta"))
        assertEquals("codex_cli_rs", request.header("Originator"))
        assertEquals("Bearer fixture-token", request.header("Authorization"))
        assertEquals("fixture-account", request.header("Chatgpt-Account-Id"))
    }

    // ── Negative cases ──────────────────────────────────────────────────────

    @Test
    fun `a refused login is an error, not the bundled list shown as a refresh`() {
        for (code in listOf(401, 403)) {
            try {
                runBlocking {
                    OpenAIModelsApi.fetchModelsCodexOAuth(
                        "bad", null, clientAnswering(mutableListOf(), code, """{"detail":"nope"}""", "application/json"),
                    )
                }
                fail("expected an auth error for HTTP $code")
            } catch (e: LLMError.InvalidApiKey) {
                // expected
            }
        }
    }

    @Test
    fun `a server error or an unreadable body yields an empty list so the caller falls back`() {
        for ((code, body) in listOf(500 to "oops", 200 to "<html>blocked</html>", 200 to """{"models":[]}""")) {
            val models = runBlocking {
                OpenAIModelsApi.fetchModelsCodexOAuth("t", null, clientAnswering(mutableListOf(), code, body, "application/json"))
            }
            assertTrue("HTTP $code / $body", models.isEmpty())
        }
    }
}
