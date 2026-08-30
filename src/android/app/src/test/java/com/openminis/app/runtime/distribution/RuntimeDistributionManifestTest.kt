package com.openminis.app.runtime.distribution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeDistributionManifestTest {
    @Test
    fun `complete manifest is deployable`() {
        val manifest = RuntimeDistributionManifest.parse(manifestJson(ready = true))

        assertTrue(manifest.deployable)
        assertEquals("2026.08.29.1", manifest.runtimeVersion)
        assertEquals(1, manifest.protocolVersion)
        assertEquals(2, manifest.layoutVersion)
        assertEquals(3, manifest.provisionRevision)
        assertEquals(listOf("python3", "git", "curl"), manifest.requiredCommands)
    }

    @Test
    fun `development manifest without trusted hashes is fail closed`() {
        val manifest = RuntimeDistributionManifest.parse(manifestJson(ready = false))

        assertFalse(manifest.deployable)
        assertEquals(null, manifest.minisd.sha256)
        assertEquals(null, manifest.rootfs.sha256)
    }

    @Test
    fun `distribution ready requires both artifact hashes`() {
        val raw = manifestJson(ready = false)
            .replace("\"distributionReady\":false", "\"distributionReady\":true")

        assertThrows(IllegalArgumentException::class.java) {
            RuntimeDistributionManifest.parse(raw)
        }
    }

    @Test
    fun `manifest protocol and layout must match APK contract`() {
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeDistributionManifest.parse(
                manifestJson(ready = true).replace("\"protocolVersion\":1", "\"protocolVersion\":2"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeDistributionManifest.parse(
                manifestJson(ready = true).replace("\"layoutVersion\":2", "\"layoutVersion\":3"),
            )
        }
    }

    @Test
    fun `staged artifact path cannot escape trusted staging root`() {
        val raw = manifestJson(ready = true).replace(
            "/data/local/tmp/minis-runtime/minisd-arm64",
            "/data/local/tmp/minis-runtime/../evil",
        )

        assertThrows(IllegalArgumentException::class.java) {
            RuntimeDistributionManifest.parse(raw)
        }
    }

    @Test
    fun `protected user data roots match persistent storage contract`() {
        assertEquals(
            listOf(
                "/data/adb/minis/workspace",
                "/data/adb/minis/sessions",
                "/data/adb/minis/memory",
                "/data/adb/minis/skills",
                "/data/adb/minis/shared",
                "/data/adb/minis/home",
            ),
            RuntimeDistributionManifest.PROTECTED_DATA_ROOTS,
        )
    }

    companion object {
        private val MINISD_SHA = "1".repeat(64)
        private val ROOTFS_SHA = "2".repeat(64)
        private val UPSTREAM_SHA = "3".repeat(64)

        fun manifestJson(ready: Boolean): String {
            val minisdSha = if (ready) "\"$MINISD_SHA\"" else "null"
            val rootfsSha = if (ready) "\"$ROOTFS_SHA\"" else "null"
            return """{
                "schemaVersion":1,
                "runtimeVersion":"2026.08.29.1",
                "protocolVersion":1,
                "layoutVersion":2,
                "abi":"arm64",
                "distributionReady":$ready,
                "minisd":{
                    "source":"external_staged",
                    "file":"minisd-arm64",
                    "stagedPath":"/data/local/tmp/minis-runtime/minisd-arm64",
                    "sha256":$minisdSha
                },
                "rootfs":{
                    "source":"external_staged",
                    "file":"ubuntu-arm64-rootfs.tar.gz",
                    "stagedPath":"/data/local/tmp/minis-runtime/ubuntu-arm64-rootfs.tar.gz",
                    "sha256":$rootfsSha,
                    "version":"ubuntu-24.04-r1",
                    "release":"24.04",
                    "profile":"base",
                    "upstreamSha256":"$UPSTREAM_SHA"
                },
                "provisionRevision":3,
                "requiredCommands":["python3","git","curl"]
            }""".trimIndent()
        }
    }
}
