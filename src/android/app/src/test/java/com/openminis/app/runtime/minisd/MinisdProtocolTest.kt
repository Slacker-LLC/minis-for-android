package com.openminis.app.runtime.minisd

import org.json.JSONArray
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
    fun `proven pre exec setns failure becomes keeper namespace error`() {
        val raw = MinisdResponse(
            v = 1,
            id = 4,
            ok = true,
            result = JSONObject()
                .put("exit_code", 4)
                .put("stderr", "open /proc/8123/ns/mnt: No such file or directory"),
            error = null,
        )

        val promoted = MinisdProtocol.promoteExecInfrastructureFailure(
            raw,
            userCommandStarted = false,
        )

        assertFalse(promoted.ok)
        assertNull(promoted.result)
        assertEquals(MinisdProtocol.ERROR_KEEPER_NAMESPACE_LOST, promoted.code)
        assertTrue(MinisdProtocol.isRetrySafeKeeperFailure(raw, userCommandStarted = false))
    }

    @Test
    fun `other proven namespace failure is layout error and not keeper retry`() {
        val raw = MinisdResponse(
            1,
            5,
            true,
            JSONObject()
                .put("exit_code", 4)
                .put("stderr", "bind /data/adb/minis/sessions/a -> /workspace: Invalid argument"),
            null,
        )

        val promoted = MinisdProtocol.promoteExecInfrastructureFailure(
            raw,
            userCommandStarted = false,
        )

        assertFalse(promoted.ok)
        assertEquals(MinisdProtocol.ERROR_RUNTIME_LAYOUT_MISMATCH, promoted.code)
        assertFalse(MinisdProtocol.isRetrySafeKeeperFailure(raw, userCommandStarted = false))
    }

    @Test
    fun `ambiguous or started exit four remains user command result`() {
        val raw = MinisdResponse(
            1,
            6,
            true,
            JSONObject().put("exit_code", 4).put("stderr", "user chose exit 4"),
            null,
        )

        val ambiguous = MinisdProtocol.promoteExecInfrastructureFailure(raw)
        val started = MinisdProtocol.promoteExecInfrastructureFailure(
            raw,
            userCommandStarted = true,
        )

        assertTrue(ambiguous.ok)
        assertEquals(4, ambiguous.result!!.getInt("exit_code"))
        assertTrue(started.ok)
        assertEquals(4, started.result!!.getInt("exit_code"))
        assertFalse(MinisdProtocol.isRetrySafeKeeperFailure(raw))
        assertFalse(MinisdProtocol.isRetrySafeKeeperFailure(raw, userCommandStarted = true))
    }

    @Test
    fun `proven pre exec chroot privilege and execve failures are structured`() {
        val chroot = MinisdProtocol.promoteExecInfrastructureFailure(
            MinisdResponse(1, 7, true, JSONObject().put("exit_code", 5), null),
            userCommandStarted = false,
        )
        val privilege = MinisdProtocol.promoteExecInfrastructureFailure(
            MinisdResponse(1, 8, true, JSONObject().put("exit_code", 6), null),
            userCommandStarted = false,
        )
        val execve = MinisdProtocol.promoteExecInfrastructureFailure(
            MinisdResponse(
                1,
                9,
                true,
                JSONObject().put("exit_code", 7).put("stderr", "execve /bin/bash: ENOENT"),
                null,
            ),
            userCommandStarted = false,
        )

        assertEquals(MinisdProtocol.ERROR_CHROOT_UNAVAILABLE, chroot.code)
        assertEquals(MinisdProtocol.ERROR_PRIVILEGE_SETUP_FAILED, privilege.code)
        assertEquals(MinisdProtocol.ERROR_EXEC_UNAVAILABLE, execve.code)
        assertFalse(MinisdProtocol.isRetrySafeKeeperFailure(chroot))
        assertFalse(MinisdProtocol.isRetrySafeKeeperFailure(privilege))
        assertFalse(MinisdProtocol.isRetrySafeKeeperFailure(execve))
    }

    @Test
    fun `ordinary command exit code remains a command result`() {
        val raw = MinisdResponse(
            1,
            10,
            true,
            JSONObject().put("exit_code", 23).put("stderr", "user command failed"),
            null,
        )

        val promoted = MinisdProtocol.promoteExecInfrastructureFailure(
            raw,
            userCommandStarted = true,
        )

        assertTrue(promoted.ok)
        assertEquals(23, promoted.result!!.getInt("exit_code"))
        assertNull(promoted.error)
    }

    @Test
    fun `explicit future pre exec error is preserved structurally without marker`() {
        val raw = MinisdResponse(
            1,
            11,
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
    fun `structured keeper error is retry safe without legacy inference`() {
        val raw = MinisdResponse(
            1,
            12,
            false,
            null,
            MinisdError(MinisdProtocol.ERROR_KEEPER_NAMESPACE_LOST, "keeper gone"),
        )

        assertTrue(MinisdProtocol.isRetrySafeKeeperFailure(raw))
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
    fun `workspace file request carries only fixed guest path fields`() {
        val raw = MinisdProtocol.encodeRequest(
            MinisdProtocol.workspaceFile(
                operation = "write",
                sessionId = "session-a",
                path = "/workspace/hello.txt",
                dataBase64 = "aGVsbG8=",
                createDirs = true,
                id = 15,
            ),
        )
        val obj = JSONObject(raw)
        assertEquals("workspace.file", obj.getString("method"))
        val params = obj.getJSONObject("params")
        assertEquals("write", params.getString("operation"))
        assertEquals("session-a", params.getString("session_id"))
        assertEquals("/workspace/hello.txt", params.getString("path"))
        assertEquals("aGVsbG8=", params.getString("data_base64"))
        assertTrue(params.getBoolean("create_dirs"))
        assertFalse(params.has("host_path"))
    }

    @Test
    fun `workspace file copy and move requests carry source separately from path`() {
        val raw = MinisdProtocol.encodeRequest(
            MinisdProtocol.workspaceFile(
                operation = "move",
                sessionId = "session-a",
                source = "/workspace/.tmp",
                destination = "/workspace/final.txt",
                id = 16,
            ),
        )
        val params = JSONObject(raw).getJSONObject("params")
        assertEquals("move", params.getString("operation"))
        assertEquals("/workspace/.tmp", params.getString("source"))
        assertEquals("/workspace/final.txt", params.getString("destination"))
        assertFalse(params.has("path"))
    }

    @Test
    fun `workspace file cross-session move carries both session scopes`() {
        val raw = MinisdProtocol.encodeRequest(
            MinisdProtocol.workspaceFile(
                operation = "move",
                sourceSessionId = "draft",
                destinationSessionId = "real",
                source = "/var/minis/browser/state.json",
                destination = "/var/minis/browser/state.json",
                id = 17,
            ),
        )
        val params = JSONObject(raw).getJSONObject("params")
        assertEquals("draft", params.getString("source_session_id"))
        assertEquals("real", params.getString("destination_session_id"))
        assertFalse(params.has("session_id"))
    }

    @Test
    fun `legacy migration request uses fixed target and relative path`() {
        val raw = MinisdProtocol.encodeRequest(
            MinisdProtocol.workspaceFile(
                operation = "migration_write",
                target = "memory",
                path = "SOUL.md",
                dataBase64 = "bWVt",
                id = 18,
            ),
        )
        val params = JSONObject(raw).getJSONObject("params")
        assertEquals("migration_write", params.getString("operation"))
        assertEquals("memory", params.getString("target"))
        assertEquals("SOUL.md", params.getString("path"))
        assertEquals("bWVt", params.getString("data_base64"))
        assertFalse(params.has("host_path"))
    }

    @Test
    fun `session deletion request carries only broker session identity`() {
        val raw = MinisdProtocol.encodeRequest(
            MinisdProtocol.workspaceFile(
                operation = "delete_session",
                sessionId = "session-a",
                id = 19,
            ),
        )
        val params = JSONObject(raw).getJSONObject("params")
        assertEquals("delete_session", params.getString("operation"))
        assertEquals("session-a", params.getString("session_id"))
        assertFalse(params.has("path"))
        assertFalse(params.has("host_path"))
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
    fun `root full exec uses the same structured request and carries confirm id`() {
        val raw = MinisdProtocol.encodeRequest(
            MinisdProtocol.rootFullExec(
                tool = "sh",
                args = listOf("-c", "id; getenforce"),
                timeoutMs = 18_000,
                confirmId = "confirm-1",
                id = 13,
                executionId = "root:13",
            ),
        )
        val obj = JSONObject(raw)
        assertEquals("root.fullExec", obj.getString("method"))
        assertEquals("confirm-1", obj.getString("confirm_id"))
        val params = obj.getJSONObject("params")
        assertEquals("sh", params.getString("tool"))
        assertEquals("-c", params.getJSONArray("args").getString(0))
        assertEquals("id; getenforce", params.getJSONArray("args").getString(1))
        assertEquals("root:13", params.getString("execution_id"))
        assertFalse(params.has("command"))
        assertFalse(params.has("access_mode"))
    }

    @Test
    fun `root probe has a dedicated broker method`() {
        val obj = JSONObject(MinisdProtocol.encodeRequest(MinisdProtocol.rootProbe(14)))
        assertEquals("root.probe", obj.getString("method"))
        assertFalse(obj.getJSONObject("params").has("command"))
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
    fun `mount reconcile carries only URI-derived identity fields`() {
        val mounts = JSONArray().put(
            JSONObject()
                .put("id", "550e8400-e29b-41d4-a716-446655440000")
                .put("name", "docs")
                .put("volume", "primary")
                .put("path_segments", JSONArray().put("Documents"))
                .put("access", "ro"),
        )
        val obj = JSONObject(MinisdProtocol.encodeRequest(MinisdProtocol.mountReconcile(mounts, 21)))
        assertEquals("mount.reconcile", obj.getString("method"))
        val entry = obj.getJSONObject("params").getJSONArray("mounts").getJSONObject(0)
        assertEquals("primary", entry.getString("volume"))
        assertEquals("Documents", entry.getJSONArray("path_segments").getString(0))
        assertFalse(entry.has("host_path"))
        assertFalse(entry.has("uri"))
    }

    @Test
    fun `encodeRequest declares capabilities for the called method`() {
        val raw = MinisdProtocol.encodeRequest(MinisdProtocol.ubuntuStatus(9))
        val caps = JSONObject(raw).getJSONObject("client").getJSONArray("capabilities")
        assertEquals(1, caps.length())
        assertEquals("ubuntu.status", caps.getString(0))
    }
}
