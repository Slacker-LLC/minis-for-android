package io.github.slackerllc.minis.runtime

import io.github.slackerllc.minis.runtime.minisd.MinisdProtocol
import io.github.slackerllc.minis.runtime.ubuntu.UbuntuRuntime
import io.github.slackerllc.minis.runtime.terminal.TerminalSanitizer
import io.github.slackerllc.minis.sandbox.RootfsManager
import io.github.slackerllc.minis.sandbox.TerminalSession
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimePackageBoundaryTest {

    @Test
    fun activeComponentsLiveUnderCurrentRuntimeBoundary() {
        assertEquals("io.github.slackerllc.minis.runtime", RuntimePathRegistry::class.java.packageName)
        assertEquals("io.github.slackerllc.minis.runtime", ExternalMountCoordinator::class.java.packageName)
        assertEquals("io.github.slackerllc.minis.runtime", ExecutionCoordinator::class.java.packageName)
        assertEquals("io.github.slackerllc.minis.runtime.ubuntu", UbuntuRuntime::class.java.packageName)
        assertEquals("io.github.slackerllc.minis.runtime.terminal", TerminalSanitizer::class.java.packageName)
    }

    @Test
    fun compatibilityShellsRemainOutsideActiveRuntimeBoundary() {
        assertEquals("io.github.slackerllc.minis.sandbox", RootfsManager::class.java.packageName)
        assertEquals("io.github.slackerllc.minis.sandbox", TerminalSession::class.java.packageName)
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
