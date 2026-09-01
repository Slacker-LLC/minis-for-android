package com.openminis.app.runtime.distribution

import android.content.Context
import com.openminis.app.runtime.ubuntu.RuntimeProvision
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Validates the APK-owned runtime payload against the schema-v2 manifest:
 * minisd digest, rootfs digest, rootfs tar layout, and rootfs metadata
 * identity. Any mismatch fails closed and must never reach installation.
 */
object RuntimePayloadVerifier {
    const val MANIFEST_ASSET = "minis-runtime/runtime-manifest.json"
    private const val TAR_BLOCK = 512
    private val REQUIRED_LAYOUT = listOf(
        "etc/os-release",
        "etc/passwd",
        "etc/group",
        "etc/minis/rootfs.json",
        "workspace",
        "memory",
        "skills",
        "shared",
        "proc",
        "sys",
        "dev",
        "tmp",
        "run",
        "var/minis",
    )

    data class VerificationResult(val ok: Boolean, val error: String? = null) {
        companion object {
            fun ok(): VerificationResult = VerificationResult(true)
            fun fail(message: String): VerificationResult = VerificationResult(false, message)
        }
    }

    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun verifyMinisdDigest(
        actualSha256: String,
        manifest: RuntimeDistributionManifest,
    ): VerificationResult =
        if (actualSha256 == manifest.minisdSha256) {
            VerificationResult.ok()
        } else {
            VerificationResult.fail("APK minisd SHA-256 does not match manifest")
        }

    fun verifyRootfsArchive(
        rootfs: InputStream,
        manifest: RuntimeDistributionManifest,
    ): VerificationResult {
        val raw = try {
            rootfs.readBytes()
        } catch (t: Throwable) {
            return VerificationResult.fail("cannot read rootfs asset: ${t.message}")
        }
        val digest = sha256(raw.inputStream())
        if (digest != manifest.rootfsSha256) {
            return VerificationResult.fail("APK rootfs SHA-256 does not match manifest")
        }
        val metadataText = try {
            GZIPInputStream(raw.inputStream()).use { scanTar(it) }
        } catch (t: Throwable) {
            return VerificationResult.fail("rootfs archive is invalid: ${t.message}")
        }
        val metadata = try {
            JSONObject(metadataText)
        } catch (t: Throwable) {
            return VerificationResult.fail("rootfs metadata is invalid JSON: ${t.message}")
        }
        if (metadata.optString("distro") != "ubuntu" || metadata.optString("profile") != "base") {
            return VerificationResult.fail("rootfs metadata has an unsupported identity")
        }
        if (metadata.optString("release") != manifest.rootfsRelease) {
            return VerificationResult.fail("rootfs metadata release does not match manifest")
        }
        if (metadata.optString("upstream_sha256").lowercase() != manifest.rootfsUpstreamSha256) {
            return VerificationResult.fail("rootfs metadata upstream SHA-256 does not match manifest")
        }
        return VerificationResult.ok()
    }

    fun verifyApkPayload(context: Context): VerificationResult {
        val manifest = try {
            context.assets.open(MANIFEST_ASSET)
                .bufferedReader()
                .use { RuntimeDistributionManifest.parse(it.readText()) }
        } catch (t: Throwable) {
            return VerificationResult.fail("cannot read runtime manifest: ${t.message}")
        }
        val broker = File(
            context.applicationInfo.nativeLibraryDir,
            RuntimeProvision.PACKAGED_BROKER_NAME,
        )
        if (!broker.isFile) {
            return VerificationResult.fail("APK is missing packaged minisd: ${broker.absolutePath}")
        }
        val brokerSha = try {
            broker.inputStream().use { sha256(it) }
        } catch (t: Throwable) {
            return VerificationResult.fail("cannot read packaged minisd: ${t.message}")
        }
        verifyMinisdDigest(brokerSha, manifest).let { if (!it.ok) return it }
        return try {
            context.assets.open(RuntimeProvision.ROOTFS_ASSET).use { verifyRootfsArchive(it, manifest) }
        } catch (t: Throwable) {
            VerificationResult.fail("cannot read rootfs asset: ${t.message}")
        }
    }

