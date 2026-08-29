package com.openminis.app.sandbox.ubuntu

import com.openminis.app.sandbox.minisd.MinisdError
import com.openminis.app.sandbox.minisd.MinisdProtocol
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UbuntuRuntimeRecoveryTest {

    @Test
    fun `keeper namespace loss retries once and only once`() {
        val error = MinisdError(
            MinisdProtocol.ERROR_KEEPER_NAMESPACE_LOST,
            "setns failed before execve",
        )

        assertTrue(UbuntuRuntime.shouldRetryAfterPreExecFailure(error, attempt = 0))
        assertFalse(UbuntuRuntime.shouldRetryAfterPreExecFailure(error, attempt = 1))
        assertFalse(UbuntuRuntime.shouldRetryAfterPreExecFailure(error, attempt = 2))
    }

    @Test
    fun `non keeper failures are never blindly retried`() {
        val chroot = MinisdError(MinisdProtocol.ERROR_CHROOT_UNAVAILABLE, "chroot failed")
        val runtime = MinisdError(MinisdProtocol.ERROR_RUNTIME_UNAVAILABLE, "transport lost")
        val privilege = MinisdError(MinisdProtocol.ERROR_PRIVILEGE_SETUP_FAILED, "setuid failed")

        assertFalse(UbuntuRuntime.shouldRetryAfterPreExecFailure(chroot, 0))
        assertFalse(UbuntuRuntime.shouldRetryAfterPreExecFailure(runtime, 0))
        assertFalse(UbuntuRuntime.shouldRetryAfterPreExecFailure(privilege, 0))
    }

    @Test
    fun `stale app uid is rejected`() {
        val snapshot = UbuntuRuntime.Snapshot(
            running = true,
            guestUid = 10421,
        )

        assertFalse(UbuntuRuntime.brokerIdentityMatches(snapshot, expectedUid = 10422))
        assertTrue(UbuntuRuntime.brokerIdentityMatches(snapshot, expectedUid = 10421))
    }

    @Test
    fun `workspace mismatch makes runtime layout invalid`() {
        val snapshot = UbuntuRuntime.Snapshot(
            running = true,
            layoutKnown = true,
            hostWorkspace = "/data/user/0/app/files/minis/workspace",
            hostMemory = "/data/adb/minis/memory",
            hostSkills = "/data/adb/minis/skills",
            hostShared = "/data/adb/minis/shared",
        )

        assertFalse(
            UbuntuRuntime.runtimeLayoutMatches(
                snapshot,
                expectedWorkspace = "/data/adb/minis/workspace",
                expectedMemory = "/data/adb/minis/memory",
                expectedSkills = "/data/adb/minis/skills",
                expectedShared = "/data/adb/minis/shared",
            ),
        )
        assertTrue(
            UbuntuRuntime.layoutMismatchDetail(
                snapshot,
                expectedWorkspace = "/data/adb/minis/workspace",
                expectedMemory = "/data/adb/minis/memory",
                expectedSkills = "/data/adb/minis/skills",
                expectedShared = "/data/adb/minis/shared",
            ).contains("workspace="),
        )
    }

    @Test
    fun `matching uid and canonical layout are accepted`() {
        val snapshot = UbuntuRuntime.Snapshot(
            running = true,
            guestUid = 10422,
            layoutKnown = true,
            hostWorkspace = "/data/adb/minis/workspace",
            hostMemory = "/data/adb/minis/memory",
            hostSkills = "/data/adb/minis/skills",
            hostShared = "/data/adb/minis/shared",
        )

        assertTrue(UbuntuRuntime.brokerIdentityMatches(snapshot, 10422))
        assertTrue(
            UbuntuRuntime.runtimeLayoutMatches(
                snapshot,
                "/data/adb/minis/workspace",
                "/data/adb/minis/memory",
                "/data/adb/minis/skills",
                "/data/adb/minis/shared",
            ),
        )
    }
}
