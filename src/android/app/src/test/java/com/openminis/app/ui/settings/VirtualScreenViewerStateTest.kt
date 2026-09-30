package com.openminis.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class VirtualScreenViewerStateTest {
    @Test fun offMeansDisabledWhateverTheDisplayId() {
        assertEquals(ViewerState.DISABLED, viewerStateFor(enabled = false, activeDisplayId = null))
        assertEquals(ViewerState.DISABLED, viewerStateFor(enabled = false, activeDisplayId = 5))
    }

    @Test fun onWithoutADisplayIsIdle() {
        assertEquals(ViewerState.IDLE, viewerStateFor(enabled = true, activeDisplayId = null))
    }

    @Test fun thePhysicalDisplayIsNeverShown() {
        assertEquals(ViewerState.IDLE, viewerStateFor(enabled = true, activeDisplayId = 0))
        assertEquals(ViewerState.IDLE, viewerStateFor(enabled = true, activeDisplayId = -1))
    }

    @Test fun aVirtualDisplayIdIsLive() {
        assertEquals(ViewerState.LIVE, viewerStateFor(enabled = true, activeDisplayId = 8))
    }
}
