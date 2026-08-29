package com.openminis.app.sandbox.distribution

import com.openminis.app.sandbox.RootfsHealth
import com.openminis.app.sandbox.RootfsHealthCode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeDistributionManagerTest {
    private val manifest = RuntimeDistributionManifest.parse(
        RuntimeDistributionManifestTest.manifestJson(ready = true),
    )

    @Test
    fun `arm64 aliases accepted and other ABIs rejected`() {
        assertTrue(RuntimeDistributionManager.supportsArm64(listOf("arm64-v8a")))
        assertTrue(RuntimeDistributionManager.supportsArm64(listOf("aarch64")))
        assertFalse(RuntimeDistributionManager.supportsArm64(listOf("armeabi-v7a", "x86_64")))
    }

    @Test
    fun `host probe reads hashes versions provision and pending marker`() {
        val output = buildString {
            appendLine("MINIS_DIST:MINISD:${"1".repeat(64)}")
            appendLine("MINIS_DIST:PROVISION:3")
            appendLine("MINIS_DIST:PENDING:2026.08.29.0")
            appendLine("MINIS_DIST:MANIFEST_BEGIN")
            appendLine(RuntimeDistributionManifestTest.manifestJson(ready = true))
            appendLine("MINIS_DIST:MANIFEST_END")
        }

        val probe = RuntimeDistributionManager.evaluateHostProbe(output)

        assertEquals("1".repeat(64), probe.minisdSha256)
        assertEquals("2026.08.29.1", probe.installedVersion)
        assertEquals("1".repeat(64), probe.installedMinisdSha256)
        assertEquals("2".repeat(64), probe.installedRootfsSha256)
        assertEquals(3, probe.provisionRevision)
        assertEquals("2026.08.29.0", probe.pendingVersion)
    }

    @Test
    fun `host probe treats malformed digest as missing`() {
        val probe = RuntimeDistributionManager.evaluateHostProbe(
            "MINIS_DIST:MINISD:not-a-digest\n" +
                "MINIS_DIST:PROVISION:MISSING\n" +
                "MINIS_DIST:PENDING:NONE\n" +
                "MINIS_DIST:MANIFEST_MISSING\n",
        )

        assertNull(probe.minisdSha256)
        assertNull(probe.provisionRevision)
        assertNull(probe.pendingVersion)
        assertNull(probe.installedVersion)
    }

    @Test
    fun `rootfs metadata must match packaged release profile and upstream`() {
        val metadata = JSONObject()
            .put("release", "24.04.3-LTS")
            .put("profile", "base")
            .put("upstream_sha256", "3".repeat(64))
        val healthy = RootfsHealth(RootfsHealthCode.HEALTHY, "ok", metadata)

        assertTrue(RuntimeDistributionManager.rootfsMatchesManifest(healthy, manifest))
        metadata.put("profile", "custom")
        assertFalse(RuntimeDistributionManager.rootfsMatchesManifest(healthy, manifest))
    }

    @Test
    fun `artifact preflight verifies both trusted digests before switch`() {
        val command = RuntimeDistributionManager.buildArtifactVerificationCommand(manifest)

        assertTrue(command.contains("sha256sum \"\$MINISD_SRC\""))
        assertTrue(command.contains("sha256sum \"\$ROOTFS_SRC\""))
        assertTrue(command.contains("1".repeat(64)))
        assertTrue(command.contains("2".repeat(64)))
        assertTrue(command.contains("MINIS_DIST:ARTIFACTS_VERIFIED"))
    }

    @Test
    fun `atomic switch prepares and validates before live rename`() {
        val command = RuntimeDistributionManager.buildSwitchCommand(
            manifest,
            RuntimeDistributionManifestTest.manifestJson(ready = true),
            "/data/user/0/app/files/minis/minisd.sock",
        )

        val extract = command.indexOf("tar -xzf")
        val pending = command.indexOf("runtime-upgrade.pending")
        val liveRootRename = command.indexOf("mv '/data/adb/minis/rootfs'")
        val newRootRename = command.indexOf("mv \"\$NEW_ROOT\" '/data/adb/minis/rootfs'")

        assertTrue(extract >= 0)
        assertTrue(pending >= 0)
        assertTrue(liveRootRename > pending)
        assertTrue(newRootRename > liveRootRename)
        assertTrue(command.contains("prepared minisd digest mismatch"))
        assertTrue(command.contains("new rootfs missing etc/minis/rootfs.json"))
        assertTrue(command.contains("upstream_sha256"))
    }

    @Test
    fun `runtime switch never deletes protected persistent data roots`() {
        val command = RuntimeDistributionManager.buildSwitchCommand(
            manifest,
            RuntimeDistributionManifestTest.manifestJson(ready = true),
            "/data/user/0/app/files/minis/minisd.sock",
        )
        val rollback = RuntimeDistributionManager.buildRollbackCommand()

        RuntimeDistributionManifest.PROTECTED_DATA_ROOTS.forEach { protected ->
            assertFalse("switch must not target $protected", command.contains("rm -rf '$protected'"))
            assertFalse("rollback must not target $protected", rollback.contains("rm -rf '$protected'"))
            assertFalse("switch must not move $protected", command.contains("mv '$protected'"))
            assertFalse("rollback must not move $protected", rollback.contains("mv '$protected'"))
        }
    }

    @Test
    fun `rollback restores all replaceable components and keeps marker on missing backup`() {
        val command = RuntimeDistributionManager.buildRollbackCommand()

        assertTrue(command.contains("rootfs rollback backup missing"))
        assertTrue(command.contains("minisd rollback backup missing"))
        assertTrue(command.contains("manifest rollback backup missing"))
        assertTrue(command.contains("provision rollback backup missing"))
        assertTrue(command.indexOf("rm -f \"\$PENDING\"") > command.indexOf("provision rollback backup missing"))
        assertTrue(command.contains("MINIS_DIST:ROLLED_BACK"))
    }

    @Test
    fun `commit checks pending version before deleting rollback backups`() {
        val command = RuntimeDistributionManager.buildCommitCommand("2026.08.29.1")

        assertTrue(command.contains("runtime transaction version changed"))
        assertTrue(command.contains("rootfs.previous-2026.08.29.1"))
        assertTrue(command.contains("minisd.previous-2026.08.29.1"))
        assertTrue(command.contains("MINIS_DIST:COMMITTED"))
    }
}
