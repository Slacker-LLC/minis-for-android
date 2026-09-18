package com.openminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPromptRemnantGuardTest {

    private fun locateChatViewModelSource(): File {
        val cwd = File(System.getProperty("user.dir")).canonicalFile
        val candidates = listOf(
            File(cwd, "src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt"),
            File(cwd, "app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt"),
            File(cwd, "src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("cannot find ChatViewModel.kt from ${cwd.path}")
    }

    @Test
    fun systemPromptPurgesObsoleteAlpineAndProotRemnants() {
        // [T-system-prompt-modules] The static prompt is no longer a literal in
        // ChatViewModel; the shipped wording lives in the module default files
        // under assets/prompts. Scan every module file rather than a slice of one
        // source file, so wording cannot dodge the check by moving modules.
        val prompt = locatePromptModuleDir().listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".md") }
            .sortedBy { it.name }
            .joinToString("\n") { it.readText(Charsets.UTF_8) }

        // Must NOT contain obsolete package manager references
        assertFalse("Prompt must not instruct model to use apk add", prompt.contains("apk add"))
        assertFalse("Prompt must not instruct model to use apk search", prompt.contains("apk search"))

        // Must NOT frame shell as BusyBox ash
        assertFalse("Prompt must not claim shell is BusyBox ash", prompt.contains("BusyBox ash"))

        // Must NOT frame runtime as PRoot
        assertFalse("Prompt must not reference PRoot sandbox", prompt.contains("PRoot sandbox"))
        assertFalse("Prompt must not claim PRoot stays default", prompt.contains("PRoot stays the default"))

        // Must NOT contain Alpine-specific wheel warnings
        assertFalse("Prompt must not warn about musllinux", prompt.contains("musllinux"))

        // Must NOT contain obsolete self-update branding
        assertFalse("Prompt must not reference OpenMinis in self-update", prompt.contains("self-update of OpenMinis"))

        // Positive assertions: must reference Ubuntu and apt
        assertTrue("Prompt must guide to apt", prompt.contains("apt"))
        assertTrue("Prompt must frame shell as Ubuntu 24.04 / Bash", prompt.contains("Ubuntu 24.04"))
    }

    private fun locatePromptModuleDir(): File {
        val cwd = File(System.getProperty("user.dir")).canonicalFile
        val candidates = listOf(
            File(cwd, "src/main/assets/prompts"),
            File(cwd, "app/src/main/assets/prompts"),
            File(cwd, "src/android/app/src/main/assets/prompts"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("cannot find assets/prompts from ${cwd.path}")
    }

    @Test
    fun chatViewModelDelegatesTheStaticPromptToTheModuleComposer() {
        val source = locateChatViewModelSource().readText(Charsets.UTF_8)

        assertTrue(
            "ChatViewModel must assemble the static prompt through AgentSystemPrompt",
            source.contains("com.openminis.app.prompt.AgentSystemPrompt.base("),
        )
        assertFalse(
            "ChatViewModel must not inline prompt wording again",
            source.contains("Available tools:"),
        )
    }

    @Test
    fun androidAgentToolsPurgesObsoleteProotAndOpenMinis() {
        val defs = com.openminis.app.tools.android.AndroidAgentTools.definitions()
        for (tool in defs) {
            assertFalse("Tool ${tool.name} must not reference PRoot: ${tool.description}", tool.description.contains("PRoot", ignoreCase = true))
            assertFalse("Tool ${tool.name} must not reference OpenMinis: ${tool.description}", tool.description.contains("OpenMinis", ignoreCase = true))
        }
        val deployTool = defs.first { it.name == com.openminis.app.tools.android.AndroidAgentTools.DEPLOY }
        assertTrue("Deploy tool must reference Ubuntu 24.04", deployTool.description.contains("Ubuntu 24.04"))
        val capTool = defs.first { it.name == com.openminis.app.tools.android.AndroidAgentTools.CAPABILITIES }
        assertTrue("Capabilities tool must reference Direct Ubuntu", capTool.description.contains("Direct Ubuntu"))
        assertFalse("Capabilities tool must not reference obsolete minisd", capTool.description.contains("minisd", ignoreCase = true))
    }

    @Test
    fun debugMethodRegistryPurgesObsoleteProotRemnants() {
        val methods = com.openminis.app.debug.DebugMethodRegistry.methods
        for (m in methods) {
            assertFalse("Debug method ${m.name} description must not contain proot: ${m.description}", m.description.contains("proot", ignoreCase = true))
            for (p in m.params) {
                assertFalse("Debug method ${m.name} param ${p.name} description must not contain proot: ${p.description}", p.description.contains("proot", ignoreCase = true))
            }
        }
    }

    @Test
    fun mirrorSettingsPurgesAlpineAndAdoptsUbuntuApt() {
        val categories = com.openminis.app.ui.sandbox.MirrorCategory.entries
        assertFalse("MirrorCategory must not contain Alpine", categories.any { it.name.contains("ALPINE", ignoreCase = true) })
        assertTrue("MirrorCategory must contain UBUNTU_APT", categories.any { it == com.openminis.app.ui.sandbox.MirrorCategory.UBUNTU_APT })

        val aptCat = com.openminis.app.ui.sandbox.MirrorCategory.UBUNTU_APT
        assertTrue("Ubuntu APT config path must target apt sources", aptCat.configPath.contains("sources"))

        // Backward compatibility
        val legacyResolved = com.openminis.app.ui.sandbox.MirrorCatalog.categoryFromKey("alpine")
        assertTrue("Legacy 'alpine' key must map to UBUNTU_APT", legacyResolved == aptCat)

        val aptMirrors = com.openminis.app.ui.sandbox.MirrorCatalog.aptMirrors
        assertTrue("Must include official Ubuntu ports mirror", aptMirrors.any { it.baseURL.contains("ubuntu-ports") })
        assertTrue("Must include Tsinghua TUNA mirror", aptMirrors.any { it.id.contains("tuna") })
    }
}
