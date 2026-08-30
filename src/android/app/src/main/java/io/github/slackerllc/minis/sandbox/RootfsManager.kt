package io.github.slackerllc.minis.sandbox

import android.content.Context
import android.util.Log
import io.github.slackerllc.minis.runtime.minisd.MinisdProtocol
import io.github.slackerllc.minis.runtime.ubuntu.RootfsHealth
import io.github.slackerllc.minis.runtime.ubuntu.RootfsHealthCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

sealed class RootfsInstallState {
    object Idle : RootfsInstallState()
    object Preparing : RootfsInstallState()
    data class Extracting(val progress: Float) : RootfsInstallState()
    object Finalizing : RootfsInstallState()
    object Installed : RootfsInstallState()
    data class Failed(val error: String) : RootfsInstallState()
}

/**
 * Authoritative health/recovery manager for the privileged Ubuntu rootfs.
 * Persistent Agent data lives outside the rootfs; replacing the rootfs never
 * migrates workspace/memory/skills/shared data here.
 */
class RootfsManager private constructor(private val context: Context) {

    /** Current version when present; legacy fixed path is the migration fallback. */
    val rootfsDir: File
        get() = activeRootfsDir()

    val isInstalled: Boolean
        get() = _installState.value is RootfsInstallState.Installed

    private val _installState = MutableStateFlow<RootfsInstallState>(RootfsInstallState.Idle)
    val installState: StateFlow<RootfsInstallState> = _installState.asStateFlow()

    suspend fun checkHealth(): RootfsHealth = withContext(Dispatchers.IO) {
        val su = findSu()
            ?: return@withContext RootfsHealth(
                RootfsHealthCode.ROOT_UNAVAILABLE,
                "no executable su found",
            )
        val result = runSu(su, probeCommand(), HEALTH_TIMEOUT_MS)
        if (!result.completed) {
            return@withContext RootfsHealth(
                RootfsHealthCode.ROOT_UNAVAILABLE,
                result.error ?: "rootfs health probe timed out",
            )
        }
        if (result.exitCode != 0) {
            return@withContext RootfsHealth(
                RootfsHealthCode.ROOT_UNAVAILABLE,
                result.output.ifBlank { "rootfs health probe exited ${result.exitCode}" },
            )
        }
        evaluateProbeOutput(result.output)
    }

    suspend fun installIfNeeded() = withContext(Dispatchers.IO) {
        _installState.value = RootfsInstallState.Preparing
        val before = checkHealth()
        if (before.healthy) {
            _installState.value = RootfsInstallState.Installed
            return@withContext
        }
        if (before.code == RootfsHealthCode.ROOT_UNAVAILABLE) {
            _installState.value = RootfsInstallState.Failed(before.detail)
            return@withContext
        }

        val su = findSu()
        if (su == null) {
            _installState.value = RootfsInstallState.Failed("Root unavailable: no executable su found")
            return@withContext
        }

        _installState.value = RootfsInstallState.Extracting(0f)
        val repaired = runSu(su, repairCommand(), REPAIR_TIMEOUT_MS)
        if (!repaired.completed || repaired.exitCode != 0) {
            val detail = repaired.error
                ?: repaired.output.ifBlank { "rootfs recovery exited ${repaired.exitCode}" }
            _installState.value = RootfsInstallState.Failed(detail)
            return@withContext
        }

        _installState.value = RootfsInstallState.Finalizing
        val after = checkHealth()
        if (after.healthy) {
            _installState.value = RootfsInstallState.Installed
        } else {
            val rollback = runSu(su, rollbackCommand(), HEALTH_TIMEOUT_MS)
            val restored = checkHealth()
            val rollbackDetail = if (rollback.completed && rollback.exitCode == 0 && restored.healthy) {
                "previous rootfs restored"
            } else {
                "rollback failed: ${rollback.error ?: rollback.output.ifBlank { "exit ${rollback.exitCode}" }}"
            }
            _installState.value = RootfsInstallState.Failed(
                "rootfs recovery completed but validation failed: ${after.detail}; $rollbackDetail",
            )
        }
    }

