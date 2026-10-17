package com.openminis.app.data.model

import com.openminis.app.provider.ModelRulesTestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ModelMetadataInheritanceTest {

    @Before
    fun setUp() {
        ModelRulesTestFixtures.installBundledCatalog()
    }

    private fun model(
        id: String,
        window: Int? = null,
        reasoning: Boolean? = null,
        effort: List<String>? = null,
        noEffort: Boolean? = null,
    ) = LLMModel(
        id = id,
        displayName = id,
        provider = "Custom",
        contextWindow = window,
        supportsReasoning = reasoning,
        reasoningEffortValues = effort,
        declaresNoEffortTiers = noEffort,
    )

    @Test
    fun `a relay model inherits window, reasoning and effort tiers from the same id elsewhere`() {
        val relay = model("zz-test-flash")
        val donor = model("zz-test-flash", window = 1_000_000, reasoning = true, effort = listOf("high", "max"))

        val merged = relay.inheritMissingMetadata(listOf(donor))

        assertEquals(1_000_000, merged.contextWindow)
        assertEquals(true, merged.supportsReasoning)
        assertEquals(listOf("high", "max"), merged.reasoningEffortValues)
    }

    @Test
    fun `ids match across vendor prefixes and case`() {
        val merged = model("ZZ-Test-Flash")
            .inheritMissingMetadata(listOf(model("deepseek/zz-test-flash", window = 262_144)))
        assertEquals(262_144, merged.contextWindow)
    }

    @Test
    fun `values the entry already has are never replaced`() {
        val own = model("zz-test-flash", window = 128_000, reasoning = false, effort = listOf("low"))
        val donor = model("zz-test-flash", window = 1_000_000, reasoning = true, effort = listOf("high", "max"))

        assertEquals(own, own.inheritMissingMetadata(listOf(donor)))
    }

    @Test
    fun `a different model is not a donor`() {
        val relay = model("zz-test-flash")
        val others = listOf(
            model("zz-test-flash-lite", window = 32_000, reasoning = true),
            model("zz-test", window = 64_000),
        )
        assertEquals(relay, relay.inheritMissingMetadata(others))
        assertEquals(relay, relay.inheritMissingMetadata(emptyList()))
    }

    @Test
    fun `disagreeing windows resolve to the most common value, then the smaller one`() {
        val relay = model("zz-test-flash")
        val common = relay.inheritMissingMetadata(
            listOf(1_048_576, 1_000_000, 1_000_000).map { model("zz-test-flash", window = it) },
        )
        assertEquals(1_000_000, common.contextWindow)

        val tie = relay.inheritMissingMetadata(
            listOf(1_048_576, 1_000_000).map { model("zz-test-flash", window = it) },
        )
        assertEquals(1_000_000, tie.contextWindow)

        // A non-positive window is not metadata.
        assertNull(relay.inheritMissingMetadata(listOf(model("zz-test-flash", window = 0))).contextWindow)
    }

    @Test
    fun `no-effort-tiers is not copied when another peer declares tiers`() {
        val merged = model("zz-test-flash").inheritMissingMetadata(
            listOf(
                model("zz-test-flash", reasoning = true, noEffort = true),
                model("zz-test-flash", reasoning = true, effort = listOf("high")),
            ),
        )
        assertEquals(listOf("high"), merged.reasoningEffortValues)
        assertNull(merged.declaresNoEffortTiers)
    }

    @Test
    fun `window is reported unknown only when neither the entry nor the rules have one`() {
        assertFalse(model("zz-never-heard-of-it").hasKnownContextWindow)
        assertTrue(model("zz-never-heard-of-it", window = 8_000).hasKnownContextWindow)
        assertFalse(model("zz-never-heard-of-it", window = 0).hasKnownContextWindow)
        assertTrue(model("gpt-6-astra").hasKnownContextWindow)
    }
}
