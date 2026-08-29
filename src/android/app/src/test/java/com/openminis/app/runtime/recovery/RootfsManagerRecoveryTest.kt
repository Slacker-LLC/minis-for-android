package com.openminis.app.runtime.recovery

import com.openminis.app.runtime.ubuntu.RootfsHealthCode
import com.openminis.app.sandbox.RootfsManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootfsManagerRecoveryTest {

    @Test
    fun `missing rootfs is not reported installed`() {
        val health = RootfsManager.evaluateProbeOutput("KernelSU notice\nMINIS_ROOTFS:MISSING")

        assertEquals(RootfsHealthCode.MISSING, health.code)
        assertFalse(health.healthy)
    }

    @Test
    fun `partial rootfs layout is corrupt`() {
        val health = RootfsManager.evaluateProbeOutput("MINIS_ROOTFS:CORRUPT:etc/minis/rootfs.json")

        assertEquals(RootfsHealthCode.CORRUPT, health.code)
        assertTrue(health.detail.contains("rootfs.json"))
    }

    @Test
    fun `compatible metadata is healthy`() {
        val metadata = JSONObject()
            .put("distro", "ubuntu")
            .put("version", "24.04.3")
            .put("release", "24.04.3")
            .put("arch", "arm64")
            .put("profile", "base")
            .put("upstream_sha256", "a".repeat(64))

        val health = RootfsManager.evaluateProbeOutput(
            "MINIS_ROOTFS:METADATA\n${metadata}",
        )

        assertEquals(RootfsHealthCode.HEALTHY, health.code)
        assertTrue(health.healthy)
    }

    @Test
    fun `wrong architecture is incompatible`() {
        val metadata = JSONObject()
            .put("distro", "ubuntu")
            .put("version", "24.04.3")
            .put("release", "24.04.3")
            .put("arch", "x86_64")
            .put("profile", "base")
            .put("upstream_sha256", "a".repeat(64))

        val health = RootfsManager.validateMetadata(metadata)

        assertEquals(RootfsHealthCode.INCOMPATIBLE, health.code)
        assertFalse(health.healthy)
        assertTrue(health.detail.contains("arch=x86_64"))
    }

    @Test
    fun `invalid metadata json is corrupt`() {
        val health = RootfsManager.evaluateProbeOutput(
            "MINIS_ROOTFS:METADATA\n{not-json}",
        )

        assertEquals(RootfsHealthCode.CORRUPT, health.code)
    }

    @Test
    fun `probe validates metadata shell and runtime layout`() {
        val command = RootfsManager.buildProbeCommand("/data/adb/minis/rootfs")

        assertTrue(command.contains("etc/minis/rootfs.json"))
        assertTrue(command.contains("etc/os-release"))
        assertTrue(command.contains("workspace"))
        assertTrue(command.contains("memory"))
        assertTrue(command.contains("skills"))
        assertTrue(command.contains("shared"))
        assertTrue(command.contains("/bin/bash"))
        assertTrue(command.contains("MINIS_ROOTFS:MISSING"))
    }

    @Test
    fun `repair uses staged archive and swap only after validation`() {
        val command = RootfsManager.buildRepairCommand(
            "/data/adb/minis/rootfs",
            RootfsManager.STAGED_ROOTFS_ARCHIVE,
        )

        val extractAt = command.indexOf("tar -xzf")
        val metadataAt = command.indexOf("etc/minis/rootfs.json")
        val swapAt = command.indexOf("mv \"\$ROOTFS\" \"\$OLD\"")
        assertTrue(command.contains("/data/local/tmp/ubuntu-arm64-rootfs.tar.gz"))
        assertTrue(extractAt >= 0)
        assertTrue(metadataAt > extractAt)
        assertTrue(swapAt > metadataAt)
        assertTrue(command.contains("mv \"\$NEW\" \"\$ROOTFS\""))
        assertTrue(command.contains("mv \"\$OLD\" \"\$ROOTFS\""))
        assertTrue(command.contains("MINIS_ROOTFS:REPAIRED"))
    }
}
