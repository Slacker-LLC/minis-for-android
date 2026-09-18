package com.openminis.app.prompt

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-system-prompt-modules] Pins the extraction of the system prompt into
 * standalone modules.
 *
 * The two fixture comparisons are the important ones: the checked-in
 * `legacy_modules_memory_on/off.txt` files are the prompt exactly as it was
 * hardcoded in ChatViewModel.buildSystemPrompt() before the extraction. If the
 * default modules (asset files + registry order + gaps + gates) stop
 * reproducing them byte for byte, this refactor changed what the model sees and
 * the test fails instead of shipping silently.
 */
class SystemPromptComposerTest {

    private val assetDir = File("src/main/assets/prompts")

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/prompts/$name")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("missing test fixture: $name")

    private fun snapshots(
        overrides: Map<String, String> = emptyMap(),
        disabled: Set<String> = emptySet(),
    ): List<PromptModuleStore.ModuleSnapshot> {
        val defaults = PromptModuleStore.DirectoryDefaults(assetDir)
        return PromptModuleRegistry.modules.map { module ->
            PromptModuleStore.ModuleSnapshot(
                module = module,
                defaultText = (
                    defaults.read(module.assetName)
                        ?: error("missing shipped default: ${module.assetName}")
                    ).trim(),
                overrideText = overrides[module.id]?.trim(),
                isEnabled = module.id !in disabled,
            )
        }
    }

    private fun compose(
        memoryOn: Boolean,
        overrides: Map<String, String> = emptyMap(),
        disabled: Set<String> = emptySet(),
    ): String = AgentSystemPrompt.modulesSection(snapshots(overrides, disabled), memoryOn)

    private fun asset(assetName: String): String =
        File(assetDir, assetName).readText(Charsets.UTF_8).trim()

    @Test
    fun `default modules reproduce the pre-module prompt with memory on`() {
        assertEquals(fixture("legacy_modules_memory_on.txt"), compose(memoryOn = true))
    }

    @Test
    fun `default modules reproduce the pre-module prompt with memory off`() {
        assertEquals(fixture("legacy_modules_memory_off.txt"), compose(memoryOn = false))
    }

    @Test
    fun `memory gate swaps the tool bullets and the notice`() {
        val on = compose(memoryOn = true)
        val off = compose(memoryOn = false)

        assertTrue(on.contains("memory_write: Save a memory entry"))
        assertTrue(on.contains("Memory system (currently ENABLED)"))
        assertFalse(on.contains("Memory system (currently DISABLED)"))

        assertTrue(off.contains("Memory system (currently DISABLED)"))
        assertFalse(off.contains("Memory system (currently ENABLED)"))
        assertFalse(off.contains("memory_get: Recall memories"))
    }

    @Test
    fun `override replaces the module text in place`() {
        val prompt = compose(memoryOn = true, overrides = mapOf("style.tone" to "Always answer in Latin."))

        // A module is the whole section, heading included: the override replaces
        // all of it and keeps the surrounding gaps/order.
        assertTrue(prompt.contains("\n\nAlways answer in Latin.\n\n"))
        assertFalse(prompt.contains("Tone and style:"))
        assertFalse(prompt.contains("Reply in the language that best matches the user's input."))
        // Assembly order is owned by the registry, not by the override.
        assertTrue(prompt.indexOf("Tool call style:") < prompt.indexOf("Always answer in Latin."))
        assertTrue(prompt.indexOf("Always answer in Latin.") < prompt.indexOf("Android development debug loop"))
    }

    @Test
    fun `switching a module off drops its text and its separator`() {
        val block = asset("style.toolCall.md")
        val full = compose(memoryOn = true)
        val without = compose(memoryOn = true, disabled = setOf("style.toolCall"))

        assertTrue(full.contains(block + "\n\n"))
        assertEquals(full.replace(block + "\n\n", ""), without)
    }

    @Test
    fun `blank text drops the module exactly like switching it off`() {
        val disabled = compose(memoryOn = true, disabled = setOf("paths.minisUrl"))
        val blanked = compose(memoryOn = true, overrides = mapOf("paths.minisUrl" to "   \n  "))

        assertEquals(disabled, blanked)
    }

    @Test
    fun `module text is trimmed so editor whitespace cannot change the layout`() {
        val clean = compose(memoryOn = true, overrides = mapOf("style.tone" to "Be brief."))
        val noisy = compose(memoryOn = true, overrides = mapOf("style.tone" to "\n\nBe brief.\n\n"))

        assertEquals(clean, noisy)
    }

    @Test
    fun `custom system prompt renders nothing when unset`() {
        assertEquals("", AgentSystemPrompt.customPromptSection(null))
        assertEquals("", AgentSystemPrompt.customPromptSection(""))
        assertEquals("", AgentSystemPrompt.customPromptSection("  \n\t "))
    }

    @Test
    fun `custom system prompt states its precedence and keeps the text verbatim`() {
        val section = AgentSystemPrompt.customPromptSection("OWNER-RULE: always answer in Latin.")

        assertTrue(section.contains("highest priority"))
        assertTrue(section.endsWith("follow it.\nOWNER-RULE: always answer in Latin.\n\n"))
        // The stored text is not rewritten; only the header is ours.
        assertEquals(
            AgentSystemPrompt.customPromptSection("OWNER-RULE: always answer in Latin."),
            AgentSystemPrompt.customPromptSection("  OWNER-RULE: always answer in Latin.  \n"),
        )
    }

    @Test
    fun `custom system prompt precedes every shipped module`() {
        val prompt = "You are Minis.\n\n" +
            AgentSystemPrompt.customPromptSection("OWNER-RULE: be terse.") +
            compose(memoryOn = true)

        val owner = prompt.indexOf("OWNER-RULE")
        assertTrue(owner > 0)
        assertTrue(owner < prompt.indexOf("You should proactively use shell commands"))
        assertTrue(owner < prompt.indexOf("Available tools:"))
        assertTrue(owner < prompt.indexOf("Scheduled tasks: crontab"))
    }
}
