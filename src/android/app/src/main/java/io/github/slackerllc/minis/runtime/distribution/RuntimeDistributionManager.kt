package io.github.slackerllc.minis.runtime.distribution

import android.content.Context
import android.os.Build
import io.github.slackerllc.minis.runtime.minisd.MinisdBootstrap
import io.github.slackerllc.minis.runtime.ubuntu.RootfsHealth
import io.github.slackerllc.minis.runtime.ubuntu.RootfsHealthCode
import io.github.slackerllc.minis.runtime.ubuntu.UbuntuRuntime
import io.github.slackerllc.minis.sandbox.RootfsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

enum class RuntimeDistributionCode {
    READY,
    ROOT_REQUIRED,
    UNSUPPORTED_ABI,
    INSTALL_REQUIRED,
    UPGRADE_REQUIRED,
    MIXED_VERSION,
    CORRUPT,
    INSTALLING,
    ROLLED_BACK,
    FAILED,
}

data class RuntimeDistributionSnapshot(
    val code: RuntimeDistributionCode,
    val desiredVersion: String? = null,
    val rootfsVersion: String? = null,
    val minisdSha256: String? = null,
    val provisionRevision: Int? = null,
    val rootfsHealth: RootfsHealth? = null,
    val detail: String = "",
) {
    val ready: Boolean get() = code == RuntimeDistributionCode.READY
}

data class RuntimeDistributionResult(
    val success: Boolean,
    val snapshot: RuntimeDistributionSnapshot,
    val rolledBack: Boolean = false,
)

/** APK-owned minisd + versioned rootfs lifecycle for Issue #51. */
class RuntimeDistributionManager private constructor(private val context: Context) {
    private val rootfs = RootfsManager.getInstance(context)
    private val lock = Mutex()
    private val _state = MutableStateFlow(
        RuntimeDistributionSnapshot(RuntimeDistributionCode.INSTALL_REQUIRED, detail = "runtime not probed"),
    )
    val state: StateFlow<RuntimeDistributionSnapshot> = _state.asStateFlow()

    suspend fun probe(): RuntimeDistributionSnapshot = withContext(Dispatchers.IO) {
        val manifest = loadManifest().getOrElse {
            return@withContext setState(
                RuntimeDistributionSnapshot(RuntimeDistributionCode.CORRUPT, detail = it.message ?: "invalid runtime manifest"),
            )
        }
        if (!supportsArm64(Build.SUPPORTED_ABIS.toList())) {
            return@withContext setState(
                RuntimeDistributionSnapshot(
                    RuntimeDistributionCode.UNSUPPORTED_ABI,
                    desiredVersion = manifest.runtimeVersion,
                    detail = "runtime supports arm64-v8a only",
                ),
            )
        }
        val native = verifyApkOwnedMinisd(manifest)
        if (!native.ok) {
            return@withContext setState(
                RuntimeDistributionSnapshot(
                    RuntimeDistributionCode.CORRUPT,
                    desiredVersion = manifest.runtimeVersion,
                    minisdSha256 = native.actualSha,
                    detail = native.detail,
                ),
            )
        }
        val health = rootfs.checkHealth()
        if (health.code == RootfsHealthCode.ROOT_UNAVAILABLE) {
            return@withContext setState(
                RuntimeDistributionSnapshot(
                    RuntimeDistributionCode.ROOT_REQUIRED,
                    desiredVersion = manifest.runtimeVersion,
                    minisdSha256 = native.actualSha,
                    rootfsHealth = health,
                    detail = health.detail,
                ),
            )
        }
        val provision = rootfs.readProvisionRevision()
        val rootfsMatches = RootfsManager.healthMatchesManifest(health, manifest)
        val code = when {
            health.code == RootfsHealthCode.MISSING -> RuntimeDistributionCode.INSTALL_REQUIRED
            !health.healthy -> RuntimeDistributionCode.CORRUPT
            !rootfsMatches -> RuntimeDistributionCode.UPGRADE_REQUIRED
            provision != manifest.provisionRevision -> RuntimeDistributionCode.MIXED_VERSION
            else -> RuntimeDistributionCode.READY
        }
        setState(
            RuntimeDistributionSnapshot(
                code = code,
                desiredVersion = manifest.runtimeVersion,
                rootfsVersion = health.metadata?.optString("_rootfs_version")?.takeIf { it.isNotBlank() },
                minisdSha256 = native.actualSha,
                provisionRevision = provision,
                rootfsHealth = health,
                detail = when (code) {
                    RuntimeDistributionCode.READY -> "APK minisd, rootfs, layout and provision revision match the authoritative manifest"
                    RuntimeDistributionCode.INSTALL_REQUIRED -> "rootfs is not installed"
                    RuntimeDistributionCode.UPGRADE_REQUIRED -> "rootfs identity does not match the APK runtime manifest"
                    RuntimeDistributionCode.MIXED_VERSION -> "provision revision does not match the APK runtime manifest"
                    else -> health.detail
                },
            ),
        )
    }

