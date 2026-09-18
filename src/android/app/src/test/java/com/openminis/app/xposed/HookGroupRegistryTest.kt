package com.openminis.app.xposed

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-eta-xposed-entry] The dispatch seam the module entry asks instead of carrying a branch per
 * vendor (see Eta `ModuleMain.kt`, Mangi-11/Eta @ c15de97). An empty registry is a valid, honest
 * state: the module loads and reports that it has nothing to install for a target.
 */
class HookGroupRegistryTest {

    @After
    fun tearDown() = HookGroupRegistry.clear()

    @Test
    fun `a target with no group reports nothing`() {
        assertTrue(HookGroupRegistry.forTarget("com.android.systemui").isEmpty())
        assertTrue(HookGroupRegistry.targets().isEmpty())
    }

    @Test
    fun `groups are returned in registration order and scoped to their target`() {
        val first = HookGroupInstaller { _, _, _ -> HookInstallReport("first", emptyList()) }
        val second = HookGroupInstaller { _, _, _ -> HookInstallReport("second", emptyList()) }

        HookGroupRegistry.register("com.miui.voiceassist", first)
        HookGroupRegistry.register("com.miui.voiceassist", second)
        HookGroupRegistry.register(HookGroupRegistry.SYSTEM_TARGET, first)

        assertEquals(listOf(first, second), HookGroupRegistry.forTarget("com.miui.voiceassist"))
        assertEquals(listOf(first), HookGroupRegistry.forTarget(HookGroupRegistry.SYSTEM_TARGET))
        assertTrue(HookGroupRegistry.forTarget("com.android.systemui").isEmpty())
        assertEquals(setOf("com.miui.voiceassist", "system_server"), HookGroupRegistry.targets())
    }

    @Test
    fun `a blank target is refused`() {
        val installer = HookGroupInstaller { _, _, _ -> HookInstallReport("x", emptyList()) }

        val failure = runCatching { HookGroupRegistry.register("   ", installer) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }
}
