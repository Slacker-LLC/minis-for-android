package com.openminis.app.sandbox

import android.content.Context
import android.util.Log
import com.openminis.app.runtime.minisd.MinisdProtocol
import com.openminis.app.runtime.ubuntu.RootfsHealth
import com.openminis.app.runtime.ubuntu.RootfsHealthCode
import com.openminis.app.runtime.ubuntu.UbuntuRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset

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

    val rootfsDir: File = File(MinisdProtocol.DEFAULT_ROOTFS)

    val isInstalled: Boolean
        get() = _installState.value is RootfsInstallState.Installed

    private val _installState = MutableStateFlow<RootfsInstallState>(RootfsInstallState.Idle)
    val installState: StateFlow<RootfsInstallState> = _installState.asStateFlow()

    suspend fun checkHealth(): RootfsHealth = withContext(Dispatchers.IO) {
        if (!com.openminis.app.runtime.ubuntu.UbuntuRuntime.isInitialized) {
            com.openminis.app.runtime.ubuntu.UbuntuRuntime.init(context)
        }
        com.openminis.app.runtime.ubuntu.UbuntuRuntime.inspectRootfs()
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

        _installState.value = RootfsInstallState.Extracting(0f)
        // RuntimeDistributionManager owns staging, validation, switching, and
        // rollback. RootfsManager must not recreate those privileged actions.
        val started = UbuntuRuntime.start()
        if (!started.statusFresh || !started.running) {
            _installState.value = RootfsInstallState.Failed(
                started.lastError ?: "rootfs deployment did not start Ubuntu",
            )
            return@withContext
        }

        _installState.value = RootfsInstallState.Finalizing
        val after = checkHealth()
        if (after.healthy) {
            _installState.value = RootfsInstallState.Installed
        } else {
            _installState.value = RootfsInstallState.Failed(
                "rootfs recovery completed but validation failed: ${after.detail}",
            )
        }
    }

    suspend fun installProotIfNeeded() = withContext(Dispatchers.IO) { Unit }

    suspend fun reset(keepUserData: Boolean = false): File? = withContext(Dispatchers.IO) {
        if (keepUserData) Log.i(TAG, "reset: persistent user data is external to rootfs and will be preserved")
        if (!UbuntuRuntime.isInitialized) {
            UbuntuRuntime.init(context)
        }
        val result = UbuntuRuntime.resetRootfs()
        if (result.outcome != com.openminis.app.runtime.distribution.RuntimeDistributionManager.DeploymentOutcome.RESET) {
            _installState.value = RootfsInstallState.Failed(result.detail)
            throw IllegalStateException(result.detail)
        }
        _installState.value = RootfsInstallState.Idle
        null
    }

    suspend fun getRootfsSize(): Long = checkHealth().sizeBytes ?: 0L

    suspend fun restoreUserData(backupDir: File) = withContext(Dispatchers.IO) {
        Log.i(TAG, "restoreUserData ignored for ${backupDir.path}: persistent data is not stored in rootfs")
    }

    fun ensureSessionDirs(sessionId: String) {
        com.openminis.app.runtime.ubuntu.UbuntuPaths.ensureSessionDirs(sessionId)
    }

    fun refreshDns() = Unit

    suspend fun applyDefaultMountOverlay() = withContext(Dispatchers.IO) { Unit }

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
        internal const val STAGED_ROOTFS_ARCHIVE =
            com.openminis.app.runtime.ubuntu.RuntimeProvision.STAGED_ROOTFS_ARCHIVE
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
            commands += "ROOTFS=${shellQuote(rootfs)}"
            commands += "ARCHIVE=${shellQuote(archive)}"
            commands += "PARENT=\"\${ROOTFS%/*}\""
            commands += "NEW=\"\$PARENT/rootfs.recovery.\$\$\""
            commands += "OLD=\"\$PARENT/rootfs.failed.\$\$\""
            commands += "[ -s \"\$ARCHIVE\" ] || { echo 'staged rootfs archive missing or empty' >&2; exit 71; }"
            commands += "rm -rf \"\$NEW\" \"\$OLD\""
            commands += "mkdir -p \"\$NEW\" || exit 72"
            commands += "tar -xzf \"\$ARCHIVE\" -C \"\$NEW\" || { rm -rf \"\$NEW\"; exit 73; }"
            REQUIRED_LAYOUT.forEach { rel ->
                commands += "[ -e \"\$NEW/$rel\" ] || { echo 'recovery rootfs missing $rel' >&2; rm -rf \"\$NEW\"; exit 74; }"
            }
            commands += "if [ ! -x \"\$NEW/bin/bash\" ] && [ ! -x \"\$NEW/usr/bin/bash\" ] && [ ! -x \"\$NEW/bin/sh\" ]; then rm -rf \"\$NEW\"; exit 75; fi"
            commands += "META=\"\$NEW/etc/minis/rootfs.json\""
            commands += "grep -Eq '\"distro\"[[:space:]]*:[[:space:]]*\"ubuntu\"' \"\$META\" || { rm -rf \"\$NEW\"; exit 76; }"
            commands += "grep -Eq '\"version\"[[:space:]]*:[[:space:]]*\"24\\.04' \"\$META\" || { rm -rf \"\$NEW\"; exit 77; }"
            commands += "grep -Eq '\"release\"[[:space:]]*:[[:space:]]*\"24\\.04' \"\$META\" || { rm -rf \"\$NEW\"; exit 78; }"
            commands += "grep -Eq '\"arch\"[[:space:]]*:[[:space:]]*\"arm64\"' \"\$META\" || { rm -rf \"\$NEW\"; exit 79; }"
            commands += "grep -Eq '\"profile\"[[:space:]]*:[[:space:]]*\"base\"' \"\$META\" || { rm -rf \"\$NEW\"; exit 80; }"
            commands += "grep -Eq '\"upstream_sha256\"[[:space:]]*:[[:space:]]*\"[0-9a-fA-F]{64}\"' \"\$META\" || { rm -rf \"\$NEW\"; exit 81; }"
            commands += "if [ -e \"\$ROOTFS\" ]; then mv \"\$ROOTFS\" \"\$OLD\" || { rm -rf \"\$NEW\"; exit 82; }; fi"
            commands += "if ! mv \"\$NEW\" \"\$ROOTFS\"; then [ -e \"\$OLD\" ] && mv \"\$OLD\" \"\$ROOTFS\" 2>/dev/null || true; exit 83; fi"
            commands += "rm -rf \"\$OLD\""
            commands += "echo 'MINIS_ROOTFS:REPAIRED'"
            return commands.joinToString("\n")
        }

        internal fun shellQuote(value: String): String =
            "'" + value.replace("'", "'\"'\"'") + "'"
    }
}