    suspend fun installProotIfNeeded() = withContext(Dispatchers.IO) { Unit }

    suspend fun reset(keepUserData: Boolean = false): File? = withContext(Dispatchers.IO) {
        if (keepUserData) Log.i(TAG, "reset: persistent user data is external to rootfs and will be preserved")
        val su = findSu() ?: run {
            _installState.value = RootfsInstallState.Failed("Root unavailable: no executable su found")
            return@withContext null
        }
        val result = runSu(
            su,
            "rm -rf ${shellQuote(MinisdProtocol.DEFAULT_ROOTFS)} ${shellQuote(ROOTFS_RUNTIME_ROOT)}",
            HEALTH_TIMEOUT_MS,
        )
        if (result.completed && result.exitCode == 0) {
            _installState.value = RootfsInstallState.Idle
        } else {
            _installState.value = RootfsInstallState.Failed(
                result.error ?: result.output.ifBlank { "rootfs reset failed" },
            )
        }
        null
    }

    suspend fun getRootfsSize(): Long = withContext(Dispatchers.IO) {
        val su = findSu() ?: return@withContext 0L
        val command = "du -sk ${shellQuote(activeRootfsDir().path)} 2>/dev/null | awk '{print \$1}'"
        val result = runSu(su, command, HEALTH_TIMEOUT_MS)
        if (!result.completed || result.exitCode != 0) return@withContext 0L
        val kib = result.output.lineSequence().mapNotNull { it.trim().toLongOrNull() }.firstOrNull() ?: 0L
        kib * 1024L
    }

    suspend fun restoreUserData(backupDir: File) = withContext(Dispatchers.IO) {
        Log.i(TAG, "restoreUserData ignored for ${backupDir.path}: persistent data is not stored in rootfs")
    }

    fun ensureSessionDirs(sessionId: String) {
        io.github.slackerllc.minis.runtime.ubuntu.UbuntuPaths.ensureSessionDirs(
            context.filesDir,
            sessionId,
        )
    }

    fun refreshDns() = Unit

    suspend fun applyDefaultMountOverlay() = withContext(Dispatchers.IO) { Unit }

    private fun findSu(): String? {
        val direct = SU_CANDIDATES.firstOrNull { File(it).canExecute() }
        if (direct != null) return direct
        return System.getenv("PATH").orEmpty()
            .split(File.pathSeparatorChar)
            .asSequence()
            .map { File(it, "su") }
            .firstOrNull { it.canExecute() }
            ?.absolutePath
    }

    private data class RootCommandResult(
        val completed: Boolean,
        val exitCode: Int,
        val output: String,
        val error: String? = null,
    )

