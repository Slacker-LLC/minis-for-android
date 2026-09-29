package com.openminis.app.tools.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnattendedScreenAccessPolicyTest {
    @Test fun unattendedPhysicalScreenIsDeniedByDefault() {
        assertEquals(
            UnattendedScreenAccessPolicy.ERROR_UNATTENDED_PHYSICAL_SCREEN_DENIED,
            UnattendedScreenAccessPolicy.denialCode(displayId = 0, unattended = true, allowUnattendedPhysical = false),
        )
    }

    @Test fun attendedPhysicalAndVirtualDisplaysAreNotBlockedByPhysicalException() {
        assertNull(UnattendedScreenAccessPolicy.denialCode(0, unattended = false, allowUnattendedPhysical = false))
        assertNull(UnattendedScreenAccessPolicy.denialCode(8, unattended = true, allowUnattendedPhysical = false))
    }

    @Test fun explicitPhysicalScreenExceptionAllowsUnattendedPhysicalAccess() {
        assertNull(UnattendedScreenAccessPolicy.denialCode(0, unattended = true, allowUnattendedPhysical = true))
    }
}
