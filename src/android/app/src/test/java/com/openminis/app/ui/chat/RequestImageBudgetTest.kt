package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.provider.ImageBudget
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestImageBudgetTest {
    private fun image(bytes: Int, linuxPath: String?, fill: Byte = 1) =
        AgentContentPart.ImageData(ByteArray(bytes) { fill }, "image/jpeg", linuxPath)

    private fun msg(vararg parts: AgentContentPart) =
        LLMMessage(role = LLMMessage.Role.USER, content = "", contentParts = parts.toList())

    private fun run(messages: List<LLMMessage>): Pair<List<LLMMessage>, List<ImageBudget.RequestBudgetPlan>> {
        val events = mutableListOf<ImageBudget.RequestBudgetPlan>()
        val out = runBlocking { RequestImageBudget.apply(messages, "s", events::add) }
        return out to events
    }

    @Test
    fun `without images the same list comes back and nothing is reported`() {
        val messages = listOf(msg(AgentContentPart.Text("hi")))
        val (out, events) = run(messages)
        assertSame(messages, out)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `images that fit are left alone`() {
        val messages = listOf(msg(image(1_000, "/var/minis/attachments/uploads/a.jpg")))
        val (out, events) = run(messages)
        assertSame(messages, out)
        assertTrue(events.isEmpty())
    }

    /** Six images of the per-image cap: the request cap holds five, so the oldest does not fit. */
    private val cap = ImageBudget.MAX_PER_IMAGE_BYTES.toInt()

    @Test
    fun `when the history is over the cap the oldest image becomes a placeholder that points at the upload`() {
        val images = (1..6).map { image(cap, "/var/minis/attachments/uploads/$it.jpg", fill = it.toByte()) }
        val messages = images.map { msg(it) }
        val (out, events) = run(messages)

        assertEquals(1, events.size)
        assertEquals(1, events.single().droppedCount)
        // The newest images are protected; the oldest is replaced by a text part naming where to re-read it.
        assertTrue(out.drop(1).all { it.contentParts.single() is AgentContentPart.ImageData })
        val first = out.first().contentParts.single()
        assertTrue(first is AgentContentPart.Text)
        assertTrue((first as AgentContentPart.Text).text.contains("/var/minis/attachments/uploads/1.jpg"))
        // The input list is not modified.
        assertTrue(messages.first().contentParts.single() is AgentContentPart.ImageData)
    }

    @Test
    fun `a tool result keeps its place but loses its bytes and gains the elision note`() {
        val result = AgentContentPart.ToolResult(
            id = "t1", name = "browser_use", content = "screenshot taken", imageData = ByteArray(cap) { 9 },
            imageMimeType = "image/jpeg", imageLinuxPath = "/var/minis/attachments/uploads/shot.jpg",
        )
        val rest = (1..5).map { msg(image(cap, "/p/$it.jpg", it.toByte())) }
        val (out, _) = run(listOf(msg(result)) + rest)
        val part = out.first().contentParts.single() as AgentContentPart.ToolResult
        assertNull(part.imageData)
        assertTrue(part.content.startsWith("screenshot taken\n"))
        assertTrue(part.content.contains("/var/minis/attachments/uploads/shot.jpg"))
    }
}
