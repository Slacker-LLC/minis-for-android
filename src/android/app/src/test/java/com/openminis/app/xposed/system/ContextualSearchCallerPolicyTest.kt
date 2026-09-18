package com.openminis.app.xposed.system

import com.openminis.app.xposed.ModuleTargets
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-eta-xposed-groups] Ported from Eta `hook/system/ContextualSearchCallerPolicy.kt`
 * (Mangi-11/Eta @ c15de97). The policy exists to stay narrow: letting the gesture's own surface
 * through must not become "anyone may start contextual search".
 */
class ContextualSearchCallerPolicyTest {

    private val systemUi = listOf(ModuleTargets.SYSTEM_UI_PACKAGE)
    private val launcher = listOf(ModuleTargets.XIAOMI_LAUNCHER_PACKAGE)
    private val globalLauncher = listOf(ModuleTargets.XIAOMI_GLOBAL_LAUNCHER_PACKAGE)
    private val xiaoAi = listOf(ModuleTargets.XIAOAI_PACKAGE)

    @Test
    fun `the navigation bar is let through without the gesture takeover`() {
        assertTrue(
            ContextualSearchCallerPolicy.allows(systemUi, systemPackages = emptySet(), gestureEnabled = false),
        )
        assertTrue(
            ContextualSearchCallerPolicy.allows(
                listOf(ModuleTargets.COLOROS_DIRECT_PACKAGE),
                systemPackages = emptySet(),
                gestureEnabled = false,
            ),
        )
    }

    @Test
    fun `the HyperOS surfaces need the gesture takeover and a system package`() {
        val enabled = true

        assertTrue(
            ContextualSearchCallerPolicy.allows(
                launcher,
                systemPackages = setOf(ModuleTargets.XIAOMI_LAUNCHER_PACKAGE),
                gestureEnabled = enabled,
            ),
        )
        assertTrue(
            ContextualSearchCallerPolicy.allows(
                globalLauncher,
                systemPackages = setOf(ModuleTargets.XIAOMI_GLOBAL_LAUNCHER_PACKAGE),
                gestureEnabled = enabled,
            ),
        )
        assertTrue(
            ContextualSearchCallerPolicy.allows(
                xiaoAi,
                systemPackages = setOf(ModuleTargets.XIAOAI_PACKAGE),
                gestureEnabled = enabled,
            ),
        )
    }

    @Test
    fun `a package wearing the launcher's name is not a system package`() {
        assertFalse(
            "a side-loaded app must not inherit the gesture's permission",
            ContextualSearchCallerPolicy.allows(launcher, systemPackages = emptySet(), gestureEnabled = true),
        )
        assertFalse(
            ContextualSearchCallerPolicy.allows(xiaoAi, systemPackages = emptySet(), gestureEnabled = true),
        )
    }

    @Test
    fun `the takeover switch is what lets the launcher through`() {
        assertFalse(
            ContextualSearchCallerPolicy.allows(
                launcher,
                systemPackages = setOf(ModuleTargets.XIAOMI_LAUNCHER_PACKAGE),
                gestureEnabled = false,
            ),
        )
    }

    @Test
    fun `anything else keeps the platform check`() {
        assertFalse(
            ContextualSearchCallerPolicy.allows(
                listOf("com.example.thirdparty"),
                systemPackages = setOf("com.example.thirdparty"),
                gestureEnabled = true,
            ),
        )
        assertFalse(
            ContextualSearchCallerPolicy.allows(
                callingPackages = emptyList(),
                systemPackages = emptySet(),
                gestureEnabled = true,
            ),
        )
    }
}
