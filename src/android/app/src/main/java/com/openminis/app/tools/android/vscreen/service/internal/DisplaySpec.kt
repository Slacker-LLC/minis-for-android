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

internal data class DisplaySpec(val width: Int, val height: Int, val dpi: Int) {
    companion object {
        const val FALLBACK_WIDTH = 720
        const val FALLBACK_HEIGHT = 1280
        const val FALLBACK_DPI = 320

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
