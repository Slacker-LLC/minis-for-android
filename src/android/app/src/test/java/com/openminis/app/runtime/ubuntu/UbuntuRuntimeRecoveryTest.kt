package com.openminis.app.runtime.ubuntu

import com.openminis.app.runtime.minisd.MinisdError
import com.openminis.app.runtime.minisd.MinisdProtocol
import com.openminis.app.runtime.minisd.MinisdResponse
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `shell wrapper emits execution marker before user command`() {
        val marker = UbuntuRuntime.shellStartMarker(0x1234)
        val wrapped = UbuntuRuntime.wrapShellCommand("touch /tmp/side-effect", marker)

        assertEquals("__MINIS_EXEC_STARTED_1234__", marker)
        assertTrue(wrapped.startsWith("printf '%s\\n' '$marker' >&2\n"))
        assertTrue(wrapped.endsWith("touch /tmp/side-effect"))
    }

    @Test
    fun `execution marker proves command wrapper started`() {
        val marker = UbuntuRuntime.shellStartMarker(99)
        val response = MinisdResponse(
            1,
            1,
            true,
            JSONObject()
                .put("exit_code", 4)
                .put("stderr", "profile warning\n$marker\nuser output\n"),
            null,
        )

        assertTrue(UbuntuRuntime.didUserCommandStart(response, marker))
        assertEquals(
            "profile warning\nuser output\n",
            UbuntuRuntime.stripShellStartMarker(response.result!!.getString("stderr"), marker),
        )
        val classified = MinisdProtocol.promoteExecInfrastructureFailure(
            response,
            userCommandStarted = UbuntuRuntime.didUserCommandStart(response, marker),
        )
        assertTrue(classified.ok)
        assertEquals(4, classified.result!!.getInt("exit_code"))
    }

    @Test
    fun `missing execution marker permits keeper pre exec classification`() {
        val marker = UbuntuRuntime.shellStartMarker(100)
        val response = MinisdResponse(
            1,
            2,
            true,
            JSONObject()
                .put("exit_code", 4)
                .put("stderr", "open /proc/8123/ns/mnt: No such file or directory"),
            null,
        )

        assertFalse(UbuntuRuntime.didUserCommandStart(response, marker))
        val classified = MinisdProtocol.promoteExecInfrastructureFailure(
            response,
            userCommandStarted = UbuntuRuntime.didUserCommandStart(response, marker),
        )
        assertFalse(classified.ok)
        assertEquals(MinisdProtocol.ERROR_KEEPER_NAMESPACE_LOST, classified.code)
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
    fun `broker hash parser ignores root diagnostics`() {
        assertEquals(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            UbuntuRuntime.parseSha256sum(
                "KernelSU: granted\n0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef  /data/adb/minis/bin/minisd\n",
            ),
        )
        assertNull(UbuntuRuntime.parseSha256sum("permission denied"))
    }

    @Test
    fun `broker binary mismatch is detected`() {
        val expected = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val other = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"

        assertTrue(UbuntuRuntime.brokerBinaryMatches(expected, "$expected  /data/adb/minis/bin/minisd"))
        assertFalse(UbuntuRuntime.brokerBinaryMatches(expected, "$other  /data/adb/minis/bin/minisd"))
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

    @Test
    fun `failed status response preserves last known snapshot and marks it stale`() {
        val previous = UbuntuRuntime.Snapshot(
            running = true,
            available = true,
            pid = 8123,
            version = "24.04",
            provisioned = true,
            guestUid = 10422,
            sessionsRoot = "/data/adb/minis/sessions",
            layoutKnown = true,
            hostWorkspace = "/data/adb/minis/workspace",
            statusFresh = true,
        )
        val response = MinisdResponse(
            v = 1,
            id = 3,
            ok = false,
            result = null,
            error = MinisdError("TRANSPORT_TIMEOUT", "status request timed out"),
        )

        val next = UbuntuRuntime.mergeSnapshot(previous, response)

        assertEquals(previous.running, next.running)
        assertEquals(previous.pid, next.pid)
        assertEquals(previous.version, next.version)
        assertEquals(previous.hostWorkspace, next.hostWorkspace)
        assertFalse(next.statusFresh)
        assertEquals("TRANSPORT_TIMEOUT: status request timed out", next.lastError)
    }
}
