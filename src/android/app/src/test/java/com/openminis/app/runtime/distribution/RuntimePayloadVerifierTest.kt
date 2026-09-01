package com.openminis.app.runtime.distribution

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class RuntimePayloadVerifierTest {

    private val upstream = RuntimeDistributionManifest.PINNED_UPSTREAM_SHA256

    private fun manifest(rootfsSha: String, release: String = "24.04.3", profile: String = "base", metadataUpstream: String = upstream): RuntimeDistributionManifest {
        val text = JSONObject()
            .put("schemaVersion", 2)
            .put("protocolVersion", 1)
            .put("layoutVersion", 2)
            .put("abi", "arm64-v8a")
            .put("minisdVersion", "0.1.0")
            .put("minisdSha256", "ab".repeat(32))
            .put("rootfsVersion", "ubuntu-24.04-r1-${rootfsSha.take(16)}")
            .put("rootfsSha256", rootfsSha)
            .put("rootfsRelease", release)
            .put("rootfsProfile", profile)
            .put("rootfsUpstreamSha256", upstream)
            .put("provisionRevision", 1)
            .put("requiredCommands", org.json.JSONArray(listOf("python3", "git", "curl")))
            .toString()
        return RuntimeDistributionManifest.parse(text)
    }

    private fun tarEntry(
        name: String,
        content: ByteArray? = null,
        type: Char = '0',
        linkName: String = "",
    ): ByteArray {
        val header = ByteArray(512)
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        nameBytes.copyInto(header, 0, 0, minOf(100, nameBytes.size))
        writeOctal(header, 100, if (type == '5') 0x1ED else 0x1A4, 7)
        writeOctal(header, 108, 0, 7)
        writeOctal(header, 116, 0, 7)
        writeOctal(header, 124, (content?.size ?: 0).toLong(), 11)
        writeOctal(header, 136, 0, 11)
        header[156] = type.code.toByte()
        linkName.toByteArray(Charsets.UTF_8).copyInto(header, 157, 0, minOf(100, linkName.length))
        "ustar".toByteArray(Charsets.US_ASCII).copyInto(header, 257)
        header[263] = '0'.code.toByte()
        header[264] = '0'.code.toByte()
        java.util.Arrays.fill(header, 148, 156, ' '.code.toByte())
        val checksum = header.sumOf { it.toInt() and 0xFF }
        writeOctal(header, 148, checksum.toLong(), 6)
        val size = content?.size ?: 0
        val pad = (512 - (size % 512)) % 512
        return header + (content ?: ByteArray(0)) + ByteArray(pad)
    }

    private fun writeOctal(buffer: ByteArray, offset: Int, value: Long, digits: Int) {
        val formatted = java.lang.String.format("%0${digits}o", value)
            .toByteArray(Charsets.US_ASCII)
        formatted.copyInto(buffer, offset, 0, minOf(digits, formatted.size))
    }

    private fun paxRecords(vararg fields: Pair<String, String>): ByteArray = buildString {
        fields.forEach { (field, value) ->
            var length = ("$field=$value\n".toByteArray(Charsets.UTF_8).size + 3)
            while (true) {
                val record = "$length $field=$value\n"
                val bytes = record.toByteArray(Charsets.UTF_8)
                if (bytes.size == length) {
                    append(record)
                    break
                }
                length = bytes.size
            }
        }
    }.toByteArray(Charsets.UTF_8)

    private fun rootfsTar(
        dirs: List<String> = listOf(
            "workspace", "memory", "skills", "shared", "proc", "sys", "dev", "tmp", "run",
            "var", "var/minis", "etc", "etc/minis", "bin",
        ),
        shell: String? = "bin/bash",
        memberPrefix: String = "",
        directorySuffix: String = "",
        extraEntries: List<ByteArray> = emptyList(),
        metadata: JSONObject = JSONObject()
            .put("distro", "ubuntu")
            .put("version", "24.04")
            .put("release", "24.04.3")
            .put("arch", "arm64")
            .put("profile", "base")
            .put("revision", 1)
            .put("upstream_sha256", upstream),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        dirs.forEach { out.write(tarEntry("$memberPrefix$it$directorySuffix", null, '5')) }
        out.write(tarEntry("${memberPrefix}etc/os-release", "VERSION_ID=\"24.04\"\n".toByteArray()))
        out.write(tarEntry("${memberPrefix}etc/passwd", "root:x:0:0:root:/root:/bin/bash\n".toByteArray()))
        out.write(tarEntry("${memberPrefix}etc/group", "root:x:0:\n".toByteArray()))
        out.write(tarEntry("${memberPrefix}etc/minis/rootfs.json", metadata.toString().toByteArray()))
        if (shell != null) out.write(tarEntry("$memberPrefix$shell", "#!/bin/sh\n".toByteArray()))
        extraEntries.forEach { out.write(it) }
        out.write(ByteArray(1024))
        return out.toByteArray()
    }

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().use { bos ->
            GZIPOutputStream(bos).use { it.write(bytes) }
            bos.toByteArray()
        }

    @Test
    fun `valid rootfs archive verifies against manifest`() {
        val archive = gzip(rootfsTar())
        val digest = RuntimePayloadVerifier.sha256(ByteArrayInputStream(archive))
        val result = RuntimePayloadVerifier.verifyRootfsArchive(
            ByteArrayInputStream(archive),
            manifest(digest),
        )

        assertTrue(result.error.orEmpty(), result.ok)
    }

    @Test
    fun `rootfs archive normalizes dot slash and directory suffixes`() {
        val archive = gzip(rootfsTar(memberPrefix = "./", directorySuffix = "/"))
        val digest = RuntimePayloadVerifier.sha256(ByteArrayInputStream(archive))
        val result = RuntimePayloadVerifier.verifyRootfsArchive(
            ByteArrayInputStream(archive),
            manifest(digest),
        )

        assertTrue(result.error.orEmpty(), result.ok)
    }

    @Test
    fun `rootfs digest mismatch is rejected`() {
        val archive = gzip(rootfsTar())
        val result = RuntimePayloadVerifier.verifyRootfsArchive(
            ByteArrayInputStream(archive),
            manifest("0".repeat(64)),
        )

        assertFalse(result.ok)
        assertTrue(result.error.orEmpty().contains("SHA-256 does not match manifest"))
    }

    @Test
    fun `non gzip rootfs is rejected`() {
        val bytes = "not-gzip".toByteArray()
        val digest = RuntimePayloadVerifier.sha256(ByteArrayInputStream(bytes))
        val result = RuntimePayloadVerifier.verifyRootfsArchive(
            ByteArrayInputStream(bytes),
            manifest(digest),
        )

        assertFalse(result.ok)
        assertTrue(result.error.orEmpty().contains("archive is invalid"))
    }

    @Test
    fun `truncated gzip rootfs is rejected`() {
        val full = gzip(rootfsTar())
        val truncated = full.copyOf(full.size / 2)
        val digest = RuntimePayloadVerifier.sha256(ByteArrayInputStream(truncated))
        val result = RuntimePayloadVerifier.verifyRootfsArchive(
            ByteArrayInputStream(truncated),
            manifest(digest),
        )

        assertFalse(result.ok)
        assertTrue(result.error.orEmpty().contains("archive is invalid"))
    }

    @Test
    fun `missing layout entry is rejected`() {
        val archive = gzip(rootfsTar(dirs = listOf("workspace", "memory", "skills", "shared")))
        val digest = RuntimePayloadVerifier.sha256(ByteArrayInputStream(archive))
        val result = RuntimePayloadVerifier.verifyRootfsArchive(
            ByteArrayInputStream(archive),
            manifest(digest),
        )

        assertFalse(result.ok)
        assertTrue(result.error.orEmpty().contains("missing layout entries"))
    }

    @Test
    fun `missing shell is rejected`() {
        val archive = gzip(rootfsTar(shell = null))
        val digest = RuntimePayloadVerifier.sha256(ByteArrayInputStream(archive))
        val result = RuntimePayloadVerifier.verifyRootfsArchive(
            ByteArrayInputStream(archive),
            manifest(digest),
        )

        assertFalse(result.ok)
        assertTrue(result.error.orEmpty().contains("no shell"))
    }

    @Test
    fun `absolute symlink target is rejected`() {
        val archive = gzip(rootfsTar(
            extraEntries = listOf(
                tarEntry("etc/minis/escape", type = '2', linkName = "/data/adb/minis/workspace"),
            ),
        ))
        val digest = RuntimePayloadVerifier.sha256(ByteArrayInputStream(archive))
        val result = RuntimePayloadVerifier.verifyRootfsArchive(
            ByteArrayInputStream(archive),
            manifest(digest),
        )

        assertFalse(result.ok)
        assertTrue(result.error.orEmpty().contains("unsafe link"))
    }

    @Test
    fun `fixed guest bridge symlink is allowed`() {
        val archive = gzip(rootfsTar(
            extraEntries = listOf(
                tarEntry("var/minis/workspace", type = '2', linkName = "/workspace"),
                tarEntry("var/run", type = '2', linkName = "/run"),
            ),
        ))
        val digest = RuntimePayloadVerifier.sha256(ByteArrayInputStream(archive))
        val result = RuntimePayloadVerifier.verifyRootfsArchive(
            ByteArrayInputStream(archive),
            manifest(digest),
        )

        assertTrue(result.error.orEmpty(), result.ok)
    }

    @Test
    fun `pax path and linkpath metadata are honored`() {
        val archive = gzip(rootfsTar(
            extraEntries = listOf(
                tarEntry(
                    "PaxHeaders.0/entry",
                    content = paxRecords("path" to "var/minis/workspace", "linkpath" to "/workspace"),
                    type = 'x',
                ),
                tarEntry("placeholder", type = '2', linkName = "wrong"),
            ),
        ))
        val digest = RuntimePayloadVerifier.sha256(ByteArrayInputStream(archive))
        val result = RuntimePayloadVerifier.verifyRootfsArchive(
            ByteArrayInputStream(archive),
            manifest(digest),
        )

        assertTrue(result.error.orEmpty(), result.ok)
    }

    @Test
    fun `global pax linkpath metadata is honored`() {
        val archive = gzip(rootfsTar(
            extraEntries = listOf(
                tarEntry(
                    "PaxHeaders.0/global",
                    content = paxRecords("linkpath" to "/workspace"),
                    type = 'g',
                ),
                tarEntry("var/minis/workspace", type = '2', linkName = "wrong"),
            ),
        ))
        val digest = RuntimePayloadVerifier.sha256(ByteArrayInputStream(archive))
        val result = RuntimePayloadVerifier.verifyRootfsArchive(
            ByteArrayInputStream(archive),
            manifest(digest),
        )

        assertTrue(result.error.orEmpty(), result.ok)
    }

    @Test
    fun `symlink chain through fixed guest bridge is rejected`() {
        val archive = gzip(rootfsTar(
            extraEntries = listOf(
                tarEntry("var/run", type = '2', linkName = "/run"),
                tarEntry("etc/minis/escape", type = '2', linkName = "../../var/run"),
            ),
        ))
        val digest = RuntimePayloadVerifier.sha256(ByteArrayInputStream(archive))
        val result = RuntimePayloadVerifier.verifyRootfsArchive(
            ByteArrayInputStream(archive),
            manifest(digest),
        )

        assertFalse(result.ok)
        assertTrue(result.error.orEmpty(), result.error.orEmpty().contains("absolute link"))
    }

    @Test
    fun `special archive node is rejected`() {
        val archive = gzip(rootfsTar(extraEntries = listOf(tarEntry("etc/minis/device", type = '3'))))
        val digest = RuntimePayloadVerifier.sha256(ByteArrayInputStream(archive))
        val result = RuntimePayloadVerifier.verifyRootfsArchive(
            ByteArrayInputStream(archive),
            manifest(digest),
        )

        assertFalse(result.ok)
        assertTrue(result.error.orEmpty().contains("unsupported node type"))
    }

    @Test
    fun `hardlink target must be a regular archive file`() {
        val archive = gzip(rootfsTar(
            extraEntries = listOf(
                tarEntry("etc/minis/link", type = '1', linkName = "etc/minis/missing"),
            ),
        ))
        val digest = RuntimePayloadVerifier.sha256(ByteArrayInputStream(archive))
        val result = RuntimePayloadVerifier.verifyRootfsArchive(
            ByteArrayInputStream(archive),
            manifest(digest),
        )

        assertFalse(result.ok)
        assertTrue(result.error.orEmpty().contains("hardlink target"))
    }

    @Test
    fun `rootfs metadata release mismatch is rejected`() {
        val archive = gzip(rootfsTar(metadata = JSONObject()
            .put("distro", "ubuntu")
            .put("version", "22.04")
            .put("release", "22.04")
            .put("arch", "arm64")
            .put("profile", "base")
            .put("revision", 1)
            .put("upstream_sha256", upstream)))
        val digest = RuntimePayloadVerifier.sha256(ByteArrayInputStream(archive))
        val result = RuntimePayloadVerifier.verifyRootfsArchive(
            ByteArrayInputStream(archive),
            manifest(digest),
        )

        assertFalse(result.ok)
        assertTrue(result.error.orEmpty().contains("release does not match manifest"))
    }

    @Test
    fun `rootfs metadata upstream mismatch is rejected`() {
        val archive = gzip(rootfsTar(metadata = JSONObject()
            .put("distro", "ubuntu")
            .put("version", "24.04")
            .put("release", "24.04.3")
            .put("arch", "arm64")
            .put("profile", "base")
            .put("revision", 1)
            .put("upstream_sha256", "a".repeat(64))))
        val digest = RuntimePayloadVerifier.sha256(ByteArrayInputStream(archive))
        val result = RuntimePayloadVerifier.verifyRootfsArchive(
            ByteArrayInputStream(archive),
            manifest(digest),
        )

        assertFalse(result.ok)
        assertTrue(result.error.orEmpty().contains("upstream SHA-256 does not match manifest"))
    }

    @Test
    fun `rootfs metadata profile mismatch is rejected`() {
        val archive = gzip(rootfsTar(metadata = JSONObject()
            .put("distro", "ubuntu")
            .put("version", "24.04")
            .put("release", "24.04.3")
            .put("arch", "arm64")
            .put("profile", "full")
            .put("revision", 1)
            .put("upstream_sha256", upstream)))
        val digest = RuntimePayloadVerifier.sha256(ByteArrayInputStream(archive))
        val result = RuntimePayloadVerifier.verifyRootfsArchive(
            ByteArrayInputStream(archive),
            manifest(digest),
        )

        assertFalse(result.ok)
        assertTrue(result.error.orEmpty().contains("unsupported identity"))
    }

    @Test
    fun `minisd digest match passes`() {
        val result = RuntimePayloadVerifier.verifyMinisdDigest(
            "ab".repeat(32),
            manifest("0".repeat(64)),
        )

        assertTrue(result.ok)
    }

    @Test
    fun `minisd digest mismatch is rejected`() {
        val result = RuntimePayloadVerifier.verifyMinisdDigest(
            "cd".repeat(32),
            manifest("0".repeat(64)),
        )

        assertFalse(result.ok)
        assertTrue(result.error.orEmpty().contains("minisd SHA-256 does not match manifest"))
    }

    @Test
    fun `sha256 is stable across calls`() {
        val bytes = "fixture-bytes".toByteArray()
        val first = RuntimePayloadVerifier.sha256(ByteArrayInputStream(bytes))
        val second = RuntimePayloadVerifier.sha256(ByteArrayInputStream(bytes))

        assertEquals(first, second)
        assertTrue(first.matches(Regex("^[0-9a-f]{64}$")))
    }
}
