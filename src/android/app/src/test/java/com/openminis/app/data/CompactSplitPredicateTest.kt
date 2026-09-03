package com.openminis.app.data

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.ui.chat.ChatViewModel.Companion.shouldSplitOnError
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class CompactSplitPredicateTest {

    @Test
    fun `safe split prefers a complete user turn`() {
        val messages = listOf(
            user("first"),
            assistant("answer"),
            user("second"),
            assistant("answer two"),
        )

        assertTrue(CompactSplitPredicate.isSafeBoundary(messages, 2))
        assertTrue(CompactSplitPredicate.isSafeBoundary(messages, 3))
        assertTrue(CompactSplitPredicate.findSafeSplit(messages, 2) == 2)
    }

    @Test
    fun `tool use and result stay in the same split`() {
        val messages = listOf(
            LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = "",
                contentParts = listOf(
                    AgentContentPart.ToolUse("call-1", "file_read", JSONObject("{\"path\":\"a\"}")),
                ),
            ),
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = "",
                contentParts = listOf(
                    AgentContentPart.ToolResult("call-1", "file_read", "contents"),
                ),
            ),
            user("next request"),
        )

        assertFalse(CompactSplitPredicate.isSafeBoundary(messages, 1))
        assertTrue(CompactSplitPredicate.isSafeBoundary(messages, 2))
        assertTrue(CompactSplitPredicate.findSafeSplit(messages, 1) == 2)
    }

    @Test
    fun `open fenced code block is not split`() {
        val messages = listOf(
            user("before"),
            assistant("```kotlin\nval answer = 42"),
            user("inside the example"),
            assistant("```"),
            user("after"),
        )

        assertFalse(CompactSplitPredicate.isSafeBoundary(messages, 2))
        assertFalse(CompactSplitPredicate.isSafeBoundary(messages, 3))
        assertTrue(CompactSplitPredicate.isSafeBoundary(messages, 4))
        assertTrue(CompactSplitPredicate.findSafeSplit(messages, 2) == 4)
    }

    @Test
    fun `open JSON object is not split and braces in strings are ignored`() {
        val messages = listOf(
            user("payload {\"text\": \"literal } and ]\", \"items\": [1,"),
            assistant("2]}"),
            user("the next turn"),
        )

        assertFalse(CompactSplitPredicate.isSafeBoundary(messages, 1))
        assertTrue(CompactSplitPredicate.isSafeBoundary(messages, 2))
    }

    @Test
    fun `nearest midpoint is skipped when its structure is open`() {
        val messages = listOf(
            user("before"),
            assistant("```text\nunfinished"),
            user("still inside"),
            assistant("```"),
            user("safe later turn"),
        )

        assertTrue(CompactSplitPredicate.findSafeSplit(messages, 2) == 4)
    }

    @Test
    fun `rate limiting and transport failures do not split`() {
        assertFalse(shouldSplitOnError(LLMError.RateLimited()))
        assertFalse(shouldSplitOnError(LLMError.TransientError("502 Bad Gateway")))
        assertFalse(shouldSplitOnError(LLMError.InvalidApiKey("bad key")))
        assertFalse(shouldSplitOnError(CancellationException("user stopped")))
        assertFalse(shouldSplitOnError(LLMError.Cancelled()))
        assertFalse(shouldSplitOnError(LLMError.NetworkError(IOException("offline"))))
        assertFalse(shouldSplitOnError(IOException("connection reset")))
        assertFalse(shouldSplitOnError(SocketTimeoutException("read timed out")))
    }

    @Test
    fun `provider and unclassified errors remain eligible for splitting`() {
        assertTrue(
            shouldSplitOnError(
                LLMError.ProviderError(
                    "[context_length_exceeded] Your input exceeds the context window of this model",
                ),
            ),
        )
        assertTrue(shouldSplitOnError(LLMError.ProviderError("400 request too large")))
        assertTrue(shouldSplitOnError(LLMError.Unknown(RuntimeException("???"))))
        assertTrue(shouldSplitOnError(LLMError.DecodingError(RuntimeException("bad json"))))
        assertTrue(shouldSplitOnError(IllegalStateException("something odd")))
    }

    @Test
    fun `known amplification failures stay excluded`() {
        val previouslyAmplified = listOf<Throwable>(
            LLMError.RateLimited(),
            LLMError.TransientError("503"),
            LLMError.InvalidApiKey(),
        )
        for (error in previouslyAmplified) {
            assertFalse(
                "${error.javaClass.simpleName} must not split",
                shouldSplitOnError(error),
            )
        }
    }

    private fun user(text: String): LLMMessage =
        LLMMessage(LLMMessage.Role.USER, text)

    private fun assistant(text: String): LLMMessage =
        LLMMessage(LLMMessage.Role.ASSISTANT, text)
}
