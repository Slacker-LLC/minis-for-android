package com.openminis.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayPositionTest {
    @Test
    fun `a position already on screen is unchanged`() {
        assertEquals(100 to 200, clampOverlayPosition(100, 200, 300, 120, 1080, 2400))
    }

    @Test
    fun `a position saved on a wider screen is pulled back inside the narrow one`() {
        // Saved at x=1900 on a 2400-wide landscape screen; the portrait screen is 1080 wide.
        assertEquals(780 to 400, clampOverlayPosition(1900, 400, 300, 120, 1080, 2400))
    }

    @Test
    fun `a position below the new screen height is pulled up`() {
        assertEquals(50 to 1080 - 120, clampOverlayPosition(50, 2000, 300, 120, 2400, 1080))
    }

    @Test
    fun `a window larger than the screen sits at the origin`() {
        assertEquals(0 to 0, clampOverlayPosition(500, 500, 3000, 3000, 1080, 2400))
    }

    @Test
    fun `negative positions are lifted to zero`() {
        assertEquals(0 to 0, clampOverlayPosition(-5, -9, 300, 120, 1080, 2400))
    }
}
