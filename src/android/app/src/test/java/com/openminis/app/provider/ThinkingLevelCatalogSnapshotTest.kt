package com.openminis.app.provider

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Baseline captured from the pre-rules Kotlin catalog on PR0. */
class ThinkingLevelCatalogSnapshotTest {
    @Test
    fun `static and explicitly requested ids retain their catalog ceiling`() {
        val document = ModelRulesTestFixtures.bundledDocument()
        val additionalIds = listOf(
            "mimo-v2.5", "mimo-v2.5-pro", "seed-2.0", "bytedance-seed/x",
            "claude-opus-4-8", "claude-opus-4.7", "gpt-5.6-sol", "gpt-5.6-luna",
        )
        val allModels = document.staticModels.values.flatten() + additionalIds.map { LLMModel(it, it, "Snapshot") }
        val observed = allModels.associate { model ->
            val level = if (model.supportsReasoning == false) ThinkingLevel.OFF
            else model.selectableThinkingLevels.lastOrNull()
                ?: document.capabilitiesFor(model.id).maxThinkingLevel
                ?: ThinkingLevel.HIGH
            model.id to level
        }

        val expected = buildMap<String, ThinkingLevel> {
            listOf(
                "claude-fable-5", "claude-sonnet-5", "claude-sonnet-4-6", "claude-haiku-4-5",
                "gemini-3-flash-preview", "gemini-2.5-pro", "gemini-2.5-flash",
                "gemini-2.5-flash-lite", "gpt-5.5", "gpt-5.3-codex", "gpt-5.2-codex",
                "gpt-5.1-codex-max", "gpt-5.2", "gpt-4o", "gpt-4o-mini", "o3", "o4-mini",
                "codex-mini-latest", "anthropic/claude-sonnet-4", "google/gemini-2.5-flash",
                "openai/gpt-4o", "meta-llama/llama-4-maverick", "grok-4.6", "grok-4.5", "grok-4.3",
                "grok-4.20-0309-reasoning", "grok-4.20-0309-non-reasoning", "grok-4.20-multi-agent-0309",
                "grok-build-0.1", "grok-3-mini", "grok-3-mini-fast", "grok-composer-2.5-fast",
                "grok-4-fast", "grok-4-fast-non-reasoning", "grok-code-fast-1", "kimi-k3", "kimi-k2",
                "gpt-5.4", "gpt-5.4-mini", "gpt-image-2",
                // Added when the catalogs were brought up to date (2026-10).
                "claude-fable-5-1", "claude-opus-5-5", "claude-sonnet-5-5", "claude-opus-5",
                "gemini-3.1-pro-preview", "gemini-3.8-flash", "gemini-3.5-flash-lite",
                "grok-4.7", "anthropic/claude-opus-5.5", "anthropic/claude-sonnet-5.5",
                "google/gemini-3.8-flash", "x-ai/grok-4.7",
                "gpt-image-2.5-flare", "gpt-image-2.5-sunburst",
            ).forEach { put(it, ThinkingLevel.XHIGH) }
            listOf("claude-opus-4-8", "claude-opus-4-6", "claude-opus-4.7", "gpt-6-astra",
                "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna",
                "gpt-6.1-sol", "gpt-6-sol", "gpt-6-luna", "openai/gpt-6.1-sol").forEach { put(it, ThinkingLevel.MAX) }
            listOf("mimo-v2.5", "mimo-v2.5-pro", "seed-2.0", "bytedance-seed/x").forEach {
                put(it, ThinkingLevel.HIGH)
            }
        }
        assertEquals("snapshot includes every current static/requested id", expected.keys, observed.keys)
        assertEquals(expected, observed)
        document.staticModels.values.flatten().forEach { model ->
            if (model.supportsReasoning != false && model.selectableThinkingLevels.isEmpty()) {
                assertNotNull("${model.id} must have an explicit catalog rule", document.capabilitiesFor(model.id).maxThinkingLevel)
            }
        }
    }

    @Test
    fun `completely unknown model uses the explicitly changed HIGH fallback`() {
        val document = ModelRulesTestFixtures.bundledDocument()
        val unknown = LLMModel("vendor-new-reasoner-999", "Unknown", "Custom")
        assertEquals(null, document.capabilitiesFor(unknown.id).maxThinkingLevel)
        assertEquals(ThinkingLevel.HIGH, ThinkingLevelCatalog.declaredMaxLevel(unknown.id) ?: ThinkingLevel.HIGH)
    }
}
