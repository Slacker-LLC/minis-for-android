package com.openminis.app.sandbox

import android.content.Context
import android.os.Build
import android.util.Log
import com.openminis.app.sandbox.minisd.MinisdClient
import com.openminis.app.sandbox.ubuntu.UbuntuPaths
import com.openminis.app.sandbox.ubuntu.UbuntuRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

sealed class RootfsInstallState {
    object Idle : RootfsInstallState()
    object Preparing : RootfsInstallState()
    data class Extracting(val progress: Float) : RootfsInstallState()
    object Finalizing : RootfsInstallState()
    object Installed : RootfsInstallState()
    data class Failed(val error: String) : RootfsInstallState()
}

/**
 * Authoritative Android-side health/recovery manager for the active Ubuntu
 * rootfs at `/data/adb/minis/rootfs`.
 *
 * The recovery image is a build-generated, SHA-256-verified APK asset. Repair
 * is deliberately performed through a fixed internal `su -c` program rather
 * than an agent/root-shell surface: the broker/rootfs may be the component that
 * is broken, and no user-controlled text is interpolated into the command.
 *
 * Workspace/memory/skills/shared live under the app-private files directory and
 * are bind-mounted into the guest, so replacing a broken rootfs never deletes
 * those persistent user-data trees.
 */
class RootfsManager private constructor(private val context: Context) {

    data class Health(
        val healthy: Boolean,
        val detail: String,
    )

    private data class RootCommandResult(
        val exitCode: Int,
        val output: String,
    )

    val rootfsDir: File = File(UbuntuPaths.HOST_ROOTFS)

    @Volatile
    private var lastHealth: Health? = null

    @Volatile
    private var lastHealthAtMs: Long = 0L

    val isInstalled: Boolean
        get() = lastHealth?.healthy == true

    private val _installState = MutableStateFlow<RootfsInstallState>(RootfsInstallState.Idle)
    val installState: StateFlow<RootfsInstallState> = _installState.asStateFlow()

    private val repairLock = Mutex()

    /**
     * Cheap cached check in the common case, authoritative root-side probe when
     * [force] is true or the cache has expired.
     */
    suspend fun checkHealth(force: Boolean = false): Health = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cached = lastHealth
        if (!force && cached != null && now - lastHealthAtMs < HEALTH_CACHE_MS) {
            return@withContext cached
        }

        if (Build.SUPPORTED_ABIS.none { it.equals("arm64-v8a", ignoreCase = true) }) {
            return@withContext rememberHealth(
                Health(false, "unsupported device ABI for bundled Ubuntu arm64 rootfs"),
            )
        }

