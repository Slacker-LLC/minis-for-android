package com.openminis.app.provider

import com.openminis.app.data.model.LLMError
import com.openminis.app.provider.openai.OpenAIModelsApi
import com.openminis.app.ui.chat.LLMErrorPresenter
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Model refresh must be able to tell "the provider answered" from "the request
 * failed". With `strict = true` the models fetch reports HTTP / parse failures
 * as typed [LLMError]s instead of returning an empty or built-in list, so the
 * repository keeps the user's existing models and the UI can show the reason.
 * The default (non-strict) behaviour is pinned by [XAIDynamicCatalogTest].
 */
class OpenAIModelsStrictFetchTest {

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

    private fun base(): String = server.url("/v1").toString().trimEnd('/')

    private fun strictFetch(): Result<List<*>> = runCatching {
        runBlocking { OpenAIModelsApi.fetchModels("k", base(), strict = true) }
    }

    @Test
    fun `401 and 403 are reported as an invalid key, not an empty list`() {
        for (code in listOf(401, 403)) {
            server.enqueue(MockResponse().setResponseCode(code).setBody("""{"error":{"message":"bad key"}}"""))
            val error = strictFetch().exceptionOrNull()
            assertTrue("HTTP $code -> $error", error is LLMError.InvalidApiKey)
            assertEquals(code, LLMErrorPresenter.statusOf((error as LLMError.InvalidApiKey).detail))
            assertEquals(LLMErrorPresenter.Kind.AUTH, LLMErrorPresenter.classify(error)?.kind)
        }
    }

    @Test
    fun `429 is rate limited and 5xx is transient`() {
        server.enqueue(MockResponse().setResponseCode(429))
        assertTrue(strictFetch().exceptionOrNull() is LLMError.RateLimited)

        server.enqueue(MockResponse().setResponseCode(503).setBody("upstream down"))
        val error = strictFetch().exceptionOrNull()
        assertTrue("$error", error is LLMError.TransientError)
        assertEquals(503, LLMErrorPresenter.statusOf((error as LLMError.TransientError).detail))
    }

    @Test
    fun `404 is a provider error carrying the status`() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("no such route"))
        val error = strictFetch().exceptionOrNull()
        assertTrue("$error", error is LLMError.ProviderError)
        assertEquals(LLMErrorPresenter.Kind.NOT_FOUND, LLMErrorPresenter.classify(error!!)?.kind)
    }

    @Test
    fun `an unparseable 200 is a decoding error, not a silent empty list`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))
        assertTrue(strictFetch().exceptionOrNull() is LLMError.DecodingError)

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"object":"list"}"""))
        assertTrue(strictFetch().exceptionOrNull() is LLMError.DecodingError)
    }

    @Test
    fun `a good response still returns the live list`() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"data":[{"id":"my-model","object":"model"}]}"""),
        )
        val models = strictFetch().getOrThrow()
        assertEquals(1, models.size)
    }

    @Test
    fun `the failure detail is bounded and single-line`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("line one\n\n   line two " + "x".repeat(1000)))
        val detail = (strictFetch().exceptionOrNull() as LLMError.TransientError).detail
        assertTrue(detail.length <= "HTTP 500: ".length + 160)
        assertTrue('\n' !in detail)
    }

    @Test
    fun `non-strict fetch keeps returning an empty list on a custom base`() {
        server.enqueue(MockResponse().setResponseCode(401))
        val models = runBlocking { OpenAIModelsApi.fetchModels("k", base()) }
        assertTrue(models.isEmpty())
    }
}
