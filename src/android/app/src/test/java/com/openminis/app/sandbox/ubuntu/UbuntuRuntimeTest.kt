package com.openminis.app.sandbox.ubuntu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UbuntuRuntimeTest {
    @Test
    fun `broker identity requires both app uid and mount namespace`() {
        val expected = UbuntuRuntime.Snapshot(
            guestUid = 10394,
            brokerMountNamespace = "mnt:[4026533001]",
        )
        assertTrue(
            UbuntuRuntime.brokerIdentityMatches(
                expected,
                expectedUid = 10394,
                expectedMountNamespace = "mnt:[4026533001]",
            ),
        )
        assertFalse(
            UbuntuRuntime.brokerIdentityMatches(
                expected.copy(guestUid = 10395),
                expectedUid = 10394,
                expectedMountNamespace = "mnt:[4026533001]",
            ),
        )
        assertFalse(
            UbuntuRuntime.brokerIdentityMatches(
                expected.copy(brokerMountNamespace = "mnt:[4026533002]"),
                expectedUid = 10394,
                expectedMountNamespace = "mnt:[4026533001]",
            ),
        )
        assertFalse(
            UbuntuRuntime.brokerIdentityMatches(
                expected.copy(brokerMountNamespace = null),
                expectedUid = 10394,
                expectedMountNamespace = "mnt:[4026533001]",
            ),
        )
    }
}
