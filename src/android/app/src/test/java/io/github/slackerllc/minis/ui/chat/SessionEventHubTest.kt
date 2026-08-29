package io.github.slackerllc.minis.ui.chat

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression coverage for the snapshot-waterline invariant.  A raw response
 * can be much larger than the replay window; evicting its original
 * assistant/placeholder frame must not make a fresh snapshot lose its early
 * tokens while fencing at a later sequence number.
 */
class SessionEventHubTest {

    @Before
    fun setUp() {
        SessionEventHub.clearForTests()
    }

    @After
    fun tearDown() {
        SessionEventHub.clearForTests()
    }

    @Test
    fun `materialized tail retains raw assistant text after hot replay eviction`() {
        val sessionId = "session-tail-test"
        val messageId = "assistant-tail-test"
        SessionEventHub.append(
            sessionId,
            "assistant/placeholder",
            JSONObject().apply {
                put("messageId", messageId)
                put("message", JSONObject().apply {
                    put("id", messageId)
                    put("role", "assistant")
                    put("content", "")
                    put("isStreaming", true)
                })
            },
        )
        val expected = buildString {
            repeat(SessionEventHub.MAX_EVENTS_PER_SESSION + 37) { index ->
                val delta = "<$index>"
                append(delta)
                SessionEventHub.append(
                    sessionId,
                    "assistant/chunk",
                    JSONObject().apply {
                        put("messageId", messageId)
                        put("chunk", JSONObject().apply {
                            put("type", "text-delta")
                            put("text", delta)
                        })
                    },
                )
            }
        }

        val capture = SessionEventHub.captureWithWatermark(sessionId) { _, tail, _ -> tail }
        val message = capture.value.messages.getValue(messageId)
        assertTrue("placeholder should have aged out of the bounded replay window",
            SessionEventHub.eventsSince(sessionId, 0L).resetRequired)
        assertEquals(expected, message.content)
        assertTrue(message.contentAuthoritative)
        assertEquals(1L + SessionEventHub.MAX_EVENTS_PER_SESSION + 37L, capture.lastSeq)
        assertFalse(SessionEventHub.eventsSince(sessionId, capture.lastSeq).resetRequired)
        assertTrue(SessionEventHub.eventsSince(sessionId, capture.lastSeq).events.isEmpty())
    }
}
