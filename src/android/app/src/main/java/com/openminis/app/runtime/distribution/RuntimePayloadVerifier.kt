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
    private const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024
    private const val MAX_EXPANDED_BYTES = 2L * 1024 * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 16L * 1024 * 1024
    private const val MAX_ENTRIES = 1_000_000
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
    private val REQUIRED_REAL_DIRECTORIES = setOf(
        "etc",
        "etc/minis",
        "workspace",
        "memory",
        "skills",
        "shared",
        "proc",
        "sys",
        "dev",
        "tmp",
        "run",
        "var",
        "var/minis",
    )
    private val OPTIONAL_REAL_DIRECTORIES = setOf(
        "dev/pts",
        "dev/shm",
        "mnt",
        "home",
        "home/minis",
        "root",
    )
    private val REQUIRED_REGULAR_FILES = setOf(
        "etc/passwd",
        "etc/group",
        "etc/minis/rootfs.json",
    )
    private val ALLOWED_ABSOLUTE_GUEST_LINKS = mapOf(
        "etc/alternatives/awk" to "/usr/bin/mawk",
        "etc/alternatives/nawk" to "/usr/bin/mawk",
        "etc/alternatives/pager" to "/bin/more",
        "etc/alternatives/rmt" to "/usr/sbin/rmt-tar",
        "etc/alternatives/which" to "/usr/bin/which.debianutils",
        "etc/rmt" to "/usr/sbin/rmt",
        "usr/bin/awk" to "/etc/alternatives/awk",
        "usr/bin/nawk" to "/etc/alternatives/nawk",
        "usr/bin/pager" to "/etc/alternatives/pager",
        "usr/bin/which" to "/etc/alternatives/which",
        "usr/sbin/rmt" to "/etc/alternatives/rmt",
        "etc/systemd/system/multi-user.target.wants/e2scrub_reap.service" to
            "/lib/systemd/system/e2scrub_reap.service",
        "etc/systemd/system/timers.target.wants/apt-daily-upgrade.timer" to
            "/lib/systemd/system/apt-daily-upgrade.timer",
        "etc/systemd/system/timers.target.wants/apt-daily.timer" to
            "/lib/systemd/system/apt-daily.timer",
        "etc/systemd/system/timers.target.wants/dpkg-db-backup.timer" to
            "/lib/systemd/system/dpkg-db-backup.timer",
        "etc/systemd/system/timers.target.wants/e2scrub_all.timer" to
            "/lib/systemd/system/e2scrub_all.timer",
        "etc/systemd/system/timers.target.wants/fstrim.timer" to
            "/lib/systemd/system/fstrim.timer",
        "etc/systemd/system/timers.target.wants/motd-news.timer" to
            "/lib/systemd/system/motd-news.timer",
        "var/minis/workspace" to "/workspace",
        "var/minis/attachments" to "/workspace/attachments",
        "var/minis/offloads" to "/workspace/offloads",
        "var/minis/browser" to "/workspace/browser",
        "var/minis/memory" to "/memory",
        "var/minis/skills" to "/skills",
        "var/minis/shared" to "/shared",
        "var/run" to "/run",
        "var/lock" to "/run/lock",
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
            readBounded(rootfs, MAX_ARCHIVE_BYTES)
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
        val expectedRevision = manifest.rootfsVersion
            .substringAfter("-r")
            .substringBefore("-")
            .toIntOrNull()
        if (metadata.optString("distro") != "ubuntu" ||
            !metadata.optString("version").startsWith("24.04") ||
            metadata.optString("arch") != "arm64" ||
            metadata.optString("profile") != "base" ||
            metadata.optInt("revision", -1) != expectedRevision
        ) {
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
        val hardLinks = mutableListOf<Pair<String, String>>()
        val regularNames = mutableSetOf<String>()
        val symlinkTargets = mutableMapOf<String, String>()
        var metadataText: String? = null
        var pendingName: String? = null
        var pendingLink: String? = null
        var globalName: String? = null
        var globalLink: String? = null
        var expandedBytes = 0L
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
                    pendingName = out.toString(Charsets.UTF_8.name()).trimEnd('\u0000')
                }
                'x' -> {
                    val out = ByteArrayOutputStream()
                    readEntryData(tar, size) { out.write(it) }
                    val records = out.toByteArray()
                    pendingName = parsePaxField(records, "path")
                    pendingLink = parsePaxField(records, "linkpath")
                }
                'g' -> {
                    val out = ByteArrayOutputStream()
                    readEntryData(tar, size) { out.write(it) }
                    val records = out.toByteArray()
                    parsePaxField(records, "path")?.let { globalName = it }
                    parsePaxField(records, "linkpath")?.let { globalLink = it }
                }
                '0', '\u0000', '1', '2', '5' -> {
                    val raw = pendingName
                        ?: globalName
                        ?: if (prefix.isNotEmpty()) "$prefix/$rawName" else rawName
                    pendingName = null
                    val name = normalizeArchivePath(raw)
                    if (name == null) {
                        if (type != '5') throw IllegalArgumentException("rootfs archive has a non-directory root entry")
                        pendingLink = null
                        skipEntryData(tar, size)
                        continue
                    }
                    if (!names.add(name)) {
                        throw IllegalArgumentException("rootfs archive contains duplicate entry: $name")
                    }
                    if (names.size > MAX_ENTRIES) {
                        throw IllegalArgumentException("rootfs archive contains too many entries")
                    }
                    if (name in REQUIRED_REAL_DIRECTORIES && type != '5') {
                        throw IllegalArgumentException("rootfs layout entry is not a real directory: $name")
                    }
                    if (name in OPTIONAL_REAL_DIRECTORIES && type != '5') {
                        throw IllegalArgumentException("rootfs optional directory is not real: $name")
                    }
                    if (name in REQUIRED_REGULAR_FILES && type !in listOf('0', '\u0000')) {
                        throw IllegalArgumentException("rootfs required file is not regular: $name")
                    }
                    if (type in listOf('0', '\u0000')) regularNames += name
                    if (type == '2') {
                        val target = pendingLink ?: globalLink ?: extractString(header, 157, 100)
                        pendingLink = null
                        symlinkTargets[name] = normalizeLinkTarget(name, target)
                    } else if (type == '1') {
                        val target = pendingLink ?: globalLink ?: extractString(header, 157, 100)
                        pendingLink = null
                        val normalized = normalizeArchivePath(target)
                            ?: throw IllegalArgumentException("hardlink target is the archive root: $name")
                        hardLinks += name to normalized
                    } else {
                        if (pendingLink != null) {
                            throw IllegalArgumentException("rootfs archive has an unused long link target")
                        }
                        if (type in listOf('0', '\u0000')) {
                            if (size > MAX_EXPANDED_BYTES - expandedBytes) {
                                throw IllegalArgumentException("rootfs archive expands beyond $MAX_EXPANDED_BYTES bytes")
                            }
                            expandedBytes += size
                        }
                    }
                    if (name == "etc/minis/rootfs.json" && type in listOf('0', '\u0000')) {
                        val out = ByteArrayOutputStream()
                        readEntryData(tar, size) { out.write(it) }
                        metadataText = out.toString(Charsets.UTF_8.name())
                    } else {
                        skipEntryData(tar, size)
                    }
                }
                'K' -> {
                    val out = ByteArrayOutputStream()
                    readEntryData(tar, size) { out.write(it) }
                    pendingLink = out.toString(Charsets.UTF_8.name()).trimEnd('\u0000')
                }
                else -> throw IllegalArgumentException("rootfs archive contains unsupported node type: $type")
            }
        }
        if (pendingName != null || pendingLink != null) {
            throw IllegalArgumentException("rootfs archive has an incomplete extended entry")
        }
        names.forEach { name ->
            val parent = name.substringBeforeLast('/', "")
            resolveArchivePath(parent, symlinkTargets)
        }
        symlinkTargets.values.forEach { target ->
            if (!target.startsWith('/')) resolveArchivePath(target, symlinkTargets)
        }
        hardLinks.forEach { (name, target) ->
            if (target !in regularNames) {
                throw IllegalArgumentException("hardlink target is not a regular archive file: $name -> $target")
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

    private fun readBounded(input: InputStream, maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            if (total > maxBytes) throw IllegalArgumentException("input exceeds $maxBytes bytes")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun normalizeArchivePath(raw: String): String? {
        var value = raw.trim()
        while (value.startsWith("./")) value = value.removePrefix("./")
        value = value.removeSuffix("/")
        if (value.isEmpty() || value == ".") return null
        require(!value.startsWith('/') && !value.contains('\u0000')) {
            "rootfs archive contains an unsafe path: $raw"
        }
        val parts = value.split('/')
        require(parts.none { it.isEmpty() || it == "." || it == ".." || it.any { c -> c.isISOControl() } }) {
            "rootfs archive contains an unsafe path: $raw"
        }
        return parts.joinToString("/")
    }

    private fun resolveArchivePath(path: String, symlinkTargets: Map<String, String>) {
        if (path.isEmpty()) return
        var components = path.split('/').toMutableList()
        val visited = mutableSetOf<String>()
        while (true) {
            var replaced = false
            for (index in 1..components.size) {
                val prefix = components.subList(0, index).joinToString("/")
                val target = symlinkTargets[prefix] ?: continue
                require(!target.startsWith('/')) {
                    "rootfs archive path resolves through an absolute link: $path"
                }
                require(visited.add(prefix)) {
                    "rootfs archive path contains a symlink cycle: $path"
                }
                components = (target.split('/') + components.drop(index)).toMutableList()
                replaced = true
                break
            }
            if (!replaced) return
        }
    }

    private fun normalizeLinkTarget(entry: String, target: String): String {
        require(target.isNotEmpty() && !target.contains('\u0000')) {
            "rootfs archive contains an unsafe link: $entry -> $target"
        }
        if (target.startsWith('/')) {
            require(ALLOWED_ABSOLUTE_GUEST_LINKS[entry] == target) {
                "rootfs archive contains an unsafe link: $entry -> $target"
            }
            return target
        }
        val parts = entry.split('/').toMutableList().also { it.removeAt(it.lastIndex) }
        target.split('/').forEach { component ->
            when {
                component.isEmpty() || component == "." -> Unit
                component == ".." -> require(parts.isNotEmpty()) {
                    "rootfs archive contains an unsafe link: $entry -> $target"
                }.also { parts.removeAt(parts.lastIndex) }
                component.any { c -> c.isISOControl() } -> require(false) {
                    "rootfs archive contains an unsafe link: $entry -> $target"
                }
                else -> parts += component
            }
        }
        require(parts.isNotEmpty()) {
            "rootfs archive contains an unsafe link: $entry -> $target"
        }
        return parts.joinToString("/")
    }

    private fun parsePaxField(records: ByteArray, field: String): String? {
        var offset = 0
        while (offset < records.size) {
            val space = (offset until records.size).firstOrNull { records[it] == ' '.code.toByte() }
                ?: throw IllegalArgumentException("PAX record length is missing")
            var length = 0
            for (index in offset until space) {
                val digit = records[index].toInt() - '0'.code
                require(digit in 0..9) { "PAX record length is invalid" }
                length = try {
                    Math.addExact(Math.multiplyExact(length, 10), digit)
                } catch (_: ArithmeticException) {
                    throw IllegalArgumentException("PAX record length overflows")
                }
            }
            val end = try {
                Math.addExact(offset, length)
            } catch (_: ArithmeticException) {
                throw IllegalArgumentException("PAX record length overflows")
            }
            require(length > 0 && end > space + 1 && end <= records.size) {
                "PAX record length is invalid"
            }
            val recordBytes = records.copyOfRange(space + 1, end)
            require(recordBytes.last() == '\n'.code.toByte()) { "PAX record is not newline-terminated" }
            val record = String(recordBytes, 0, recordBytes.size - 1, Charsets.UTF_8)
            val eq = record.indexOf('=')
            if (eq > 0 && record.substring(0, eq) == field) {
                return record.substring(eq + 1)
            }
            offset = end
        }
        return null
    }

    private fun readEntryData(
        input: InputStream,
        size: Long,
        consumer: (ByteArray) -> Unit,
    ) {
        require(size in 0..MAX_ENTRY_BYTES) { "tar entry exceeds $MAX_ENTRY_BYTES bytes" }
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
        var index = offset
        while (index < end && header[index] == ' '.code.toByte()) index++
        var value = 0L
        var digits = 0
        while (index < end) {
            val c = header[index].toInt()
            if (c == 0 || c == ' '.code) break
            val digit = c - '0'.code
            require(digit in 0..7) { "tar octal field is invalid" }
            value = try {
                Math.addExact(Math.multiplyExact(value, 8L), digit.toLong())
            } catch (_: ArithmeticException) {
                throw IllegalArgumentException("tar octal field overflows")
            }
            digits++
            index++
        }
        require(digits > 0) { "tar octal field is empty" }
        while (index < end) {
            require(header[index] == 0.toByte() || header[index] == ' '.code.toByte()) {
                "tar octal field has trailing data"
            }
            index++
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
