package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.MediaRef
import com.openminis.app.provider.LLMProvider
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a streamed response is folded into blocks, text and tool calls, tested without a ViewModel. */
class TurnStreamTest {
    private class FakeHost : TurnStreamHost {
        val updates = mutableListOf<String>()
        val checkpointText = StringBuilder()
        override suspend fun <T> onMain(block: () -> T): T = block()
        override fun updateMessage(assistantId: String, content: String, blocks: List<AssistantBlock>) {
            updates += content
        }
        override fun recordCheckpointText(blockIndex: Int, text: String) { checkpointText.append(text) }
        override fun hostedToolLabel(kind: String) = "Web search"
        override fun hostedToolFailed() = "failed"
        override fun saveMedia(data: ByteArray, mimeType: String): MediaRef = error("no media in these tests")
        override val mediaBaseDir = File("/nonexistent")
    }

    private class FakeProvider(override val streamTextIsMonolithic: Boolean = false) : LLMProvider {
        override val name = "fake"
        override var model = LLMModel("fake-model", "Fake", "fake")

        override suspend fun sendMessageClamped(
            messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int, temperature: Double?,
            imageParts: List<LLMMessage.ImagePart>, tools: List<AgentToolDefinition>, thinkingLevel: ThinkingLevel,
        ): LLMResponse = error("not used")

        override fun streamMessageClamped(
            messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int, temperature: Double?,
            imageParts: List<LLMMessage.ImagePart>, tools: List<AgentToolDefinition>, thinkingLevel: ThinkingLevel,
        ): Flow<LLMStreamChunk> = error("not used")
    }

    private class Rig(monolithic: Boolean = false, startTokens: Int = 0) {
        val host = FakeHost()
        val blocks = mutableListOf<AssistantBlock>()
        val tokens = mutableListOf<Int>()
        val pending = PendingAssistantTurn("a", "s", 0)
        val stream = TurnStream(
            host = host, turn = 0, assistantId = "a", allToolBlocks = blocks, turnStartBlockIndex = 0,
            pendingTurn = pending, sessionEventEmitter = ChatSessionEventEmitter { "s" },
            toolInputChunkRings = mutableMapOf(), provider = { FakeProvider(monolithic) },
            contextTokens = startTokens, onContextTokens = { tokens += it },
        )
        fun feed(vararg chunks: LLMStreamChunk) = runBlocking { chunks.forEach { stream.handle(it, "") } }
    }

    @Test
    fun `text deltas build one text block and the turn text`() {
        val r = Rig()
        r.feed(LLMStreamChunk.Text("Hel"), LLMStreamChunk.Text("lo"))
        runBlocking { r.stream.finishStream("") }
        assertEquals("Hello", r.stream.turnTextSb.toString())
        assertEquals(listOf("text"), r.blocks.map { it.kind })
        assertEquals("Hello", r.blocks.single().content)
        assertEquals("Hello", r.host.checkpointText.toString())
        assertEquals("the last flush shows all of it", "Hello", r.host.updates.last())
    }

    @Test
    fun `a tool call goes streaming then pending and is collected with its arguments`() {
        val r = Rig()
        r.feed(
            LLMStreamChunk.ToolUseStart("c1", "file_read"),
            LLMStreamChunk.ToolInputDelta("c1", """{"path":"/a"""),
        )
        assertEquals(ToolBlockStatus.STREAMING, r.blocks.single().toolStatus)
        r.feed(LLMStreamChunk.ToolCallComplete("c1", "file_read", JSONObject("""{"path":"/a","tool_title":"Read a"}""")))
        val b = r.blocks.single()
        assertEquals(ToolBlockStatus.PENDING, b.toolStatus)
        assertEquals("Read a", b.toolTitle)
        assertEquals(listOf("c1"), r.stream.toolCalls.map { it.first })
    }

    @Test
    fun `a repeated tool call id is renamed so each call stays unique`() {
        val r = Rig()
        r.feed(
            LLMStreamChunk.ToolUseStart("dup", "a"), LLMStreamChunk.ToolUseStart("dup", "b"),
            LLMStreamChunk.ToolCallComplete("dup", "a", JSONObject("{}")),
            LLMStreamChunk.ToolCallComplete("dup", "b", JSONObject("{}")),
        )
        assertEquals(listOf("dup", "dup-2"), r.stream.toolCalls.map { it.first })
        assertEquals(listOf("dup", "dup-2"), r.blocks.map { it.id })
    }

    @Test
    fun `thinking is kept in its own block and marked done when text starts`() {
        val r = Rig()
        r.feed(LLMStreamChunk.ThinkingDelta("hm"), LLMStreamChunk.ThinkingDelta("m"))
        assertEquals("hmm", r.stream.turnThinking.toString())
        assertEquals("hmm", r.pending.reasoningContent)
        r.feed(LLMStreamChunk.Text("ok"))
        assertEquals(ToolBlockStatus.SUCCESS, r.blocks.first { it.kind == "thinking" }.toolStatus)
    }

