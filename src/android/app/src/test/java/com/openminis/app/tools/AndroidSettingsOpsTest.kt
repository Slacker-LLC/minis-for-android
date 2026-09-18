package com.openminis.app.tools

import com.openminis.app.tools.runtime.TestContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSettingsOpsTest {
    @Test
    fun `invalid namespace and key are rejected before provider access`() {
        val badNamespace = AndroidSettingsOps.get(TestContext.dummy(), "private", "foo")
        assertFalse(badNamespace.success)
        val badKey = AndroidSettingsOps.get(TestContext.dummy(), "system", "../escape")
        assertFalse(badKey.success)
        assertTrue(badKey.output.contains("invalid settings key"))
    }
}
