package com.openminis.app.prompt

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-system-prompt-modules] Keeps the registry and the shipped defaults in sync:
 * a module without a default file (or a default file no module claims) is a
 * prompt silently missing a section, so both directions are asserted.
 */
class PromptModuleRegistryTest {

    private val assetDir = File("src/main/assets/prompts")

    @Test
    fun `module ids are unique and stable dotted keys`() {
        val ids = PromptModuleRegistry.ids()

        assertEquals(ids.size, ids.toSet().size)
        for (id in ids) {
            assertTrue("unexpected id shape: $id", Regex("[a-z][A-Za-z0-9]*(\\.[A-Za-z][A-Za-z0-9]*)+").matches(id))
        }
    }

    @Test
    fun `every module has a non-empty shipped default`() {
        for (module in PromptModuleRegistry.modules) {
            val file = File(assetDir, module.assetName)
            assertTrue("missing shipped default: ${module.assetName}", file.isFile)
            assertTrue("blank shipped default: ${module.assetName}", file.readText(Charsets.UTF_8).isNotBlank())
        }
    }

    @Test
    fun `no default file is left unregistered`() {
        val registered = PromptModuleRegistry.modules.map { it.assetName }.toSet()
        val files = assetDir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".md") }
            .map { it.name }
            .toSet()

        assertEquals(registered, files)
    }

    @Test
    fun `memory notice modules cover both gate states`() {
        val memoryOn = PromptModuleRegistry.modules
            .filter { it.gate == PromptGate.MEMORY_ON }
            .map { it.id }
        val memoryOff = PromptModuleRegistry.modules
            .filter { it.gate == PromptGate.MEMORY_OFF }
            .map { it.id }

        assertEquals(listOf("tools.memory", "memory.notice.enabled"), memoryOn)
        assertEquals(listOf("memory.notice.disabled"), memoryOff)
    }
}
