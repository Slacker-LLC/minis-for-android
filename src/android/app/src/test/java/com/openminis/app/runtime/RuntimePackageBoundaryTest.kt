package com.openminis.app.runtime

import com.openminis.app.runtime.terminal.TerminalSanitizer
import com.openminis.app.runtime.ubuntu.UbuntuPaths
import com.openminis.app.runtime.ubuntu.UbuntuRuntime
import com.openminis.app.sandbox.RootfsManager
import com.openminis.app.sandbox.TerminalSession
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimePackageBoundaryTest {
    @Test
    fun activeComponentsLiveUnderCurrentRuntimeBoundary() {
        assertEquals("com.openminis.app.runtime", RuntimePathRegistry::class.java.packageName)
        assertEquals("com.openminis.app.runtime", ExecutionCoordinator::class.java.packageName)
        assertEquals("com.openminis.app.runtime.ubuntu", UbuntuRuntime::class.java.packageName)
        assertEquals("com.openminis.app.runtime.terminal", TerminalSanitizer::class.java.packageName)
    }

    @Test
    fun compatibilityShellsRemainOutsideActiveRuntimeBoundary() {
        assertEquals("com.openminis.app.sandbox", RootfsManager::class.java.packageName)
        assertEquals("com.openminis.app.sandbox", TerminalSession::class.java.packageName)
    }

    @Test
    fun directRuntimeKeepsOnlyRootfsUnderPrivilegedDataRoot() {
        assertEquals("/data/adb/minis", UbuntuPaths.HOST_MINIS)
        assertEquals("/data/adb/minis/rootfs", UbuntuPaths.HOST_ROOTFS)
    }
}
