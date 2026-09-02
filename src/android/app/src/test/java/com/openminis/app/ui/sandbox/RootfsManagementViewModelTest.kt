package com.openminis.app.ui.sandbox

import com.openminis.app.runtime.ubuntu.RootfsHealth
import com.openminis.app.runtime.ubuntu.RootfsHealthCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RootfsManagementViewModelTest {
    @Test
    fun `health mapping keeps stable code and detail instead of only installed boolean`() {
        val state = RootfsManagementUiState(
            isInstalled = true,
            rootfsSize = 4096L,
            rootfsHealthCode = RootfsHealthCode.HEALTHY,
        )

        val next = state.withHealth(
            RootfsHealth(RootfsHealthCode.CORRUPT, "rootfs manifest is invalid"),
        )

        assertFalse(next.isInstalled)
        assertEquals(RootfsHealthCode.CORRUPT, next.rootfsHealthCode)
        assertEquals("rootfs manifest is invalid", next.rootfsHealthDetail)
        assertEquals(0L, next.rootfsSize)
    }
}
