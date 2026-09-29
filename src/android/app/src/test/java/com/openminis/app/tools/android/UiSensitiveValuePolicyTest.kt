package com.openminis.app.tools.android

import org.junit.Assert.assertEquals
import org.junit.Test

class UiSensitiveValuePolicyTest {
    @Test
    fun `password node values are always redacted but ordinary labels remain`() {
        assertEquals("", UiSensitiveValuePolicy.redactAccessibilityValue(true, "sensitive-value"))
        assertEquals("Email address", UiSensitiveValuePolicy.redactAccessibilityValue(false, "Email address"))
    }
}
