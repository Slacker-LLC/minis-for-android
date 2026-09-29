package com.openminis.app.tools.android.vscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test
import java.lang.reflect.InvocationTargetException

class VirtualScreenPolicyTest {
    @Test fun physicalDisplayIsAlwaysRefused() {
        assertEquals(VirtualScreenPolicy.PHYSICAL_DISPLAY_REFUSED, VirtualScreenPolicy.displayError(0, 7))
        assertEquals(VirtualScreenPolicy.DISPLAY_GONE, VirtualScreenPolicy.displayError(7, null))
        assertEquals(VirtualScreenPolicy.UNKNOWN_DISPLAY, VirtualScreenPolicy.displayError(8, 7))
        assertEquals(null, VirtualScreenPolicy.displayError(7, 7))
    }

    @Test fun virtualDisplayFlagsFallBackInTheRequiredOrder() {
        assertEquals(
            listOf("TRUSTED|OWN_FOCUS|SUPPORTS_TOUCH", "OWN_FOCUS|SUPPORTS_TOUCH", "SUPPORTS_TOUCH", "BASE"),
            VirtualScreenPolicy.flagCandidates(1, 2, 4, 8).map { it.label },
        )
        assertEquals(listOf(15, 7, 3, 1), VirtualScreenPolicy.flagCandidates(1, 2, 4, 8).map { it.flags })
        assertEquals(1, VirtualScreenPolicy.flagCandidates(1, 0, 0, 0).size)
    }

    @Test fun aProbeFailureDoesNotPreventLaterStepsAndHasAReasonCode() {
        val recorder = VirtualScreenProbeRecorder()
        var laterRan = false
        recorder.check("context", "context_ready") { "constructed" }
        recorder.check("display", "display_created") { throw SecurityException("denied") }
        recorder.check("input", "input_injected") { laterRan = true; "KEYCODE_UNKNOWN" }
        val report = recorder.report(123L, "rom-1")
        assertTrue(laterRan)
        assertEquals("permission_denied", report.steps[1].code)
        assertFalse(report.passed)
        assertTrue(report.toJson().contains("\"id\":\"display\",\"status\":\"fail\",\"code\":\"permission_denied\""))
    }

    @Test fun imeWarningDoesNotFailOtherwiseSuccessfulProbe() {
        val recorder = VirtualScreenProbeRecorder()
        recorder.record("environment", "pass", "supported")
        recorder.warning("ime", "ime_policy_unavailable", "LOCAL policy unsupported")
        assertTrue(recorder.report(1L, "rom").passed)
    }

    @Test fun wrappedPermissionFailureKeepsItsReasonCode() {
        val recorder = VirtualScreenProbeRecorder()
        recorder.check("input", "input_injected") {
            throw InvocationTargetException(SecurityException("shell input denied"))
        }
        assertEquals("permission_denied", recorder.report(1L, "rom").steps.single().code)
    }

    @Test fun oversizedDumpIsBoundedAndMarkedTruncated() {
        val bounded = VirtualScreenPolicy.fitDumpJson("{\"text\":\"${"键🙂\\\"\\n".repeat(1000)}\"}", 128)
        assertTrue(bounded.toByteArray(Charsets.UTF_8).size <= 128)
        assertTrue(JSONObject(bounded).getBoolean("truncated"))
    }

    @Test fun probeCacheInvalidatesOnAndroidOrRomFingerprintChange() {
        assertTrue(VirtualScreenProbeCachePolicy.isCurrentAndPassed(true, "rom|34", "rom|34"))
        assertFalse(VirtualScreenProbeCachePolicy.isCurrentAndPassed(false, "rom|34", "rom|34"))
        assertFalse(VirtualScreenProbeCachePolicy.isCurrentAndPassed(true, "rom|34", "rom|35"))
        assertFalse(VirtualScreenProbeCachePolicy.isCurrentAndPassed(true, null, "rom|34"))
    }
}
