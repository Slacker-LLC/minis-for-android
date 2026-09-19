package com.openminis.app.xposed

import com.openminis.app.BuildConfig
import com.openminis.app.xposed.aimemory.ColorOsMemoryBridgeProtocol
import com.openminis.app.xposed.system.AccessibilityProtectionProtocol
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-eta-xposed-groups] The module's self-references must name the install that ships them.
 *
 * Every one of these strings is an address: the package the power-key takeover opens, the
 * permission a control request is signed with, the authority the health check asks. On the Xiaomi
 * 24129PN74C the app had been renamed to `llc.slacker.eta` while the Xposed layer still said
 * `llc.slacker.minis`, so the takeover opened another install and the assistant-role check
 * compared a package that could never hold the role. BuildConfig carries the Gradle
 * applicationId, which is why pinning these to it is the guard that would have caught that.
 */
class XposedIdentityTest {

    @Test
    fun `self-references follow the application id`() {
        assertEquals(BuildConfig.APPLICATION_ID, ModuleTargets.OWN_PACKAGE)
        assertEquals(
            "${BuildConfig.APPLICATION_ID}.permission.CONTROL_ACCESSIBILITY_PROTECTION",
            AccessibilityProtectionProtocol.PERMISSION,
        )
        assertEquals(
            "${BuildConfig.APPLICATION_ID}.action.SET_ACCESSIBILITY_PROTECTION",
            AccessibilityProtectionProtocol.ACTION_SET,
        )
        assertEquals(
            "${BuildConfig.APPLICATION_ID}.action.RECOVER_ACCESSIBILITY_SERVICE",
            AccessibilityProtectionProtocol.ACTION_RECOVER,
        )
        assertEquals(
            "${BuildConfig.APPLICATION_ID}.accessibility.health",
            AccessibilityProtectionProtocol.HEALTH_AUTHORITY,
        )
        assertEquals(
            true,
            ColorOsMemoryBridgeProtocol.METHOD.startsWith(BuildConfig.APPLICATION_ID),
        )
    }
}
