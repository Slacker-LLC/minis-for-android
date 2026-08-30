package io.github.slackerllc.minis.sandbox

import android.content.Context
import android.util.Log
import io.github.slackerllc.minis.runtime.distribution.RuntimeDistributionManifest
import io.github.slackerllc.minis.runtime.minisd.MinisdProtocol
import io.github.slackerllc.minis.runtime.ubuntu.RootfsHealth
import io.github.slackerllc.minis.runtime.ubuntu.RootfsHealthCode
import io.github.slackerllc.minis.runtime.ubuntu.UbuntuPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.security.MessageDigest
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
 * Root-only Ubuntu rootfs health/distribution manager.
 *
 * Android never resolves `/data/adb/minis/runtime/rootfs/current` itself. Every
 * pointer/path check runs under `su`; minisd independently resolves and pins the
 * same concrete version directory before creating the keeper namespace.
 */
class RootfsManager private constructor(private val context: Context) {

    /** Control-plane pointer only. Callers must use [checkHealth] for identity. */
    val rootfsDir: File
        get() = File(ROOTFS_CURRENT)

    val isInstalled: Boolean
        get() = _installState.value is RootfsInstallState.Installed

    private val _installState = MutableStateFlow<RootfsInstallState>(RootfsInstallState.Idle)
    val installState: StateFlow<RootfsInstallState> = _installState.asStateFlow()

