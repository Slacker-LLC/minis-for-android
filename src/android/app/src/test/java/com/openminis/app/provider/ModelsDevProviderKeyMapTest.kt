package com.openminis.app.provider

import com.openminis.app.data.model.LLMModel
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The same model id is published by many resellers on models.dev with
 * different limits (`grok-4.7` output: 500000 on xai, 128000 on
 * github-copilot). A vendor missing from `providerKeyMap` falls through to a
 * registry-wide scan and picks whichever reseller sorts first, so first-party
 * vendors must resolve to their own entry.
 */
class ModelsDevProviderKeyMapTest {

    /** Aggregators whose keys sort BEFORE the first-party entries, so a registry-wide scan hits them first. */
    private val registry = requireNotNull(
        ModelsDevApi.parseRegistry(
            """
            {
              "302ai": {"models": {
                "grok-4.7": {"limit": {"context": 256000, "output": 1000}},
                "k3": {"limit": {"context": 8000, "output": 1000}},
                "only-on-302ai": {"limit": {"context": 7000, "output": 700}}
              }},
              "github-copilot": {"models": {
                "grok-4.7": {"limit": {"context": 256000, "output": 128000}},
                "k3": {"limit": {"context": 16000, "output": 2000}}
              }},
              "xai": {"models": {
                "grok-4.7": {"limit": {"context": 1000000, "output": 500000}}
              }},
              "kimi-code-plan-global": {"models": {
                "k3": {"limit": {"context": 1048576, "output": 131072}}
              }},
              "kimi-code-plan-cn": {"models": {
                "k3": {"limit": {"context": 1048576, "output": 131072}},
                "kimi-for-coding": {"limit": {"context": 1048576, "output": 32768}}
              }}
            }
            """.trimIndent(),
        ),
    )

    @Test
    fun `xAI model resolves to the xai entry when resellers disagree`() {
        val model = LLMModel("grok-4.7", "Grok 4.7", "xAI")

        val single = ModelsDevApi.enrichModel(registry, model)
        val batch = ModelsDevApi.enrichModels(registry, listOf(model)).single()

        assertEquals(500000, single.maxOutputTokens)
        assertEquals(1000000, single.contextWindow)
        assertEquals(single, batch)
    }

    @Test
    fun `Kimi model resolves to the kimi code entry when resellers disagree`() {
        val model = LLMModel("k3", "K3", "Kimi")

        val enriched = ModelsDevApi.enrichModels(registry, listOf(model)).single()

        assertEquals(131072, enriched.maxOutputTokens)
        assertEquals(1048576, enriched.contextWindow)
        assertEquals(enriched, ModelsDevApi.enrichModel(registry, model))
    }

    @Test
    fun `Kimi falls back from the global entry to the cn entry`() {
        // Only `kimi-code-plan-cn` lists this id.
        val enriched = ModelsDevApi.enrichModels(registry, listOf(LLMModel("kimi-for-coding", "x", "Kimi"))).single()

        assertEquals(32768, enriched.maxOutputTokens)
    }

    @Test
    fun `an id absent from the mapped vendor still falls back to the registry scan`() {
        val enriched = ModelsDevApi.enrichModels(registry, listOf(LLMModel("only-on-302ai", "x", "xAI"))).single()

        assertEquals(700, enriched.maxOutputTokens)
    }

    @Test
    fun `custom provider label keeps the registry scan`() {
        // A relay's models are labelled "Custom" and are expected to hit the scan;
        // sorted keys make "302ai" the pick, not the first-party entry.
        val enriched = ModelsDevApi.enrichModels(registry, listOf(LLMModel("grok-4.7", "x", "Custom"))).single()

        assertEquals(1000, enriched.maxOutputTokens)
    }

    @Test
    fun `every mapped models-dev key exists in the bundled snapshot`() {
        val snapshot = requireNotNull(ModelsDevApi.parseRegistry(bundledSnapshotText()))

        for ((label, keys) in ModelsDevApi.providerKeyMap) {
            for (key in keys) {
                assertNotNull("providerKeyMap[$label] -> '$key' is missing from models-dev-api.json", snapshot[key])
            }
        }
    }

    @Test
    fun `bundled snapshot resolves current first-party ids to their own vendor`() {
        val snapshot = requireNotNull(ModelsDevApi.parseRegistry(bundledSnapshotText()))
        val grok = snapshot.getValue("xai").models.getValue("grok-4.7")

        val enriched = ModelsDevApi.enrichModel(snapshot, LLMModel("grok-4.7", "Grok 4.7", "xAI"))

        assertEquals(grok.maxOutputTokens, enriched.maxOutputTokens)
        assertTrue(enriched.contextWindow != null && enriched.maxOutputTokens != null)
    }

    private fun bundledSnapshotText(): String {
        val candidates = listOf(
            File("src/main/assets/models-dev-api.json"),
            File("app/src/main/assets/models-dev-api.json"),
            File("src/android/app/src/main/assets/models-dev-api.json"),
        )
        val asset = candidates.firstOrNull(File::isFile)
        assertNotNull("Unable to locate models-dev-api.json from ${System.getProperty("user.dir")}", asset)
        return requireNotNull(asset).readText(Charsets.UTF_8)
    }
}
