package com.openminis.app.data.repository

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRepositoryTruncationTest {

    private val max = ChatRepository.MAX_MESSAGE_PARTS_JSON_LENGTH

    private fun truncate(parts: JSONArray) = JSONArray(ChatRepository.buildTruncatedPartsJson(parts.toString()))

    @Test
    fun anOversizedToolResultKeepsItsTypeAndIds() {
        val parts = JSONArray()
            .put(JSONObject().put("type", "tool_result").put("toolUseId", "call_1").put("name", "browser").put("output", "x".repeat(max * 2)))
            .put(JSONObject().put("type", "tool_result").put("toolUseId", "call_2").put("name", "shell").put("output", "short"))
        val out = truncate(parts)

        assertEquals(2, out.length())
        assertEquals("tool_result", out.getJSONObject(0).getString("type"))
        assertEquals("call_1", out.getJSONObject(0).getString("toolUseId"))
        assertEquals("browser", out.getJSONObject(0).getString("name"))
        assertEquals("the small result is untouched", "short", out.getJSONObject(1).getString("output"))
        assertEquals("call_2", out.getJSONObject(1).getString("toolUseId"))
        assertTrue(out.toString().length <= max)
        assertTrue(out.getJSONObject(0).getString("output").contains("Content truncated"))
    }

    @Test
    fun anOversizedAssistantTurnKeepsItsToolUseParts() {
        val parts = JSONArray()
            .put(JSONObject().put("type", "text").put("value", "y".repeat(max + 5_000)))
            .put(JSONObject().put("type", "tool_use").put("id", "call_9").put("name", "files").put("input", "{\"path\":\"/a\"}"))
        val out = truncate(parts)

        assertEquals(listOf("text", "tool_use"), (0 until out.length()).map { out.getJSONObject(it).getString("type") })
        assertEquals("call_9", out.getJSONObject(1).getString("id"))
        assertTrue(out.toString().length <= max)
    }

    @Test
    fun theLongestFieldIsCutFirstSoSeveralLargeOnesShare()  {
        val parts = JSONArray()
            .put(JSONObject().put("type", "text").put("value", "a".repeat(max)))
            .put(JSONObject().put("type", "text").put("value", "b".repeat(max)))
        val out = truncate(parts)
        assertTrue(out.getJSONObject(0).getString("value").startsWith("a"))
        assertTrue(out.getJSONObject(1).getString("value").startsWith("b"))
        assertTrue(out.toString().length <= max)
    }

    @Test
    fun nestedStringsAreReachedToo() {
        val parts = JSONArray().put(
            JSONObject().put("type", "tool_result").put("toolUseId", "c").put(
                "snapshot", JSONObject().put("dom", "d".repeat(max * 2)),
            ),
        )
        val out = truncate(parts)
        assertEquals("c", out.getJSONObject(0).getString("toolUseId"))
        assertTrue(out.toString().length <= max)
    }

    @Test
    fun somethingThatIsNotAJsonArrayFallsBackToOneTextPart() {
        val out = JSONArray(ChatRepository.buildTruncatedPartsJson("z".repeat(max + 10)))
        assertEquals(1, out.length())
        assertEquals("text", out.getJSONObject(0).getString("type"))
    }
}
