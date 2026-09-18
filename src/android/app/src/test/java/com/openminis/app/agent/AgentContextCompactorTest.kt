package com.openminis.app.agent

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [C3-android-compaction-guards] Behaviour of the ported Eta compaction guards
 * (Mangi-11/Eta @ c15de97). Test names and cases mirror upstream's
 * AgentContextCompactionTest where the behaviour is identical; the Minis-only
 * adaptation (the range ends before the newest user turn instead of carrying
 * that message out of the summary) is covered explicitly.
 *
 * Rejections are asserted by the upstream failure code, because those codes are
 * what the ChatViewModel call site logs: a regression that silently compacted
 * anyway would flip Rejected into Compactable, not merely change wording.
 */
class AgentContextCompactorTest {

    // ── fixtures ─────────────────────────────────────────────────────────

    private var nextId = 0

    private fun id(): String = "m" + (++nextId)

    private fun user(text: String): LLMMessage =
        LLMMessage(role = LLMMessage.Role.USER, content = text, dbMessageId = id())

    private fun assistant(text: String): LLMMessage =
        LLMMessage(role = LLMMessage.Role.ASSISTANT, content = text, dbMessageId = id())

    private fun toolUse(vararg callIds: String): LLMMessage =
        LLMMessage(
            role = LLMMessage.Role.ASSISTANT,
            content = "",
            contentParts = callIds.map { AgentContentPart.ToolUse(it, "file_read", JSONObject("{}")) },
            dbMessageId = id(),
        )

    private fun toolResult(
        vararg callIds: String,
        content: String = "ok",
    ): LLMMessage =
        LLMMessage(
            role = LLMMessage.Role.USER,
            content = "",
            contentParts = callIds.map { AgentContentPart.ToolResult(it, "file_read", content) },
            dbMessageId = id(),
        )

    private fun planOf(history: List<LLMMessage>, recentTokenLimit: Int? = null): AgentContextCompactor.Plan =
        AgentContextCompactor.planRange(
            history = history,
            startIndex = 0,
            anchorIndex = history.lastIndex,
            contextWindow = 128_000,
            recentTokenLimit = recentTokenLimit,
        )

    private fun assertRejected(plan: AgentContextCompactor.Plan, code: String) {
        assertTrue("expected a rejection, got " + plan, plan is AgentContextCompactor.Plan.Rejected)
        assertEquals(code, (plan as AgentContextCompactor.Plan.Rejected).code)
    }

    private fun compactable(plan: AgentContextCompactor.Plan): AgentContextCompactor.Plan.Compactable {
        assertTrue("expected a compactable range, got " + plan, plan is AgentContextCompactor.Plan.Compactable)
        return plan as AgentContextCompactor.Plan.Compactable
    }

    // ── batch boundaries (upstream: toolBatchCannotBeSplitUntilEveryResultIsPresent)

    @Test
    fun toolBatchCannotBeSplitUntilEveryResultIsPresent() {
        val messages = listOf(
            toolUse("a", "b"),
            toolResult("a"),
            toolResult("b"),
            user("继续"),
        )
        assertFalse(AgentContextCompactor.canSplit(messages, 1))
        assertFalse(AgentContextCompactor.canSplit(messages, 2))
        assertTrue(AgentContextCompactor.canSplit(messages, 3))
    }

    @Test
    fun aCutDirectlyAfterAUserTurnIsRefused() {
        val messages = listOf(user("问题"), assistant("回答"), user("新问题"))
        assertFalse(AgentContextCompactor.canSplit(messages, 1))
        assertTrue(AgentContextCompactor.canSplit(messages, 2))
    }

    @Test
    fun aCutDirectlyBeforeAToolResultIsRefused() {
        val messages = listOf(user("问题"), assistant("回答"), toolUse("a"), toolResult("a"), user("继续"))
        assertFalse(AgentContextCompactor.canSplit(messages, 3))
        assertTrue(AgentContextCompactor.canSplit(messages, 4))
    }