        val result = runRootCommand(healthCommand(), HEALTH_TIMEOUT_MS)
        val health = when {
            result == null -> Health(false, "root unavailable while checking Ubuntu rootfs")
            result.exitCode == 0 && result.output.contains("ROOTFS_OK") -> Health(true, "ok")
            else -> Health(
                false,
                result.output.lineSequence().firstOrNull { it.isNotBlank() }
                    ?: "rootfs health check failed (exit=${result.exitCode})",
            )
        }
        rememberHealth(health)
    }

    /**
     * Repair only when unhealthy unless [force] is requested. The old rootfs is
     * kept as `/data/adb/minis/rootfs.backup` until the replacement passes the
     * same authoritative health check, then removed.
     */
    suspend fun repairIfNeeded(
        client: MinisdClient,
        force: Boolean = false,
    ): Boolean = repairLock.withLock {
        withContext(Dispatchers.IO) {
            val before = checkHealth(force = true)
            if (!force && before.healthy) {
                _installState.value = RootfsInstallState.Installed
                return@withContext true
            }

            _installState.value = RootfsInstallState.Preparing
            Log.w(TAG, "rootfs repair requested: ${before.detail}")

            // Best effort only. A missing/dead broker is exactly one of the
            // states this recovery path exists to survive.
            runCatching { client.ubuntuStop() }

            val archive = try {
                materializeVerifiedRecoveryArchive()
            } catch (t: Throwable) {
                val detail = "recovery asset unavailable: ${t.message}"
                _installState.value = RootfsInstallState.Failed(detail)
                rememberHealth(Health(false, detail))
                Log.e(TAG, detail, t)
                return@withContext false
            }

            val su = resolveSu()
            if (su == null) {
                val detail = "no executable su binary for rootfs repair"
                _installState.value = RootfsInstallState.Failed(detail)
                rememberHealth(Health(false, detail))
                return@withContext false
            }

            _installState.value = RootfsInstallState.Extracting(0f)
            val result = streamArchiveToAtomicInstaller(su, archive)
            if (result == null || result.exitCode != 0) {
                val detail = result?.output?.take(1000)?.ifBlank { null }
                    ?: "atomic rootfs installer failed"
                _installState.value = RootfsInstallState.Failed(detail)
                rememberHealth(Health(false, detail))
                Log.e(TAG, "rootfs repair failed: $detail")
                return@withContext false
            }

            _installState.value = RootfsInstallState.Finalizing
            lastHealthAtMs = 0L
            val after = checkHealth(force = true)
            if (!after.healthy) {
                Log.e(TAG, "replacement rootfs failed verification: ${after.detail}; rolling back")
                runRootCommand(rollbackCommand(), HEALTH_TIMEOUT_MS)
                lastHealthAtMs = 0L
                val rolledBack = checkHealth(force = true)
                val detail = "replacement rootfs failed verification: ${after.detail}; " +
                    "rollback=${rolledBack.detail}"
                _installState.value = RootfsInstallState.Failed(detail)
                return@withContext false
            }

            // Only discard the old image after the new tree independently
            // passes validation. Persistent user data is outside both trees.
            runRootCommand(cleanupBackupCommand(), HEALTH_TIMEOUT_MS)
            _installState.value = RootfsInstallState.Installed
            rememberHealth(Health(true, "ok"))
            Log.i(TAG, "Ubuntu rootfs repaired and verified")
            true
        }
    }

    suspend fun installIfNeeded() {
        val client = if (UbuntuRuntime.isInitialized) UbuntuRuntime.client else MinisdClient()
        repairIfNeeded(client, force = false)
    }

    /** Legacy PRoot entry point. PRoot no longer exists; retained until #44 cleanup. */
    suspend fun installProotIfNeeded() = withContext(Dispatchers.IO) { Unit }

    /**
     * Rebuild the Ubuntu rootfs. [keepUserData] is inherently honored because
     * user data is no longer stored inside the rootfs.
     */
    suspend fun reset(keepUserData: Boolean = true): File? {
        @Suppress("UNUSED_VARIABLE")
        val preserved = keepUserData
        val client = if (UbuntuRuntime.isInitialized) UbuntuRuntime.client else MinisdClient()
        repairIfNeeded(client, force = true)
        return null
    }

    suspend fun getRootfsSize(): Long = withContext(Dispatchers.IO) {
        val result = runRootCommand(
            "du -sk ${shellQuote(UbuntuPaths.HOST_ROOTFS)} 2>/dev/null",
            HEALTH_TIMEOUT_MS,
        ) ?: return@withContext 0L
        result.output.trim().split(Regex("\\s+"), limit = 2)
            .firstOrNull()?.toLongOrNull()?.times(1024L) ?: 0L
    }

    /** User data is outside the current rootfs; there is nothing to restore here. */
    suspend fun restoreUserData(backupDir: File) = withContext(Dispatchers.IO) {
        @Suppress("UNUSED_VARIABLE")
        val ignored = backupDir
        Unit
    }

    fun ensureSessionDirs(sessionId: String) {
        val sessionBase = File(context.filesDir, "minis-sessions/$sessionId")
        listOf("attachments", "offloads", "workspace", "browser").forEach { subdir ->
            File(sessionBase, subdir).mkdirs()
        }
    }

    /** DNS is generated by minisd on ubuntu.start; kept as no-op for callers. */
    fun refreshDns() = Unit

    suspend fun applyDefaultMountOverlay() = withContext(Dispatchers.IO) { Unit }

    private fun healthCommand(): String = """
        ROOT=${shellQuote(UbuntuPaths.HOST_ROOTFS)}
        if [ ! -d "${'$'}ROOT" ]; then echo ROOTFS_MISSING; exit 10; fi
        if [ ! -f "${'$'}ROOT/etc/os-release" ]; then echo ROOTFS_OS_RELEASE_MISSING; exit 11; fi
        if ! grep -Eq '^ID=("?ubuntu"?)$' "${'$'}ROOT/etc/os-release"; then echo ROOTFS_NOT_UBUNTU; exit 12; fi
        if [ ! -f "${'$'}ROOT/etc/minis/rootfs.json" ]; then echo ROOTFS_MARKER_MISSING; exit 13; fi
        if ! grep -Fq '"distro": "ubuntu"' "${'$'}ROOT/etc/minis/rootfs.json"; then echo ROOTFS_MARKER_DISTRO_INVALID; exit 14; fi
        if ! grep -Fq '"arch": "arm64"' "${'$'}ROOT/etc/minis/rootfs.json"; then echo ROOTFS_MARKER_ARCH_INVALID; exit 15; fi
        if [ ! -x "${'$'}ROOT/bin/bash" ] && [ ! -x "${'$'}ROOT/usr/bin/bash" ] && [ ! -x "${'$'}ROOT/bin/sh" ]; then echo ROOTFS_SHELL_MISSING; exit 16; fi
        echo ROOTFS_OK
    """.trimIndent()

    private fun rollbackCommand(): String = """
        ROOT=${shellQuote(ROOT_BASE)}
        TARGET="${'$'}ROOT/rootfs"
        BACKUP="${'$'}ROOT/rootfs.backup"
        if [ -d "${'$'}BACKUP" ]; then
          rm -rf "${'$'}TARGET"
          mv "${'$'}BACKUP" "${'$'}TARGET"
        fi
    """.trimIndent()

    private fun cleanupBackupCommand(): String =
        "rm -rf ${shellQuote("$ROOT_BASE/rootfs.backup")} ${shellQuote("$ROOT_BASE/rootfs.installing")}"

    /**
     * Extract to a sibling staging directory and swap only after every required
     * marker has been validated. stdin is the already SHA-verified APK asset.
     */
    private fun installerCommand(): String = """
        set -eu
        ROOT=${shellQuote(ROOT_BASE)}
        TARGET="${'$'}ROOT/rootfs"
        BACKUP="${'$'}ROOT/rootfs.backup"
        STAGE="${'$'}ROOT/rootfs.installing"
        mkdir -p "${'$'}ROOT"
        if [ ! -d "${'$'}TARGET" ] && [ -d "${'$'}BACKUP" ]; then mv "${'$'}BACKUP" "${'$'}TARGET"; fi
        rm -rf "${'$'}STAGE"
        mkdir -p "${'$'}STAGE"
        cleanup_stage() { rm -rf "${'$'}STAGE"; }
        trap cleanup_stage EXIT HUP INT TERM
        tar -xzf - -C "${'$'}STAGE"
        test -f "${'$'}STAGE/etc/os-release"
        grep -Eq '^ID=("?ubuntu"?)$' "${'$'}STAGE/etc/os-release"
        test -f "${'$'}STAGE/etc/minis/rootfs.json"
        grep -Fq '"distro": "ubuntu"' "${'$'}STAGE/etc/minis/rootfs.json"
        grep -Fq '"arch": "arm64"' "${'$'}STAGE/etc/minis/rootfs.json"
        if [ ! -x "${'$'}STAGE/bin/bash" ] && [ ! -x "${'$'}STAGE/usr/bin/bash" ] && [ ! -x "${'$'}STAGE/bin/sh" ]; then exit 72; fi
        rm -rf "${'$'}BACKUP"
        if [ -e "${'$'}TARGET" ]; then mv "${'$'}TARGET" "${'$'}BACKUP"; fi
        if ! mv "${'$'}STAGE" "${'$'}TARGET"; then
          if [ -d "${'$'}BACKUP" ]; then mv "${'$'}BACKUP" "${'$'}TARGET"; fi
          exit 73
        fi
        trap - EXIT HUP INT TERM
        echo ROOTFS_REPAIRED
    """.trimIndent()

    private fun streamArchiveToAtomicInstaller(su: String, archive: File): RootCommandResult? {
        val process = try {
            ProcessBuilder(su, "-c", installerCommand())
                .redirectErrorStream(true)
                .start()
        } catch (t: Throwable) {
            Log.w(TAG, "failed to start rootfs installer: ${t.message}")
            return null
        }

        val output = AtomicReference("")
        val outputThread = Thread({
            output.set(runCatching { process.inputStream.bufferedReader().use { it.readText() } }.getOrDefault(""))
        }, "rootfs-repair-output").apply {
            isDaemon = true
            start()
        }
        val writeError = AtomicReference<Throwable?>(null)
        val writerThread = Thread({
            try {
                archive.inputStream().use { input ->
                    process.outputStream.use { out -> input.copyTo(out, DEFAULT_BUFFER_SIZE) }
                }
            } catch (t: Throwable) {
                writeError.set(t)
                runCatching { process.outputStream.close() }
            }
        }, "rootfs-repair-input").apply {
            isDaemon = true
            start()
        }

        val finished = try {
            process.waitFor(REPAIR_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(1, TimeUnit.SECONDS)
        }
        writerThread.join(1_000)
        outputThread.join(1_000)

        if (!finished) {
            return RootCommandResult(124, "rootfs repair timed out")
        }
        val writerFailure = writeError.get()
        val text = buildString {
            append(output.get())
            if (writerFailure != null) {
                if (isNotEmpty()) append('\n')
                append("archive stream failed: ${writerFailure.message}")
            }
        }
        return RootCommandResult(process.exitValue(), text.trim())
    }

    /** Materialize APK asset locally and verify it before any root process sees it. */
    private fun materializeVerifiedRecoveryArchive(): File {
        val expected = context.assets.open(RECOVERY_SHA_ASSET).bufferedReader().use { reader ->
            reader.readLine()?.trim()?.substringBefore(' ')
        }?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
            ?: error("invalid bundled rootfs SHA-256 manifest")

        val dir = File(context.filesDir, "minis/recovery").apply { mkdirs() }
        val target = File(dir, "ubuntu-arm64-rootfs.tar.gz")
        if (target.isFile && sha256(target).equals(expected, ignoreCase = true)) {
            return target
        }

        val tmp = File(dir, "ubuntu-arm64-rootfs.tar.gz.tmp")
        tmp.delete()
        context.assets.open(RECOVERY_ASSET).use { input ->
            tmp.outputStream().buffered().use { output -> input.copyTo(output) }
        }
        val actual = sha256(tmp)
        check(actual.equals(expected, ignoreCase = true)) {
            "bundled rootfs checksum mismatch: expected=$expected actual=$actual"
        }
        if (target.exists() && !target.delete()) error("cannot replace stale recovery archive")
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        return target
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun runRootCommand(command: String, timeoutMs: Long): RootCommandResult? {
        val su = resolveSu() ?: return null
        val process = try {
            ProcessBuilder(su, "-c", command)
                .redirectErrorStream(true)
                .start()
        } catch (t: Throwable) {
            Log.d(TAG, "root command spawn failed: ${t.message}")
            return null
        }
        val output = AtomicReference("")
        val reader = Thread({
            output.set(runCatching { process.inputStream.bufferedReader().use { it.readText() } }.getOrDefault(""))
        }, "rootfs-health-output").apply {
            isDaemon = true
            start()
        }
        val finished = try {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(1, TimeUnit.SECONDS)
        }
        reader.join(1_000)
        return if (finished) {
            RootCommandResult(process.exitValue(), output.get().trim())
        } else {
            RootCommandResult(124, "root command timed out")
        }
    }

    private fun resolveSu(): String? = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/debug_ramdisk/su",
    ).firstOrNull { File(it).canExecute() }

    private fun rememberHealth(health: Health): Health {
        lastHealth = health
        lastHealthAtMs = System.currentTimeMillis()
        return health
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    // --- POSIX tar extraction (kept temporarily for legacy TarExtractionTest; #44 removes it) ---

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
                            java.nio.file.Paths.get(linkName)
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
                    if (mode and 0b001_001_001 != 0) {
                        outFile.setExecutable(true, false)
                    }
                    val remainder = (size % 512).toInt()
                    if (remainder != 0) {
                        skipFully(input, (512 - remainder).toLong())
                    }
                    continue
                }
                '1' -> {
                    outFile.parentFile?.mkdirs()
                    val linkTarget = File(targetDir, linkName)
                    if (linkTarget.exists()) {
                        linkTarget.copyTo(outFile, overwrite = true)
                    }
                }
                else -> {}
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
        private const val ROOT_BASE = "/data/adb/minis"
        private const val RECOVERY_ASSET = "runtime/ubuntu-arm64-rootfs.tar.gz"
        private const val RECOVERY_SHA_ASSET = "runtime/ubuntu-arm64-rootfs.tar.gz.sha256"
        private const val HEALTH_CACHE_MS = 30_000L
        private const val HEALTH_TIMEOUT_MS = 10_000L
        private const val REPAIR_TIMEOUT_MS = 180_000L

        @Volatile
        private var instance: RootfsManager? = null

        fun getInstance(context: Context): RootfsManager =
            instance ?: synchronized(this) {
                instance ?: RootfsManager(context.applicationContext).also { instance = it }
            }
    }
}
