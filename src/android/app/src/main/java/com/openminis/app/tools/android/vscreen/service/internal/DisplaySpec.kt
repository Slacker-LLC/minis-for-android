/*
 * Adapted for Minis from ShadowAuto (`android-notes/ShadowAuto`, commit `611e5eb0e1c94befda3c17c97438912dfd30e8a8`); modified.
 * Original source: android-shell/src/main/java/com/silentauto/shell/DisplaySpec.java.
 * Licensed under Apache-2.0; see third_party/shadowauto/LICENSE.
 */
package com.openminis.app.tools.android.vscreen.service.internal

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import com.openminis.app.tools.android.vscreen.VirtualScreenDisplaySettings
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal data class DisplaySpec(val width: Int, val height: Int, val dpi: Int) {
    fun scaledForVirtualDisplay(requestedScale: Float): DisplaySpec {
        require(
            requestedScale.isFinite() && requestedScale in
                VirtualScreenDisplaySettings.MIN_SCALE..VirtualScreenDisplaySettings.MAX_SCALE,
        ) {
            "invalid_display_scale"
        }
        val maxDimensionScale = MAX_DIMENSION.toFloat() / max(width, height)
        val maxAreaScale = sqrt(MAX_PIXELS.toDouble() / (width.toLong() * height)).toFloat()
        val maxSupportedScale = min(1f, min(maxDimensionScale, maxAreaScale))
        val minRequiredScale = max(MIN_DIMENSION.toFloat() / width, MIN_DIMENSION.toFloat() / height)
        require(minRequiredScale <= maxSupportedScale) { "device_display_out_of_bounds" }
        val scale = min(requestedScale, maxSupportedScale).coerceAtLeast(minRequiredScale)
        return DisplaySpec(
            width = evenPixels(width * scale),
            height = evenPixels(height * scale),
            dpi = (dpi * scale).roundToInt().coerceIn(MIN_DPI, MAX_DPI),
        )
    }

    companion object {
        const val FALLBACK_WIDTH = 720
        const val FALLBACK_HEIGHT = 1280
        const val FALLBACK_DPI = 320
        const val MIN_DIMENSION = 320
        const val MAX_DIMENSION = 2400
        const val MIN_DPI = 80
        const val MAX_DPI = 800
        const val MAX_PIXELS = 4_194_304L

        private fun evenPixels(value: Float): Int =
            (value.toInt() / 2 * 2).coerceIn(MIN_DIMENSION, MAX_DIMENSION)

        fun current(): DisplaySpec {
            val display = runCatching {
                (ShellContext.get().getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                    ?.getDisplay(Display.DEFAULT_DISPLAY)
            }.getOrNull() ?: return DisplaySpec(FALLBACK_WIDTH, FALLBACK_HEIGHT, FALLBACK_DPI)
            val point = Point()
            val metrics = DisplayMetrics()
            runCatching { display.getRealSize(point) }
            runCatching { display.getRealMetrics(metrics) }
            val width = point.x.takeIf { it > 0 } ?: metrics.widthPixels
            val height = point.y.takeIf { it > 0 } ?: metrics.heightPixels
            val dpi = metrics.densityDpi.takeIf { it > 0 } ?: FALLBACK_DPI
            return if (width > 0 && height > 0) DisplaySpec(width, height, dpi)
            else DisplaySpec(FALLBACK_WIDTH, FALLBACK_HEIGHT, FALLBACK_DPI)
        }
    }
}