    @Test
    fun completeGroupsOnlyBreakAtSafeBoundaries() {
        val messages = listOf(
            user("问题"),
            assistant("回答"),
            toolUse("a"),
            toolResult("a"),
            user("继续"),
        )
        val groups = AgentContextCompactor.completeGroups(messages)
        assertEquals(3, groups.size)
        assertEquals(listOf("问题"), groups[0].map { it.content })
        assertEquals(listOf("", ""), groups[1].map { it.content })
        assertEquals(listOf("继续"), groups[2].map { it.content })
    }

    @Test
    fun halfSplitBoundaryHalvesTheGroupList() {
        val messages = listOf(
            user("一"),
            assistant("甲"),
            user("二"),
            assistant("乙"),
            user("三"),
            assistant("丙"),
        )
        assertEquals(3, AgentContextCompactor.completeGroups(messages).size)
        assertEquals(
            "the first half is groups.take(groups.size / 2) flattened",
            2,
            AgentContextCompactor.halfSplitBoundary(messages),
        )
    }

    @Test
    fun halfSplitBoundaryIsNullForOneCompleteBatch() {
        val messages = listOf(toolUse("a"), toolResult("a"))
        assertNull(AgentContextCompactor.halfSplitBoundary(messages))
    }

    // ── range selection ──────────────────────────────────────────────────

    @Test
    fun anEmptyHistoryIsNotCompactable() {
        assertRejected(
            planOf(emptyList()),
            AgentContextCompactor.FAILURE_NOT_COMPACTABLE,
        )
    }

    @Test
    fun aFullyCompactedRangeIsNotCompactable() {
        val messages = listOf(user("一"), assistant("甲"), user("二"), assistant("乙"))
        val plan = AgentContextCompactor.planRange(
            history = messages,
            startIndex = 3,
            anchorIndex = 1,
            contextWindow = 128_000,
            recentTokenLimit = null,
        )
        assertRejected(plan, AgentContextCompactor.FAILURE_NOT_COMPACTABLE)
    }

    @Test
    fun theLatestFourMessagesStayOutOfTheRange() {
        val history = (1..5).flatMap { turn -> listOf(user("问题 " + turn), assistant("回答 " + turn)) }
        val range = compactable(planOf(history))
        assertTrue(
            "range must stop at or before size - RECENT_MESSAGES (end=" + range.endExclusive + ")",
            range.endExclusive <= history.size - AgentContextBudget.RECENT_MESSAGES,
        )
    }

    @Test
    fun anOverBudgetTailAdvancesTheCutForward() {
        val history = (1..5).flatMap { turn -> listOf(user("问题 " + turn), assistant("回答 " + turn)) }
        val start = compactable(planOf(history))
        val advanced = compactable(planOf(history, recentTokenLimit = 1))
        assertTrue(
            "a tail over the recent budget may be compacted further (start=" + start.endExclusive +
                " advanced=" + advanced.endExclusive + ")",
            advanced.endExclusive > start.endExclusive,
        )
        assertTrue(advanced.endExclusive < history.size)
    }

    @Test
    fun theNewestUserTurnIsNeverSummarized() {
        // Round 2 ends on a completed tool batch, so without the protection the
        // ratio walk would push the cut past the newest user turn (index 2).
        val history = listOf(
            user("一"),
            assistant("甲"),
            user("二"),
            toolUse("a"),
            toolResult("a"),
            assistant("乙"),
        )
        val range = compactable(planOf(history, recentTokenLimit = 1))
        assertEquals(
            "the newest user turn and everything after it stay verbatim",
            2,
            range.endExclusive,
        )
    }

    @Test
    fun aSessionWithASingleRoundIsNotCompactable() {
        val history = listOf(user("唯一的问题"), assistant("唯一的回答"))
        assertRejected(planOf(history), AgentContextCompactor.FAILURE_NOT_COMPACTABLE)
    }

    @Test
    fun aRoundThatCannotBeCutIsNotCompactable() {
        // Round 1 ends on an assistant tool_use whose result never landed, and
        // that call sits before the newest user turn.
        val history = listOf(user("问题"), assistant("回答"), toolUse("a"))
        assertRejected(planOf(history), AgentContextCompactor.FAILURE_NOT_COMPACTABLE)
    }