    suspend fun checkHealth(): RootfsHealth = withContext(Dispatchers.IO) {
        val su = findSu()
            ?: return@withContext RootfsHealth(RootfsHealthCode.ROOT_UNAVAILABLE, "no executable su found")
        val result = runSu(su, buildProbeCommand(), HEALTH_TIMEOUT_MS)
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

    /**
     * Installs the exact APK-packaged rootfs if the active concrete revision does
     * not match the authoritative runtime manifest. The source archive remains
     * inside app cache; no `/data/local/tmp` staging path is used.
     */
    suspend fun installIfNeeded() = withContext(Dispatchers.IO) {
        _installState.value = RootfsInstallState.Preparing
        val manifest = loadManifest().getOrElse {
            _installState.value = RootfsInstallState.Failed(it.message ?: "invalid runtime manifest")
            return@withContext
        }
        val before = checkHealth()
        if (before.healthy && healthMatchesManifest(before, manifest)) {
            _installState.value = RootfsInstallState.Installed
            return@withContext
        }
        if (before.code == RootfsHealthCode.ROOT_UNAVAILABLE) {
            _installState.value = RootfsInstallState.Failed(before.detail)
            return@withContext
        }
        val su = findSu() ?: run {
            _installState.value = RootfsInstallState.Failed("Root unavailable: no executable su found")
            return@withContext
        }

        val interrupted = runSu(su, buildPendingProbeCommand(), HEALTH_TIMEOUT_MS)
        if (interrupted.completed && interrupted.exitCode == 0 && interrupted.output.contains("MINIS_ROOTFS:PENDING")) {
            val rollback = runSu(su, buildRollbackPendingCommand(), HEALTH_TIMEOUT_MS)
            if (!rollback.completed || rollback.exitCode != 0) {
                _installState.value = RootfsInstallState.Failed(
                    "interrupted rootfs transaction rollback failed: ${rollback.error ?: rollback.output}",
                )
                return@withContext
            }
        }

        val archive = runCatching { materializePackagedRootfs(manifest) }.getOrElse {
            _installState.value = RootfsInstallState.Failed(it.message ?: "cannot materialize packaged rootfs")
            return@withContext
        }

        _installState.value = RootfsInstallState.Extracting(0f)
        val switched = runSu(
            su,
            buildInstallCommand(archive.absolutePath, manifest),
            REPAIR_TIMEOUT_MS,
        )
        if (!switched.completed || switched.exitCode != 0) {
            val rollback = runSu(su, buildRollbackPendingCommand(), HEALTH_TIMEOUT_MS)
            val rollbackDetail = if (rollback.completed && rollback.exitCode == 0) {
                "previous rootfs restored"
            } else {
                "rollback failed: ${rollback.error ?: rollback.output.ifBlank { "exit ${rollback.exitCode}" }}"
            }
            _installState.value = RootfsInstallState.Failed(
                "rootfs switch failed: ${switched.error ?: switched.output.ifBlank { "exit ${switched.exitCode}" }}; $rollbackDetail",
            )
            return@withContext
        }

        _installState.value = RootfsInstallState.Finalizing
        val after = checkHealth()
        if (!after.healthy || !healthMatchesManifest(after, manifest)) {
            val rollback = runSu(su, buildRollbackPendingCommand(), HEALTH_TIMEOUT_MS)
            val restored = checkHealth()
            val rollbackDetail = if (rollback.completed && rollback.exitCode == 0 && restored.healthy) {
                "previous rootfs restored"
            } else {
                "rollback failed: ${rollback.error ?: rollback.output.ifBlank { "exit ${rollback.exitCode}" }}"
            }
            _installState.value = RootfsInstallState.Failed(
                "rootfs validation failed after COMMIT: ${after.detail}; $rollbackDetail",
            )
            return@withContext
        }

        val finalized = runSu(su, "rm -f ${shellQuote(ROOTFS_PENDING)}", HEALTH_TIMEOUT_MS)
        if (!finalized.completed || finalized.exitCode != 0) {
            _installState.value = RootfsInstallState.Failed(
                "rootfs is healthy but transaction finalization failed: ${finalized.error ?: finalized.output}",
            )
            return@withContext
        }
        _installState.value = RootfsInstallState.Installed
    }

    /** Used by #51 post-provision health when a new guest fails after the switch. */
    suspend fun rollbackToPrevious(): Boolean = withContext(Dispatchers.IO) {
        val su = findSu() ?: return@withContext false
        val result = runSu(su, buildRollbackPreviousCommand(), HEALTH_TIMEOUT_MS)
        result.completed && result.exitCode == 0
    }

    suspend fun writeProvisionRevision(revision: Int): Boolean = withContext(Dispatchers.IO) {
        require(revision > 0)
        val su = findSu() ?: return@withContext false
        val result = runSu(
            su,
            listOf(
                "mkdir -p ${shellQuote(PROVISION_ROOT)} || exit 1",
                "umask 077",
                "printf '%s\\n' '$revision' > ${shellQuote(PROVISION_REVISION)}.tmp || exit 2",
                "mv -f ${shellQuote(PROVISION_REVISION)}.tmp ${shellQuote(PROVISION_REVISION)} || exit 3",
            ).joinToString("\n"),
            HEALTH_TIMEOUT_MS,
        )
        result.completed && result.exitCode == 0
    }

    suspend fun readProvisionRevision(): Int? = withContext(Dispatchers.IO) {
        val su = findSu() ?: return@withContext null
        val result = runSu(su, "cat ${shellQuote(PROVISION_REVISION)} 2>/dev/null || true", HEALTH_TIMEOUT_MS)
        if (!result.completed || result.exitCode != 0) return@withContext null
        result.output.lineSequence().mapNotNull { it.trim().toIntOrNull() }.firstOrNull()
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
            "rm -rf ${shellQuote(MinisdProtocol.DEFAULT_ROOTFS)} ${shellQuote(ROOTFS_RUNTIME_ROOT)} ${shellQuote(PROVISION_ROOT)}",
            HEALTH_TIMEOUT_MS,
        )
        if (result.completed && result.exitCode == 0) _installState.value = RootfsInstallState.Idle
        else _installState.value = RootfsInstallState.Failed(result.error ?: result.output)
        null
    }

    suspend fun getRootfsSize(): Long = withContext(Dispatchers.IO) {
        val su = findSu() ?: return@withContext 0L
        val command = "${resolveRootfsShell()}\ndu -sk \"\$ROOTFS\" 2>/dev/null | awk '{print \$1}'"
        val result = runSu(su, command, HEALTH_TIMEOUT_MS)
        if (!result.completed || result.exitCode != 0) return@withContext 0L
        val kib = result.output.lineSequence().mapNotNull { it.trim().toLongOrNull() }.firstOrNull() ?: 0L
        kib * 1024L
    }

    suspend fun restoreUserData(backupDir: File) = withContext(Dispatchers.IO) {
        Log.i(TAG, "restoreUserData ignored for ${backupDir.path}: persistent data is not stored in rootfs")
    }

    fun ensureSessionDirs(sessionId: String) {
        UbuntuPaths.ensureSessionDirs(sessionId)
    }

    fun refreshDns() = Unit
    suspend fun applyDefaultMountOverlay() = withContext(Dispatchers.IO) { Unit }

    private fun loadManifest(): Result<RuntimeDistributionManifest> = runCatching {
        context.assets.open(RuntimeDistributionManifest.ASSET_PATH)
            .bufferedReader()
            .use { RuntimeDistributionManifest.parse(it.readText()) }
    }

