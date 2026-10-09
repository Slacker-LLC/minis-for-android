package com.openminis.app.ui.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A document the user attaches, or a long paste that became one, reaches the model as an entry in
 * `<user-attached-files>` (path, size, time) and nothing more. The model opens the file from that path when it
 * needs the content, so the body is not paid for on every turn.
 */
@RunWith(AndroidJUnit4::class)
class AttachedFilesAreListedNotInlinedTest {
    private fun userTexts(messages: List<LLMMessage>): String = messages
        .filter { it.role == LLMMessage.Role.USER }
        .flatMap { m -> m.contentParts.filterIsInstance<AgentContentPart.Text>().map { it.text } + m.content }
        .joinToString("\n")

    @Test
    fun aLongPasteIsListedByPath() = runBlocking {
        val seen = mutableListOf<List<LLMMessage>>()
        val provider = ScriptedProvider { _, messages, _ -> seen += messages; text("Got it") }
        withChatVm(chatOnly = true, provider) { vm, _, _ ->
            val big = "MARKER-LONG-PASTE\n" + "line of a long paste.\n".repeat(1200)
            withContext(Dispatchers.Main) {
                vm.stashPastedTextAsFile(big)
                vm.sendMessage("")
            }
            withTimeout(30_000L) { while (seen.isEmpty()) delay(50) }
            val request = userTexts(seen.first())
            assertTrue("the paste is listed by its path", request.contains("/var/minis/attachments/uploads/Pasted_"))
            assertFalse("the pasted body is not in the request", request.contains("MARKER-LONG-PASTE"))
        }
    }

    @Test
    fun aTextFileIsListedByPath() = runBlocking {
        val seen = mutableListOf<List<LLMMessage>>()
        val provider = ScriptedProvider { _, messages, _ -> seen += messages; text("Got it") }
        withChatVm(chatOnly = true, provider) { vm, _, _ ->
            val ctx = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            val file = java.io.File(ctx.cacheDir, "listed-note.txt").apply { writeText("MARKER-NOTE-BODY") }
            val attachment = InputAttachment(
                fileName = file.name,
                uri = android.net.Uri.fromFile(file),
                mimeType = "text/plain",
                kind = InputAttachment.Kind.DOCUMENT,
            )
            withContext(Dispatchers.Main) {
                vm.addAttachment(attachment)
                vm.sendMessage("read this")
            }
            withTimeout(30_000L) { while (seen.isEmpty()) delay(50) }
            val request = userTexts(seen.first())
            assertTrue("the file is listed by its path", request.contains("/var/minis/attachments/uploads/listed-note.txt"))
            assertFalse("the file body is not in the request", request.contains("MARKER-NOTE-BODY"))
            file.delete()
        }
    }
}
