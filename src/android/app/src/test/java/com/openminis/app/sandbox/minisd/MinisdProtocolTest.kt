package com.openminis.app.sandbox.minisd

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MinisdProtocolTest {
    @Test
    fun `encode ping matches minisd v1 shape`() {
        val raw = MinisdProtocol.encodeRequest(MinisdProtocol.ping(9))
        val obj = JSONObject(raw)
        assertEquals(1, obj.getInt("v"))
        assertEquals(9, obj.getLong("id"))
        assertEquals("system.ping", obj.getString("method"))
        assertEquals("app", obj.getJSONObject("client").getString("id"))
    }

    @Test
    fun `decode ok and error frames`() {
        val ok = MinisdProtocol.decodeResponse(
            """{"v":1,"id":1,"ok":true,"result":{"running":true,"pid":42}}""",
        )
        assertTrue(ok.ok)
        assertEquals(42, ok.result!!.getInt("pid"))
        assertNull(ok.error)

        val err = MinisdProtocol.decodeResponse(
            """{"v":1,"id":2,"ok":false,"error":{"code":"RUNTIME_UNAVAILABLE","detail":"rootfs missing"}}""",
        )
        assertFalse(err.ok)
        assertEquals("RUNTIME_UNAVAILABLE", err.code)
        assertEquals("rootfs missing", err.error!!.detail)
    }

    @Test
    fun `helper setns failure is promoted to keeper namespace error`() {
        val raw = MinisdResponse(
            v = 1,
            id = 4,
            ok = true,
            result = JSONObject().put("exit_code", 4).put("stderr", "setns failed"),
            error = null,
        )

        val promoted = MinisdProtocol.promoteExecInfrastructureFailure(raw)

        assertFalse(promoted.ok)
        assertNull(promoted.result)
        assertEquals(MinisdProtocol.ERROR_KEEPER_NAMESPACE_LOST, promoted.code)
        assertTrue(MinisdProtocol.isRetrySafeKeeperFailure(raw))
    }

    @Test
    fun `helper chroot and privilege failures are structured but not keeper retries`() {
        val chroot = MinisdProtocol.promoteExecInfrastructureFailure(
            MinisdResponse(1, 5, true, JSONObject().put("exit_code", 5), null),
        )
        val privilege = MinisdProtocol.promoteExecInfrastructureFailure(
            MinisdResponse(1, 6, true, JSONObject().put("exit_code", 6), null),
        )

        assertEquals(MinisdProtocol.ERROR_CHROOT_UNAVAILABLE, chroot.code)
        assertEquals(MinisdProtocol.ERROR_PRIVILEGE_SETUP_FAILED, privilege.code)
        assertFalse(MinisdProtocol.isRetrySafeKeeperFailure(chroot))
        assertFalse(MinisdProtocol.isRetrySafeKeeperFailure(privilege))
    }

    @Test
    fun `ordinary command exit code remains a command result`() {
        val raw = MinisdResponse(
            1,
            9,
            true,
            JSONObject().put("exit_code", 23).put("stderr", "user command failed"),
            null,
        )

        val promoted = MinisdProtocol.promoteExecInfrastructureFailure(raw)

        assertTrue(promoted.ok)
        assertEquals(23, promoted.result!!.getInt("exit_code"))
        assertNull(promoted.error)
    }

    @Test
    fun `explicit future pre exec error is preserved structurally`() {
        val raw = MinisdResponse(
            1,
            10,
            true,
            JSONObject()
                .put("exit_code", 1)
                .put("pre_exec_error", MinisdProtocol.ERROR_ROOTFS_INVALID)
                .put("pre_exec_detail", "metadata mismatch"),
            null,
        )

        val promoted = MinisdProtocol.promoteExecInfrastructureFailure(raw)

        assertFalse(promoted.ok)
        assertEquals(MinisdProtocol.ERROR_ROOTFS_INVALID, promoted.code)
        assertEquals("metadata mismatch", promoted.error!!.detail)
    }

    @Test
    fun `ubuntu exec argv is structured not a raw cmd string`() {
        val raw = MinisdProtocol.encodeRequest(
            MinisdProtocol.ubuntuExec(
                listOf("/usr/bin/id"),
                cwd = "/workspace",
                sessionId = "session-a",
            ),
        )
        val obj = JSONObject(raw)
        assertEquals("ubuntu.exec", obj.getString("method"))
        val argv = obj.getJSONObject("params").getJSONArray("argv")
        assertEquals("/usr/bin/id", argv.getString(0))
        assertFalse(obj.getJSONObject("params").has("cmd"))
        assertEquals("session-a", obj.getJSONObject("params").getString("session_id"))
    }

    @Test
    fun `provision method has no raw command`() {
        val raw = MinisdProtocol.encodeRequest(MinisdProtocol.ubuntuProvision(3))
        val obj = JSONObject(raw)
        assertEquals("ubuntu.provision", obj.getString("method"))
        assertFalse(obj.getJSONObject("params").has("cmd"))
    }

    @Test
    fun `root exec is structured and has no shell command`() {
        val raw = MinisdProtocol.encodeRequest(
            MinisdProtocol.rootExec("getprop", listOf("ro.build.version.sdk"), 12_000, id = 8),
        )
        val obj = JSONObject(raw)
        assertEquals("root.exec", obj.getString("method"))
        val params = obj.getJSONObject("params")
        assertEquals("getprop", params.getString("tool"))
        assertEquals("ro.build.version.sdk", params.getJSONArray("args").getString(0))
        assertFalse(params.has("command"))
    }

    @Test
    fun `admin exec carries confirm_id`() {
        val raw = MinisdProtocol.encodeRequest(
            MinisdProtocol.ubuntuAdminExec(listOf("/usr/bin/apt-get", "update"), confirmId = "c-1"),
        )
        val obj = JSONObject(raw)
        assertEquals("c-1", obj.getString("confirm_id"))
        assertEquals("ubuntu.adminExec", obj.getString("method"))
    }

    @Test
    fun `ubuntu start carries rootfs and optional mounts`() {
        val raw = MinisdProtocol.encodeRequest(
            MinisdProtocol.ubuntuStart(
                id = 7,
                rootfs = "/data/adb/minis/rootfs",
                workspace = "/data/adb/minis/workspace",
                memory = "/data/adb/minis/memory",
                skills = "/data/adb/minis/skills",
                shared = "/data/adb/minis/shared",
                sessionsRoot = "/data/user/0/app/files/minis-sessions",
            ),
        )
        val obj = JSONObject(raw)
        assertEquals(7, obj.getLong("id"))
        assertEquals("ubuntu.start", obj.getString("method"))
        val params = obj.getJSONObject("params")
        assertEquals("/data/adb/minis/rootfs", params.getString("rootfs"))
        assertEquals("/data/adb/minis/workspace", params.getString("workspace"))
        assertEquals("/data/adb/minis/memory", params.getString("memory"))
        assertEquals("/data/adb/minis/skills", params.getString("skills"))
        assertEquals("/data/adb/minis/shared", params.getString("shared"))
        assertEquals(
            "/data/user/0/app/files/minis-sessions",
            params.getString("sessions_root"),
        )
    }

    @Test
    fun `ubuntu stop uses current supervisor-free contract`() {
        val stop = JSONObject(MinisdProtocol.encodeRequest(MinisdProtocol.ubuntuStop(5)))
        assertEquals("ubuntu.stop", stop.getString("method"))
        assertEquals(5, stop.getLong("id"))
        assertFalse(stop.getJSONObject("params").has("cmd"))
    }

    @Test
    fun `encodeRequest declares capabilities for the called method`() {
        val raw = MinisdProtocol.encodeRequest(MinisdProtocol.ubuntuStatus(9))
        val caps = JSONObject(raw).getJSONObject("client").getJSONArray("capabilities")
        assertEquals(1, caps.length())
        assertEquals("ubuntu.status", caps.getString(0))
    }
}
