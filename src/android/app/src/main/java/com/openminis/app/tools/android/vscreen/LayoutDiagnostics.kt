package com.openminis.app.tools.android.vscreen

import kotlin.math.abs

internal data class WindowGeometry(
    val type: Int,
    val packageName: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Explains "the layout in the virtual screen looks wrong" with evidence instead of a guess.
 *
 * An application window that does not fill the virtual display means the system laid the app
 * out differently from the display we asked for. The patterns below are hints, not proof; the
 * numbers they quote are the proof. Only application windows are judged: status bars, IME and
 * overlays legitimately cover part of the display.
 */
internal object LayoutDiagnostics {
    const val TYPE_APPLICATION = 1
    private const val TOLERANCE_PX = 2

    fun warnings(displayWidth: Int, displayHeight: Int, windows: List<WindowGeometry>): List<String> {
        if (displayWidth <= 0 || displayHeight <= 0) return emptyList()
        val out = ArrayList<String>()
        for (window in windows) {
            if (window.type != TYPE_APPLICATION || window.width <= 0 || window.height <= 0) continue
            val fills = abs(window.left) <= TOLERANCE_PX && abs(window.top) <= TOLERANCE_PX &&
                abs(window.width - displayWidth) <= TOLERANCE_PX && abs(window.height - displayHeight) <= TOLERANCE_PX
            if (fills) continue
            out += describe(displayWidth, displayHeight, window)
        }
        return out
    }

    private fun describe(displayWidth: Int, displayHeight: Int, window: WindowGeometry): String {
        val shape = when {
            // Checked first: a window sized height x width always also exceeds the display.
            abs(window.width - displayHeight) <= TOLERANCE_PX && abs(window.height - displayWidth) <= TOLERANCE_PX ->
                "orientation_swapped(app laid out in the other orientation)"
            window.width > displayWidth + TOLERANCE_PX || window.height > displayHeight + TOLERANCE_PX ->
                "window_exceeds_display"
            abs(window.width - displayWidth) <= TOLERANCE_PX ->
                "letterboxed_vertically(app likely caps its aspect ratio)"
            abs(window.height - displayHeight) <= TOLERANCE_PX ->
                "letterboxed_horizontally(app likely caps its aspect ratio or is not resizable)"
            else -> "scaled_into_display(compat or non-resizable app)"
        }
        return "$shape pkg=${window.packageName} window=${window.left},${window.top},${window.right},${window.bottom}" +
            " (${window.width}x${window.height}, ratio ${ratio(window.width, window.height)})" +
            " display=${displayWidth}x$displayHeight (ratio ${ratio(displayWidth, displayHeight)})"
    }

    private fun ratio(width: Int, height: Int): String =
        if (width <= 0) "n/a" else "%.2f".format(java.util.Locale.US, height.toDouble() / width)
}
