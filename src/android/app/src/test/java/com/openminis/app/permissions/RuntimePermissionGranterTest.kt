package com.openminis.app.permissions

import com.openminis.app.permissions.RuntimePermissionGranter.Declared
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePermissionGranterTest {
    private val pkg = "llc.slacker.minis"

    @Test
    fun `runtime permissions become pm grant and others are left alone`() {
        val plan = RuntimePermissionGranter.plan(
            pkg,
            listOf(
                Declared("android.permission.READ_CONTACTS", dangerous = true),
                Declared("android.permission.INTERNET", dangerous = false),
                Declared("android.permission.POST_NOTIFICATIONS", dangerous = true),
            ),
        )
        assertEquals(
            listOf(
                listOf("pm", "grant", pkg, "android.permission.READ_CONTACTS"),
                listOf("pm", "grant", pkg, "android.permission.POST_NOTIFICATIONS"),
            ),
            plan,
        )
    }

    @Test
    fun `all-files access and overlay go through appops, whatever their protection level`() {
        val plan = RuntimePermissionGranter.plan(
            pkg,
            listOf(
                Declared("android.permission.MANAGE_EXTERNAL_STORAGE", dangerous = false),
                Declared("android.permission.SYSTEM_ALERT_WINDOW", dangerous = false),
            ),
        )
        assertEquals(
            listOf(
                listOf("cmd", "appops", "set", pkg, "MANAGE_EXTERNAL_STORAGE", "allow"),
                listOf("cmd", "appops", "set", pkg, "SYSTEM_ALERT_WINDOW", "allow"),
            ),
            plan,
        )
    }

    @Test
    fun `only this package and plain identifiers are ever put in a command`() {
        assertTrue(RuntimePermissionGranter.plan("evil pkg; rm -rf /", listOf(Declared("android.permission.CAMERA", true))).isEmpty())
        assertTrue(RuntimePermissionGranter.plan("a/b", listOf(Declared("android.permission.CAMERA", true))).isEmpty())
        val plan = RuntimePermissionGranter.plan(
            pkg,
            listOf(
                Declared("android.permission.CAMERA; reboot", true),
                Declared("../etc/passwd", true),
                Declared("", true),
                Declared("android.permission.CAMERA", true),
            ),
        )
        assertEquals(listOf(listOf("pm", "grant", pkg, "android.permission.CAMERA")), plan)
    }

    @Test
    fun `a permission declared twice is granted once`() {
        val plan = RuntimePermissionGranter.plan(
            pkg,
            listOf(Declared("android.permission.CAMERA", true), Declared("android.permission.CAMERA", true)),
        )
        assertEquals(1, plan.size)
    }

    @Test
    fun `every special access the manifest declares goes through its app-op`() {
        val declared = SpecialAccess.entries.map { Declared(it.permission, dangerous = false) }
        val plan = RuntimePermissionGranter.plan(pkg, declared)
        assertEquals(
            SpecialAccess.entries.map { listOf("cmd", "appops", "set", pkg, it.appOp, "allow") },
            plan,
        )
        assertTrue(plan.any { it.contains("GET_USAGE_STATS") && it.contains("allow") })
    }

    @Test
    fun `battery and data-saver exemptions are fixed commands for this package and uid only`() {
        assertEquals(
            listOf(
                listOf("cmd", "deviceidle", "whitelist", "+$pkg"),
                listOf("cmd", "netpolicy", "add", "restrict-background-whitelist", "10234"),
            ),
            RuntimePermissionGranter.exemptionPlan(pkg, 10234),
        )
        assertTrue(RuntimePermissionGranter.exemptionPlan("evil; reboot", 10234).isEmpty())
        assertTrue(RuntimePermissionGranter.exemptionPlan(pkg, 0).isEmpty())
        assertTrue(RuntimePermissionGranter.exemptionPlan(pkg, -5).isEmpty())
    }

    private val a11y = "$pkg/com.openminis.app.accessibility.MinisAccessibilityService"
    private val listener = "$pkg/com.openminis.app.offload.MinisNotificationListenerService"

    @Test
    fun `own accesses become settings and cmd commands`() {
        val plan = RuntimePermissionGranter.accessPlan(pkg, a11y, listOf(listener), "other.app/other.Service", true)
        assertEquals(
            listOf(
                listOf("settings", "put", "secure", "enabled_accessibility_services", "other.app/other.Service:$a11y"),
                listOf("settings", "put", "secure", "accessibility_enabled", "1"),
                listOf("cmd", "notification", "allow_listener", listener),
                listOf("cmd", "role", "add-role-holder", "--user", "0", "android.app.role.ASSISTANT", pkg),
            ),
            plan,
        )
    }

    @Test
    fun `the assistant role is left alone where the phone does not have it`() {
        val plan = RuntimePermissionGranter.accessPlan(pkg, a11y, listOf(listener), null, false)
        assertTrue(plan.none { it.contains("add-role-holder") })
    }

    @Test
    fun `a service already on the list is not added twice`() {
        assertEquals(a11y, RuntimePermissionGranter.withAccessibilityService(a11y, a11y))
        assertEquals(a11y, RuntimePermissionGranter.withAccessibilityService("null", a11y))
    }

    @Test
    fun `an accessibility list with an entry it cannot vouch for is not rewritten`() {
        assertEquals(null, RuntimePermissionGranter.withAccessibilityService("x.y/z.W:evil; reboot", a11y))
        val plan = RuntimePermissionGranter.accessPlan(pkg, a11y, emptyList(), "bad entry", false)
        assertTrue(plan.isEmpty())
    }

    @Test
    fun `only this package's own components are ever allowed`() {
        val plan = RuntimePermissionGranter.accessPlan(
            pkg,
            "other.app/other.Service",
            listOf("other.app/other.Listener", "$pkg/x; reboot", listener),
            null,
            false,
        )
        assertEquals(listOf(listOf("cmd", "notification", "allow_listener", listener)), plan)
        assertTrue(RuntimePermissionGranter.accessPlan("evil pkg", a11y, listOf(listener), null, true).isEmpty())
    }
}