    private fun scanTar(tar: InputStream): String {
        val names = mutableSetOf<String>()
        var metadataText: String? = null
        var pendingName: String? = null
        val header = ByteArray(TAR_BLOCK)
        while (true) {
            val read = readExact(tar, header)
            if (read == 0) break
            if (read < TAR_BLOCK) throw IllegalArgumentException("truncated tar header")
            if (header.all { it == 0.toByte() }) break
            val type = header[156].toInt().toChar()
            val size = parseOctal(header, 124, 12)
            val rawName = extractString(header, 0, 100)
            val prefix = extractString(header, 345, 155)
            when (type) {
                'L' -> {
                    val out = ByteArrayOutputStream()
                    readEntryData(tar, size) { out.write(it) }
                    pendingName = out.toString(Charsets.UTF_8.name())
                }
                'x', 'g' -> {
                    val out = ByteArrayOutputStream()
                    readEntryData(tar, size) { out.write(it) }
                    parsePaxPath(out.toString(Charsets.UTF_8.name()))?.let { pendingName = it }
                }
                else -> {
                    val raw = pendingName
                        ?: if (prefix.isNotEmpty()) "$prefix/$rawName" else rawName
                    pendingName = null
                    val name = raw.removePrefix("./").removeSuffix("/")
                    if (name == "etc/minis/rootfs.json" && type in listOf('0', '\u0000')) {
                        val out = ByteArrayOutputStream()
                        readEntryData(tar, size) { out.write(it) }
                        metadataText = out.toString(Charsets.UTF_8.name())
                    } else {
                        skipEntryData(tar, size)
                    }
                    names += name
                }
            }
        }
        val missing = REQUIRED_LAYOUT.filterNot(names::contains)
        if (missing.isNotEmpty()) {
            throw IllegalArgumentException(
                "rootfs missing layout entries: ${missing.joinToString(", ")}",
            )
        }
        if ("bin/bash" !in names && "usr/bin/bash" !in names && "bin/sh" !in names) {
            throw IllegalArgumentException("rootfs has no shell")
        }
        return metadataText ?: throw IllegalArgumentException("rootfs metadata is not a regular file")
    }

    private fun parsePaxPath(records: String): String? {
        var offset = 0
        while (offset < records.length) {
            val space = records.indexOf(' ', offset)
            if (space < 0) break
            val length = records.substring(offset, space).toIntOrNull() ?: break
            if (space + 1 + (length - (space - offset)) > records.length) break
            val record = records.substring(space + 1, space + 1 + (length - (space - offset)))
            val eq = record.indexOf('=')
            if (eq > 0 && record.substring(0, eq) == "path") {
                return record.substring(eq + 1)
            }
            offset = space + 1 + (length - (space - offset))
        }
        return null
    }

    private fun readEntryData(input: InputStream, size: Long, consumer: (ByteArray) -> Unit) {
        var remaining = size
        val buffer = ByteArray(8192)
        while (remaining > 0) {
            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
            val n = readExact(input, buffer, toRead)
            if (n < 0) throw IllegalArgumentException("unexpected end of tar entry")
            consumer(buffer.copyOf(n))
            remaining -= n
        }
        skipFully(input, padding(size))
    }

    private fun skipEntryData(input: InputStream, size: Long) {
        skipFully(input, size)
        skipFully(input, padding(size))
    }

    private fun padding(size: Long): Long = (TAR_BLOCK - (size % TAR_BLOCK)) % TAR_BLOCK

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        val buffer = ByteArray(8192)
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (n < 0) throw IllegalArgumentException("unexpected end of tar")
            remaining -= n
        }
    }

    private fun readExact(input: InputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val n = input.read(buffer, offset, buffer.size - offset)
            if (n < 0) return offset
            offset += n
        }
        return offset
    }

    private fun readExact(input: InputStream, buffer: ByteArray, length: Int): Int {
        var offset = 0
        while (offset < length) {
            val n = input.read(buffer, offset, length - offset)
            if (n < 0) return offset
            offset += n
        }
        return offset
    }

    private fun parseOctal(header: ByteArray, offset: Int, length: Int): Long {
        val end = minOf(offset + length, header.size)
        var value = 0L
        for (i in offset until end) {
            val c = header[i].toInt()
            if (c == 0 || c == ' '.code) continue
            val digit = c - '0'.code
            if (digit !in 0..7) break
            value = value * 8 + digit
        }
        return value
    }

    private fun extractString(header: ByteArray, offset: Int, length: Int): String {
        val end = minOf(offset + length, header.size)
        var actualEnd = offset
        for (i in offset until end) {
            if (header[i] == 0.toByte()) break
            actualEnd = i + 1
        }
        return String(header, offset, actualEnd - offset, Charsets.UTF_8)
    }
}
