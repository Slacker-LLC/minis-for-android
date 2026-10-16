package com.openminis.app.provider

import com.openminis.app.provider.openai.OpenAIProvider
import com.openminis.app.provider.openai.buildRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** OpenCode answers `MissingSessionID` without `x-opencode-session`; other hosts must not see it. */
class OpenCodeSessionHeaderTest {
    private fun provider(base: String, extra: Map<String, String> = emptyMap()) = OpenAIProvider(
        apiKey = "test-key",
        model = ModelRulesTestFixtures.staticModel("openAI", "gpt-4o-mini"),
        basePath = base,
        extraHeaders = extra,
    )

    @Test
    fun opencodeHost_getsSessionHeader() = runBlocking {
        val req = provider("https://opencode.ai/inference/go/openai/v1").buildRequest("{}", "minis-abc")
        assertEquals("minis-abc", req.header("x-opencode-session"))
    }

    @Test
    fun otherHost_neverGetsSessionHeader() = runBlocking {
        val req = provider("https://api.openai.com/v1").buildRequest("{}", "minis-abc")
        assertNull(req.header("x-opencode-session"))
    }

    @Test
    fun lookalikeHost_neverGetsSessionHeader() = runBlocking {
        val req = provider("https://opencode.ai.evil.example/v1").buildRequest("{}", "minis-abc")
        assertNull(req.header("x-opencode-session"))
    }

    @Test
    fun userHeader_wins() = runBlocking {
        val req = provider(
            "https://opencode.ai/inference/go/openai/v1",
            mapOf("X-OpenCode-Session" to "mine"),
        ).buildRequest("{}", "minis-abc")
        assertEquals("mine", req.header("x-opencode-session"))
    }
}
