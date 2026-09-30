/*
 * Adapted for Minis from ShadowAuto (`android-notes/ShadowAuto`, commit `611e5eb0e1c94befda3c17c97438912dfd30e8a8`); modified.
 * Original source: android-shell/src/main/java/com/silentauto/shell/ScreenCapture.java.
 * Licensed under Apache-2.0; see third_party/shadowauto/LICENSE.
 */
package com.openminis.app.tools.android.vscreen.service.internal

import android.graphics.Bitmap
import android.media.Image
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

internal object ScreenCapture {
    /** A virtual display is at most 2560 px on a side, so a full-resolution capture fits. */
    private const val MAX_OUTPUT_DIM = 2560

    fun bitmapFromImage(image: Image): Bitmap {
        val plane = image.planes.firstOrNull() ?: throw IllegalStateException("image_plane_unavailable")
        val width = image.width
        val height = image.height
        val paddedWidth = plane.rowStride / plane.pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        val buffer: ByteBuffer = plane.buffer.duplicate().apply { rewind() }
        padded.copyPixelsFromBuffer(buffer)
        val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
        padded.recycle()
        return cropped
    }

    fun isNonBlack(bitmap: Bitmap): Boolean {
        if (bitmap.width <= 0 || bitmap.height <= 0) return false
        val strideX = (bitmap.width / 32).coerceAtLeast(1)
        val strideY = (bitmap.height / 32).coerceAtLeast(1)
        var samples = 0
        var lit = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val color = bitmap.getPixel(x, y)
                val brightness = ((color shr 16) and 0xff) + ((color shr 8) and 0xff) + (color and 0xff)
                if (brightness > 36) lit++
                samples++
                x += strideX
            }
            y += strideY
        }
        return samples > 0 && lit >= (samples / 100).coerceAtLeast(1)
    }

    /** The Binder response is a PFD; this bounded local buffer is never marshalled as a Binder byte array. */
    fun jpeg(bitmap: Bitmap, maxDim: Int, requestedQuality: Int, maxBytes: Int): ByteArray {
        var current = scaled(bitmap, maxDim.coerceIn(256, MAX_OUTPUT_DIM))
        val initialQuality = requestedQuality.coerceIn(30, 95)
        try {
            while (true) {
                val qualities = listOf(initialQuality, 75, 60, 45, 30).distinct().filter { it <= initialQuality }
                for (quality in qualities) {
                    val output = CappedByteArrayOutputStream(maxBytes)
                    try {
                        if (current.compress(Bitmap.CompressFormat.JPEG, quality, output)) return output.toByteArray()
                    } catch (_: SizeLimitReached) {
                        // Retry at lower quality and, if needed, smaller dimensions.
                    }
                }
                if (maxOf(current.width, current.height) <= 256) throw IllegalStateException("screenshot_too_large")
                val next = Bitmap.createScaledBitmap(current, (current.width * 0.75f).toInt().coerceAtLeast(1),
                    (current.height * 0.75f).toInt().coerceAtLeast(1), true)
                if (current !== bitmap) current.recycle()
                current = next
            }
        } finally {
            if (current !== bitmap && !current.isRecycled) current.recycle()
        }
    }

    private fun scaled(bitmap: Bitmap, maxDim: Int): Bitmap {
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= maxDim) return bitmap
        val scale = maxDim.toFloat() / largest
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1), true)
    }

    private class SizeLimitReached : RuntimeException()

    private class CappedByteArrayOutputStream(private val limit: Int) : ByteArrayOutputStream(64 * 1024) {
        override fun write(value: Int) {
            if (count >= limit) throw SizeLimitReached()
            super.write(value)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (length > limit - count) throw SizeLimitReached()
            super.write(buffer, offset, length)
        }
    }
}
