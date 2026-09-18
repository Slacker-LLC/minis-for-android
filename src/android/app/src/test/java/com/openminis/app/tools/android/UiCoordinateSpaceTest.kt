package com.openminis.app.tools.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-eta-ui-coordinate-space] Ported from Eta `agent/model/AgentToolSchema.kt`
 * (Mangi-11/Eta @ c15de97). Every refusal path is covered: a coordinate that cannot
 * be placed in the frame the model was looking at must fail closed instead of
 * being dispatched as a device pixel.
 */
class UiCoordinateSpaceTest {

    private val halfScale = ScreenshotFrame(
        width = 540,
        height = 1200,
        originalWidth = 1080,
        originalHeight = 2400,
    )

    @Test
    fun `default space is the screenshot the model was shown`() {
        assertEquals(UiCoordinateSpace.SCREENSHOT, UiCoordinateSpace.DEFAULT)
        assertEquals(UiCoordinateSpace.SCREENSHOT, UiCoordinateSpace.parse(null))
        assertEquals(UiCoordinateSpace.SCREENSHOT, UiCoordinateSpace.parse(""))
        assertEquals(UiCoordinateSpace.SCREENSHOT, UiCoordinateSpace.parse("nonsense"))
    }

    @Test
    fun `space parsing is case insensitive and lists the wire values`() {
        assertEquals(UiCoordinateSpace.SCREEN, UiCoordinateSpace.parse("screen"))
        assertEquals(UiCoordinateSpace.SCREEN, UiCoordinateSpace.parse(" Screen "))
        assertEquals(UiCoordinateSpace.SCREENSHOT, UiCoordinateSpace.parse("SCREENSHOT"))
        assertEquals(listOf("screenshot", "screen"), UiCoordinateSpace.wireValues)
    }

    @Test
    fun `a point read off a half scale capture is converted to device pixels`() {
        val resolved = UiCoordinateSpacePolicy.resolvePoint(
            x = 100.0,
            y = 250.0,
            space = UiCoordinateSpace.SCREENSHOT,
            frame = halfScale,
            screenWidth = 1080,
            screenHeight = 2400,
        ) as UiCoordinateResolution.Resolved

        assertEquals(200.0, resolved.x, 0.001)
        assertEquals(500.0, resolved.y, 0.001)
        assertEquals(UiCoordinateSpace.SCREENSHOT, resolved.space)
    }

    @Test
    fun `an unscaled capture is the identity conversion`() {
        val full = ScreenshotFrame(1080, 2400, 1080, 2400)

        val resolved = UiCoordinateSpacePolicy.resolvePoint(
            321.0,
            654.0,
            UiCoordinateSpace.SCREENSHOT,
            full,
            1080,
            2400,
        ) as UiCoordinateResolution.Resolved

        assertEquals(321.0, resolved.x, 0.001)
        assertEquals(654.0, resolved.y, 0.001)
    }

    @Test
    fun `axes scale independently when the capture is not uniformly scaled`() {
        val frame = ScreenshotFrame(width = 500, height = 1000, originalWidth = 1080, originalHeight = 2400)

        val resolved = UiCoordinateSpacePolicy.resolvePoint(
            100.0,
            100.0,
            UiCoordinateSpace.SCREENSHOT,
            frame,
            1080,
            2400,
        ) as UiCoordinateResolution.Resolved

        assertEquals(216.0, resolved.x, 0.001)
        assertEquals(240.0, resolved.y, 0.001)
    }

    @Test
    fun `screen space is dispatched verbatim even when a capture exists`() {
        val resolved = UiCoordinateSpacePolicy.resolvePoint(
            700.0,
            900.0,
            UiCoordinateSpace.SCREEN,
            halfScale,
            1080,
            2400,
        ) as UiCoordinateResolution.Resolved

        assertEquals(700.0, resolved.x, 0.001)
        assertEquals(900.0, resolved.y, 0.001)
    }

