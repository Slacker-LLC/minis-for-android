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

class VirtualScreenFailActionTest {
    @Test fun rootProblemsPointToTheRootPage() {
        for (code in listOf("root_not_ready", "permission_denied", "unexpected_user_service_uid")) {
            assertEquals(code, VirtualScreenCopyPolicy.FailAction.ROOT, VirtualScreenCopyPolicy.actionFor(code, xiaomi = false))
        }
    }

    @Test fun retiredShizukuCodesNoLongerSendAnywhere() {
        // Reports saved before the switch to Root still carry these; they must not offer a dead destination.
        for (code in listOf("shizuku_not_ready", "shizuku_unavailable", "shizuku_shell_user_service")) {
            assertEquals(code, VirtualScreenCopyPolicy.FailAction.NONE, VirtualScreenCopyPolicy.actionFor(code, xiaomi = false))
        }
    }

    @Test fun developerOptionsHintOnlyOnXiaomi() {
        assertEquals(
            VirtualScreenCopyPolicy.FailAction.DEVELOPER_OPTIONS,
            VirtualScreenCopyPolicy.actionFor("input_injection_failed", xiaomi = true),
        )
        assertEquals(
            VirtualScreenCopyPolicy.FailAction.NONE,
            VirtualScreenCopyPolicy.actionFor("input_injection_failed", xiaomi = false),
        )
    }

    @Test fun unknownAndTimeoutCodesOfferNoAction() {
        assertEquals(VirtualScreenCopyPolicy.FailAction.NONE, VirtualScreenCopyPolicy.actionFor("vscreen_timeout", xiaomi = true))
        assertEquals(VirtualScreenCopyPolicy.FailAction.NONE, VirtualScreenCopyPolicy.actionFor("never_seen", xiaomi = true))
    }
}
