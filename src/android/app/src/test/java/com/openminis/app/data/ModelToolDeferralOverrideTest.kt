package com.openminis.app.data

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelOverrides
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The per-model deferred-tool-loading choice: stored on the entry's overrides, read back off the effective model. */
class ModelToolDeferralOverrideTest {
    private fun entry(id: String, overrides: ModelOverrides = ModelOverrides()) =
        ModelEntry("p", LLMModel(id, id, "Provider"), overrides)

    @Test
    fun `the choice reaches the effective model, null meaning auto`() {
        assertNull(entry("a").model.toolDeferral)
        assertEquals(true, entry("a", ModelOverrides(toolDeferral = true)).model.toolDeferral)
        assertEquals(false, entry("a", ModelOverrides(toolDeferral = false)).model.toolDeferral)
    }

    @Test
    fun `a choice makes the overrides non-empty so it is saved, and auto leaves them empty`() {
        assertFalse(ModelOverrides(toolDeferral = false).isEmpty)
        assertFalse(ModelOverrides(toolDeferral = true).isEmpty)
        assertTrue(ModelOverrides(toolDeferral = null).isEmpty)
        assertTrue(entry("a", ModelOverrides(toolDeferral = false)).isUserModified)
    }

    @Test
    fun `one entry's choice does not leak into another entry of the same model`() {
        val off = entry("same-model", ModelOverrides(toolDeferral = false))
        val untouched = entry("same-model")
        assertEquals(false, off.model.toolDeferral)
        assertNull(untouched.model.toolDeferral)
    }

    @Test
    fun `the choice survives the stored JSON and an older record without it reads as auto`() {
        val json = Json { ignoreUnknownKeys = true }
        val stored = json.encodeToString(ModelOverrides.serializer(), ModelOverrides(toolDeferral = false))
        assertEquals(false, json.decodeFromString(ModelOverrides.serializer(), stored).toolDeferral)
        assertNull(json.decodeFromString(ModelOverrides.serializer(), "{}").toolDeferral)
        assertNull(json.decodeFromString(ModelOverrides.serializer(), """{"displayName":"x"}""").toolDeferral)
    }
}
