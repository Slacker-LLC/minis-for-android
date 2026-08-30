package com.openminis.app.tools.android

import com.openminis.app.runtime.minisd.MinisdProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PrivilegedAccessModeContractTest {
    @Test
    fun `missing or invalid preference fails closed to standard mode`() {
        assertEquals(PrivilegedAccessMode.STANDARD, PrivilegedAccessModeStore.parse(null))
        assertEquals(PrivilegedAccessMode.STANDARD, PrivilegedAccessModeStore.parse(""))
        assertEquals(PrivilegedAccessMode.STANDARD, PrivilegedAccessModeStore.parse("unexpected"))
        assertEquals(PrivilegedAccessMode.FULL_ACCESS, PrivilegedAccessModeStore.parse("full"))
    }

    @Test
    fun `standard root exec carries full structured request and confirm id`() {
        val raw = MinisdProtocol.encodeRequest(
            MinisdProtocol.rootExec(
                tool = "cmd",
                args = listOf("package", "list", "packages"),
                timeoutMs = 12_345,
                accessMode = MinisdProtocol.ACCESS_STANDARD,
                confirmId = "c-7",
                id = 9,
            ),
        )
        val obj = JSONObject(raw)
        val params = obj.getJSONObject("params")
        assertEquals("root.exec", obj.getString("method"))
        assertEquals("c-7", obj.getString("confirm_id"))
        assertEquals("cmd", params.getString("tool"))
        assertEquals("package", params.getJSONArray("args").getString(0))
        assertEquals("list", params.getJSONArray("args").getString(1))
        assertEquals("packages", params.getJSONArray("args").getString(2))
        assertEquals(12_345L, params.getLong("timeout_ms"))
        assertEquals("standard", params.getString("access_mode"))
        assertFalse(params.has("command"))
    }

    @Test
    fun `full access is an explicit wire value rather than a shell flag`() {
        val raw = MinisdProtocol.encodeRequest(
            MinisdProtocol.rootExec(
                tool = "sh",
                args = listOf("-c", "id"),
                accessMode = PrivilegedAccessMode.FULL_ACCESS.wireValue,
            ),
        )
        val params = JSONObject(raw).getJSONObject("params")
        assertEquals("full", params.getString("access_mode"))
        assertEquals("sh", params.getString("tool"))
        assertFalse(params.has("command"))
    }
}
