package com.openminis.app.offload

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-offload-permission-gate] The shared permission-gate helpers.
 *
 * The device pass found a permission-gated CLI waiting two minutes for a dialog
 * nobody could answer and then reporting only `command timed out after 120000ms`.
 * These pin both halves of the fix: the gate fails fast when no Activity can host
 * the prompt, and the failure body names the permission and the human action
 * instead of a bare code.
 */
class OffloadPermissionFailureTest {

    private val location = listOf("android.permission.ACCESS_FINE_LOCATION")

    private fun failure(
        tool: String,
        permissions: List<String>,
        result: OffloadPermissionManager.AndroidPermissionResult,
        detail: String? = null,
        deniedCode: String = "permission_denied",
    ) = OffloadPermissionManager.permissionFailure(tool, permissions, result, detail, deniedCode)

    @Test
    fun `a granted gate has no failure body`() {
        assertNull(failure("android-location", location, OffloadPermissionManager.AndroidPermissionResult.GRANTED))
    }

    @Test
    fun `no ui reports permission_required and names the permission`() {
        val body = failure("android-location", location, OffloadPermissionManager.AndroidPermissionResult.NO_UI)!!
        assertEquals("permission_required", body.getString("error"))
        assertEquals("android-location", body.getString("tool"))
        assertEquals(location.first(), body.getJSONArray("permissions").getString(0))
        val message = body.getString("message")
        assertTrue(message, message.contains("not on screen"))
        assertTrue(message, message.contains("android.permission.ACCESS_FINE_LOCATION"))
    }

    @Test
    fun `denied names the tool and keeps a capability code when one is given`() {
        val denied = failure("android-notification", listOf("Notification access"), OffloadPermissionManager.AndroidPermissionResult.DENIED)!!
        assertEquals("permission_denied", denied.getString("error"))
        assertTrue(denied.getString("message"), denied.getString("message").contains("android-notification"))

        val capability = failure(
            "android-notification",
            listOf("Notification access"),
            OffloadPermissionManager.AndroidPermissionResult.DENIED,
            deniedCode = "notification_access_not_granted",
        )!!
        assertEquals("notification_access_not_granted", capability.getString("error"))
    }

    @Test
    fun `timeout keeps the budget and warns the prompt may still be up`() {
        val body = failure("android-calendar", location, OffloadPermissionManager.AndroidPermissionResult.TIMEOUT)!!
        assertEquals("permission_timeout", body.getString("error"))
        val message = body.getString("message")
        assertTrue(message, message.contains((OffloadPermissionManager.PERMISSION_FLOW_BUDGET_MS / 1000).toString()))
        assertTrue(message, message.contains("may still be on"))
    }

    @Test
    fun `detail is appended to the message and published on its own`() {
        val body = failure(
            "android-photos",
            listOf("android.permission.ACCESS_MEDIA_LOCATION"),
            OffloadPermissionManager.AndroidPermissionResult.DENIED,
            detail = "Without it, GPS EXIF is redacted.",
        )!!
        assertEquals("Without it, GPS EXIF is redacted.", body.getString("detail"))
        assertTrue(body.getString("message"), body.getString("message").endsWith("Without it, GPS EXIF is redacted."))
    }

    @Test
    fun `without a host the whole gate fails fast instead of waiting`() {
        OffloadPermissionManager.setPermissionHostAttached(false)
        val started = System.currentTimeMillis()
        val result = runBlocking {
            OffloadPermissionManager.requestPermissionFlow(
                permissions = location,
                satisfied = { false },
                settingsGate = OffloadPermissionManager.SettingsGateRequest(
                    id = "test",
                    title = "t",
                    message = "m",
                    settingsAction = "android.settings.APPLICATION_DETAILS_SETTINGS",
                    requiresPackageUri = true,
                    positiveLabel = "Open Settings",
                ),
            )
        }
        val elapsed = System.currentTimeMillis() - started
        assertEquals(OffloadPermissionManager.AndroidPermissionResult.NO_UI, result)
        assertTrue("gate took ${elapsed}ms", elapsed < 2_000)
    }
}

