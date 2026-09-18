package com.openminis.app.tools.android

enum class ScreenshotQuality(val wireName: String) {
    NOT_REQUESTED("not_requested"),
    COMPLETE("complete"),
    PARTIAL("partial"),
    FAILED("failed"),
}

object ScreenshotOutcomePolicy {
    fun classify(
        requested: Boolean,
        hasImage: Boolean,
        complete: Boolean,
    ): ScreenshotQuality = when {
        !requested -> ScreenshotQuality.NOT_REQUESTED
        hasImage && complete -> ScreenshotQuality.COMPLETE
        hasImage -> ScreenshotQuality.PARTIAL
        else -> ScreenshotQuality.FAILED
    }

    /** Minis has no Root screenshot replay: excluded or unresolved windows forbid a complete result. */
    fun mayFallbackToRoot(
        excludedPackagesPresent: Boolean,
        criticalWindowMissing: Boolean,
    ): Boolean = !excludedPackagesPresent && !criticalWindowMissing
}
