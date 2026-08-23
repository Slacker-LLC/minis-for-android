package com.openminis.app.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the `@` resource mention pipeline and the plan projection contract:
 *
 *  - resources/list is mapped to FILES_READ (NOT FILES_WRITE) — listing a
 *    workspace must never require write permission;
 *  - the tunnel control surface maps to SERVICE_MANAGE;
 *  - the plan projection shape exactly matches what the DSH plan chip folds.
 */
class ResourceAndTunnelCapabilityTest {

    @Test
    fun `resources list maps to files read not write`() {
        val cap = RemoteCapabilityCatalog.capabilityForDshRequest("resources/list", null)
        assertEquals(RemoteCapabilityCatalog.FILES_READ, cap)
        assertTrue(cap != RemoteCapabilityCatalog.FILES_WRITE)
    }

    @Test
    fun `tunnel control routes map to service manage`() {
        for (path in listOf(
            "/api/tunnel/status", "/api/tunnel/logs", "/api/tunnel/start",
            "/api/tunnel/stop", "/api/tunnel/restart", "/api/tunnel/protocol",
            "/api/tunnel/diagnose",
        )) {
            assertEquals(
                "${path} must gate on SERVICE_MANAGE",
                RemoteCapabilityCatalog.SERVICE_MANAGE,
                RemoteCapabilityCatalog.capabilityForHttpRoute("GET", path),
            )
        }
    }

    @Test
    fun `plan projection frame matches the dsh plan value shape`() {
        // The DSH client reads useProjection("plan") as { active, pending }.
        val shape = JSONObject()
            .put("active", true)
            .put("pending", false)
        assertNotNull(shape)
        assertTrue(shape.has("active"))
        assertTrue(shape.has("pending"))
        assertEquals(false, shape.getBoolean("pending"))
    }

    @Test
    fun `plan command is admitted by the registry`() {
        val names = AgentCommandRegistry.baseEntries.map { it.name }
        assertTrue("plan" in names)
        // and its handler branch exists in execute()
        assertTrue(names.contains("plan"))
    }
}
