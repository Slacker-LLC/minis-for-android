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
        val source = locateChatViewModelSource().readText(Charsets.UTF_8)
        val promptStart = source.indexOf("val base = identitySection")
        val promptEnd = source.indexOf("Scheduled tasks:", promptStart)
        assertTrue(promptStart > 0 && promptEnd > promptStart)
        val prompt = source.substring(promptStart, promptEnd)

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
}
