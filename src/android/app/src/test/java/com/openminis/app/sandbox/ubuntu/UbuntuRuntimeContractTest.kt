package com.openminis.app.sandbox.ubuntu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UbuntuRuntimeContractTest {
    @Test
    fun `status accepts only fixed rootfs and sessions root`() {
        assertTrue(
            UbuntuRuntime.runtimeLayoutMatches(
                UbuntuRuntime.Snapshot(
                    running = true,
                    rootfs = UbuntuPaths.HOST_ROOTFS,
                    sessionsRoot = UbuntuPaths.HOST_SESSIONS,
                ),
            ),
        )
        assertFalse(
            UbuntuRuntime.runtimeLayoutMatches(
                UbuntuRuntime.Snapshot(
                    running = true,
                    rootfs = UbuntuPaths.HOST_ROOTFS,
                    sessionsRoot = "/data/user/0/dev.openminispet.android/files/minis-sessions",
                ),
            ),
        )
        assertFalse(
            UbuntuRuntime.runtimeLayoutMatches(
                UbuntuRuntime.Snapshot(
                    running = true,
                    rootfs = "/data/user/0/dev.openminispet.android/files/rootfs",
                    sessionsRoot = UbuntuPaths.HOST_SESSIONS,
                ),
            ),
        )
    }

    @Test
    fun `workspace and home remain separate contracts`() {
        assertTrue(UbuntuPaths.HOST_WORKSPACE != UbuntuPaths.HOST_HOME)
        assertTrue(UbuntuPaths.GUEST_WORKSPACE != UbuntuPaths.GUEST_HOME)
    }
}
