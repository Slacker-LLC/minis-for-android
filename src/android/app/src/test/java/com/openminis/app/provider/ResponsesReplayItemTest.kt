package com.openminis.app.provider

import com.openminis.app.provider.openai.responsesReplayItem
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replayed reasoning items are re-serialized to the fields official Codex sends
 * (id, summary, content, encrypted_content), so backend-only output fields can't
 * trigger `400 Unknown parameter` on the next request.
 */
class ResponsesReplayItemTest {

    @Test
    fun `unknown and status fields are dropped from a reasoning item`() {
        val raw = JSONObject()
            .put("type", "reasoning")
            .put("id", "rs_1")
            .put("status", "completed")
            .put("future_field", JSONObject().put("x", 1))
            .put("summary", JSONArray().put(JSONObject().put("type", "summary_text").put("text", "t")))
            .put("encrypted_content", "gAAAA")

        val out = responsesReplayItem(raw)

        assertEquals(setOf("type", "id", "summary", "encrypted_content"), out.keys().asSequence().toSet())
        assertEquals("gAAAA", out.getString("encrypted_content"))
        assertEquals(1, out.getJSONArray("summary").length())
    }

    @Test
    fun `summary defaults to an empty array`() {
        val out = responsesReplayItem(JSONObject().put("type", "reasoning").put("encrypted_content", "e"))
        assertEquals(0, out.getJSONArray("summary").length())
        assertFalse(out.has("id"))
    }

    @Test
    fun `content holding reasoning_text is omitted`() {
        val raw = JSONObject().put("type", "reasoning").put("id", "rs_2").put("encrypted_content", "e")
            .put("content", JSONArray().put(JSONObject().put("type", "reasoning_text").put("text", "raw cot")))
        assertFalse(responsesReplayItem(raw).has("content"))
    }

    @Test
    fun `content without reasoning_text is kept`() {
        val raw = JSONObject().put("type", "reasoning").put("id", "rs_3").put("encrypted_content", "e")
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "x")))
        assertTrue(responsesReplayItem(raw).has("content"))
    }

    @Test
    fun `non-reasoning items pass through untouched`() {
        val raw = JSONObject().put("type", "compaction").put("encrypted_content", "c").put("extra", 1)
        assertSame(raw, responsesReplayItem(raw))
        assertTrue(raw.has("extra"))
    }
}
