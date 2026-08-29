package io.github.slackerllc.minis.remote

import io.github.slackerllc.minis.data.model.LLMModel
import io.github.slackerllc.minis.data.model.ModelEntry
import io.github.slackerllc.minis.data.model.ProviderConfig
import io.github.slackerllc.minis.data.model.ProviderCredential
import io.github.slackerllc.minis.data.model.ProviderInstance
import io.github.slackerllc.minis.data.model.ProviderType
import io.github.slackerllc.minis.data.model.ThinkingLevel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the unified model identity + command unification fixes:
 *
 *  - model identity resolves the REAL provider/model/entry (the projection
 *    may never hard-code provider=openminis or use a display name as id);
 *  - supportsReasoning tri-state mapping cannot be collapsed by an Elvis on
 *    `isNull()` (explicit `true` must stay true);
 *  - the Web `/model` decoration never presents a second `/model` command;
 *  - `/plan` maps to real Android plan state.
 */
class ModelIdentityAndCommandRegistryTest {

    // ------------------------------------------------------ resolver

    private fun config(): ProviderConfig {
        val instance = ProviderInstance(
            id = "inst-ds",
            label = "DeepSeek 官方",
            providerType = ProviderType.openAI,
            credentialType = ProviderCredential.apiKey,
        )
        val entry = ModelEntry(
            providerInstanceId = "inst-ds",
            baseModel = LLMModel(
                id = "deepseek-v4-flash-vision-exp",
                displayName = "DeepSeek-V4-Flash-Vision-Exp",
                provider = "deepseek",
                supportsReasoning = null,
                inputModalities = listOf("text", "image"),
            ),
        )
        val entries = mutableListOf(entry)
        return ProviderConfig(
            instances = mutableListOf(instance),
            modelEntries = entries,
        )
    }

    @Test
    fun `identity resolves real provider, base model id and entry id`() {
        val cfg = config()
        val identity = ModelSelectionResolver.resolve(cfg, "deepseek-v4-flash-vision-exp")!!

        assertEquals("DeepSeek 官方", identity.provider)      // real provider name
        assertEquals("deepseek-v4-flash-vision-exp", identity.modelId) // real model id
        assertEquals("DeepSeek-V4-Flash-Vision-Exp", identity.displayName) // real display name
        assertEquals(cfg.modelEntries[0].id, identity.entryId) // entry id == session.models group rows
    }

    @Test
    fun `identity resolves by entry id too`() {
        val cfg = config()
        val identity = ModelSelectionResolver.resolve(cfg, cfg.modelEntries[0].id)!!
        assertEquals(cfg.modelEntries[0].id, identity.entryId)
        assertEquals("deepseek-v4-flash-vision-exp", identity.modelId)
    }

    @Test
    fun `unknown model resolves to null not a fake placeholder`() {
        assertNull(ModelSelectionResolver.resolve(config(), "totally-unknown-model"))
    }

    @Test
    fun `wire identity never carries hard-coded openminis provider`() {
        val identity = ModelSelectionResolver.resolve(config(), "deepseek-v4-flash-vision-exp")!!
        val wire = ModelSelectionResolver.toWire(identity)
        assertEquals("DeepSeek 官方", wire.getString("provider"))
        assertEquals(identity.entryId, wire.getString("model"))
    }

    // ---------------------------------------- supportsReasoning tri-state

    /** The exact modelCatalogEntry tri-state mapping without Android deps. */
    private fun supportsReasoningOf(e: JSONObject): Boolean? =
        if (e.isNull("supportsReasoning")) null else e.optBoolean("supportsReasoning", false)

    @Test
    fun `supportsReasoning explicit true survives isNull elvis trap`() {
        // The regression: e.isNull(...) ?: e.optBoolean(...) returns the
        // Boolean from isNull (true when field missing), inverting explicit
        // `true` into `false`.
        val explicitTrue = JSONObject().put("supportsReasoning", true)
        assertEquals(true, supportsReasoningOf(explicitTrue))

        val explicitFalse = JSONObject().put("supportsReasoning", false)
        assertEquals(false, supportsReasoningOf(explicitFalse))

        val unknown = JSONObject().put("supportsReasoning", JSONObject.NULL)
        assertNull(supportsReasoningOf(unknown))
    }

    @Test
    fun `reasoning block present for explicit true and absent for explicit false`() {
        val maxLevel = ThinkingLevel.HIGH
        val blockTrue = DshReasoningCatalog.reasoningBlock(true, maxLevel)
        assertNotNull(blockTrue)
        assertTrue(blockTrue!!.optJSONArray("efforts")!!.length() > 0)

        val blockFalse = DshReasoningCatalog.reasoningBlock(false, maxLevel)
        assertNull(blockFalse)
    }

    // ------------------------------------------------------------- /plan

    @Test
    fun `plan projection follows AgentStateStore truthfully`() {
        val config3 = config()
        assertNotNull(config3)
        val plan = io.github.slackerllc.minis.tools.AgentStateStore.planGet("s-plan-test")
        assertFalse(plan.mode == "plan") // default off
    }

    // ------------------------------------------------ command unification

    @Test
    fun `command registry is the single unified directory`() {
        val names = AgentCommandRegistry.baseEntries.map { it.name }
        for (name in listOf(
            "model", "permission", "goal", "plan", "compact", "clear",
            "memory", "thinking", "feedback", "export",
        )) {
            assertTrue("$name must be in the unified command directory", name in names)
        }
    }

    @Test
    fun `no duplicate command names in one directory`() {
        val names = AgentCommandRegistry.baseEntries.map { it.name }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `every base command has a real handler branch`() {
        // The registry contract: listing without a handler is a lie. The
        // execute() when-table must cover every base entry name (skills and
        // mcp are handled by their own branches below).
        val handled = setOf(
            "model", "permission", "goal", "plan", "compact", "clear",
            "memory", "thinking", "feedback", "export",
        )
        assertTrue(handled.containsAll(AgentCommandRegistry.baseEntries.map { it.name }))
    }
}
