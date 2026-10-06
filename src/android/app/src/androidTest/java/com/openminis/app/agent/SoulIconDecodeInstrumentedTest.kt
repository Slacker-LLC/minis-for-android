package com.openminis.app.agent

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class SoulIconDecodeInstrumentedTest {
    /** A flat PNG: tiny on disk, side x side pixels (4 bytes each) once decoded in full. */
    private fun hugeFlatPng(side: Int): ByteArray {
        val big = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val out = ByteArrayOutputStream()
        big.compress(Bitmap.CompressFormat.PNG, 100, out)
        big.recycle()
        return out.toByteArray()
    }

    @Test
    fun aVeryLargeImageDecodesSampledAndStillEncodes() {
        val png = hugeFlatPng(4000)
        assertTrue("the file itself is small", png.size < 2 * 1024 * 1024)

        val decoded = SoulIcon.decodeForIcon(png)
        assertNotNull(decoded)
        val expected = SoulIcon.sampleSizeFor(4000, 4000)
        assertEquals(4000 / expected, decoded!!.width)
        assertTrue("far below full size", decoded.width <= 4000 / 16)
        assertTrue("never below the target", decoded.width >= SoulIcon.STORED_PIXELS)

        val encoded = SoulIcon.encode(decoded)
        assertTrue(encoded is SoulIcon.EncodeResult.Success)
    }

    @Test
    fun aSmallImageIsDecodedAtFullSize() {
        val small = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val out = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val decoded = SoulIcon.decodeForIcon(out.toByteArray())
        assertEquals(48, decoded!!.width)
    }

    @Test
    fun bytesThatAreNotAnImageGiveNull() {
        assertNull(SoulIcon.decodeForIcon(ByteArray(64) { it.toByte() }))
        assertNull(SoulIcon.decodeForIcon(ByteArray(0)))
    }
}