    @Test
    fun `an opaque reasoning blob wins over the streamed thinking, even when empty`() {
        val r = Rig()
        r.feed(LLMStreamChunk.ThinkingDelta("streamed"), LLMStreamChunk.ReasoningContent(""))
        assertEquals("", r.stream.turnReasoningBlob)
        assertEquals("", r.pending.reasoningContent)
    }

    @Test
    fun `usage reports the context size, falling back to input plus cache`() {
        val r = Rig(startTokens = 5)
        r.feed(LLMStreamChunk.Usage(LLMUsage(inputTokens = 10, outputTokens = 1, latestContextTokens = 900)))
        r.feed(LLMStreamChunk.Usage(LLMUsage(inputTokens = 10, outputTokens = 1, cacheReadInputTokens = 90, cacheCreationInputTokens = 5)))
        assertEquals(listOf(900, 105), r.tokens)
        // A usage with nothing in it republishes the last known size instead of zero.
        r.feed(LLMStreamChunk.Usage(LLMUsage(inputTokens = 0, outputTokens = 0)))
        assertEquals(105, r.tokens.last())
        assertEquals(LLMUsage(0, 0), r.stream.lastUsage)
    }

    @Test
    fun `usage with no size at all and no earlier reading publishes nothing`() {
        val r = Rig(startTokens = 0)
        r.feed(LLMStreamChunk.Usage(LLMUsage(inputTokens = 0, outputTokens = 3)))
        assertTrue(r.tokens.isEmpty())
    }

    @Test
    fun `the finish reason and provider items are kept for the end of the turn`() {
        val r = Rig()
        r.feed(LLMStreamChunk.ProviderOutputItem("""{"x":1}"""), LLMStreamChunk.Finished("length"))
        assertEquals("length", r.stream.turnFinishReason)
        assertEquals(listOf("""{"x":1}"""), r.stream.turnProviderItems)
    }

    @Test
    fun `a failed attempt leaves nothing behind for the retry`() {
        val r = Rig()
        r.feed(
            LLMStreamChunk.Text("partial"), LLMStreamChunk.ThinkingDelta("t"),
            LLMStreamChunk.ReasoningContent("blob"), LLMStreamChunk.ProviderOutputItem("{}"),
            LLMStreamChunk.ToolCallComplete("c1", "x", JSONObject("{}")),
        )
        r.stream.resetForRetry()
        assertEquals("", r.stream.turnTextSb.toString())
        assertEquals("", r.stream.turnThinking.toString())
        assertNull(r.stream.turnReasoningBlob)
        assertNull(r.pending.reasoningContent)
        assertTrue(r.stream.turnProviderItems.isEmpty())
        assertTrue(r.stream.toolCalls.isEmpty())
        assertEquals(-1, r.stream.turnTextBlockIdx)
        // The next attempt's first text opens a fresh block rather than appending to the old one.
        r.blocks.clear()
        r.feed(LLMStreamChunk.Text("again"))
        assertEquals("again", r.stream.turnTextSb.toString())
        assertEquals("again", r.blocks.single().content)
    }

    @Test
    fun `a provider that streams one monolithic text keeps late text in the same block`() {
        val r = Rig(monolithic = true)
        r.feed(
            LLMStreamChunk.Text("I will "),
            LLMStreamChunk.ToolUseStart("c1", "x"),
            LLMStreamChunk.Text("read it."),
        )
        runBlocking { r.stream.finishStream("") }
        assertEquals(listOf("text", "tool_use"), r.blocks.map { it.kind })
        assertEquals("I will read it.", r.blocks.first().content)
    }

    @Test
    fun `an ordered provider puts text after a tool call in a new block`() {
        val r = Rig(monolithic = false)
        r.feed(LLMStreamChunk.Text("a"), LLMStreamChunk.ToolUseStart("c1", "x"), LLMStreamChunk.Text("b"))
        runBlocking { r.stream.finishStream("") }
        assertEquals(listOf("text", "tool_use", "text"), r.blocks.map { it.kind })
        assertEquals(listOf("a", "b"), r.blocks.filter { it.kind == "text" }.map { it.content })
    }

    @Test
    fun `a provider-run tool shows one info row that is updated, not duplicated`() {
        val r = Rig()
        r.feed(
            LLMStreamChunk.HostedToolActivity("h1", "web_search", finished = false, success = true),
            LLMStreamChunk.HostedToolActivity("h1", "web_search", finished = true, success = false),
        )
        val row = r.blocks.single()
        assertEquals("info", row.kind)
        assertEquals("Web search · failed", row.content)
        assertFalse(r.blocks.any { it.kind == "tool_use" })
    }

    @Test
    fun `partial json yields the title before the string closes`() {
        assertEquals("Read th", extractPartialStringValue("tool_title", """{"tool_title":"Read th"""))
        assertEquals("a\nb", extractPartialStringValue("tool_title", """{"tool_title": "a\nb", "x":1}"""))
        assertNull(extractPartialStringValue("tool_title", """{"path":"x"}"""))
        assertEquals("Write File", friendlyToolTitle("file_write"))
        assertEquals("Do Thing", friendlyToolTitle("do_thing"))
    }
}
