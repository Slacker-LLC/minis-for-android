package com.openminis.app.ui.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Quoting a sent message: its text becomes a quote card, its files are attached to the next message once. */
@RunWith(AndroidJUnit4::class)
class QuoteMessageTest {
    @Test
    fun aSentMessageQuotesItsTextAndItsFiles() = runBlocking {
        withChatVm(chatOnly = true, ScriptedProvider { _, _, _ -> text("ok") }) { vm, _, _ ->
            val ctx = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            val image = java.io.File(ctx.cacheDir, "quote-pic.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val doc = java.io.File(ctx.cacheDir, "quote-note.txt").apply { writeText("note") }
            val message = ChatMessage(
                id = "m1",
                role = "user",
                content = "> older quote\n\nthe question",
                imageUris = listOf(android.net.Uri.fromFile(image)),
                attachmentNames = listOf("quote-pic.png", "quote-note.txt"),
                attachmentUris = listOf(android.net.Uri.fromFile(doc)),
            )
            withContext(Dispatchers.Main) {
                vm.quoteMessage(message)
                vm.quoteMessage(message)
            }
            assertEquals(listOf("the question"), vm.quotedTexts.value)
            val attached = vm.attachments.value
            assertEquals(listOf("quote-pic.png", "quote-note.txt"), attached.map { it.fileName })
            assertTrue(attached[0].isImage)
            assertTrue(!attached[1].isImage)
            image.delete(); doc.delete()
        }
    }

    @Test
    fun aMissingFileIsNotQuoted() = runBlocking {
        withChatVm(chatOnly = true, ScriptedProvider { _, _, _ -> text("ok") }) { vm, _, _ ->
            withContext(Dispatchers.Main) { vm.quoteAttachmentFile(java.io.File("/nonexistent/x.png"), "x.png") }
            assertTrue(vm.attachments.value.isEmpty())
        }
    }
}