    @Test
    fun `screenshot space without a capture is refused`() {
        val refused = UiCoordinateSpacePolicy.resolvePoint(
            10.0,
            10.0,
            UiCoordinateSpace.SCREENSHOT,
            null,
            1080,
            2400,
        ) as UiCoordinateResolution.Refused

        assertEquals(UiCoordinateSpacePolicy.ERROR_UNAVAILABLE, refused.code)
        assertTrue(refused.message.contains("coordinateSpace=screen"))
    }

    @Test
    fun `a capture from before a screen size change is refused`() {
        val refused = UiCoordinateSpacePolicy.resolvePoint(
            10.0,
            10.0,
            UiCoordinateSpace.SCREENSHOT,
            halfScale,
            2400,
            1080,
        ) as UiCoordinateResolution.Refused

        assertEquals(UiCoordinateSpacePolicy.ERROR_FRAME_STALE, refused.code)
    }

    @Test
    fun `a point outside the capture is refused instead of guessed`() {
        val refused = UiCoordinateSpacePolicy.resolvePoint(
            x = 600.0,
            y = 100.0,
            space = UiCoordinateSpace.SCREENSHOT,
            frame = halfScale,
            screenWidth = 1080,
            screenHeight = 2400,
        ) as UiCoordinateResolution.Refused

        assertEquals(UiCoordinateSpacePolicy.ERROR_OUT_OF_FRAME, refused.code)
        assertTrue(refused.message.contains("540x1200"))
    }

    @Test
    fun `a point exactly on the capture edge is allowed`() {
        val resolved = UiCoordinateSpacePolicy.resolvePoint(
            x = 540.0,
            y = 1200.0,
            space = UiCoordinateSpace.SCREENSHOT,
            frame = halfScale,
            screenWidth = 1080,
            screenHeight = 2400,
        ) as UiCoordinateResolution.Resolved

        assertEquals(1080.0, resolved.x, 0.001)
        assertEquals(2400.0, resolved.y, 0.001)
    }

    @Test
    fun `deltas are scaled with the same rule`() {
        val screenshotSpace = UiCoordinateSpacePolicy.resolveDelta(
            deltaX = -100.0,
            deltaY = 200.0,
            space = UiCoordinateSpace.SCREENSHOT,
            frame = halfScale,
            screenWidth = 1080,
            screenHeight = 2400,
        ) as UiCoordinateResolution.Resolved
        assertEquals(-200.0, screenshotSpace.x, 0.001)
        assertEquals(400.0, screenshotSpace.y, 0.001)

        val screenSpace = UiCoordinateSpacePolicy.resolveDelta(
            deltaX = -100.0,
            deltaY = 200.0,
            space = UiCoordinateSpace.SCREEN,
            frame = halfScale,
            screenWidth = 1080,
            screenHeight = 2400,
        ) as UiCoordinateResolution.Resolved
        assertEquals(-100.0, screenSpace.x, 0.001)
        assertEquals(200.0, screenSpace.y, 0.001)
    }

    @Test
    fun `delta in screenshot space without a capture is refused`() {
        val refused = UiCoordinateSpacePolicy.resolveDelta(
            10.0,
            10.0,
            UiCoordinateSpace.SCREENSHOT,
            null,
            1080,
            2400,
        ) as UiCoordinateResolution.Refused

        assertEquals(UiCoordinateSpacePolicy.ERROR_UNAVAILABLE, refused.code)
    }

    @Test
    fun `a degenerate frame cannot divide by zero`() {
        val frame = ScreenshotFrame(width = 0, height = 0, originalWidth = 1080, originalHeight = 2400)

        assertEquals(1.0, frame.scaleX, 0.0)
        assertEquals(1.0, frame.scaleY, 0.0)
    }

    @Test
    fun `the registry keeps the most recent capture`() {
        assertNull(ScreenshotFrameRegistry.latest())

        ScreenshotFrameRegistry.record(halfScale)
        assertEquals(halfScale, ScreenshotFrameRegistry.latest())

        val full = ScreenshotFrame(1080, 2400, 1080, 2400)
        ScreenshotFrameRegistry.record(full)
        assertEquals(full, ScreenshotFrameRegistry.latest())
    }
}