    @Test
    fun anUnknownWindowStillCompactsWithoutTheRatioWalk() {
        val history = (1..5).flatMap { turn -> listOf(user("问题 " + turn), assistant("回答 " + turn)) }
        val range = compactable(
            AgentContextCompactor.planRange(
                history = history,
                startIndex = 0,
                anchorIndex = history.lastIndex,
                contextWindow = null,
                recentTokenLimit = null,
            ),
        )
        assertEquals(6, range.endExclusive)
    }

    // ── summary budget ───────────────────────────────────────────────────

    @Test
    fun summaryCharCapFollowsTheWindowWithAFloor() {
        assertEquals(256, AgentContextCompactor.summaryCharCap(1_000))
        assertEquals(1_200, AgentContextCompactor.summaryCharCap(2_000))
        assertEquals(12_000, AgentContextCompactor.summaryCharCap(1_000_000))
        assertEquals(256, AgentContextCompactor.summaryCharCap(null))
        assertEquals(32_000, AgentContextCompactor.summaryMaxInputTokens(null))
    }

    @Test
    fun aSingleBatchLargerThanTheSummaryBudgetIsRefused() {
        val messages = listOf(toolUse("a"), toolResult("a", content = "x".repeat(50_000)))
        val plan = AgentContextCompactor.chunkForSummary(messages, maxInputTokens = 1_000)
        assertTrue(plan is AgentContextCompactor.ChunkPlan.Rejected)
        assertEquals(
            AgentContextCompactor.FAILURE_ITEM_TOO_LARGE,
            (plan as AgentContextCompactor.ChunkPlan.Rejected).code,
        )
    }

    @Test
    fun chunkingPacksCompleteBatchesUntilTheBudgetIsReached() {
        val history = (1..4).flatMap { turn -> listOf(user("问题 " + turn), assistant("回答 " + turn)) }
        val plan = AgentContextCompactor.chunkForSummary(history, maxInputTokens = 400)
        assertTrue(plan is AgentContextCompactor.ChunkPlan.Chunks)
        val chunks = (plan as AgentContextCompactor.ChunkPlan.Chunks).chunks
        assertTrue("expected more than one chunk, got " + chunks.size, chunks.size > 1)
        assertEquals(history, chunks.flatten())
    }

    @Test
    fun aPreviousSummaryIsChargedAgainstEveryChunk() {
        val history = (1..4).flatMap { turn -> listOf(user("问题 " + turn), assistant("回答 " + turn)) }
        val without = AgentContextCompactor.chunkForSummary(history, maxInputTokens = 400)
        val with = AgentContextCompactor.chunkForSummary(
            history,
            maxInputTokens = 400,
            previousSummary = "旧摘要".repeat(20),
        )
        val withoutCount = (without as AgentContextCompactor.ChunkPlan.Chunks).chunks.size
        val withCount = (with as AgentContextCompactor.ChunkPlan.Chunks).chunks.size
        assertTrue(
            "a carried summary must consume chunk budget (" + withCount + " vs " + withoutCount + ")",
            withCount > withoutCount,
        )
    }

    // ── summary validation (upstream: truncatedSummaryNeverReplacesOriginalContext)

    @Test
    fun aTruncatedSummaryIsInvalid() {
        val check = AgentContextCompactor.validateSummary(
            summary = "半截摘要",
            stopReason = "length",
            maxChars = 12_000,
        )
        assertTrue(check is AgentContextCompactor.SummaryCheck.Rejected)
        assertEquals(
            AgentContextCompactor.FAILURE_SUMMARY_INVALID,
            (check as AgentContextCompactor.SummaryCheck.Rejected).code,
        )
    }

    @Test
    fun aMissingStopReasonIsAcceptedBecauseProvidersMayOmitIt() {
        assertTrue(AgentContextCompactor.isNormalFinish(null))
        assertTrue(AgentContextCompactor.isNormalFinish("stop"))
        assertTrue(AgentContextCompactor.isNormalFinish("END_TURN"))
        assertFalse(AgentContextCompactor.isNormalFinish("max_tokens"))
        assertFalse(AgentContextCompactor.isNormalFinish("tool_calls"))
        assertEquals(
            AgentContextCompactor.SummaryCheck.Valid,
            AgentContextCompactor.validateSummary("摘要", null, 12_000),
        )
    }

