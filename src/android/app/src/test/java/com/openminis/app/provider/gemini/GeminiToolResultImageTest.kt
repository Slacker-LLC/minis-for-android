package com.openminis.app.provider.gemini

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A tool result's image must reach Gemini as `inlineData`; snapshot GeminiProvider.kt:277-291. */
class GeminiToolResultImageTest {
    @Test
    fun noImageMeansNoInlineData() {
        assertNull(toolResultInlineImage(null, "image/png"))
        assertNull(toolResultInlineImage(ByteArray(0), "image/png"))
    }

    @Test
    fun smallImageKeepsItsBytesAndMime() {
        val bytes = byteArrayOf(1, 2, 3)
        val (out, mime) = toolResultInlineImage(bytes, "image/png")!!
        assertArrayEquals(bytes, out)
        assertEquals("image/png", mime)
    }

    @Test
    fun missingMimeDefaultsToJpeg() {
        assertEquals("image/jpeg", toolResultInlineImage(byteArrayOf(9), null)!!.second)
    }
}
