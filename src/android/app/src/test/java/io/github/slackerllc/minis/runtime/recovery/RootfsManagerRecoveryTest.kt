package io.github.slackerllc.minis.runtime.recovery

import io.github.slackerllc.minis.runtime.distribution.RuntimeDistributionManifest
import io.github.slackerllc.minis.runtime.ubuntu.RootfsHealthCode
import io.github.slackerllc.minis.sandbox.RootfsManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootfsManagerRecoveryTest {
    private val rootfsSha = "0123456789abcdef" + "0".repeat(48)
    private val upstreamSha = "a".repeat(64)
    private val version = "ubuntu-24.04-r7-${rootfsSha.take(16)}"

    private fun manifest(): RuntimeDistributionManifest = RuntimeDistributionManifest.parse(
        """
        {
          "schemaVersion": 2,
          "runtimeVersion": "1.01-beta.2",
          "minisdVersion": "1.01-beta.2",
          "minisdSha256": "${"1".repeat(64)}",
          "protocolVersion": 1,
          "layoutVersion": 2,
          "abi": "arm64-v8a",
          "rootfsVersion": "$version",
          "rootfsSha256": "$rootfsSha",
          "rootfsRelease": "24.04.3",
          "rootfsProfile": "base",
          "rootfsUpstreamSha256": "$upstreamSha",
          "provisionRevision": 3,
          "requiredCommands": ["python3", "git", "curl"]
        }
        """.trimIndent(),
    )

    private fun healthyProbe(): String {
        val metadata = JSONObject()
            .put("distro", "ubuntu")
            .put("version", "24.04")
            .put("release", "24.04.3")
            .put("arch", "arm64")
            .put("profile", "base")
            .put("upstream_sha256", upstreamSha)
        return listOf(
            "MINIS_ROOTFS:PATH:${RootfsManager.ROOTFS_VERSIONS}/$version",
            "MINIS_ROOTFS:ARTIFACT_SHA:$rootfsSha",
            "MINIS_ROOTFS:REVISION:$version",
            "MINIS_ROOTFS:METADATA:$metadata",
        ).joinToString("\n")
    }

    @Test
    fun `missing and corrupt rootfs are never healthy`() {
        val missing = RootfsManager.evaluateProbeOutput("KernelSU notice\nMINIS_ROOTFS:MISSING")
        assertEquals(RootfsHealthCode.MISSING, missing.code)
        assertFalse(missing.healthy)

        val corrupt = RootfsManager.evaluateProbeOutput("MINIS_ROOTFS:CORRUPT:etc/minis/rootfs.json")
        assertEquals(RootfsHealthCode.CORRUPT, corrupt.code)
        assertFalse(corrupt.healthy)
    }

    @Test
    fun `concrete artifact identity matches authoritative manifest`() {
        val health = RootfsManager.evaluateProbeOutput(healthyProbe())
        assertEquals(RootfsHealthCode.HEALTHY, health.code)
        assertTrue(RootfsManager.healthMatchesManifest(health, manifest()))
        assertEquals(rootfsSha, health.metadata!!.getString("_artifact_sha256"))
        assertEquals(version, health.metadata!!.getString("_rootfs_version"))
        assertTrue(health.metadata!!.getString("_path").endsWith("/versions/$version"))
    }

    @Test
    fun `artifact or concrete version mismatch is rejected`() {
        val health = RootfsManager.evaluateProbeOutput(healthyProbe())
        val otherSha = "f".repeat(64)
        val other = RuntimeDistributionManifest.parse(
            """
            {
              "schemaVersion":2,"runtimeVersion":"1.01-beta.2","minisdVersion":"1.01-beta.2",
              "minisdSha256":"${"1".repeat(64)}","protocolVersion":1,"layoutVersion":2,"abi":"arm64-v8a",
              "rootfsVersion":"ubuntu-24.04-r7-${otherSha.take(16)}","rootfsSha256":"$otherSha",
              "rootfsRelease":"24.04.3","rootfsProfile":"base","rootfsUpstreamSha256":"$upstreamSha",
              "provisionRevision":3,"requiredCommands":["python3","git","curl"]
            }
            """.trimIndent(),
        )
        assertFalse(RootfsManager.healthMatchesManifest(health, other))
    }

    @Test
    fun `install validates final archive before extraction and has one current commit point`() {
        val command = RootfsManager.buildInstallCommand("/data/user/0/app/cache/rootfs.tar.gz", manifest())
        val hashAt = command.indexOf("sha256sum")
        val extractAt = command.indexOf("tar -xzf")
        val pendingAt = command.indexOf("PENDING.tmp")
        val commitNeedle = "mv -f \"\$NEXT\" \"\$CURRENT\""
        val commitAt = command.indexOf(commitNeedle)

        assertTrue(hashAt >= 0)
        assertTrue(extractAt > hashAt)
        assertTrue(pendingAt > extractAt)
        assertTrue(commitAt > pendingAt)
        assertEquals(commitAt, command.lastIndexOf(commitNeedle))
        assertTrue(command.contains(rootfsSha))
        assertTrue(command.contains("$version"))
        assertFalse(command.contains("/data/local/tmp/minis-runtime"))
        assertFalse(command.contains("/data/adb/minis/bin/minisd"))
        RuntimeDistributionManifest.PROTECTED_DATA_ROOTS.forEach { protected ->
            assertFalse("runtime switch must not touch $protected", command.contains(protected))
        }
    }

    @Test
    fun `rollback commands only restore validated version pointers`() {
        val pending = RootfsManager.buildRollbackPendingCommand()
        val previous = RootfsManager.buildRollbackPreviousCommand()
        listOf(pending, previous).forEach { command ->
            assertTrue(command.contains("ubuntu-24.04-r[1-9][0-9]*-"))
            assertTrue(command.contains("current.rollback"))
            assertFalse(command.contains("rm -rf '/data/adb/minis'"))
            RuntimeDistributionManifest.PROTECTED_DATA_ROOTS.forEach { protected ->
                assertFalse(command.contains(protected))
            }
        }
    }

    @Test
    fun `root probe resolves current only inside root control plane`() {
        val shell = RootfsManager.resolveRootfsShell()
        assertTrue(shell.contains(RootfsManager.ROOTFS_CURRENT))
        assertTrue(shell.contains(RootfsManager.ROOTFS_VERSIONS))
        assertTrue(shell.contains("readlink"))
        assertTrue(shell.contains("MINIS_ROOTFS:CORRUPT:current"))
    }
}
