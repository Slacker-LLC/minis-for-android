package com.openminis.app.runtime.distribution

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeDistributionManifestTest {

    private val rootfsSha = "c0e6a145b3c8eb401f5e55eb7545f431c599290541fe61739985fb5fd1b464d7"
    private val minisdSha = "ab".repeat(32)

    private fun manifestJson(block: JSONObject.() -> Unit = {}): JSONObject = JSONObject().apply {
        put("schemaVersion", 2)
        put("protocolVersion", 1)
        put("layoutVersion", 2)
        put("abi", "arm64-v8a")
        put("minisdVersion", "0.1.0")
        put("minisdSha256", minisdSha)
        put("rootfsVersion", "ubuntu-24.04-r1-${rootfsSha.take(16)}")
        put("rootfsSha256", rootfsSha)
        put("rootfsRelease", "24.04.3")
        put("rootfsProfile", "base")
        put("rootfsUpstreamSha256", RuntimeDistributionManifest.PINNED_UPSTREAM_SHA256)
        put("provisionRevision", 1)
        put("requiredCommands", JSONArray(listOf("python3", "git", "curl")))
        block()
    }

    private fun parseInvalid(json: JSONObject, expectedFragment: String) {
        val error = assertThrows(IllegalArgumentException::class.java) {
            RuntimeDistributionManifest.parse(json.toString())
        }
        assertTrue("expected '$expectedFragment' in: ${error.message}", error.message.orEmpty().contains(expectedFragment))
    }

    @Test
    fun `valid manifest parses all fields`() {
        val manifest = RuntimeDistributionManifest.parse(manifestJson().toString())

        assertEquals("0.1.0", manifest.minisdVersion)
        assertEquals(minisdSha, manifest.minisdSha256)
        assertEquals(1, manifest.protocolVersion)
        assertEquals(2, manifest.layoutVersion)
        assertEquals("arm64-v8a", manifest.abi)
        assertEquals("ubuntu-24.04-r1-${rootfsSha.take(16)}", manifest.rootfsVersion)
        assertEquals(rootfsSha, manifest.rootfsSha256)
        assertEquals("24.04.3", manifest.rootfsRelease)
        assertEquals("base", manifest.rootfsProfile)
        assertEquals(RuntimeDistributionManifest.PINNED_UPSTREAM_SHA256, manifest.rootfsUpstreamSha256)
        assertEquals(1, manifest.provisionRevision)
        assertEquals(listOf("python3", "git", "curl"), manifest.requiredCommands)
    }

    @Test
    fun `invalid json is rejected`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            RuntimeDistributionManifest.parse("{not-json")
        }
        assertTrue(error.message.orEmpty().contains("not valid JSON"))
    }

    @Test
    fun `old schema version is rejected`() {
        parseInvalid(manifestJson { put("schemaVersion", 1) }, "schemaVersion must be 2")
    }

    @Test
    fun `unknown schema version is rejected`() {
        parseInvalid(manifestJson { put("schemaVersion", 3) }, "schemaVersion must be 2")
    }

    @Test
    fun `wrong protocol version is rejected`() {
        parseInvalid(manifestJson { put("protocolVersion", 2) }, "protocolVersion must be 1")
    }

    @Test
    fun `wrong layout version is rejected`() {
        parseInvalid(manifestJson { put("layoutVersion", 1) }, "layoutVersion must be 2")
    }

    @Test
    fun `wrong abi is rejected`() {
        parseInvalid(manifestJson { put("abi", "x86_64") }, "abi must be arm64-v8a")
    }

    @Test
    fun `missing minisd digest is rejected`() {
        parseInvalid(manifestJson { remove("minisdSha256") }, "invalid minisdSha256")
    }

    @Test
    fun `non hex minisd digest is rejected`() {
        parseInvalid(manifestJson { put("minisdSha256", "z".repeat(64)) }, "invalid minisdSha256")
    }

    @Test
    fun `short minisd digest is rejected`() {
        parseInvalid(manifestJson { put("minisdSha256", "ab") }, "invalid minisdSha256")
    }

    @Test
    fun `missing rootfs digest is rejected`() {
        parseInvalid(manifestJson { remove("rootfsSha256") }, "invalid rootfsSha256")
    }

    @Test
    fun `invalid minisd version is rejected`() {
        parseInvalid(manifestJson { put("minisdVersion", "0.1") }, "invalid minisdVersion")
    }

    @Test
    fun `malformed rootfs version is rejected`() {
        parseInvalid(
            manifestJson { put("rootfsVersion", "ubuntu-24.04-r0-${rootfsSha.take(16)}") },
            "invalid rootfsVersion",
        )
    }

    @Test
    fun `rootfs version not derived from digest is rejected`() {
        parseInvalid(
            manifestJson { put("rootfsVersion", "ubuntu-24.04-r1-${"0".repeat(16)}") },
            "must be derived from rootfsSha256",
        )
    }

    @Test
    fun `non 2404 release is rejected`() {
        parseInvalid(manifestJson { put("rootfsRelease", "22.04") }, "unsupported rootfsRelease")
    }

    @Test
    fun `wrong rootfs profile is rejected`() {
        parseInvalid(manifestJson { put("rootfsProfile", "full") }, "rootfsProfile must be base")
    }

    @Test
    fun `unpinned upstream digest is rejected`() {
        parseInvalid(
            manifestJson { put("rootfsUpstreamSha256", "a".repeat(64)) },
            "pinned Ubuntu upstream SHA-256",
        )
    }

    @Test
    fun `missing provision revision is rejected`() {
        parseInvalid(manifestJson { remove("provisionRevision") }, "provisionRevision must be positive")
    }

    @Test
    fun `zero provision revision is rejected`() {
        parseInvalid(manifestJson { put("provisionRevision", 0) }, "provisionRevision must be positive")
    }

    @Test
    fun `missing required commands is rejected`() {
        parseInvalid(manifestJson { remove("requiredCommands") }, "requiredCommands mismatch")
    }

    @Test
    fun `partial required commands is rejected`() {
        parseInvalid(
            manifestJson { put("requiredCommands", JSONArray(listOf("python3"))) },
            "requiredCommands mismatch",
        )
    }
}
