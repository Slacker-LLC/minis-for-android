package com.openminis.app.ui.chat

import com.openminis.app.agent.AgentContextCompactor
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.LLMProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The summarise-and-verify half of a compaction, with a fake model and an in-memory "database". */
class CompactionExecutionTest {
    private class FakeProvider(var text: String, var stopReason: String? = "end_turn") : LLMProvider {
        override val name = "fake"
        override var model = LLMModel("fake", "Fake", "fake", contextWindow = 100_000)
        var calls = 0
        override suspend fun sendMessageClamped(
            messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int, temperature: Double?,
            imageParts: List<LLMMessage.ImagePart>, tools: List<AgentToolDefinition>, thinkingLevel: ThinkingLevel,
        ): LLMResponse { calls++; return LLMResponse(text, stopReason, null) }

        override fun streamMessageClamped(
            messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int, temperature: Double?,
            imageParts: List<LLMMessage.ImagePart>, tools: List<AgentToolDefinition>, thinkingLevel: ThinkingLevel,
        ): Flow<LLMStreamChunk> = error("not used")
    }

    private fun history(turns: Int, text: String) = (1..turns).flatMap {
        listOf(
            LLMMessage(LLMMessage.Role.USER, "$text $it", dbMessageId = "u$it"),
            LLMMessage(LLMMessage.Role.ASSISTANT, "$text $it", dbMessageId = "a$it"),
        )
    }

    private val long = "x".repeat(400)

    private fun ready(h: List<LLMMessage>): CompactionPlanner.Outcome.Ready =
        CompactionPlanner.plan(h, null, null, null, 100_000) as CompactionPlanner.Outcome.Ready

    private class Db(val ids: Set<String>?, val failInsert: Boolean = false) {
        val inserted = mutableListOf<CompactMarkerEntity>()
        suspend fun load(): Set<String> = ids ?: error("db down")
        suspend fun insert(m: CompactMarkerEntity) { if (failInsert) error("disk full"); inserted += m }
    }

    private fun run(h: List<LLMMessage>, provider: FakeProvider, db: Db): CompactionExecution.Result = runBlocking {
        val summarizer = CompactionSummarizer({ provider }, { 100_000 }, { _, _ -> })
        CompactionExecution.run(ready(h), h, summarizer, 60_000, "s", db::load, db::insert)
    }

    private fun allIds(h: List<LLMMessage>) = h.mapNotNull { it.dbMessageId }.toSet()

    @Test
    fun `a good summary becomes a marker anchored at the end of the range and is written once`() {
        val h = history(60, long)
        val db = Db(allIds(h))
        val r = run(h, FakeProvider("Short summary."), db) as CompactionExecution.Result.Done
        val end = ready(h).compactEndIdx
        assertEquals(h[end].dbMessageId, r.cutoffId)
        assertEquals(r.cutoffId, r.marker.lastCompactedMessageId)
        assertEquals(2, r.marker.version)
        assertEquals(end + 1, r.marker.compactedCount)
        assertTrue(r.summary.startsWith("Short summary."))
        assertEquals(listOf(r.marker), db.inserted)
    }

    @Test
    fun `an anchor the database does not have walks back to the closest one it does`() {
        val h = history(60, long)
        val end = ready(h).compactEndIdx
        val known = allIds(h) - h.drop(end - 1).mapNotNull { it.dbMessageId }.toSet()
        val db = Db(known)
        val r = run(h, FakeProvider("Short summary."), db) as CompactionExecution.Result.Done
        assertEquals(h[end - 2].dbMessageId, r.cutoffId)
    }

    @Test
    fun `when no message is persisted nothing is written`() {
        val h = history(60, long)
        val db = Db(setOf("elsewhere"))
        val r = run(h, FakeProvider("Short summary."), db)
        assertEquals("Compact failed: could not anchor to a persisted message.", (r as CompactionExecution.Result.Stopped).message)
        assertTrue(db.inserted.isEmpty())
    }

    @Test
    fun `an unreadable database trusts the in-memory anchor`() {
        val h = history(60, long)
        val db = Db(null)
        val r = run(h, FakeProvider("Short summary."), db) as CompactionExecution.Result.Done
        assertEquals(h[ready(h).compactEndIdx].dbMessageId, r.cutoffId)
    }

    @Test
    fun `a summary that would not shrink the context is refused and nothing is written`() {
        val h = history(60, "a")
        val big = "y".repeat(AgentContextCompactor.summaryCharCap(100_000) - 100)
        val db = Db(allIds(h))
        val r = run(h, FakeProvider(big), db)
        assertTrue((r as CompactionExecution.Result.Stopped).message.contains("would not shrink"))
        assertTrue(db.inserted.isEmpty())
    }

    @Test
    fun `a truncated summary is rejected before anything is written`() {
        val h = history(60, long)
        val db = Db(allIds(h))
        try {
            run(h, FakeProvider("cut off", stopReason = "length"), db)
            fail("expected a rejection")
        } catch (e: CompactRejectedSummary) {
            assertTrue(e.message.orEmpty().isNotEmpty())
        }
        assertTrue(db.inserted.isEmpty())
    }

    @Test
    fun `a failed marker write does not lose the compaction`() {
        val h = history(60, long)
        val db = Db(allIds(h), failInsert = true)
        val r = run(h, FakeProvider("Short summary."), db)
        assertTrue(r is CompactionExecution.Result.Done)
    }
}
