package com.openminis.app.tools.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualScreenObservationRegistryTest {
    @After fun clear() = VirtualScreenObservationRegistry.clearForTests()

    @Test fun refsAreBoundToSessionAndDisplayAndCarryTheLocator() {
        val observed = VirtualScreenObservationRegistry.observe("session-a", 9, dump(label = "Continue"))
        assertEquals("display-local", observed.getString("coordinateSpace"))
        assertEquals("u1", observed.getJSONArray("targets").getJSONObject(0).getString("ref"))
        val generation = observed.getLong("generation")

        val same = VirtualScreenObservationRegistry.locate("session-a", 9, generation, "u1")
        assertTrue(same is VirtualScreenRefResolution.Found)
        val found = same as VirtualScreenRefResolution.Found
        assertEquals(1, found.target.index)
        assertEquals("0.1", found.target.locator.getString("path"))
        assertFalse(found.scanTruncated)

        val otherSession = VirtualScreenObservationRegistry.locate("session-b", 9, generation, "u1")
        assertEquals("STALE_UI_REF", (otherSession as VirtualScreenRefResolution.Error).code)
        val otherDisplay = VirtualScreenObservationRegistry.locate("session-a", 10, generation, "u1")
        assertEquals("STALE_UI_REF", (otherDisplay as VirtualScreenRefResolution.Error).code)
    }

    @Test fun unknownGenerationAndUnknownRefAreRefused() {
        val observed = VirtualScreenObservationRegistry.observe("session", 7, dump(label = "A"))
        val generation = observed.getLong("generation")
        val unknownRef = VirtualScreenObservationRegistry.locate("session", 7, generation, "u99")
        assertEquals("UI_REF_NOT_FOUND", (unknownRef as VirtualScreenRefResolution.Error).code)
        val unknownGeneration = VirtualScreenObservationRegistry.locate("session", 7, generation + 100, "u1")
        assertEquals("STALE_UI_REF", (unknownGeneration as VirtualScreenRefResolution.Error).code)
    }

    @Test fun clearedSessionAndDisplayDropTheirRefs() {
        val a = VirtualScreenObservationRegistry.observe("session", 7, dump(label = "A")).getLong("generation")
        VirtualScreenObservationRegistry.clearSession("session")
        assertTrue(VirtualScreenObservationRegistry.locate("session", 7, a, "u1") is VirtualScreenRefResolution.Error)
        val b = VirtualScreenObservationRegistry.observe("session", 7, dump(label = "B")).getLong("generation")
        VirtualScreenObservationRegistry.clearDisplay(7)
        assertTrue(VirtualScreenObservationRegistry.locate("session", 7, b, "u1") is VirtualScreenRefResolution.Error)
    }

    @Test fun outputCapIsInformationalAndDoesNotMakeTheScanTruncated() {
        val raw = dump(rows = listOf("One", "Two", "Three"))
        val observed = VirtualScreenObservationRegistry.observe("s", 7, raw, VirtualScreenObserveOptions(maxNodes = 2))
        assertEquals(2, observed.getJSONArray("targets").length())
        assertTrue(observed.getBoolean("outputTruncated"))
        assertFalse(observed.getBoolean("truncated"))
        val shown = VirtualScreenObservationRegistry.locate("s", 7, observed.getLong("generation"), "u2")
        assertFalse((shown as VirtualScreenRefResolution.Found).scanTruncated)
        // A target the model was never shown cannot be acted on.
        val hidden = VirtualScreenObservationRegistry.locate("s", 7, observed.getLong("generation"), "u3")
        assertEquals("UI_REF_NOT_FOUND", (hidden as VirtualScreenRefResolution.Error).code)
    }

    @Test fun serviceScanTruncationIsReportedAndPropagatedToTheLocator() {
        val observed = VirtualScreenObservationRegistry.observe("s", 7, dump(label = "A", truncated = true))
        assertTrue(observed.getBoolean("truncated"))
        val found = VirtualScreenObservationRegistry.locate("s", 7, observed.getLong("generation"), "u1")
        assertTrue((found as VirtualScreenRefResolution.Found).scanTruncated)
    }

    @Test fun truncatedWindowAlsoCountsAsScanTruncation() {
        val result = JSONObject(dump(label = "A"))
        result.put("windows", JSONArray().put(JSONObject().put("package", "p").put("truncated", true)))
        val observed = VirtualScreenObservationRegistry.observe("s", 7, result.toString())
        assertTrue(observed.getBoolean("truncated"))
    }

    @Test fun modelFacingTargetsDoNotLeakTheLocator() {
        val observed = VirtualScreenObservationRegistry.observe("s", 7, dump(label = "Continue"))
        val target = observed.getJSONArray("targets").getJSONObject(0)
        assertFalse(target.has("path"))
        assertFalse(target.has("locator"))
        assertFalse(target.has("windowId"))
        assertEquals("Continue", target.getString("label"))
    }

    @Test fun unlabelledTargetIsNamedByItsResourceId() {
        val observed = VirtualScreenObservationRegistry.observe("s", 7, dump(label = "", viewId = "com.x:id/send_button"))
        assertEquals("send_button", observed.getJSONArray("targets").getJSONObject(0).getString("id"))
    }

    @Test fun layoutWarningsReachTheModel() {
        val raw = JSONObject(dump(label = "A")).put("layoutWarnings", JSONArray().put(JSONObject().put("code", "letterboxed"))).toString()
        val observed = VirtualScreenObservationRegistry.observe("s", 7, raw)
        assertEquals(1, observed.getJSONArray("layoutWarnings").length())
        assertFalse(VirtualScreenObservationRegistry.observe("s", 7, dump(label = "A")).has("layoutWarnings"))
    }

    @Test fun fingerprintChangesWithContentAndIsRetrievableByGeneration() {
        val first = VirtualScreenObservationRegistry.observe("s", 7, dump(label = "Before")).getLong("generation")
        val same = VirtualScreenObservationRegistry.observe("s", 7, dump(label = "Before")).getLong("generation")
        val changed = VirtualScreenObservationRegistry.observe("s", 7, dump(label = "After")).getLong("generation")
        assertEquals(
            VirtualScreenObservationRegistry.observedFingerprint(first),
            VirtualScreenObservationRegistry.observedFingerprint(same),
        )
        assertNotEquals(
            VirtualScreenObservationRegistry.observedFingerprint(first),
            VirtualScreenObservationRegistry.observedFingerprint(changed),
        )
        assertNull(VirtualScreenObservationRegistry.observedFingerprint(first + 1000))
    }

    private fun dump(
        label: String = "",
        truncated: Boolean = false,
        viewId: String = "",
        rows: List<String> = listOf(label),
    ): String {
        val targets = JSONArray()
        rows.forEachIndexed { i, text ->
            targets.put(
                JSONObject().put("index", i + 1).put("label", text).put("path", "0.${i + 1}").put("windowId", 3)
                    .put("packageName", "com.x").put("className", "android.widget.Button").put("viewId", viewId)
                    .put("text", text).put("desc", "").put("bounds", "10,${20 + i * 60},90,${70 + i * 60}")
                    .put("actions", JSONArray().put("click").put("input")),
            )
        }
        return JSONObject().put("displayId", 7).put("windows", JSONArray()).put("targets", targets)
            .put("inputs", JSONArray()).put("texts", JSONArray()).apply { if (truncated) put("truncated", true) }.toString()
    }
}
