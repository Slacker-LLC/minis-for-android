package com.openminis.app.runtime.recovery

import com.openminis.app.sandbox.RootfsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootfsSizeProbeTest {
    @Test
    fun parsesDuKibibytesIntoBytes() {
        assertEquals(2_516_582_400L, RootfsManager.parseSizeProbeOutput("2457600\t/data/adb/minis/rootfs\n"))
    }

    @Test
    fun skipsLeadingNoiseLines() {
        assertEquals(1024L, RootfsManager.parseSizeProbeOutput("\n1\t/x\n"))
    }

    @Test
    fun rejectsOutputThatIsNotANumber() {
        assertNull(RootfsManager.parseSizeProbeOutput(""))
        assertNull(RootfsManager.parseSizeProbeOutput("du: permission denied"))
        assertNull(RootfsManager.parseSizeProbeOutput("-5\t/x"))
    }

    @Test
    fun probeIsAFixedReadOnlyCommandOnTheGivenPathOnly() {
        val cmd = RootfsManager.buildSizeProbeCommand("/data/adb/minis/rootfs")
        assertTrue(cmd.startsWith("du -skx "))
        // A path with shell metacharacters stays one quoted argument.
        val hostile = RootfsManager.buildSizeProbeCommand("/x; rm -rf /")
        assertTrue(hostile.contains("'/x; rm -rf /'"))
    }
}
