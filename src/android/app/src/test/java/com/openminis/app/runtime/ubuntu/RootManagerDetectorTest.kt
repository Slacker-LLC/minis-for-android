package com.openminis.app.runtime.ubuntu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-root-manager-detection-android] Which root manager a device carries, asked purely so
 * the list can be tested here: the real device that motivated this (Xiaomi 24129PN74C,
 * HyperOS + KernelSU Next) hides su from apps that are not on the manager allowlist, and
 * "no su binary" was the wrong thing to tell a user who has root.
 */
class RootManagerDetectorTest {

    @Test
    fun `the installed manager is named`() {
        val manager = RootManagerDetector.managerFor(setOf("com.rifsxd.ksunext"))

        assertEquals("KernelSU Next", manager?.label)
        assertEquals("com.rifsxd.ksunext", manager?.packageName)
    }

    @Test
    fun `a device with no known manager reports none`() {
        assertNull(RootManagerDetector.managerFor(setOf("com.android.chrome", "org.telegram.messenger")))
        assertNull(RootManagerDetector.managerFor(emptySet()))
    }

    @Test
    fun `every known manager is recognised`() {
        val cases = mapOf(
            "com.rifsxd.ksunext" to "KernelSU Next",
            "me.weishu.kernelsu" to "KernelSU",
            "me.bmax.apatch" to "APatch",
            "com.topjohnwu.magisk" to "Magisk",
        )
        cases.forEach { (packageName, label) ->
            assertEquals(label, RootManagerDetector.managerFor(setOf(packageName))?.label)
        }
    }

    @Test
    fun `two managers installed reports the first in the list`() {
        val manager = RootManagerDetector.managerFor(setOf("com.topjohnwu.magisk", "com.rifsxd.ksunext"))

        assertEquals("KernelSU Next", manager?.label)
    }
}
