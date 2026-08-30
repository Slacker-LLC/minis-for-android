package com.openminis.app.runtime

import com.openminis.app.runtime.minisd.MinisdProtocol
import com.openminis.app.runtime.ubuntu.UbuntuRuntime
import com.openminis.app.runtime.terminal.TerminalSanitizer
import com.openminis.app.sandbox.RootfsManager
import com.openminis.app.sandbox.TerminalSession
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimePackageBoundaryTest {

    @Test
    fun activeComponentsLiveUnderCurrentRuntimeBoundary() {
        assertEquals("com.openminis.app.runtime", RuntimePathRegistry::class.java.packageName)
        assertEquals("com.openminis.app.runtime", ExternalMountCoordinator::class.java.packageName)
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
    fun privilegedRuntimePathsAndWireVersionRemainStable() {
        assertEquals(1, MinisdProtocol.PROTOCOL_V)
        assertEquals("/data/adb/minis/run/minisd.sock", MinisdProtocol.DEFAULT_SOCKET)
        assertEquals("/data/adb/minis/bin/minisd", MinisdProtocol.DEFAULT_BIN)
        assertEquals("/data/adb/minis/rootfs", MinisdProtocol.DEFAULT_ROOTFS)
        assertEquals("/data/adb/minis/workspace", MinisdProtocol.HOST_WORKSPACE)
        assertEquals("/workspace", MinisdProtocol.GUEST_WORKSPACE)
    }
}
