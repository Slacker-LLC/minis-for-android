package com.openminis.app.ui.chat

import com.openminis.app.data.ContextOffload
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Offload only replaces what it really saved, and reports what it really freed. */
class ContextOffloaderFailureTest {
    private class FakeStore(var failContent: Boolean = false, var failImage: Boolean = false) : OffloadStore {
        val written = mutableListOf<String>()
        override suspend fun content(content: String, toolId: String, toolName: String): String =
            if (failContent) "" else "/var/minis/offloads/tools/${toolName}_$toolId.txt".also { written += it }
        override suspend fun image(bytes: ByteArray, toolId: String, mimeType: String): String =
            if (failImage) "" else "/var/minis/offloads/tools/image_$toolId.png".also { written += it }
    }

    private val big = "x".repeat(40_000)

    /** Old turns with a big tool result, then four short messages that are always protected. */
    private fun history(result: AgentContentPart.ToolResult): MutableList<LLMMessage> = mutableListOf(
        LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(AgentContentPart.Text("go"))),
        LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(result)),
        LLMMessage(LLMMessage.Role.ASSISTANT, "a"), LLMMessage(LLMMessage.Role.USER, "b"),
        LLMMessage(LLMMessage.Role.ASSISTANT, "c"), LLMMessage(LLMMessage.Role.USER, "d"),
    )

    private fun run(store: OffloadStore, h: MutableList<LLMMessage>): Int = runBlocking {
        ContextOffloader.offloadIfNeeded(store, h, contextWindow = 32_768, lastContextTokens = 30_000)
    }

    private fun firstResult(h: List<LLMMessage>) = h[1].contentParts.single() as AgentContentPart.ToolResult

    @Test
    fun `a saved result becomes a stub and the freed tokens are reported`() {
        val store = FakeStore()
        val h = history(AgentContentPart.ToolResult("t1", "shell", big))
        val freed = run(store, h)
        assertTrue(firstResult(h).content.startsWith(ContextOffload.OFFLOADED_PREFIX))
        assertTrue(freed > 1_000)
        assertEquals(1, store.written.size)
    }

    @Test
    fun `a failed write keeps the original result and reports nothing freed`() {
        val h = history(AgentContentPart.ToolResult("t1", "shell", big))
        val freed = run(FakeStore(failContent = true), h)
        assertEquals(big, firstResult(h).content)
        assertEquals(0, freed)
    }

    @Test
    fun `a failed tool input write keeps the file content the model wrote`() {
        val use = AgentContentPart.ToolUse("t2", "file_write", JSONObject().put("path", "/a").put("content", big))
        val h = mutableListOf(
            LLMMessage(LLMMessage.Role.ASSISTANT, "", contentParts = listOf(use)),
            LLMMessage(LLMMessage.Role.USER, "u"), LLMMessage(LLMMessage.Role.ASSISTANT, "a"),
            LLMMessage(LLMMessage.Role.USER, "b"), LLMMessage(LLMMessage.Role.ASSISTANT, "c"),
        )
        run(FakeStore(failContent = true), h)
        assertEquals(big, (h[0].contentParts.single() as AgentContentPart.ToolUse).input.getString("content"))
        run(FakeStore(), h)
        assertTrue((h[0].contentParts.single() as AgentContentPart.ToolUse).input.getString("content").startsWith(ContextOffload.OFFLOADED_PREFIX))
    }

    @Test
    fun `a failed image write keeps the image while the text is still stubbed`() {
        val img = ByteArray(50_000) { 7 }
        val h = history(AgentContentPart.ToolResult("t3", "browser", big, imageData = img, imageMimeType = "image/png"))
        run(FakeStore(failImage = true), h)
        val r = firstResult(h)
        assertTrue(r.content.startsWith(ContextOffload.OFFLOADED_PREFIX))
        assertTrue("the image was not saved, so it stays", r.imageData != null)
    }

    @Test
    fun `a failed image write with nothing else to save leaves a bare image alone`() {
        val h = mutableListOf(
            LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(AgentContentPart.ImageData(ByteArray(50_000) { 1 }, "image/png"))),
            LLMMessage(LLMMessage.Role.ASSISTANT, "a"), LLMMessage(LLMMessage.Role.USER, "b"),
            LLMMessage(LLMMessage.Role.ASSISTANT, "c"), LLMMessage(LLMMessage.Role.USER, "d"),
        )
        run(FakeStore(failImage = true), h)
        assertTrue(h[0].contentParts.single() is AgentContentPart.ImageData)
    }

    @Test
    fun `two tool ids that share their last 12 characters get different files`() {
        val a = "toolu_01ABCDEFGHIJKLMN-same-tail-0001"
        val b = "toolu_02ZYXWVUTSRQPONM-same-tail-0001"
        assertEquals(a.takeLast(12), b.takeLast(12))
        assertNotEquals(ContextOffload.shortToolId(a), ContextOffload.shortToolId(b))
        // A short safe id is used as it is; an unsafe one never carries a path separator.
        assertEquals("call_1", ContextOffload.shortToolId("call_1"))
        assertTrue(!ContextOffload.shortToolId("../../etc/x").contains('/'))
    }
}
