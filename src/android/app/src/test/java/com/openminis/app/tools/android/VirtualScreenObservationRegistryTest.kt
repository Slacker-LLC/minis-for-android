package com.openminis.app.tools.android

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class VirtualScreenObservationRegistryTest {
    @After fun clear() = VirtualScreenObservationRegistry.clearForTests()

    @Test fun refsAreBoundToSessionAndDisplayAndCarryServiceTargetIndex() {
        val observed = VirtualScreenObservationRegistry.observe("session-a", 9, dump(label = "Continue"))
        assertEquals("display-local", observed.getString("coordinateSpace"))
        assertEquals("u1", observed.getJSONArray("targets").getJSONObject(0).getString("ref"))
        val generation = observed.getLong("generation")
        val same = VirtualScreenObservationRegistry.resolve("session-a", 9, generation, "u1", dump(label = "Continue"))
        assertTrue(same is VirtualScreenRefResolution.Found)
        assertEquals(1, (same as VirtualScreenRefResolution.Found).target.index)

        val otherSession = VirtualScreenObservationRegistry.resolve("session-b", 9, generation, "u1", dump(label = "Continue"))
        assertEquals("STALE_UI_REF", (otherSession as VirtualScreenRefResolution.Error).code)
        val otherDisplay = VirtualScreenObservationRegistry.resolve("session-a", 10, generation, "u1", dump(label = "Continue"))
        assertEquals("STALE_UI_REF", (otherDisplay as VirtualScreenRefResolution.Error).code)
    }

    @Test fun changedOrTruncatedTreeCannotAuthorizeARefAction() {
        val observed = VirtualScreenObservationRegistry.observe("session", 7, dump(label = "Before"))
        val changed = VirtualScreenObservationRegistry.resolve("session", 7, observed.getLong("generation"), "u1", dump(label = "After"))
        assertEquals("STALE_UI_REF", (changed as VirtualScreenRefResolution.Error).code)

        val truncatedObserved = VirtualScreenObservationRegistry.observe("session", 7, dump(label = "Before"))
        val truncated = VirtualScreenObservationRegistry.resolve(
            "session", 7, truncatedObserved.getLong("generation"), "u1", dump(label = "Before", truncated = true),
        )
        assertEquals("UI_SNAPSHOT_TRUNCATED", (truncated as VirtualScreenRefResolution.Error).code)
    }

    private fun dump(label: String, truncated: Boolean = false) = JSONObject()
        .put("displayId", 7)
        .put("windows", "[]")
        .put("targets", "[]")
        .let {
            val target = JSONObject().put("index", 1).put("label", label).put("bounds", "10,20,90,70")
                .put("actions", org.json.JSONArray().put("click").put("input"))
            val result = JSONObject().put("displayId", 7).put("windows", org.json.JSONArray())
                .put("targets", org.json.JSONArray().put(target)).put("inputs", org.json.JSONArray())
            if (truncated) result.put("truncated", true)
            result.toString()
        }
}
