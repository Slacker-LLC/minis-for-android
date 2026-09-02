package com.openminis.app.runtime.ubuntu

import com.openminis.app.runtime.minisd.MinisdError
import com.openminis.app.runtime.minisd.MinisdResponse
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeDiagnosticsTest {
    @Test
    fun `reachable broker and invalid rootfs remain separate diagnoses`() {
        val diagnostics = RuntimeDiagnosticsMapper.fromResponses(
            ping = response(JSONObject().put("pong", true)),
            rootProbe = response(
                JSONObject()
                    .put("uid", 0)
                    .put("gid", 0)
                    .put("groups", org.json.JSONArray().put(0))
                    .put("capEff", "000001ffffffffff")
                    .put("selinux", "u:r:su:s0")
                    .put("enforcing", JSONObject.NULL),
            ),
            ubuntuStatus = response(
                JSONObject()
                    .put("running", false)
                    .put("available", false)
                    .put("provisioned", false)
                    .put("layout_known", false),
            ),
            rootfsHealth = RootfsHealth(
                RootfsHealthCode.INCOMPATIBLE,
                "rootfs revision is unsupported",
                sizeBytes = 123_456L,
            ),
        )

        assertEquals(BrokerDiagnosticState.REACHABLE, diagnostics.broker.state)
        assertEquals(RootDiagnosticState.AVAILABLE, diagnostics.root.state)
        assertNull(diagnostics.root.selinuxEnforcing)
        assertEquals(RootfsHealthCode.INCOMPATIBLE, diagnostics.rootfs.state)
        assertEquals(123_456L, diagnostics.rootfs.sizeBytes)
        assertEquals(KeeperDiagnosticState.STOPPED, diagnostics.keeper.state)
        assertEquals(ProvisionDiagnosticState.NOT_PROVISIONED, diagnostics.provision.state)
        assertEquals("rootfs revision is unsupported", diagnostics.rootfs.detail)
    }

    @Test
    fun `failed component response does not erase independent broker diagnosis`() {
        val diagnostics = RuntimeDiagnosticsMapper.fromResponses(
            ping = response(JSONObject().put("pong", true)),
            rootProbe = failed("root probe denied"),
            ubuntuStatus = failed("ubuntu status unavailable"),
        )

        assertEquals(BrokerDiagnosticState.UNKNOWN, diagnostics.broker.state)
        assertEquals(true, diagnostics.broker.socketReachable)
        assertEquals(RootDiagnosticState.UNAVAILABLE, diagnostics.root.state)
        assertEquals(KeeperDiagnosticState.UNKNOWN, diagnostics.keeper.state)
        assertEquals(ProvisionDiagnosticState.UNKNOWN, diagnostics.provision.state)
    }

    private fun response(result: JSONObject) = MinisdResponse(1, 1, true, result, null)

    private fun failed(detail: String) = MinisdResponse(
        1,
        1,
        false,
        null,
        MinisdError("RUNTIME_UNAVAILABLE", detail),
    )
}
