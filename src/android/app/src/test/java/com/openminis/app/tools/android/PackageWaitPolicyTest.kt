package com.openminis.app.tools.android

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-eta-wait-for-package] Ported from Eta's `wait_for_package` (Mangi-11/Eta @ c15de97).
 * The fail-closed rule is the point: a foreground this app cannot read is never reported as
 * a match, in either direction.
 */
class PackageWaitPolicyTest {

    private val target = "com.example.target"

    @Test
    fun `appear matches only the target while it is visible`() {
        assertEquals(
            PackageWaitPolicy.Decision.MATCHED,
            PackageWaitPolicy.decide(target, PackageWaitPolicy.Mode.APPEAR, visiblePackage(target)),
        )
        assertEquals(
            PackageWaitPolicy.Decision.PENDING,
            PackageWaitPolicy.decide(target, PackageWaitPolicy.Mode.APPEAR, visiblePackage("com.other")),
        )
    }

    @Test
    fun `disappear matches anything else that is visible`() {
        assertEquals(
            PackageWaitPolicy.Decision.MATCHED,
            PackageWaitPolicy.decide(target, PackageWaitPolicy.Mode.DISAPPEAR, visiblePackage("com.other")),
        )
        assertEquals(
            PackageWaitPolicy.Decision.PENDING,
            PackageWaitPolicy.decide(target, PackageWaitPolicy.Mode.DISAPPEAR, visiblePackage(target)),
        )
    }

    @Test
    fun `an unreadable foreground is never a match`() {
        val invisible = PackageWaitPolicy.Observation(target, visible = false)
        val unresolved = PackageWaitPolicy.Observation(null, visible = true)
        val blank = PackageWaitPolicy.Observation("", visible = true)

        assertEquals(PackageWaitPolicy.Decision.UNKNOWN, PackageWaitPolicy.decide(target, PackageWaitPolicy.Mode.APPEAR, invisible))
        assertEquals(PackageWaitPolicy.Decision.UNKNOWN, PackageWaitPolicy.decide(target, PackageWaitPolicy.Mode.DISAPPEAR, invisible))
        assertEquals(PackageWaitPolicy.Decision.UNKNOWN, PackageWaitPolicy.decide(target, PackageWaitPolicy.Mode.APPEAR, unresolved))
        assertEquals(PackageWaitPolicy.Decision.UNKNOWN, PackageWaitPolicy.decide(target, PackageWaitPolicy.Mode.DISAPPEAR, unresolved))
        assertEquals(PackageWaitPolicy.Decision.UNKNOWN, PackageWaitPolicy.decide(target, PackageWaitPolicy.Mode.DISAPPEAR, blank))
    }

    @Test
    fun `package comparison ignores case and unknown modes fall back to appear`() {
        assertEquals(
            PackageWaitPolicy.Decision.MATCHED,
            PackageWaitPolicy.decide(target, PackageWaitPolicy.Mode.APPEAR, visiblePackage("COM.EXAMPLE.TARGET")),
        )
        assertEquals(PackageWaitPolicy.Mode.APPEAR, PackageWaitPolicy.Mode.parse(null))
        assertEquals(PackageWaitPolicy.Mode.APPEAR, PackageWaitPolicy.Mode.parse("nonsense"))
        assertEquals(PackageWaitPolicy.Mode.DISAPPEAR, PackageWaitPolicy.Mode.parse(" Disappear "))
    }

    private fun visiblePackage(name: String) = PackageWaitPolicy.Observation(name, visible = true)
}
