package com.openminis.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-eta-overlay-run-panel] Ported from Eta's assistant overlay panel (Mangi-11/Eta @
 * c15de97). The rule under test is the one a user feels: the stop control exists while the
 * run can still be cancelled, and a finished turn that lingers on the capsule does not look
 * cancellable.
 */
class OverlayRunActionsTest {

    @Test
    fun `a running tool can be stopped`() {
        assertTrue(OverlayRunActions.offersStop(toolRunning = true, streamActive = false))
    }

    @Test
    fun `an open stream can be stopped before any tool runs`() {
        assertTrue(OverlayRunActions.offersStop(toolRunning = false, streamActive = true))
    }

    @Test
    fun `an idle capsule offers nothing to stop`() {
        assertFalse(OverlayRunActions.offersStop(toolRunning = false, streamActive = false))
    }

    @Test
    fun `both signals together still mean one stop`() {
        assertTrue(OverlayRunActions.offersStop(toolRunning = true, streamActive = true))
    }
}