    suspend fun installOrUpgrade(): RuntimeDistributionResult = lock.withLock {
        val manifest = loadManifest().getOrElse {
            return@withLock result(RuntimeDistributionSnapshot(RuntimeDistributionCode.CORRUPT, detail = it.message ?: "invalid runtime manifest"))
        }
        if (!supportsArm64(Build.SUPPORTED_ABIS.toList())) {
            return@withLock result(
                RuntimeDistributionSnapshot(RuntimeDistributionCode.UNSUPPORTED_ABI, desiredVersion = manifest.runtimeVersion, detail = "runtime supports arm64-v8a only"),
            )
        }
        val native = verifyApkOwnedMinisd(manifest)
        if (!native.ok) {
            return@withLock result(
                RuntimeDistributionSnapshot(RuntimeDistributionCode.CORRUPT, desiredVersion = manifest.runtimeVersion, minisdSha256 = native.actualSha, detail = native.detail),
            )
        }
        setState(RuntimeDistributionSnapshot(RuntimeDistributionCode.INSTALLING, desiredVersion = manifest.runtimeVersion, detail = "installing verified APK runtime"))

        rootfs.installIfNeeded()
        val installedHealth = rootfs.checkHealth()
        if (!RootfsManager.healthMatchesManifest(installedHealth, manifest)) {
            return@withLock result(
                RuntimeDistributionSnapshot(
                    RuntimeDistributionCode.FAILED,
                    desiredVersion = manifest.runtimeVersion,
                    minisdSha256 = native.actualSha,
                    rootfsHealth = installedHealth,
                    detail = "rootfs install did not produce the manifest-declared concrete revision: ${installedHealth.detail}",
                ),
            )
        }

        if (!UbuntuRuntime.isInitialized) UbuntuRuntime.init(context)
        val ready = runCatching { UbuntuRuntime.ensureReady() }.getOrElse {
            return@withLock rollbackFailure(manifest, "runtime start failed: ${it.message}")
        }
        if (!ready.running || !ready.available) {
            return@withLock rollbackFailure(manifest, "runtime start failed: ${ready.lastError ?: "keeper unavailable"}")
        }

        val provision = runCatching { UbuntuRuntime.provision() }.getOrElse {
            return@withLock rollbackFailure(manifest, "ubuntu.provision failed: ${it.message}")
        }
        if (!provision.ok) {
            return@withLock rollbackFailure(
                manifest,
                "ubuntu.provision failed: ${provision.error?.code ?: "unknown"}: ${provision.error?.detail.orEmpty()}",
            )
        }

        for (command in manifest.requiredCommands) {
            val checked = runCatching {
                UbuntuRuntime.exec(listOf("/usr/bin/env", "sh", "-c", "command -v ${shellWord(command)} >/dev/null"), timeoutMs = 30_000)
            }.getOrElse {
                return@withLock rollbackFailure(manifest, "required guest command check failed for $command: ${it.message}")
            }
            if (!checked.ok || checked.result?.optInt("exit_code", -1) != 0) {
                return@withLock rollbackFailure(manifest, "required guest command missing after provision: $command")
            }
        }

        if (!rootfs.writeProvisionRevision(manifest.provisionRevision)) {
            return@withLock rollbackFailure(manifest, "cannot commit provision revision ${manifest.provisionRevision}")
        }
        val final = probe()
        RuntimeDistributionResult(final.ready, final)
    }

    private suspend fun rollbackFailure(manifest: RuntimeDistributionManifest, detail: String): RuntimeDistributionResult {
        runCatching { if (UbuntuRuntime.isInitialized) UbuntuRuntime.stop() }
        val rolledBack = rootfs.rollbackToPrevious()
        val snapshot = RuntimeDistributionSnapshot(
            if (rolledBack) RuntimeDistributionCode.ROLLED_BACK else RuntimeDistributionCode.FAILED,
            desiredVersion = manifest.runtimeVersion,
            detail = if (rolledBack) "$detail; previous concrete rootfs restored" else "$detail; rollback failed",
        )
        return RuntimeDistributionResult(false, setState(snapshot), rolledBack)
    }

    private data class NativeCheck(val ok: Boolean, val actualSha: String?, val detail: String)

    private fun verifyApkOwnedMinisd(manifest: RuntimeDistributionManifest): NativeCheck {
        val binary = MinisdBootstrap.nativeBinaryPath(context)
        if (!binary.isFile || !binary.canExecute()) {
            return NativeCheck(false, null, "APK native minisd is missing or not executable at ${binary.absolutePath}")
        }
        val actual = sha256(binary)
        return if (actual == manifest.minisdSha256) {
            NativeCheck(true, actual, "APK minisd hash matches manifest")
        } else {
            NativeCheck(false, actual, "APK minisd SHA-256 mismatch: actual=$actual expected=${manifest.minisdSha256}")
        }
    }

    private fun loadManifest(): Result<RuntimeDistributionManifest> = runCatching {
        context.assets.open(RuntimeDistributionManifest.ASSET_PATH)
            .bufferedReader()
            .use { RuntimeDistributionManifest.parse(it.readText()) }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun setState(snapshot: RuntimeDistributionSnapshot): RuntimeDistributionSnapshot {
        _state.value = snapshot
        return snapshot
    }

    private fun result(snapshot: RuntimeDistributionSnapshot): RuntimeDistributionResult =
        RuntimeDistributionResult(snapshot.ready, setState(snapshot))

    companion object {
        @Volatile private var instance: RuntimeDistributionManager? = null

        fun getInstance(context: Context): RuntimeDistributionManager =
            instance ?: synchronized(this) {
                instance ?: RuntimeDistributionManager(context.applicationContext).also { instance = it }
            }

        internal fun supportsArm64(abis: List<String>): Boolean = abis.any { it == "arm64-v8a" }

        internal fun shellWord(value: String): String {
            require(value.matches(Regex("^[A-Za-z0-9._+-]+$"))) { "unsafe shell word" }
            return value
        }
    }
}
