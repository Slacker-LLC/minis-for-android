package com.openminis.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualScreenGeometryTest {
    @Test fun aTallDisplayInAWideViewIsCentredAndScaledByHeight() {
        val fit = VirtualScreenGeometry.fit(viewWidth = 1000, viewHeight = 800, displayWidth = 720, displayHeight = 1600)
        assertEquals(0.5f, fit.scale, 0.0001f)
        assertEquals(320f, fit.left, 0.001f)
        assertEquals(0f, fit.top, 0.001f)
    }

    @Test fun aWideDisplayInATallViewIsScaledByWidth() {
        val fit = VirtualScreenGeometry.fit(viewWidth = 400, viewHeight = 1000, displayWidth = 1600, displayHeight = 720)
        assertEquals(0.25f, fit.scale, 0.0001f)
        assertEquals(0f, fit.left, 0.001f)
        assertEquals(410f, fit.top, 0.001f)
    }

    @Test fun anEmptyViewOrDisplayHasNothingToTouch() {
        assertEquals(0f, VirtualScreenGeometry.fit(0, 800, 720, 1600).scale, 0f)
        assertEquals(0f, VirtualScreenGeometry.fit(800, 800, 0, 1600).scale, 0f)
    }

    @Test fun aTouchMapsBackToDisplayPixels() {
        val fit = VirtualScreenGeometry.fit(1000, 800, 720, 1600)
        // The picture spans x 320..680 at scale 0.5: its centre is display (360, 800).
        assertEquals(360 to 800, VirtualScreenGeometry.toDisplay(500f, 400f, fit, 720, 1600))
    }

    @Test fun aDragThatLeavesThePictureIsClampedOntoTheDisplay() {
        val fit = VirtualScreenGeometry.fit(1000, 800, 720, 1600)
        assertEquals(0 to 1599, VirtualScreenGeometry.toDisplay(-50f, 5000f, fit, 720, 1600))
    }

    @Test fun aTouchThatStartsInTheLetterboxIsIgnored() {
        val fit = VirtualScreenGeometry.fit(1000, 800, 720, 1600)
        assertFalse(VirtualScreenGeometry.inside(100f, 400f, fit, 720, 1600))
        assertTrue(VirtualScreenGeometry.inside(500f, 400f, fit, 720, 1600))
    }
}
