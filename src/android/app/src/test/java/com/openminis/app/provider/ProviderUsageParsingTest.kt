package com.openminis.app.provider

import com.openminis.app.provider.anthropic.AnthropicProvider
import com.openminis.app.provider.gemini.parseGeminiUsageMetadata
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderUsageParsingTest {
    private fun json(s: String) = JSONObject(s)

    @Test
    fun anthropicContextSizeIncludesCacheTokens() {
        val u = AnthropicProvider.mergeUsage(
            json("""{"input_tokens":12,"cache_read_input_tokens":90000,"cache_creation_input_tokens":500,"output_tokens":1}"""),
            null,
        )
        assertEquals(12, u.inputTokens)
        assertEquals(90_512, u.latestContextTokens)
        assertEquals(90_000, u.cacheReadInputTokens)
        assertEquals(500, u.cacheCreationInputTokens)
    }

    @Test
    fun anthropicDeltaWithOnlyOutputKeepsStartInputAndCache() {
        val start = AnthropicProvider.mergeUsage(
            json("""{"input_tokens":12,"cache_read_input_tokens":90000,"output_tokens":1}"""),
            null,
        )
        val end = AnthropicProvider.mergeUsage(json("""{"output_tokens":340}"""), start)
        assertEquals(12, end.inputTokens)
        assertEquals(90_000, end.cacheReadInputTokens)
        assertEquals(340, end.outputTokens)
        assertEquals(90_012, end.latestContextTokens)
    }

    @Test
    fun anthropicDeltaReportingZeroInputDoesNotEraseStartValues() {
        val start = AnthropicProvider.mergeUsage(json("""{"input_tokens":50,"output_tokens":1}"""), null)
        val end = AnthropicProvider.mergeUsage(json("""{"input_tokens":0,"output_tokens":9}"""), start)
        assertEquals(50, end.inputTokens)
        assertEquals(9, end.outputTokens)
    }

    @Test
    fun anthropicCumulativeDeltaOverridesStart() {
        val start = AnthropicProvider.mergeUsage(json("""{"input_tokens":50,"output_tokens":1}"""), null)
        val end = AnthropicProvider.mergeUsage(
            json("""{"input_tokens":60,"cache_read_input_tokens":10,"output_tokens":9}"""),
            start,
        )
        assertEquals(60, end.inputTokens)
        assertEquals(10, end.cacheReadInputTokens)
        assertEquals(70, end.latestContextTokens)
    }

    @Test
    fun geminiOutputIncludesThoughtsAndCacheIsSplitFromInput() {
        val u = parseGeminiUsageMetadata(
            json("""{"promptTokenCount":1000,"cachedContentTokenCount":800,"candidatesTokenCount":50,"thoughtsTokenCount":300}"""),
        )
        assertEquals(200, u.inputTokens)
        assertEquals(800, u.cacheReadInputTokens)
        assertEquals(350, u.outputTokens)
        assertEquals(1000, u.latestContextTokens)
    }

    @Test
    fun geminiWithoutCacheOrThoughtsKeepsPlainCounts() {
        val u = parseGeminiUsageMetadata(json("""{"promptTokenCount":100,"candidatesTokenCount":20}"""))
        assertEquals(100, u.inputTokens)
        assertNull(u.cacheReadInputTokens)
        assertEquals(20, u.outputTokens)
    }

    @Test
    fun geminiRejectsImplausibleCachedCount() {
        // cached larger than prompt would make input negative; treat it as absent.
        val u = parseGeminiUsageMetadata(json("""{"promptTokenCount":100,"cachedContentTokenCount":500,"candidatesTokenCount":1}"""))
        assertEquals(100, u.inputTokens)
        assertNull(u.cacheReadInputTokens)
    }
}
