package io.github.slackerllc.minis.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the three canonical built-in Agent Presets (P-presets) and the
 * unmigrated-legacy alias table — the Android-authoritative registry is the
 * only source both clients read.
 */
class AgentPresetRegistryTest {

    @Test
    fun `built-in ids are exactly standard code minimal`() {
        val ids = AgentPresetRegistry.builtins.map { it.id }
        assertEquals(listOf("standard", "code", "minimal"), ids)
    }

    @Test
    fun `display names match the required product names`() {
        val byId = AgentPresetRegistry.builtins.associateBy { it.id }
        assertEquals("标准模式", byId.getValue("standard").name)
        assertEquals("PTC 模式", byId.getValue("code").name)
        assertEquals("极简模式", byId.getValue("minimal").name)
    }

    @Test
    fun `standard is the default preset`() {
        assertTrue(AgentPresetRegistry.builtins.first { it.id == "standard" }.isDefault)
    }

    @Test
    fun `minimal has a real reduced toolset`() {
        val minimal = AgentPresetRegistry.get("minimal")!!
        assertEquals(AgentPresetRegistry.Toolset.CORE, minimal.toolset)
        assertNotNull(minimal.promptSection)
        // FULL is the only full-capability toolset — minimal must be genuinely
        // different at the runtime level, not in a label.
        val standard = AgentPresetRegistry.get("standard")!!
        assertEquals(AgentPresetRegistry.Toolset.FULL, standard.toolset)
        assertTrue(standard.toolset != minimal.toolset)
    }

    @Test
    fun `code keeps full capabilities and adds composition prompt`() {
        val code = AgentPresetRegistry.get("code")!!
        assertEquals(AgentPresetRegistry.Toolset.FULL, code.toolset)
        assertNotNull(code.promptSection)
    }

    @Test
    fun `legacy ids resolve to canonical standard`() {
        assertEquals("standard", AgentPresetRegistry.get("default")?.id)
        assertEquals("standard", AgentPresetRegistry.get("workspace-sandboxed")?.id)
    }

    @Test
    fun `unknown presets are refused`() {
        assertNull(AgentPresetRegistry.get("nope"))
        assertFalse(AgentPresetRegistry.isKnownPreset("nope"))
        assertTrue(AgentPresetRegistry.isKnownPreset("standard"))
    }

    @Test
    fun `all builtin toolsets are recognized`() {
        for (preset in AgentPresetRegistry.builtins) {
            assertTrue(preset.id.isNotBlank())
            assertTrue(preset.description.isNotBlank())
            assertTrue(preset.trust == "system")
            assertNotNull(preset.toolset)
        }
    }
}
