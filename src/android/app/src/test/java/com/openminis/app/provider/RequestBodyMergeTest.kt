package com.openminis.app.provider

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported from Eta `agent/model/RequestBodyMerge.kt` (Mangi-11/Eta @ c15de97).
 * The rules under test: objects merge recursively, everything else is a caller
 * win.
 */
class RequestBodyMergeTest {

    @Test
    fun `nested object merge keeps the app siblings`() {
        val target = JSONObject().put("reasoning", JSONObject().put("effort", "low").put("summary", "auto"))

        RequestBodyMerge.mergeInto(target, mapOf("reasoning" to JSONObject().put("effort", "high")))

        val reasoning = target.getJSONObject("reasoning")
        assertEquals("high", reasoning.getString("effort"))
        assertEquals("auto", reasoning.getString("summary"))
    }

    @Test
    fun `object absent from the app body is created`() {
        val target = JSONObject().put("model", "gpt-5")

        RequestBodyMerge.mergeInto(
            target,
            mapOf("generation_config" to JSONObject().put("seed", 42).put("top_k", 20)),
        )

        assertEquals(42, target.getJSONObject("generation_config").getInt("seed"))
        assertEquals(20, target.getJSONObject("generation_config").getInt("top_k"))
        assertEquals("gpt-5", target.getString("model"))
    }

    @Test
    fun `arrays replace the app array wholesale`() {
        val target = JSONObject().put("plugins", JSONArray().put(JSONObject().put("id", "app")))

        RequestBodyMerge.mergeInto(
            target,
            mapOf("plugins" to JSONArray().put(JSONObject().put("id", "web"))),
        )

        val plugins = target.getJSONArray("plugins")
        assertEquals(1, plugins.length())
        assertEquals("web", plugins.getJSONObject(0).getString("id"))
    }

    @Test
    fun `scalars override the app value`() {
        val target = JSONObject().put("temperature", 0.2).put("stream", false)

        RequestBodyMerge.mergeInto(target, mapOf("temperature" to 1.0, "stream" to true, "seed" to 7))

        assertEquals(1.0, target.getDouble("temperature"), 0.0)
        assertTrue(target.getBoolean("stream"))
        assertEquals(7, target.getInt("seed"))
    }

    @Test
    fun `object replaced by a scalar is a caller win`() {
        val target = JSONObject().put("reasoning", JSONObject().put("effort", "low"))

        RequestBodyMerge.mergeInto(target, mapOf("reasoning" to "none"))

        assertEquals("none", target.getString("reasoning"))
    }

    @Test
    fun `null value is written as JSON null`() {
        val target = JSONObject().put("stop", "END")

        RequestBodyMerge.mergeInto(target, mapOf("stop" to null))

        assertTrue(target.has("stop"))
        assertTrue(target.isNull("stop"))
    }

    @Test
    fun `merge recurses through every nesting level`() {
        val target = JSONObject().put(
            "a",
            JSONObject().put("b", JSONObject().put("c", JSONObject().put("keep", 1).put("over", 2))),
        )

        RequestBodyMerge.mergeInto(
            target,
            mapOf(
                "a" to JSONObject().put(
                    "b",
                    JSONObject().put("c", JSONObject().put("over", 9)),
                ),
            ),
        )

        val c = target.getJSONObject("a").getJSONObject("b").getJSONObject("c")
        assertEquals(1, c.getInt("keep"))
        assertEquals(9, c.getInt("over"))
    }

    @Test
    fun `newly merged object is detached from the caller map`() {
        val callerObject = JSONObject().put("seed", 1)
        val callerBody = linkedMapOf<String, Any?>("generation_config" to callerObject)
        val target = JSONObject()

        RequestBodyMerge.mergeInto(target, callerBody)
        callerObject.put("seed", 99)

        assertEquals(1, target.getJSONObject("generation_config").getInt("seed"))
        assertFalse(target.getJSONObject("generation_config").has("mutated"))
    }
}
