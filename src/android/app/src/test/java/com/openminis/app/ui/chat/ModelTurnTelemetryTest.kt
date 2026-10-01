package com.openminis.app.ui.chat

import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTurnTelemetryTest {
    private var now = 1_000L
    private fun telemetry() = ModelTurnTelemetry(3) { now }

    @Test fun separatesWaitingFromStreaming() {
        val t = telemetry()
        now += 100; t.onChunk(LLMStreamChunk.Started)
        now += 1_900; t.onChunk(LLMStreamChunk.Text("hi"))
        now += 500; t.onChunk(LLMStreamChunk.Text(" there"))
        now += 100; t.onChunk(LLMStreamChunk.Usage(LLMUsage(inputTokens = 900, outputTokens = 12, cacheReadInputTokens = 800)))
        t.onChunk(LLMStreamChunk.Finished("end_turn"))
        t.finish()
        val s = t.summary()
        assertTrue(s, "turn=3 " in s)
        assertTrue(s, "firstEventMs=100 " in s)
        assertTrue(s, "ttftMs=2000 " in s)
        assertTrue(s, "totalMs=2600 " in s)
        assertTrue(s, "streamMs=600 " in s)
        assertTrue(s, "inTok=900 outTok=12 cacheReadTok=800 " in s)
        assertTrue(s, "stop=end_turn" in s)
    }

    @Test fun startedAloneIsNotContent() {
        val t = telemetry()
        now += 50; t.onChunk(LLMStreamChunk.Started)
        now += 10; t.finish()
        assertTrue(t.summary(), "ttftMs=na " in t.summary())
        assertTrue(t.summary(), "streamMs=na " in t.summary())
    }

    @Test fun countsToolCallsAndTreatsThemAsContent() {
        val t = telemetry()
        now += 700; t.onChunk(LLMStreamChunk.ToolUseStart("a", "android_ui"))
        now += 100; t.onChunk(LLMStreamChunk.ToolCallComplete("a", "android_ui", JSONObject()))
        t.onChunk(LLMStreamChunk.ToolCallComplete("b", "android_ui", JSONObject()))
        t.finish()
        val s = t.summary()
        assertTrue(s, "ttftMs=700 " in s)
        assertTrue(s, "toolCalls=2 " in s)
        assertTrue(s, "inTok=na " in s)
    }

    @Test fun finishIsIdempotentAndFailedStreamsStillReport() {
        val t = telemetry()
        now += 300; t.finish()
        now += 5_000; t.finish()
        assertTrue(t.summary(), "totalMs=300 " in t.summary())
    }
}
