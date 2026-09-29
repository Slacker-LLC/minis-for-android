package com.openminis.app.tools.android.vscreen

/** User-selectable virtual display dimensions; kept in SharedPreferences rather than Room. */
data class VirtualScreenDisplaySettings(
    val width: Int = DEFAULT_WIDTH,
    val height: Int = DEFAULT_HEIGHT,
    val dpi: Int = DEFAULT_DPI,
) {
    companion object {
        const val DEFAULT_WIDTH = 720
        const val DEFAULT_HEIGHT = 1600
        const val DEFAULT_DPI = 320
    }
}

object VirtualScreenDisplaySettingsPolicy {
    fun isValid(settings: VirtualScreenDisplaySettings): Boolean =
        settings.width in 320..1920 && settings.height in 480..2560 && settings.dpi in 120..640
}
