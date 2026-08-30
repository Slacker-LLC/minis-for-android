package io.github.slackerllc.minis.debug

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatToolTrajectoryNormalizerTest {

    @Test
    fun `normalizes persisted toolUse and toolResult blocks without losing their raw values`() {
        val trajectory = ChatToolTrajectoryNormalizer.collect(JSONArray("""
            [
              {"type":"text","value":"Checking the project"},
              {"type":"toolUse","value":{"toolUseId":"use-1","name":"read_file","input":{"path":"README.md"},"description":"Read a file"}},
              {"type":"toolResult","value":{"toolUseId":"use-1","name":"read_file","output":"contents","success":true,"snapshot":{"type":"text","text":"contents"}}}
            ]
        """.trimIndent()))

        assertEquals(1, trajectory.toolCalls.length())
        assertEquals(1, trajectory.toolResults.length())

        val call = trajectory.toolCalls.getJSONObject(0)
        assertEquals("toolUse", call.getString("type"))
        assertEquals("call", call.getString("kind"))
        assertEquals("use-1", call.getString("id"))
        assertEquals("use-1", call.getString("toolUseId"))
        assertEquals("read_file", call.getString("name"))
        assertEquals("read_file", call.getString("toolName"))
        assertEquals("README.md", call.getJSONObject("input").getString("path"))
        assertEquals("Read a file", call.getString("description"))
        assertEquals("use-1", call.getJSONObject("value").getString("toolUseId"))
        assertEquals("toolUse", call.getJSONObject("raw").getString("type"))

        val result = trajectory.toolResults.getJSONObject(0)
        assertEquals("toolResult", result.getString("type"))
        assertEquals("result", result.getString("kind"))
        assertEquals("use-1", result.getString("toolUseId"))
        assertEquals("read_file", result.getString("name"))
        assertEquals("contents", result.getString("output"))
        assertTrue(result.getBoolean("success"))
        assertEquals("contents", result.getJSONObject("snapshot").getString("text"))
        assertEquals("toolResult", result.getJSONObject("raw").getString("type"))
    }

    @Test
    fun `normalizes flat snake case tool event aliases`() {
        val parts = JSONArray()
            .put(
                JSONObject()
                    .put("type", "tool_call")
                    .put("id", "call-flat")
                    .put("name", "search")
                    .put("arguments", JSONObject().put("query", "weather")),
            )
            .put(
                JSONObject()
                    .put("type", "tool_use")
                    .put("id", "call-snake")
                    .put("tool_name", "browse")
                    .put("input", JSONObject().put("url", "https://example.test")),
            )
            .put(
                JSONObject()
                    .put("type", "tool_result")
                    .put("tool_use_id", "call-snake")
                    .put("name", "browse")
                    .put("content", "navigation failed")
                    .put("is_error", true),
            )

        val trajectory = ChatToolTrajectoryNormalizer.collect(parts)

        assertEquals(2, trajectory.toolCalls.length())
        assertEquals(1, trajectory.toolResults.length())

        val flatCall = trajectory.toolCalls.getJSONObject(0)
        assertEquals("call-flat", flatCall.getString("toolUseId"))
        assertEquals("search", flatCall.getString("name"))
        assertEquals("weather", flatCall.getJSONObject("input").getString("query"))

        val snakeCall = trajectory.toolCalls.getJSONObject(1)
        assertEquals("call-snake", snakeCall.getString("toolUseId"))
        assertEquals("browse", snakeCall.getString("name"))
        assertEquals("https://example.test", snakeCall.getJSONObject("input").getString("url"))

        val result = trajectory.toolResults.getJSONObject(0)
        assertEquals("call-snake", result.getString("toolUseId"))
        assertEquals("browse", result.getString("toolName"))
        assertEquals("navigation failed", result.getString("output"))
        assertTrue(result.getBoolean("isError"))
        assertFalse(result.getBoolean("success"))
    }
}
