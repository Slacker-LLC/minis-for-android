package io.github.slackerllc.minis.runtime.distribution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFailsWith
import org.junit.Test

class RuntimeDistributionManifestTest {
    private val rootfsSha = "0123456789abcdef" + "0".repeat(48)
    private val minisdSha = "1".repeat(64)
    private val upstreamSha = "2".repeat(64)

    private fun manifest(
        layoutVersion: Any = 2,
        rootfsVersion: String = "ubuntu-24.04-r1-${rootfsSha.take(16)}",
        rootfsSha256: String = rootfsSha,
        provisionRevision: Any = 1,
        abi: String = "arm64-v8a",
    ): String = """
        {
          "schemaVersion": 2,
          "runtimeVersion": "1.01-beta.2",
          "minisdVersion": "1.01-beta.2",
          "minisdSha256": "$minisdSha",
          "protocolVersion": 1,
          "layoutVersion": $layoutVersion,
          "abi": "$abi",
          "rootfsVersion": "$rootfsVersion",
          "rootfsSha256": "$rootfsSha256",
          "rootfsRelease": "24.04.3",
          "rootfsProfile": "base",
          "rootfsUpstreamSha256": "$upstreamSha",
          "provisionRevision": $provisionRevision,
          "requiredCommands": ["python3", "git", "curl"]
        }
    """.trimIndent()

    @Test
    fun `parses complete authoritative contract`() {
        val parsed = RuntimeDistributionManifest.parse(manifest())
        assertEquals(2, parsed.schemaVersion)
        assertEquals(2, parsed.layoutVersion)
        assertEquals("arm64-v8a", parsed.abi)
        assertEquals(rootfsSha, parsed.rootfsSha256)
        assertEquals("ubuntu-24.04-r1-${rootfsSha.take(16)}", parsed.rootfsVersion)
        assertEquals(1, parsed.provisionRevision)
    }

    @Test
    fun `rejects managed and non numeric provision placeholders`() {
        assertFailsWith<IllegalArgumentException> {
            RuntimeDistributionManifest.parse(manifest(rootfsVersion = "managed"))
        }
        assertFailsWith<Exception> {
            RuntimeDistributionManifest.parse(manifest(provisionRevision = "\"managed\""))
        }
    }

    @Test
    fun `rejects layout abi and rootfs sha identity mismatch`() {
        assertFailsWith<IllegalArgumentException> {
            RuntimeDistributionManifest.parse(manifest(layoutVersion = 1))
        }
        assertFailsWith<IllegalArgumentException> {
            RuntimeDistributionManifest.parse(manifest(abi = "arm64"))
        }
        assertFailsWith<IllegalArgumentException> {
            RuntimeDistributionManifest.parse(
                manifest(rootfsVersion = "ubuntu-24.04-r1-deadbeefdeadbeef"),
            )
        }
    }

    @Test
    fun `protected roots match issue 50 and never include runtime control plane`() {
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
}
