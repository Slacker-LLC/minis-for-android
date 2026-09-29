package com.openminis.app.tools.android

import com.openminis.app.tools.android.vscreen.VirtualScreenPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class VirtualScreenAppPresencePolicyTest {
    @Test fun movedWindowReturnsTheRequiredCode() {
        assertEquals(VirtualScreenPolicy.APP_LEFT_DISPLAY, VirtualScreenAppPresencePolicy.denialCode("com.example.browser", false))
    }

    @Test fun visibleWindowAndUnknownTargetDoNotReportAnAppMove() {
        assertEquals(null, VirtualScreenAppPresencePolicy.denialCode("com.example.browser", true))
        assertEquals(null, VirtualScreenAppPresencePolicy.denialCode(null, false))
    }
}
