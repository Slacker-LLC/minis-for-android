package com.openminis.app.ui.sandbox

import com.openminis.app.runtime.distribution.RuntimeDistributionCode
import com.openminis.app.runtime.distribution.RuntimeDistributionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootfsManagementViewModelStateTest {

    @Test
    fun `healthy rootfs without ready runtime is not installed`() {
        val runtime = RuntimeDistributionSnapshot(
            code = RuntimeDistributionCode.UPGRADE_REQUIRED,
            desiredVersion = "2026.08.29.1",
            installedVersion = "2026.08.28.1",
            detail = "runtime upgrade required",
        )

        val state = RootfsManagementUiState(isInstalled = true).withRuntimeProbe(
            rootfsHealthy = true,
            rootfsSize = 1234L,
            runtime = runtime,
        )

        assertFalse(state.isInstalled)
        assertFalse(state.runtimeReady)
        assertEquals(1234L, state.rootfsSize)
        assertEquals("2026.08.29.1", state.runtimeDesiredVersion)
        assertEquals("2026.08.28.1", state.runtimeInstalledVersion)
        assertEquals(RuntimeDistributionCode.UPGRADE_REQUIRED.name, state.runtimeStatus)
        assertEquals("runtime upgrade required", state.runtimeDetail)
    }

    @Test
    fun `healthy rootfs with ready runtime is installed`() {
        val runtime = RuntimeDistributionSnapshot(
            code = RuntimeDistributionCode.READY,
            desiredVersion = "2026.08.29.1",
            installedVersion = "2026.08.29.1",
            detail = "runtime ready",
        )

        val state = RootfsManagementUiState().withRuntimeProbe(
            rootfsHealthy = true,
            rootfsSize = 4096L,
            runtime = runtime,
        )

        assertTrue(state.isInstalled)
        assertTrue(state.runtimeReady)
        assertEquals(RuntimeDistributionCode.READY.name, state.runtimeStatus)
    }

    @Test
    fun `ready runtime cannot mask unhealthy rootfs`() {
        val runtime = RuntimeDistributionSnapshot(
            code = RuntimeDistributionCode.READY,
            desiredVersion = "2026.08.29.1",
            installedVersion = "2026.08.29.1",
        )

        val state = RootfsManagementUiState(isInstalled = true).withRuntimeProbe(
            rootfsHealthy = false,
            rootfsSize = 0L,
            runtime = runtime,
        )

        assertFalse(state.isInstalled)
        assertTrue(state.runtimeReady)
        assertEquals(0L, state.rootfsSize)
    }
}
