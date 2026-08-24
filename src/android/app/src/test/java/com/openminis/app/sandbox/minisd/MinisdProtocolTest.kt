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
    fun `ubuntu exec argv is structured not a raw cmd string`() {
        val raw = MinisdProtocol.encodeRequest(
            MinisdProtocol.ubuntuExec(listOf("/usr/bin/id"), cwd = "/workspace"),
        )
        val obj = JSONObject(raw)
        assertEquals("ubuntu.exec", obj.getString("method"))
        val argv = obj.getJSONObject("params").getJSONArray("argv")
        assertEquals("/usr/bin/id", argv.getString(0))
        assertFalse(obj.getJSONObject("params").has("cmd"))
    }

    @Test
    fun `provision method has no raw command`() {
        val raw = MinisdProtocol.encodeRequest(MinisdProtocol.ubuntuProvision(3))
        val obj = org.json.JSONObject(raw)
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
    fun `supervisor restart cloudflared carries path ordered args and env`() {
        val raw = MinisdProtocol.encodeRequest(
            MinisdProtocol.supervisorRestartCloudflared(
                path = "/data/adb/minis/bin/cloudflared",
                args = listOf("tunnel", "--url", "http://127.0.0.1:8080"),
                env = mapOf("TUNNEL_TOKEN" to "secret"),
                id = 7,
            ),
        )
        val obj = JSONObject(raw)
        assertEquals(7, obj.getLong("id"))
        assertEquals("supervisor.restartCloudflared", obj.getString("method"))
        val params = obj.getJSONObject("params")
        assertEquals("/data/adb/minis/bin/cloudflared", params.getString("path"))
        val argv = params.getJSONArray("args")
        assertEquals(3, argv.length())
        assertEquals("tunnel", argv.getString(0))
        assertEquals("--url", argv.getString(1))
        assertEquals("http://127.0.0.1:8080", argv.getString(2))
        assertEquals("secret", params.getJSONObject("env").getString("TUNNEL_TOKEN"))
    }

    @Test
    fun `encodeRequest declares capabilities for the called method`() {
        val raw = MinisdProtocol.encodeRequest(MinisdProtocol.ubuntuStatus(9))
        val caps = JSONObject(raw).getJSONObject("client").getJSONArray("capabilities")
        assertEquals(1, caps.length())
        assertEquals("ubuntu.status", caps.getString(0))
    }

    @Test
    fun `supervisor restart cloudflared with empty args has empty array`() {
        val raw = MinisdProtocol.encodeRequest(
            MinisdProtocol.supervisorRestartCloudflared(path = "/bin/true"),
        )
        val params = JSONObject(raw).getJSONObject("params")
        assertEquals("/bin/true", params.getString("path"))
        assertTrue(params.getJSONArray("args").length() == 0)
    }

    @Test
    fun `supervisor status and stop methods`() {
        val status = JSONObject(MinisdProtocol.encodeRequest(MinisdProtocol.supervisorStatus(4)))
        assertEquals("supervisor.status", status.getString("method"))
        assertEquals(4, status.getLong("id"))

        val stop = JSONObject(MinisdProtocol.encodeRequest(MinisdProtocol.supervisorStopCloudflared(5)))
        assertEquals("supervisor.stopCloudflared", stop.getString("method"))
        assertEquals(5, stop.getLong("id"))
    }
}