    @Test
    fun aBlankOrLiteralNullSummaryIsInvalid() {
        listOf("   ", "", "null").forEach { text ->
            val check = AgentContextCompactor.validateSummary(text, "stop", 12_000)
            assertTrue("expected rejection for [" + text + "]", check is AgentContextCompactor.SummaryCheck.Rejected)
            assertEquals(
                AgentContextCompactor.FAILURE_SUMMARY_INVALID,
                (check as AgentContextCompactor.SummaryCheck.Rejected).code,
            )
        }
    }

    @Test
    fun aSummaryOverTheCharacterCapIsInvalid() {
        assertEquals(
            AgentContextCompactor.FAILURE_SUMMARY_INVALID,
            (AgentContextCompactor.validateSummary("x".repeat(300), "stop", 256)
                as AgentContextCompactor.SummaryCheck.Rejected).code,
        )
        assertEquals(
            AgentContextCompactor.SummaryCheck.Valid,
            AgentContextCompactor.validateSummary("x".repeat(300), "stop", 12_000),
        )
    }

    @Test
    fun aSummaryCarryingToolCallsIsInvalid() {
        val text = "摘要。\n<tool_call>{\"name\":\"file_read\"}</tool_call>"
        assertTrue(AgentContextCompactor.containsToolCallSyntax(text))
        val check = AgentContextCompactor.validateSummary(
            summary = text,
            stopReason = "stop",
            maxChars = 12_000,
            hasToolCalls = AgentContextCompactor.containsToolCallSyntax(text),
        )
        assertEquals(
            AgentContextCompactor.FAILURE_SUMMARY_INVALID,
            (check as AgentContextCompactor.SummaryCheck.Rejected).code,
        )
    }

    @Test
    fun proseThatMerelyNamesAToolIsStillValid() {
        val text = "The agent called tool_use and applied the file_read result."
        assertFalse(AgentContextCompactor.containsToolCallSyntax(text))
        assertEquals(
            AgentContextCompactor.SummaryCheck.Valid,
            AgentContextCompactor.validateSummary(text, "stop", 12_000),
        )
    }

    // ── reduction gate ───────────────────────────────────────────────────

    @Test
    fun theReductionMustBeStrict() {
        assertFalse(AgentContextCompactor.hasReduction(beforeTokens = 1_000, afterTokens = 1_000))
        assertFalse(AgentContextCompactor.hasReduction(beforeTokens = 1_000, afterTokens = 1_200))
        assertTrue(AgentContextCompactor.hasReduction(beforeTokens = 1_000, afterTokens = 999))
        val message = AgentContextCompactor.noReductionMessage(1_000, 1_000)
        assertTrue(message.contains(AgentContextCompactor.FAILURE_NO_REDUCTION))
    }

    // ── estimator (upstream: budgetUsesModelWindowAndUsageCalibration...)

    @Test
    fun textTokensPricesCjkByCodePointNotByCharacterByte() {
        assertEquals(4, AgentContextBudget.textTokens("中文测试"))
        assertEquals(2, AgentContextBudget.textTokens("abcdef"))
    }

    @Test
    fun imagesArePricedByCountNotByBase64Length() {
        fun imageMessage(base64Chars: Int) = LLMMessage(
            role = LLMMessage.Role.USER,
            content = "图片",
            imageParts = listOf(
                LLMMessage.ImagePart(
                    data = "A".repeat(base64Chars).toByteArray(),
                    mimeType = "image/png",
                ),
            ),
        )
        assertEquals(
            AgentContextBudget.rawEstimate(listOf(imageMessage(10))),
            AgentContextBudget.rawEstimate(listOf(imageMessage(10_000))),
        )
    }

    @Test
    fun toolResultsArePricedFromTheirContent() {
        val small = listOf(toolResult("a", content = "ok"))
        val large = listOf(toolResult("a", content = "x".repeat(4_000)))
        assertTrue(AgentContextBudget.rawEstimate(large) > AgentContextBudget.rawEstimate(small))
    }
}