    private fun runSu(su: String, command: String, timeoutMs: Long): RootCommandResult {
        val process = try {
            ProcessBuilder(su, "-c", command)
                .redirectErrorStream(true)
                .start()
        } catch (t: Throwable) {
            return RootCommandResult(false, -1, "", "failed to start su: ${t.message}")
        }
        return try {
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor(1, TimeUnit.SECONDS)
                RootCommandResult(false, -1, "", "root command timed out")
            } else {
                val output = runCatching {
                    process.inputStream.bufferedReader().use { it.readText().trim() }
                }.getOrDefault("")
                RootCommandResult(true, process.exitValue(), output)
            }
        } finally {
            process.destroy()
        }
    }

    private fun activeRootfsDir(): File {
        val current = File(ROOTFS_CURRENT)
        val resolved = runCatching { current.canonicalFile }.getOrNull()
        val versions = runCatching { File(ROOTFS_VERSIONS).canonicalFile }.getOrNull()
        val revision = resolved?.name.orEmpty()
        val validRevision = revision.matches(Regex("^ubuntu-24\\.04-[0-9A-Fa-f]{16}$"))
        return if (resolved != null && versions != null && resolved.parentFile == versions &&
            validRevision && resolved.isDirectory &&
            File(resolved, "etc/minis/rootfs.json").isFile
        ) {
            resolved
        } else {
            File(MinisdProtocol.DEFAULT_ROOTFS)
        }
    }

    private fun probeCommand(): String = buildProbeCommand(activeRootfsDir().path)

    private fun repairCommand(): String = buildRepairCommand(
        rootfs = activeRootfsDir().path,
        archive = STAGED_ROOTFS_ARCHIVE,
    )

    private fun rollbackCommand(): String = buildRollbackCommand()

    // --- POSIX tar extraction (kept for TarExtractionTest) ---

    internal fun extractTar(input: InputStream, targetDir: File) {
        val header = ByteArray(512)
        while (true) {
            val bytesRead = readFully(input, header)
            if (bytesRead < 512) break
            if (header.all { it == 0.toByte() }) break
            val name = extractString(header, 0, 100)
            val modeOctal = extractString(header, 100, 8)
            val sizeOctal = extractString(header, 124, 12)
            val typeFlag = header[156].toInt().toChar()
            val linkName = extractString(header, 157, 100)
            val mode = modeOctal.trim().toIntOrNull(8) ?: 0
            val prefix = extractString(header, 345, 155)
            val fullName = if (prefix.isNotEmpty()) "$prefix/$name" else name
            if (fullName.isEmpty()) break
            val size = sizeOctal.trim().toLongOrNull(8) ?: 0L
            val outFile = File(targetDir, fullName)
            when (typeFlag) {
                '5', 'D' -> outFile.mkdirs()
                '2' -> {
                    outFile.parentFile?.mkdirs()
                    try {
                        java.nio.file.Files.createSymbolicLink(
                            outFile.toPath(),
                            java.nio.file.Paths.get(linkName),
                        )
                    } catch (_: Exception) {
                        Log.w(TAG, "Failed to create symlink: $fullName -> $linkName")
                    }
                }
                '0', '\u0000' -> {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { output ->
                        var remaining = size
                        val buf = ByteArray(8192)
                        while (remaining > 0) {
                            val toRead = minOf(buf.size.toLong(), remaining).toInt()
                            val n = input.read(buf, 0, toRead)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            remaining -= n
                        }
                    }
                    if (mode and 0b001_001_001 != 0) outFile.setExecutable(true, false)
                    val remainder = (size % 512).toInt()
                    if (remainder != 0) skipFully(input, (512 - remainder).toLong())
                    continue
                }
                '1' -> {
                    outFile.parentFile?.mkdirs()
                    val linkTarget = File(targetDir, linkName)
                    if (linkTarget.exists()) linkTarget.copyTo(outFile, overwrite = true)
                }
                else -> Unit
            }
            if (typeFlag != '0' && typeFlag != '\u0000' && size > 0) {
                val blocks = (size + 511) / 512 * 512
                skipFully(input, blocks)
            }
        }
    }

    internal fun extractString(header: ByteArray, offset: Int, length: Int): String {
        val end = minOf(offset + length, header.size)
        var actualEnd = offset
        for (i in offset until end) {
            if (header[i] == 0.toByte()) break
            actualEnd = i + 1
        }
        return String(header, offset, actualEnd - offset, Charset.forName("UTF-8"))
    }

    internal fun readFully(input: InputStream, buf: ByteArray): Int {
        var offset = 0
        while (offset < buf.size) {
            val n = input.read(buf, offset, buf.size - offset)
            if (n < 0) return offset
            offset += n
        }
        return offset
    }

    internal fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        val buf = ByteArray(8192)
        while (remaining > 0) {
            val toRead = minOf(buf.size.toLong(), remaining).toInt()
            val n = input.read(buf, 0, toRead)
            if (n < 0) break
            remaining -= n
        }
    }

    companion object {
        private const val TAG = "RootfsManager"
        private const val HEALTH_TIMEOUT_MS = 15_000L
        private const val REPAIR_TIMEOUT_MS = 180_000L
        internal const val STAGED_ROOTFS_ARCHIVE = "/data/local/tmp/ubuntu-arm64-rootfs.tar.gz"
        /** Versioned control-plane state; persistent user data stays outside this tree. */
        internal const val RUNTIME_ROOT = "/data/adb/minis/runtime"
        internal const val ROOTFS_RUNTIME_ROOT = "/data/adb/minis/runtime/rootfs"
        internal const val ROOTFS_VERSIONS = "/data/adb/minis/runtime/rootfs/versions"
        internal const val ROOTFS_STAGING = "/data/adb/minis/runtime/rootfs/staging"
        internal const val ROOTFS_CURRENT = "/data/adb/minis/runtime/rootfs/current"
        private val SU_CANDIDATES = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/data/adb/ksu/bin/su",
            "/debug_ramdisk/su",
        )
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

        private var instance: RootfsManager? = null

        fun getInstance(context: Context): RootfsManager =
            instance ?: RootfsManager(context.applicationContext).also { instance = it }

        internal fun buildProbeCommand(rootfs: String): String {
            val commands = mutableListOf<String>()
            commands += "ROOTFS=${shellQuote(rootfs)}"
            commands += "[ -d \"\$ROOTFS\" ] || { echo 'MINIS_ROOTFS:MISSING'; exit 0; }"
            REQUIRED_LAYOUT.forEach { rel ->
                commands += "[ -e \"\$ROOTFS/$rel\" ] || { echo 'MINIS_ROOTFS:CORRUPT:$rel'; exit 0; }"
            }
            commands += "if [ ! -x \"\$ROOTFS/bin/bash\" ] && [ ! -x \"\$ROOTFS/usr/bin/bash\" ] && [ ! -x \"\$ROOTFS/bin/sh\" ]; then echo 'MINIS_ROOTFS:CORRUPT:shell'; exit 0; fi"
            commands += "CURRENT=${shellQuote(ROOTFS_CURRENT)}"
            commands += "if [ -L \"\$CURRENT\" ]; then CURRENT_TARGET=\$(readlink \"\$CURRENT\" 2>/dev/null || true); printf '%s\\n' \"\$CURRENT_TARGET\" | grep -Eq '^/data/adb/minis/runtime/rootfs/versions/ubuntu-24\\.04-[0-9A-Fa-f]{16}$' || { echo 'MINIS_ROOTFS:CORRUPT:current'; exit 0; }; [ -d \"\$CURRENT_TARGET\" ] && [ -e \"\$CURRENT_TARGET/etc/minis/rootfs.json\" ] || { echo 'MINIS_ROOTFS:CORRUPT:current'; exit 0; }; fi"
            commands += "echo 'MINIS_ROOTFS:METADATA'"
            commands += "cat \"\$ROOTFS/etc/minis/rootfs.json\""
            return commands.joinToString("\n")
        }

        internal fun evaluateProbeOutput(output: String): RootfsHealth {
            val lines = output.lineSequence().map { it.trim() }.toList()
            val markerIndex = lines.indexOfFirst { it.startsWith("MINIS_ROOTFS:") }
            if (markerIndex < 0) {
                return RootfsHealth(RootfsHealthCode.CORRUPT, "rootfs probe returned no marker")
            }
            val marker = lines[markerIndex]
            if (marker == "MINIS_ROOTFS:MISSING") {
                return RootfsHealth(RootfsHealthCode.MISSING, "Ubuntu rootfs is missing")
            }
            if (marker.startsWith("MINIS_ROOTFS:CORRUPT:")) {
                return RootfsHealth(
                    RootfsHealthCode.CORRUPT,
                    "rootfs missing required layout entry: ${marker.substringAfterLast(':')}",
                )
            }
            if (marker != "MINIS_ROOTFS:METADATA") {
                return RootfsHealth(RootfsHealthCode.CORRUPT, "unexpected rootfs probe marker: $marker")
            }
            val rawMetadata = lines.drop(markerIndex + 1).joinToString("\n").trim()
            if (rawMetadata.isEmpty()) {
                return RootfsHealth(RootfsHealthCode.CORRUPT, "rootfs metadata is empty")
            }
            val metadata = try {
                JSONObject(rawMetadata)
            } catch (t: Throwable) {
                return RootfsHealth(RootfsHealthCode.CORRUPT, "rootfs metadata is invalid JSON: ${t.message}")
            }
            return validateMetadata(metadata)
        }

        internal fun validateMetadata(metadata: JSONObject): RootfsHealth {
            val distro = metadata.optString("distro")
            val version = metadata.optString("version")
            val release = metadata.optString("release")
            val arch = metadata.optString("arch")
            val profile = metadata.optString("profile")
            val upstream = metadata.optString("upstream_sha256")
            val compatible = distro == "ubuntu" &&
                version.startsWith("24.04") &&
                release.startsWith("24.04") &&
                arch == "arm64" &&
                profile == "base" &&
                upstream.matches(Regex("^[0-9a-fA-F]{64}$"))
            return if (compatible) {
                RootfsHealth(RootfsHealthCode.HEALTHY, "Ubuntu rootfs metadata/layout valid", metadata)
            } else {
                RootfsHealth(
                    RootfsHealthCode.INCOMPATIBLE,
                    "incompatible rootfs metadata: distro=$distro version=$version release=$release arch=$arch profile=$profile",
                    metadata,
                )
            }
        }

        internal fun buildRepairCommand(rootfs: String, archive: String): String {
            val commands = mutableListOf<String>()
            commands += "LEGACY_ROOTFS=${shellQuote(rootfs)}"
            commands += "ARCHIVE=${shellQuote(archive)}"
            commands += "RUNTIME_ROOT=${shellQuote(ROOTFS_RUNTIME_ROOT)}"
            commands += "VERSIONS=\"\$RUNTIME_ROOT/versions\""
            commands += "STAGING=\"\$RUNTIME_ROOT/staging\""
            commands += "NEW=\"\$STAGING/rootfs.recovery.\$\$\""
            commands += "CURRENT=\"\$RUNTIME_ROOT/current\""
            commands += "CURRENT_NEXT=\"\$RUNTIME_ROOT/current.next.\$\$\""
            commands += "PREVIOUS=\"\$RUNTIME_ROOT/previous\""
            commands += "PREVIOUS_NEXT=\"\$RUNTIME_ROOT/previous.next.\$\$\""
            commands += "[ -s \"\$ARCHIVE\" ] || { echo 'staged rootfs archive missing or empty' >&2; exit 71; }"
            commands += "rm -rf \"\$NEW\" \"\$CURRENT_NEXT\" \"\$PREVIOUS_NEXT\""
            commands += "mkdir -p \"\$VERSIONS\" \"\$STAGING\" || exit 72"
            commands += "mkdir -p \"\$NEW\" || exit 73"
            commands += "tar -xzf \"\$ARCHIVE\" -C \"\$NEW\" || { rm -rf \"\$NEW\"; exit 74; }"
            REQUIRED_LAYOUT.forEach { rel ->
                commands += "[ -e \"\$NEW/$rel\" ] || { echo 'recovery rootfs missing $rel' >&2; rm -rf \"\$NEW\"; exit 75; }"
            }
            commands += "if [ ! -x \"\$NEW/bin/bash\" ] && [ ! -x \"\$NEW/usr/bin/bash\" ] && [ ! -x \"\$NEW/bin/sh\" ]; then rm -rf \"\$NEW\"; exit 76; fi"
            commands += "META=\"\$NEW/etc/minis/rootfs.json\""
            commands += "grep -Eq '\"distro\"[[:space:]]*:[[:space:]]*\"ubuntu\"' \"\$META\" || { rm -rf \"\$NEW\"; exit 77; }"
            commands += "grep -Eq '\"version\"[[:space:]]*:[[:space:]]*\"24\\.04' \"\$META\" || { rm -rf \"\$NEW\"; exit 78; }"
            commands += "grep -Eq '\"release\"[[:space:]]*:[[:space:]]*\"24\\.04' \"\$META\" || { rm -rf \"\$NEW\"; exit 79; }"
            commands += "grep -Eq '\"arch\"[[:space:]]*:[[:space:]]*\"arm64\"' \"\$META\" || { rm -rf \"\$NEW\"; exit 80; }"
            commands += "grep -Eq '\"profile\"[[:space:]]*:[[:space:]]*\"base\"' \"\$META\" || { rm -rf \"\$NEW\"; exit 81; }"
            commands += "grep -Eq '\"upstream_sha256\"[[:space:]]*:[[:space:]]*\"[0-9a-fA-F]{64}\"' \"\$META\" || { rm -rf \"\$NEW\"; exit 82; }"
            commands += "UPSTREAM_SHA=\$(grep -Eo '\"upstream_sha256\"[[:space:]]*:[[:space:]]*\"[0-9a-fA-F]{64}\"' \"\$META\" | sed -E 's/.*\"([0-9a-fA-F]{64})\".*/\\1/' | head -n 1)"
            commands += "REVISION=\"ubuntu-24.04-\$(printf '%s' \"\$UPSTREAM_SHA\" | cut -c1-16)\""
            commands += "VERSION=\"\$VERSIONS/\$REVISION\""
            commands += "if [ -e \"\$VERSION\" ]; then rm -rf \"\$NEW\"; else mv \"\$NEW\" \"\$VERSION\" || exit 83; fi"
            commands += "[ -e \"\$VERSION/etc/minis/rootfs.json\" ] || { rm -rf \"\$NEW\"; exit 84; }"
            commands += "if [ -L \"\$CURRENT\" ]; then PREVIOUS_TARGET=\$(readlink \"\$CURRENT\" 2>/dev/null || true); printf '%s\\n' \"\$PREVIOUS_TARGET\" | grep -Eq '^/data/adb/minis/runtime/rootfs/versions/ubuntu-24\\.04-[0-9A-Fa-f]{16}$' || { rm -rf \"\$NEW\"; exit 85; }; rm -f \"\$PREVIOUS_NEXT\"; ln -s \"\$PREVIOUS_TARGET\" \"\$PREVIOUS_NEXT\" || { rm -rf \"\$NEW\"; exit 86; }; mv -f \"\$PREVIOUS_NEXT\" \"\$PREVIOUS\" || { rm -rf \"\$NEW\"; exit 87; }; fi"
            commands += "if [ -e \"\$CURRENT\" ] && [ ! -L \"\$CURRENT\" ]; then rm -rf \"\$CURRENT\"; fi"
            commands += "ln -s \"\$VERSION\" \"\$CURRENT_NEXT\" || { rm -rf \"\$NEW\"; exit 88; }"
            commands += "if ! mv -f \"\$CURRENT_NEXT\" \"\$CURRENT\"; then rm -f \"\$CURRENT_NEXT\"; exit 89; fi"
            commands += "echo \"MINIS_ROOTFS:REPAIRED:\$REVISION\""
            return commands.joinToString("\n")
        }

        internal fun buildRollbackCommand(): String = listOf(
            "RUNTIME_ROOT=${shellQuote(ROOTFS_RUNTIME_ROOT)}",
            "CURRENT=\"\$RUNTIME_ROOT/current\"",
            "PREVIOUS=\"\$RUNTIME_ROOT/previous\"",
            "CURRENT_NEXT=\"\$RUNTIME_ROOT/current.rollback.\$\$\"",
            "[ -L \"\$PREVIOUS\" ] || { [ -L \"\$CURRENT\" ] && rm -f \"\$CURRENT\"; exit 0; }",
            "TARGET=\$(readlink \"\$PREVIOUS\" 2>/dev/null || true)",
            "printf '%s\\n' \"\$TARGET\" | grep -Eq '^/data/adb/minis/runtime/rootfs/versions/ubuntu-24\\.04-[0-9A-Fa-f]{16}$' || exit 91",
            "rm -f \"\$CURRENT_NEXT\"",
            "ln -s \"\$TARGET\" \"\$CURRENT_NEXT\" || exit 92",
            "mv -f \"\$CURRENT_NEXT\" \"\$CURRENT\" || { rm -f \"\$CURRENT_NEXT\"; exit 93; }",
            "echo 'MINIS_ROOTFS:ROLLED_BACK'",
        ).joinToString("\n")

        internal fun shellQuote(value: String): String =
            "'" + value.replace("'", "'\"'\"'") + "'"
    }
}
