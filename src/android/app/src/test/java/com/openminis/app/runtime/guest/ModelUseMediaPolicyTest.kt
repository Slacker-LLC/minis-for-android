package com.openminis.app.runtime.guest

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Upstream OpenMinis `ModelUseOffloadHandler.mediaRequired` / `noMediaResult`: a run that had to
 * produce media but got none fails (exit 1) instead of writing text into a `.png`.
 */
class ModelUseMediaPolicyTest {
    @Test
    fun mediaExtensionOutputRequiresMedia() {
        assertTrue(ModelUseMediaPolicy.mediaRequired("/workspace/x.png", "png", listOf("text")))
        assertTrue(ModelUseMediaPolicy.mediaRequired("/workspace/x.mp3", "mp3", listOf("text")))
        assertTrue(ModelUseMediaPolicy.mediaRequired("/workspace/x.mp4", "mp4", listOf("text")))
    }

    @Test
    fun imageOnlyModelRequiresMediaEvenWithoutOutputPath() {
        assertTrue(ModelUseMediaPolicy.mediaRequired(null, "", listOf("image")))
    }

    @Test
    fun textRunsDoNotRequireMedia() {
        assertFalse(ModelUseMediaPolicy.mediaRequired(null, "", listOf("text")))
        assertFalse(ModelUseMediaPolicy.mediaRequired("/workspace/a.txt", "txt", listOf("text")))
        assertFalse(ModelUseMediaPolicy.mediaRequired(null, "", listOf("text", "image")))
    }

    @Test
    fun noMediaResultFailsClosedAndCarriesModelText() {
        val r = ModelUseMediaPolicy.noMediaResult("m/x", "chat", "I cannot draw that.", "/workspace/x.png")
        assertEquals(1, r.exitCode)
        val j = JSONObject(r.output)
        assertEquals("no_media_generated", j.getString("error"))
        assertTrue(j.getString("message").contains("/workspace/x.png"))
        assertEquals("I cannot draw that.", j.getString("model_text"))
    }

    @Test
    fun noMediaResultBoundsAndOmitsModelText() {
        val long = ModelUseMediaPolicy.noMediaResult("m", "chat", "a".repeat(5000), null)
        assertEquals(2000, JSONObject(long.output).getString("model_text").length)
        val blank = ModelUseMediaPolicy.noMediaResult("m", "chat", "  ", null)
        assertFalse(JSONObject(blank.output).has("model_text"))
    }
}
