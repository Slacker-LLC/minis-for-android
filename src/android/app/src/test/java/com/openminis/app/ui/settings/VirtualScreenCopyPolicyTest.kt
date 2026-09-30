package com.openminis.app.ui.settings

import com.openminis.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class VirtualScreenCopyPolicyTest {
    @Test fun everyProbeReasonCodeHasActionableCopy() {
        for (code in VirtualScreenCopyPolicy.requiredProbeReasonCodes()) {
            assertNotEquals("missing copy for $code", R.string.vscreen_reason_unknown, VirtualScreenCopyPolicy.reason(code))
        }
        assertEquals(R.string.vscreen_reason_unknown, VirtualScreenCopyPolicy.reason("future_unmapped_code"))
    }

    @Test fun everyProbeStageAndStatusHasAReadableLabel() {
        assertEquals(10, VirtualScreenCopyPolicy.knownStepIds().size)
        for (id in VirtualScreenCopyPolicy.knownStepIds()) {
            assertNotEquals("missing step label for $id", R.string.vscreen_step_unknown, VirtualScreenCopyPolicy.step(id))
        }
        for (status in listOf("pass", "warning", "fail", "skipped")) {
            assertNotEquals(R.string.vscreen_status_unknown, VirtualScreenCopyPolicy.status(status))
        }
        assertEquals(R.string.vscreen_status_unknown, VirtualScreenCopyPolicy.status("future"))
    }
}
