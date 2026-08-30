package io.github.slackerllc.minis.runtime.recovery

import io.github.slackerllc.minis.runtime.ubuntu.RootfsHealthCode
import io.github.slackerllc.minis.sandbox.RootfsManager
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
        val swapAt = command.indexOf("mv -f \"\$CURRENT_NEXT\" \"\$CURRENT\"")
        assertTrue(command.contains("/data/local/tmp/ubuntu-arm64-rootfs.tar.gz"))
        assertTrue(command.contains("RUNTIME_ROOT='/data/adb/minis/runtime/rootfs'"))
        assertTrue(command.contains("VERSIONS=\"\$RUNTIME_ROOT/versions\""))
        assertTrue(command.contains("STAGING=\"\$RUNTIME_ROOT/staging\""))
        assertTrue(command.contains("CURRENT=\"\$RUNTIME_ROOT/current\""))
        assertTrue(command.contains("PREVIOUS=\"\$RUNTIME_ROOT/previous\""))
        assertTrue(!command.contains("rm -rf \"\$VERSION\""))
        assertTrue(!command.contains("rm -rf '/data/adb/minis'"))
        assertTrue(!command.contains("/data/adb/minis/workspace"))
        assertTrue(!command.contains("/data/adb/minis/sessions"))
        assertTrue(!command.contains("/data/adb/minis/memory"))
        assertTrue(!command.contains("/data/adb/minis/skills"))
        assertTrue(!command.contains("/data/adb/minis/shared"))
        assertTrue(!command.contains("/data/adb/minis/home"))
        val probe = RootfsManager.buildProbeCommand("/data/adb/minis/rootfs")
        assertTrue(probe.contains("MINIS_ROOTFS:CORRUPT:current"))
        assertTrue(probe.contains("ubuntu-24\\.04-[0-9A-Fa-f]{16}"))
        assertTrue(extractAt >= 0)
        assertTrue(metadataAt > extractAt)
        assertTrue(swapAt > metadataAt)
        assertTrue(command.contains("REVISION=\"ubuntu-24.04-\$(printf"))
        assertTrue(command.contains("ln -s \"\$VERSION\" \"\$CURRENT_NEXT\""))
        assertTrue(command.contains("MINIS_ROOTFS:REPAIRED:\$REVISION"))
    }

    @Test
    fun `rollback only accepts a versioned previous pointer`() {
        val command = RootfsManager.buildRollbackCommand()

        assertTrue(command.contains("PREVIOUS=\"\$RUNTIME_ROOT/previous\""))
        assertTrue(command.contains("ubuntu-24\\.04-[0-9A-Fa-f]{16}"))
        assertTrue(command.contains("MINIS_ROOTFS:ROLLED_BACK"))
    }
}
