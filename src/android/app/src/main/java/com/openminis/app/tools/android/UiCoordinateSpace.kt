package com.openminis.app.tools.android

/**
 * [T-eta-ui-coordinate-space] The pixel space a model's x/y (and scroll deltas) are
 * expressed in.
 *
 * Ported from Eta `agent/model/AgentToolSchema.kt` `coordinateSpace()` and the
 * shared-contract note in `agent/model/AgentScreenObservationContract.kt`
 * (Mangi-11/Eta @ c15de97): schema, executor and the returned trace describe the
 * same two spaces.
 *
 * Why this app needs it: `android_ui screenshot` scales the capture by default
 * (`scale=0.5`), so a position read off the returned image is not a device pixel.
 * Coordinates used to be dispatched verbatim, which put a coordinate read at 50 %
 * scale at half the intended distance — a silent misclick instead of an error.
 * `screenshot` (the default) therefore means "pixels of the most recent capture"
 * and is converted through that capture's geometry; `screen` means real device
 * pixels and is dispatched verbatim.
 */
enum class UiCoordinateSpace(val wireName: String) {
    SCREENSHOT("screenshot"),
    SCREEN("screen"),
    ;

    companion object {
        /** Eta's default: coordinates belong to the last image the model was shown. */
        val DEFAULT: UiCoordinateSpace = SCREENSHOT

        /** Values the model may send — the tool schema and [parse] share this list. */
        val wireValues: List<String> = entries.map { it.wireName }

        fun parse(raw: String?): UiCoordinateSpace =
            entries.firstOrNull { it.wireName.equals(raw?.trim(), ignoreCase = true) } ?: DEFAULT
    }
}

/** Geometry of one capture: the frame that `screenshot`-space coordinates refer to. */
data class ScreenshotFrame(
    val width: Int,
    val height: Int,
    val originalWidth: Int,
    val originalHeight: Int,
) {
    val scaleX: Double get() = if (width <= 0) 1.0 else originalWidth.toDouble() / width
    val scaleY: Double get() = if (height <= 0) 1.0 else originalHeight.toDouble() / height

    /** True when this frame still describes the screen the action is about to touch. */
    fun matchesScreen(screenWidth: Int, screenHeight: Int): Boolean =
        originalWidth == screenWidth && originalHeight == screenHeight
}

sealed class UiCoordinateResolution {
    data class Resolved(val x: Double, val y: Double, val space: UiCoordinateSpace) : UiCoordinateResolution()
    data class Refused(val code: String, val message: String) : UiCoordinateResolution()
}

/**
 * [T-eta-ui-coordinate-space] One place decides what a coordinate means. Refusals are
 * explicit: a `screenshot`-space action without a usable frame fails closed instead
 * of being guessed at in device pixels.
 */
object UiCoordinateSpacePolicy {

    const val ERROR_UNAVAILABLE = "COORDINATE_SPACE_UNAVAILABLE"
    const val ERROR_FRAME_STALE = "SCREENSHOT_FRAME_STALE"
    const val ERROR_OUT_OF_FRAME = "COORDINATE_OUT_OF_SCREENSHOT"

    fun resolvePoint(
        x: Double,
        y: Double,
        space: UiCoordinateSpace,
        frame: ScreenshotFrame?,
        screenWidth: Int,
        screenHeight: Int,
    ): UiCoordinateResolution = when (val scales = scales(space, frame, screenWidth, screenHeight)) {
        is FrameScales.Refused -> UiCoordinateResolution.Refused(scales.code, scales.message)
        is FrameScales.Ok -> {
            val target = frame
            // Only screenshot-space points are bounded by the image; a screen-space
            // coordinate is a device pixel and may legitimately sit anywhere.
            if (space == UiCoordinateSpace.SCREENSHOT && target != null &&
                (x > target.width || y > target.height)
            ) {
                UiCoordinateResolution.Refused(
                    ERROR_OUT_OF_FRAME,
                    "x=" + x + " y=" + y + " is outside the " + target.width + "x" + target.height +
                        " screenshot; screenshot-space coordinates must be read off that image, " +
                        "or send coordinateSpace=screen",
                )
            } else {
                UiCoordinateResolution.Resolved(
                    x = x * scales.scaleX,
                    y = y * scales.scaleY,
                    space = space,
                )
            }
        }
    }

    fun resolveDelta(
        deltaX: Double,
        deltaY: Double,
        space: UiCoordinateSpace,
        frame: ScreenshotFrame?,
        screenWidth: Int,
        screenHeight: Int,
    ): UiCoordinateResolution = when (val scales = scales(space, frame, screenWidth, screenHeight)) {
        is FrameScales.Refused -> UiCoordinateResolution.Refused(scales.code, scales.message)
        is FrameScales.Ok -> UiCoordinateResolution.Resolved(
            x = deltaX * scales.scaleX,
            y = deltaY * scales.scaleY,
            space = space,
        )
    }

    private sealed class FrameScales {
        data class Ok(val scaleX: Double, val scaleY: Double) : FrameScales()
        data class Refused(val code: String, val message: String) : FrameScales()
    }

    private fun scales(
        space: UiCoordinateSpace,
        frame: ScreenshotFrame?,
        screenWidth: Int,
        screenHeight: Int,
    ): FrameScales = when {
        space == UiCoordinateSpace.SCREEN -> FrameScales.Ok(1.0, 1.0)
        frame == null -> FrameScales.Refused(
            ERROR_UNAVAILABLE,
            "coordinateSpace=screenshot needs a capture to refer to: call android_ui screenshot first, " +
                "or send coordinateSpace=screen for real device pixels",
        )
        !frame.matchesScreen(screenWidth, screenHeight) -> FrameScales.Refused(
            ERROR_FRAME_STALE,
            "the screen is now " + screenWidth + "x" + screenHeight + " but the last screenshot was " +
                frame.originalWidth + "x" + frame.originalHeight + " — take a new screenshot, " +
                "or send coordinateSpace=screen for real device pixels",
        )
        else -> FrameScales.Ok(frame.scaleX, frame.scaleY)
    }
}

/**
 * The frame of the most recent capture, process-wide: the accessibility dispatch gate
 * serialises UI actions, so there is exactly one image a model could be reading from —
 * the same lifetime Eta gives "the last observe_screen image".
 */
object ScreenshotFrameRegistry {
    @Volatile
    private var frame: ScreenshotFrame? = null

    fun record(frame: ScreenshotFrame) {
        this.frame = frame
    }

    fun latest(): ScreenshotFrame? = frame
}
