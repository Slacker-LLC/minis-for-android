package com.openminis.app.data

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelSlot
import com.openminis.app.data.model.ModelSlots
import com.openminis.app.data.model.hasImageOutput
import com.openminis.app.ui.components.PickerModalityFilter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Image slot: which models qualify, and that it survives the same trips the other slots do. */
class ImageSlotTest {
    private fun model(vararg outputs: String) =
        LLMModel(id = "m", displayName = "m", provider = "p", outputModalities = outputs.toList())

    @Test
    fun `only a model that emits images qualifies`() {
        assertTrue(model("image").hasImageOutput)
        assertTrue(model("text", "image").hasImageOutput)
        assertFalse(model("text").hasImageOutput)
        assertFalse("outputs unknown means plain text", LLMModel(id = "m", displayName = "m", provider = "p").hasImageOutput)
        assertTrue(PickerModalityFilter.IMAGE_OUTPUT.matches(model("image")))
        assertFalse(PickerModalityFilter.IMAGE_OUTPUT.matches(model("text")))
    }

    @Test
    fun `entries and withEntries address the image slot like the others`() {
        val slots = ModelSlots(main = listOf("a")).withEntries(ModelSlot.image, listOf("img1", "img2"))
        assertEquals(listOf("img1", "img2"), slots.entries(ModelSlot.image))
        assertEquals(listOf("img1", "img2"), slots.image)
        assertEquals(listOf("a"), slots.entries(ModelSlot.main))
        assertTrue(ModelSlot.entries.map { it.name }.contains("image"))
    }

    @Test
    fun `a saved configuration from before the slot existed still reads, with the slot empty`() {
        val old = """{"main":["a"],"light":[],"vision":[],"voiceInput":[],"voiceOutput":[]}"""
        val decoded = Json.decodeFromString<ModelSlots>(old)
        assertEquals(emptyList<String>(), decoded.image)
        assertEquals(listOf("a"), decoded.main)
    }

    @Test
    fun `the slot round-trips through the backup format`() {
        val slots = ModelSlots(image = listOf("x"))
        val back = Json.decodeFromString<ModelSlots>(Json.encodeToString(slots))
        assertEquals(listOf("x"), back.image)
    }
}
