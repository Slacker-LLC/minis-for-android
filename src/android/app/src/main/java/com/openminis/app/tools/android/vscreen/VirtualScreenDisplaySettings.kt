package com.openminis.app.tools.android.vscreen

/** User-selectable scale relative to the device's physical display; kept outside Room. */
data class VirtualScreenDisplaySettings(
    val scale: Float = DEFAULT_SCALE,
) {
    companion object {
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 1.0f
        const val DEFAULT_SCALE = 1.0f
    }
}

object VirtualScreenDisplaySettingsPolicy {
    fun isValid(settings: VirtualScreenDisplaySettings): Boolean =
        settings.scale.isFinite() && settings.scale in
            VirtualScreenDisplaySettings.MIN_SCALE..VirtualScreenDisplaySettings.MAX_SCALE
}