    private fun materializePackagedRootfs(manifest: RuntimeDistributionManifest): File {
        val dir = File(context.cacheDir, "minis-runtime").apply { mkdirs() }
        val target = File(dir, "rootfs-${manifest.rootfsSha256}.tar.gz")
        if (target.isFile && sha256(target) == manifest.rootfsSha256) return target
        val tmp = File(dir, "${target.name}.tmp-${android.os.Process.myPid()}")
        runCatching { tmp.delete() }
        context.assets.open(RuntimeDistributionManifest.ROOTFS_ASSET_PATH).use { input ->
            tmp.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        }
        val actual = sha256(tmp)
        require(actual == manifest.rootfsSha256) {
            "APK rootfs SHA-256 mismatch: actual=$actual expected=${manifest.rootfsSha256}"
        }
        if (target.exists() && !target.delete()) error("cannot replace cached rootfs artifact")
        require(tmp.renameTo(target)) { "cannot atomically cache packaged rootfs" }
        return target
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

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
            ProcessBuilder(su, "-c", command).redirectErrorStream(true).start()
        } catch (t: Throwable) {
            return RootCommandResult(false, -1, "", "failed to start su: ${t.message}")
        }
        return try {
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor(1, TimeUnit.SECONDS)
                RootCommandResult(false, -1, "", "root command timed out")
            } else {
                val output = runCatching { process.inputStream.bufferedReader().use { it.readText().trim() } }
                    .getOrDefault("")
                RootCommandResult(true, process.exitValue(), output)
            }
        } finally {
            process.destroy()
        }
    }

    // Kept for existing tar parser unit tests; production extraction is root-side tar.
    internal fun extractTar(input: InputStream, targetDir: File) {
        val header = ByteArray(512)
        while (true) {
            val bytesRead = readFully(input, header)
            if (bytesRead < 512 || header.all { it == 0.toByte() }) break
            val name = extractString(header, 0, 100)
            val mode = extractString(header, 100, 8).trim().toIntOrNull(8) ?: 0
            val size = extractString(header, 124, 12).trim().toLongOrNull(8) ?: 0L
            val typeFlag = header[156].toInt().toChar()
            val linkName = extractString(header, 157, 100)
            val prefix = extractString(header, 345, 155)
            val fullName = if (prefix.isNotEmpty()) "$prefix/$name" else name
            if (fullName.isEmpty()) break
            val outFile = File(targetDir, fullName)
            when (typeFlag) {
                '5', 'D' -> outFile.mkdirs()
                '2' -> {
                    outFile.parentFile?.mkdirs()
                    runCatching { java.nio.file.Files.createSymbolicLink(outFile.toPath(), java.nio.file.Paths.get(linkName)) }
                }
                '0', '\u0000' -> {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { output ->
                        var remaining = size
                        val buf = ByteArray(8192)
                        while (remaining > 0) {
                            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
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
            }
            if (typeFlag != '0' && typeFlag != '\u0000' && size > 0) {
                skipFully(input, ((size + 511) / 512) * 512)
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
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) break
            remaining -= n
        }
    }

    companion object {
        private const val TAG = "RootfsManager"
        private const val HEALTH_TIMEOUT_MS = 15_000L
        private const val REPAIR_TIMEOUT_MS = 240_000L
        internal const val ROOTFS_RUNTIME_ROOT = "/data/adb/minis/runtime/rootfs"
        internal const val ROOTFS_VERSIONS = "$ROOTFS_RUNTIME_ROOT/versions"
        internal const val ROOTFS_STAGING = "$ROOTFS_RUNTIME_ROOT/staging"
        internal const val ROOTFS_CURRENT = "$ROOTFS_RUNTIME_ROOT/current"
        internal const val ROOTFS_PREVIOUS = "$ROOTFS_RUNTIME_ROOT/previous"
        internal const val ROOTFS_PENDING = "$ROOTFS_RUNTIME_ROOT/pending"
        internal const val PROVISION_ROOT = "/data/adb/minis/runtime/provision"
        internal const val PROVISION_REVISION = "$PROVISION_ROOT/revision"

        private val SU_CANDIDATES = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/data/adb/ksu/bin/su", "/debug_ramdisk/su",
        )
        private val REQUIRED_LAYOUT = listOf(
            "etc/os-release", "etc/passwd", "etc/group", "etc/minis/rootfs.json",
            "workspace", "memory", "skills", "shared", "proc", "sys", "dev", "tmp", "run", "var/minis",
        )

        private var instance: RootfsManager? = null
        fun getInstance(context: Context): RootfsManager =
            instance ?: RootfsManager(context.applicationContext).also { instance = it }

        internal fun resolveRootfsShell(): String = listOf(
            "CURRENT=${shellQuote(ROOTFS_CURRENT)}",
            "VERSIONS=${shellQuote(ROOTFS_VERSIONS)}",
            "LEGACY=${shellQuote(MinisdProtocol.DEFAULT_ROOTFS)}",
            "if [ -L \"\$CURRENT\" ]; then ROOTFS=\$(readlink \"\$CURRENT\" 2>/dev/null || true); case \"\$ROOTFS\" in \"\$VERSIONS\"/ubuntu-24.04-r[1-9][0-9]*-[0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]) ;; *) echo 'MINIS_ROOTFS:CORRUPT:current'; exit 0 ;; esac; elif [ -e \"\$CURRENT\" ]; then echo 'MINIS_ROOTFS:CORRUPT:current'; exit 0; elif [ -d \"\$LEGACY\" ]; then ROOTFS=\"\$LEGACY\"; else echo 'MINIS_ROOTFS:MISSING'; exit 0; fi",
        ).joinToString("\n")

        internal fun buildProbeCommand(): String {
            val commands = mutableListOf(resolveRootfsShell())
            commands += "[ -d \"\$ROOTFS\" ] || { echo 'MINIS_ROOTFS:MISSING'; exit 0; }"
            REQUIRED_LAYOUT.forEach { rel ->
                commands += "[ -e \"\$ROOTFS/$rel\" ] || { echo 'MINIS_ROOTFS:CORRUPT:$rel'; exit 0; }"
            }
            commands += "if [ ! -x \"\$ROOTFS/bin/bash\" ] && [ ! -x \"\$ROOTFS/usr/bin/bash\" ] && [ ! -x \"\$ROOTFS/bin/sh\" ]; then echo 'MINIS_ROOTFS:CORRUPT:shell'; exit 0; fi"
            commands += "ARTIFACT=\$(cat \"\$ROOTFS/.minis-artifact-sha256\" 2>/dev/null || true)"
            commands += "REVISION=\$(cat \"\$ROOTFS/.minis-rootfs-version\" 2>/dev/null || true)"
            commands += "META=\$(tr -d '\\r\\n' < \"\$ROOTFS/etc/minis/rootfs.json\" 2>/dev/null || true)"
            commands += "echo \"MINIS_ROOTFS:PATH:\$ROOTFS\""
            commands += "echo \"MINIS_ROOTFS:ARTIFACT_SHA:\$ARTIFACT\""
            commands += "echo \"MINIS_ROOTFS:REVISION:\$REVISION\""
            commands += "echo \"MINIS_ROOTFS:METADATA:\$META\""
            return commands.joinToString("\n")
        }

        internal fun evaluateProbeOutput(output: String): RootfsHealth {
            val lines = output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            lines.firstOrNull { it.startsWith("MINIS_ROOTFS:CORRUPT:") }?.let {
                return RootfsHealth(RootfsHealthCode.CORRUPT, it.removePrefix("MINIS_ROOTFS:CORRUPT:"))
            }
            if (lines.any { it == "MINIS_ROOTFS:MISSING" }) {
                return RootfsHealth(RootfsHealthCode.MISSING, "Ubuntu rootfs is not installed")
            }
            val metaLine = lines.firstOrNull { it.startsWith("MINIS_ROOTFS:METADATA:") }
                ?: return RootfsHealth(RootfsHealthCode.CORRUPT, "rootfs metadata missing")
            val raw = metaLine.removePrefix("MINIS_ROOTFS:METADATA:")
            val metadata = runCatching { JSONObject(raw) }.getOrNull()
                ?: return RootfsHealth(RootfsHealthCode.CORRUPT, "invalid rootfs metadata")
            if (metadata.optString("distro") != "ubuntu" || !metadata.optString("version").startsWith("24.04") ||
                !metadata.optString("release").startsWith("24.04") || metadata.optString("arch") != "arm64" ||
                metadata.optString("profile") != "base") {
                return RootfsHealth(RootfsHealthCode.INCOMPATIBLE, "rootfs metadata does not describe supported Ubuntu 24.04 arm64 base", metadata)
            }
            metadata.put("_path", lines.firstOrNull { it.startsWith("MINIS_ROOTFS:PATH:") }?.removePrefix("MINIS_ROOTFS:PATH:").orEmpty())
            metadata.put("_artifact_sha256", lines.firstOrNull { it.startsWith("MINIS_ROOTFS:ARTIFACT_SHA:") }?.removePrefix("MINIS_ROOTFS:ARTIFACT_SHA:").orEmpty())
            metadata.put("_rootfs_version", lines.firstOrNull { it.startsWith("MINIS_ROOTFS:REVISION:") }?.removePrefix("MINIS_ROOTFS:REVISION:").orEmpty())
            return RootfsHealth(RootfsHealthCode.HEALTHY, "rootfs healthy", metadata)
        }

        internal fun healthMatchesManifest(health: RootfsHealth, manifest: RuntimeDistributionManifest): Boolean {
            val meta = health.metadata ?: return false
            return health.healthy &&
                meta.optString("_artifact_sha256") == manifest.rootfsSha256 &&
                meta.optString("_rootfs_version") == manifest.rootfsVersion &&
                meta.optString("release") == manifest.rootfsRelease &&
                meta.optString("profile") == manifest.rootfsProfile &&
                meta.optString("upstream_sha256").lowercase() == manifest.rootfsUpstreamSha256
        }

        internal fun buildInstallCommand(archive: String, manifest: RuntimeDistributionManifest): String {
            val qArchive = shellQuote(archive)
            val qVersion = shellQuote("$ROOTFS_VERSIONS/${manifest.rootfsVersion}")
            val commands = mutableListOf<String>()
            commands += "ARCHIVE=$qArchive"
            commands += "RUNTIME=${shellQuote(ROOTFS_RUNTIME_ROOT)}"
            commands += "VERSIONS=${shellQuote(ROOTFS_VERSIONS)}"
            commands += "STAGING=${shellQuote(ROOTFS_STAGING)}"
            commands += "CURRENT=${shellQuote(ROOTFS_CURRENT)}"
            commands += "PREVIOUS=${shellQuote(ROOTFS_PREVIOUS)}"
            commands += "PENDING=${shellQuote(ROOTFS_PENDING)}"
            commands += "VERSION=$qVersion"
            commands += "NEW=\"\$STAGING/${manifest.rootfsVersion}.new.\$\$\""
            commands += "NEXT=\"\$RUNTIME/current.next.\$\$\""
            commands += "PREV_NEXT=\"\$RUNTIME/previous.next.\$\$\""
            commands += "[ -s \"\$ARCHIVE\" ] || { echo 'packaged rootfs archive missing' >&2; exit 71; }"
            commands += "ACTUAL=\$(sha256sum \"\$ARCHIVE\" 2>/dev/null | awk '{print tolower(\$1); exit}')"
            commands += "[ \"\$ACTUAL\" = '${manifest.rootfsSha256}' ] || { echo \"rootfs archive SHA mismatch: \$ACTUAL\" >&2; exit 72; }"
            commands += "mkdir -p \"\$VERSIONS\" \"\$STAGING\" || exit 73"
            commands += "chmod 0750 \"\$RUNTIME\" \"\$VERSIONS\" \"\$STAGING\" 2>/dev/null || true"
            commands += "rm -rf \"\$NEW\" \"\$NEXT\" \"\$PREV_NEXT\""
            commands += "mkdir -p \"\$NEW\" || exit 74"
            commands += "tar -xzf \"\$ARCHIVE\" -C \"\$NEW\" || { rm -rf \"\$NEW\"; exit 75; }"
            REQUIRED_LAYOUT.forEach { rel ->
                commands += "[ -e \"\$NEW/$rel\" ] || { echo 'rootfs missing $rel' >&2; rm -rf \"\$NEW\"; exit 76; }"
            }
            commands += "META=\"\$NEW/etc/minis/rootfs.json\""
            commands += "grep -Eq '\"distro\"[[:space:]]*:[[:space:]]*\"ubuntu\"' \"\$META\" || exit 77"
            commands += "grep -Eq '\"release\"[[:space:]]*:[[:space:]]*\"${manifest.rootfsRelease.replace(".", "\\.")}\"' \"\$META\" || exit 78"
            commands += "grep -Eq '\"arch\"[[:space:]]*:[[:space:]]*\"arm64\"' \"\$META\" || exit 79"
            commands += "grep -Eq '\"profile\"[[:space:]]*:[[:space:]]*\"${manifest.rootfsProfile}\"' \"\$META\" || exit 80"
            commands += "grep -Eq '\"upstream_sha256\"[[:space:]]*:[[:space:]]*\"${manifest.rootfsUpstreamSha256}\"' \"\$META\" || exit 81"
            commands += "printf '%s\\n' '${manifest.rootfsSha256}' > \"\$NEW/.minis-artifact-sha256\" || exit 82"
            commands += "printf '%s\\n' '${manifest.rootfsVersion}' > \"\$NEW/.minis-rootfs-version\" || exit 83"
            commands += "if [ -e \"\$VERSION\" ]; then EXISTING=\$(cat \"\$VERSION/.minis-artifact-sha256\" 2>/dev/null || true); [ \"\$EXISTING\" = '${manifest.rootfsSha256}' ] || { echo 'existing revision hash mismatch' >&2; exit 84; }; rm -rf \"\$NEW\"; else mv \"\$NEW\" \"\$VERSION\" || exit 85; fi"
            commands += "OLD=''"
            commands += "if [ -L \"\$CURRENT\" ]; then OLD=\$(readlink \"\$CURRENT\" 2>/dev/null || true); case \"\$OLD\" in \"\$VERSIONS\"/ubuntu-24.04-r[1-9][0-9]*-[0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]) ;; *) echo 'invalid current rootfs pointer' >&2; exit 86 ;; esac; fi"
            commands += "umask 077; { printf '%s\\n' \"\$OLD\"; printf '%s\\n' \"\$VERSION\"; } > \"\$PENDING.tmp\" || exit 87; mv -f \"\$PENDING.tmp\" \"\$PENDING\" || exit 88"
            commands += "ln -s \"\$VERSION\" \"\$NEXT\" || exit 89"
            // Single externally visible COMMIT point.
            commands += "mv -f \"\$NEXT\" \"\$CURRENT\" || { rm -f \"\$NEXT\"; exit 90; }"
            commands += "if [ -n \"\$OLD\" ] && [ \"\$OLD\" != \"\$VERSION\" ]; then ln -s \"\$OLD\" \"\$PREV_NEXT\" || exit 91; mv -f \"\$PREV_NEXT\" \"\$PREVIOUS\" || exit 92; fi"
            commands += "echo 'MINIS_ROOTFS:COMMITTED:${manifest.rootfsVersion}'"
            return commands.joinToString("\n")
        }

        internal fun buildPendingProbeCommand(): String =
            "[ -s ${shellQuote(ROOTFS_PENDING)} ] && echo 'MINIS_ROOTFS:PENDING' || true"

        internal fun buildRollbackPendingCommand(): String = listOf(
            "PENDING=${shellQuote(ROOTFS_PENDING)}",
            "CURRENT=${shellQuote(ROOTFS_CURRENT)}",
            "VERSIONS=${shellQuote(ROOTFS_VERSIONS)}",
            "[ -s \"\$PENDING\" ] || exit 0",
            "OLD=\$(sed -n '1p' \"\$PENDING\" 2>/dev/null || true)",
            "NEXT=\"${ROOTFS_RUNTIME_ROOT}/current.rollback.\$\$\"",
            "if [ -z \"\$OLD\" ]; then rm -f \"\$CURRENT\"; else case \"\$OLD\" in \"\$VERSIONS\"/ubuntu-24.04-r[1-9][0-9]*-[0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]) ;; *) exit 93 ;; esac; rm -f \"\$NEXT\"; ln -s \"\$OLD\" \"\$NEXT\" || exit 94; mv -f \"\$NEXT\" \"\$CURRENT\" || exit 95; fi",
            "rm -f \"\$PENDING\"",
        ).joinToString("\n")

        internal fun buildRollbackPreviousCommand(): String = listOf(
            "PREVIOUS=${shellQuote(ROOTFS_PREVIOUS)}",
            "CURRENT=${shellQuote(ROOTFS_CURRENT)}",
            "VERSIONS=${shellQuote(ROOTFS_VERSIONS)}",
            "[ -L \"\$PREVIOUS\" ] || exit 1",
            "OLD=\$(readlink \"\$PREVIOUS\" 2>/dev/null || true)",
            "case \"\$OLD\" in \"\$VERSIONS\"/ubuntu-24.04-r[1-9][0-9]*-[0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]) ;; *) exit 2 ;; esac",
            "NEXT=\"${ROOTFS_RUNTIME_ROOT}/current.rollback.\$\$\"",
            "rm -f \"\$NEXT\"",
            "ln -s \"\$OLD\" \"\$NEXT\" || exit 3",
            "mv -f \"\$NEXT\" \"\$CURRENT\" || exit 4",
            "rm -f ${shellQuote(ROOTFS_PENDING)}",
        ).joinToString("\n")

        internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
    }
}
