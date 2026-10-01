package com.openminis.app.tools.android.vscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutDiagnosticsTest {
    private fun app(l: Int, t: Int, r: Int, b: Int, pkg: String = "com.example") = WindowGeometry(1, pkg, l, t, r, b)

    @Test fun fullScreenAppWindowRaisesNothing() {
        assertEquals(emptyList<String>(), LayoutDiagnostics.warnings(720, 1600, listOf(app(0, 0, 720, 1600))))
    }

    @Test fun systemWindowsAreNeverJudged() {
        val ime = WindowGeometry(2, "com.keyboard", 0, 1000, 720, 1600)
        assertEquals(emptyList<String>(), LayoutDiagnostics.warnings(720, 1600, listOf(ime)))
    }

    @Test fun aspectRatioCapShowsAsLetterboxingWithTheNumbers() {
        // 720x1600 display, app capped at 16:9 and centred: 720x1280 window.
        val w = LayoutDiagnostics.warnings(720, 1600, listOf(app(0, 160, 720, 1440, "com.capped")))
        assertEquals(1, w.size)
        assertTrue(w[0], w[0].startsWith("letterboxed_vertically"))
        assertTrue(w[0], "com.capped" in w[0] && "720x1280" in w[0] && "ratio 1.78" in w[0] && "ratio 2.22" in w[0])
    }

    @Test fun forcedOtherOrientationShowsAsSwapped() {
        // Portrait display, app laid out landscape (1600x720), centred or not.
        val w = LayoutDiagnostics.warnings(720, 1600, listOf(app(0, 0, 1600, 720)))
        assertTrue(w[0], w[0].startsWith("orientation_swapped"))
        val centred = LayoutDiagnostics.warnings(720, 1600, listOf(app(-440, 440, 1160, 1160)))
        assertTrue(centred[0], centred[0].startsWith("orientation_swapped"))
    }

    @Test fun oversizedWindowIsReported() {
        val w = LayoutDiagnostics.warnings(720, 1600, listOf(app(0, 0, 720, 2400)))
        assertTrue(w[0], w[0].startsWith("window_exceeds_display"))
    }

    @Test fun smallToleranceAbsorbsRoundingButNotRealGaps() {
        assertEquals(emptyList<String>(), LayoutDiagnostics.warnings(720, 1600, listOf(app(0, 0, 719, 1601))))
        assertEquals(1, LayoutDiagnostics.warnings(720, 1600, listOf(app(0, 0, 720, 1590))).size)
    }

    @Test fun unknownDisplaySizeStaysSilent() {
        assertEquals(emptyList<String>(), LayoutDiagnostics.warnings(0, 0, listOf(app(0, 0, 10, 10))))
    }
}
