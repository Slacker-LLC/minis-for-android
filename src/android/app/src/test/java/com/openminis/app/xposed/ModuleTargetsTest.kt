package com.openminis.app.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-eta-xposed-entry] Ported from Eta `core/ModuleConfig.kt` and the process filter in
 * `ModuleMain.kt` (Mangi-11/Eta @ c15de97). Detaching from processes the module cannot hook is what
 * keeps it from costing the device anything outside its scope.
 */
class ModuleTargetsTest {

    @Test
    fun `a process belongs to its package and to its own sub-processes`() {
        assertTrue(ModuleTargets.isProcessOf("com.miui.home", "com.miui.home"))
        assertTrue(ModuleTargets.isProcessOf("com.miui.voiceassist:core", ModuleTargets.XIAOAI_PACKAGE))
        assertFalse(ModuleTargets.isProcessOf("com.miui.voiceassistx", ModuleTargets.XIAOAI_PACKAGE))
        assertFalse(ModuleTargets.isProcessOf(null, ModuleTargets.XIAOAI_PACKAGE))
        assertFalse(ModuleTargets.isProcessOf("  ", ModuleTargets.XIAOAI_PACKAGE))
        assertFalse(ModuleTargets.isProcessOf("com.miui.home", ""))
    }

    @Test
    fun `the module keeps its callbacks only in its own process and its targets`() {
        assertTrue(ModuleTargets.shouldKeepLifecycleCallbacks(ModuleTargets.OWN_PACKAGE))
        assertTrue(ModuleTargets.shouldKeepLifecycleCallbacks("llc.slacker.minis:agent"))
        assertTrue(ModuleTargets.shouldKeepLifecycleCallbacks(ModuleTargets.SYSTEM_SERVER_PROCESS))
        assertTrue(ModuleTargets.shouldKeepLifecycleCallbacks(ModuleTargets.SYSTEM_UI_PACKAGE))
        assertTrue(ModuleTargets.shouldKeepLifecycleCallbacks(ModuleTargets.XIAOAI_PACKAGE))
        assertFalse("an unrelated app is not our business", ModuleTargets.shouldKeepLifecycleCallbacks("com.example.app"))
        assertFalse(ModuleTargets.shouldKeepLifecycleCallbacks(""))
        assertFalse(ModuleTargets.shouldKeepLifecycleCallbacks(null))
    }

    @Test
    fun `the scope list and the predicates agree`() {
        assertEquals(ModuleTargets.SCOPED_PACKAGES.distinct(), ModuleTargets.SCOPED_PACKAGES)
        ModuleTargets.SCOPED_PACKAGES.forEach { target ->
            assertTrue(
                "every declared target keeps the callbacks: $target",
                ModuleTargets.shouldKeepLifecycleCallbacks(target),
            )
        }
        assertTrue(ModuleTargets.ASSISTANT_PACKAGES.all { it in ModuleTargets.SCOPED_PACKAGES })
        assertTrue(ModuleTargets.LAUNCHER_PACKAGES.all { it in ModuleTargets.SCOPED_PACKAGES })
    }
}
