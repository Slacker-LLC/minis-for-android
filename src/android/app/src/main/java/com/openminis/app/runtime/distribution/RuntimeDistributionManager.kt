package com.openminis.app.runtime.distribution

import android.content.Context
import android.os.Build
import com.openminis.app.runtime.ubuntu.RootfsHealth
import com.openminis.app.runtime.ubuntu.RootfsHealthCode
import com.openminis.app.sandbox.RootfsManager
import com.openminis.app.runtime.minisd.MinisdBootstrap
import com.openminis.app.runtime.ubuntu.UbuntuRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

enum class RuntimeDistributionCode {
    READY,
    ROOT_REQUIRED,
    UNSUPPORTED_ABI,
    ARTIFACT_REQUIRED,
    INSTALL_REQUIRED,
    UPGRADE_REQUIRED,
    MIXED_VERSION,
    CORRUPT,
    INTERRUPTED,
    INSTALLING,
    ROLLED_BACK,
    FAILED,
}

data class RuntimeDistributionSnapshot(
    val code: RuntimeDistributionCode,
    val desiredVersion: String? = null,
    val installedVersion: String? = null,
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

/**
 * APK -> privileged-runtime distribution lifecycle.
 *
 * Only executable runtime state is replaced: minisd, rootfs, runtime manifest,
 * provision marker and transient pid/socket state. The #50 user data roots
 * (workspace/sessions/memory/skills/shared/home) never participate in a switch.
 *
 * Artifact digests must come from the APK-packaged manifest. A staged sidecar
 * manifest is not trusted, so missing packaged digests fail before mutation.
 */
class RuntimeDistributionManager private constructor(private val context: Context) {
    private val rootfs = RootfsManager.getInstance(context)
    private val installLock = Mutex()
    private val _state = MutableStateFlow(
        RuntimeDistributionSnapshot(
            RuntimeDistributionCode.ARTIFACT_REQUIRED,
            detail = "runtime distribution has not been probed",
        ),
    )
    val state: StateFlow<RuntimeDistributionSnapshot> = _state.asStateFlow()

    suspend fun probe(): RuntimeDistributionSnapshot = withContext(Dispatchers.IO) {
        val loaded = loadPackagedManifest()
            ?: return@withContext setState(
                RuntimeDistributionSnapshot(
                    RuntimeDistributionCode.CORRUPT,
                    detail = "packaged runtime-distribution.json is missing or invalid",
                ),
            )
        val manifest = loaded.first
        if (!supportsArm64(Build.SUPPORTED_ABIS.toList())) {
            return@withContext setState(
                RuntimeDistributionSnapshot(
                    RuntimeDistributionCode.UNSUPPORTED_ABI,
                    desiredVersion = manifest.runtimeVersion,
                    detail = "runtime distribution supports arm64-v8a only",
                ),
            )
        }
        val su = findSu()
            ?: return@withContext setState(rootRequired(manifest, "no executable su found"))
        val root = verifyRoot(su)
        if (!root.ok) return@withContext setState(rootRequired(manifest, root.detail))
        setState(probeAsRoot(su, manifest))
    }

    suspend fun installOrUpgrade(): RuntimeDistributionResult {
        installLock.lock()
        return try {
            withContext(Dispatchers.IO) { installOrUpgradeLocked() }
        } finally {
            installLock.unlock()
        }
    }

    private suspend fun installOrUpgradeLocked(): RuntimeDistributionResult {
        val loaded = loadPackagedManifest()
            ?: return result(
                RuntimeDistributionSnapshot(
                    RuntimeDistributionCode.CORRUPT,
                    detail = "packaged runtime-distribution.json is missing or invalid",
                ),
            )
        val (manifest, rawManifest) = loaded
        if (!supportsArm64(Build.SUPPORTED_ABIS.toList())) {
            return result(
                RuntimeDistributionSnapshot(
                    RuntimeDistributionCode.UNSUPPORTED_ABI,
                    desiredVersion = manifest.runtimeVersion,
                    detail = "runtime distribution supports arm64-v8a only",
                ),
            )
        }
        val su = findSu()
            ?: return result(rootRequired(manifest, "no executable su found"))
        val root = verifyRoot(su)
        if (!root.ok) return result(rootRequired(manifest, root.detail))

        val initial = probeAsRoot(su, manifest)
        if (initial.code == RuntimeDistributionCode.INTERRUPTED) {
            val rolledBack = runSu(su, buildRollbackCommand(), SWITCH_TIMEOUT_MS)
            if (!rolledBack.ok) {
                return result(
                    RuntimeDistributionSnapshot(
                        RuntimeDistributionCode.FAILED,
                        desiredVersion = manifest.runtimeVersion,
                        detail = "interrupted runtime rollback failed: ${rolledBack.detail}",
                    ),
                )
            }
        }

        if (!manifest.deployable) {
            return result(
                RuntimeDistributionSnapshot(
                    RuntimeDistributionCode.ARTIFACT_REQUIRED,
                    desiredVersion = manifest.runtimeVersion,
                    detail = "release manifest has no trusted minisd/rootfs digests; refusing runtime mutation",
                ),
            )
        }

        val staged = runSu(su, buildArtifactVerificationCommand(manifest), ROOT_TIMEOUT_MS)
        if (!staged.ok) {
            return result(
                RuntimeDistributionSnapshot(
                    RuntimeDistributionCode.ARTIFACT_REQUIRED,
                    desiredVersion = manifest.runtimeVersion,
                    detail = staged.detail,
                ),
            )
        }

        val current = probeAsRoot(su, manifest)
        if (current.ready) return RuntimeDistributionResult(true, setState(current))

        setState(
            RuntimeDistributionSnapshot(
                RuntimeDistributionCode.INSTALLING,
                desiredVersion = manifest.runtimeVersion,
                installedVersion = current.installedVersion,
                detail = "validated artifacts; preparing atomic runtime switch",
            ),
        )

        if (UbuntuRuntime.isInitialized) runCatching { UbuntuRuntime.stop() }
        val appSocket = File(context.filesDir, "minis/minisd.sock").absolutePath
        val switched = runSu(
            su,
            buildSwitchCommand(manifest, rawManifest, appSocket),
            SWITCH_TIMEOUT_MS,
        )
        if (!switched.ok) {
            val rollback = runSu(su, buildRollbackCommand(), SWITCH_TIMEOUT_MS)
            return failedWithRollback(manifest, "runtime switch failed: ${switched.detail}", rollback)
        }

        val postSwitch = postSwitchHealthAndProvision(su, manifest)
        if (!postSwitch.ok) {
            runCatching { if (UbuntuRuntime.isInitialized) UbuntuRuntime.stop() }
            val rollback = runSu(su, buildRollbackCommand(), SWITCH_TIMEOUT_MS)
            return failedWithRollback(
                manifest,
                "new runtime failed health/provision: ${postSwitch.detail}",
                rollback,
            )
        }

        val committed = runSu(
            su,
            buildCommitCommand(manifest.runtimeVersion),
            SWITCH_TIMEOUT_MS,
        )
        if (!committed.ok) {
            return result(
                RuntimeDistributionSnapshot(
                    RuntimeDistributionCode.INTERRUPTED,
                    desiredVersion = manifest.runtimeVersion,
                    detail = "runtime health passed but transaction commit failed: ${committed.detail}",
                ),
            )
        }

        val final = probeAsRoot(su, manifest)
        return RuntimeDistributionResult(final.ready, setState(final))
    }

    private fun failedWithRollback(
        manifest: RuntimeDistributionManifest,
        failure: String,
        rollback: RootResult,
    ): RuntimeDistributionResult {
        val snapshot = RuntimeDistributionSnapshot(
            if (rollback.ok) RuntimeDistributionCode.ROLLED_BACK else RuntimeDistributionCode.FAILED,
            desiredVersion = manifest.runtimeVersion,
            detail = if (rollback.ok) {
                "$failure; previous runtime restored"
            } else {
                "$failure; rollback also failed: ${rollback.detail}"
            },
        )
        return RuntimeDistributionResult(false, setState(snapshot), rollback.ok)
    }

    private suspend fun postSwitchHealthAndProvision(
        su: String,
        manifest: RuntimeDistributionManifest,
    ): RootResult {
        if (!UbuntuRuntime.isInitialized) UbuntuRuntime.init(context)
        val ready = UbuntuRuntime.ensureReady()
        if (!ready.running || !ready.available) {
            return RootResult(false, "runtime start health failed: ${ready.lastError ?: "keeper unavailable"}")
        }
        val provision = UbuntuRuntime.provision()
        if (!provision.ok) {
            return RootResult(
                false,
                "ubuntu.provision failed: ${provision.error?.code ?: "unknown"}: ${provision.error?.detail.orEmpty()}",
            )
        }
        val guestCommand = manifest.requiredCommands.joinToString(" && ") { command ->
            "command -v ${shellWord(command)} >/dev/null"
        }
        val guest = runCatching {
            UbuntuRuntime.shell(guestCommand, timeoutMs = TOOLCHAIN_TIMEOUT_MS)
        }.getOrElse {
            return RootResult(false, "guest toolchain health failed: ${it.message}")
        }
        if (guest.exitCode != 0) {
            return RootResult(false, "required guest command missing after provision: ${guest.output.take(300)}")
        }
        return runSu(
            su,
            buildProvisionMarkerCommand(manifest.provisionRevision),
            ROOT_TIMEOUT_MS,
        )
    }

    private suspend fun probeAsRoot(
        su: String,
        manifest: RuntimeDistributionManifest,
    ): RuntimeDistributionSnapshot {
        val hostProbe = runSu(su, buildProbeCommand(), ROOT_TIMEOUT_MS)
        if (!hostProbe.ok) return rootRequired(manifest, hostProbe.detail)
        val host = evaluateHostProbe(hostProbe.output)
        val health = rootfs.checkHealth()
        if (health.code == RootfsHealthCode.ROOT_UNAVAILABLE) {
            return rootRequired(manifest, health.detail)
        }
        if (host.pendingVersion != null) {
            return RuntimeDistributionSnapshot(
                RuntimeDistributionCode.INTERRUPTED,
                desiredVersion = manifest.runtimeVersion,
                installedVersion = host.installedVersion,
                minisdSha256 = host.minisdSha256,
                provisionRevision = host.provisionRevision,
                rootfsHealth = health,
                detail = "uncommitted runtime transaction detected for ${host.pendingVersion}",
            )
        }

        val missing = host.minisdSha256 == null ||
            host.installedVersion == null ||
            health.code == RootfsHealthCode.MISSING
        if (missing) {
            return RuntimeDistributionSnapshot(
                if (manifest.deployable) RuntimeDistributionCode.INSTALL_REQUIRED else RuntimeDistributionCode.ARTIFACT_REQUIRED,
                desiredVersion = manifest.runtimeVersion,
                installedVersion = host.installedVersion,
                minisdSha256 = host.minisdSha256,
                provisionRevision = host.provisionRevision,
                rootfsHealth = health,
                detail = if (manifest.deployable) {
                    "runtime components are missing"
                } else {
                    "runtime components are missing and packaged artifact digests are unavailable"
                },
            )
        }
        if (health.code == RootfsHealthCode.CORRUPT || health.code == RootfsHealthCode.INCOMPATIBLE) {
            return RuntimeDistributionSnapshot(
                if (manifest.deployable) RuntimeDistributionCode.CORRUPT else RuntimeDistributionCode.ARTIFACT_REQUIRED,
                desiredVersion = manifest.runtimeVersion,
                installedVersion = host.installedVersion,
                minisdSha256 = host.minisdSha256,
                provisionRevision = host.provisionRevision,
                rootfsHealth = health,
                detail = health.detail,
            )
        }
        if (!manifest.deployable) {
            return RuntimeDistributionSnapshot(
                RuntimeDistributionCode.ARTIFACT_REQUIRED,
                desiredVersion = manifest.runtimeVersion,
                installedVersion = host.installedVersion,
                minisdSha256 = host.minisdSha256,
                provisionRevision = host.provisionRevision,
                rootfsHealth = health,
                detail = "packaged manifest is fail-closed until release artifacts and trusted digests are supplied",
            )
        }

        val installedMatches = host.installedVersion == manifest.runtimeVersion &&
            host.installedMinisdSha256 == manifest.minisd.sha256 &&
            host.installedRootfsSha256 == manifest.rootfs.sha256
        val binaryMatches = host.minisdSha256 == manifest.minisd.sha256
        val provisionMatches = host.provisionRevision == manifest.provisionRevision
        val metadataMatches = rootfsMatchesManifest(health, manifest)
        val code = when {
            installedMatches && binaryMatches && provisionMatches && metadataMatches -> RuntimeDistributionCode.READY
            host.installedVersion != manifest.runtimeVersion -> RuntimeDistributionCode.UPGRADE_REQUIRED
            else -> RuntimeDistributionCode.MIXED_VERSION
        }
        return RuntimeDistributionSnapshot(
            code,
            desiredVersion = manifest.runtimeVersion,
            installedVersion = host.installedVersion,
            minisdSha256 = host.minisdSha256,
            provisionRevision = host.provisionRevision,
            rootfsHealth = health,
            detail = when (code) {
                RuntimeDistributionCode.READY -> "runtime components match packaged manifest"
                RuntimeDistributionCode.UPGRADE_REQUIRED -> "installed runtime version does not match APK"
                else -> "runtime component versions/digests are mixed"
            },
        )
    }

    private fun loadPackagedManifest(): Pair<RuntimeDistributionManifest, String>? = runCatching {
        val raw = context.assets.open(RuntimeDistributionManifest.ASSET_PATH)
            .bufferedReader()
            .use { it.readText() }
        RuntimeDistributionManifest.parse(raw) to raw.trim()
    }.getOrNull()

    private data class RootResult(
        val ok: Boolean,
        val detail: String,
        val output: String = detail,
    )

    private fun verifyRoot(su: String): RootResult {
        val result = runSu(su, "id -u", ROOT_TIMEOUT_MS)
        if (!result.ok) return result
        val uid = result.output.lineSequence().mapNotNull { it.trim().toIntOrNull() }.firstOrNull()
        return if (uid == 0) {
            RootResult(true, "root authorized", result.output)
        } else {
            RootResult(false, "su returned uid=${uid ?: "unknown"}, expected 0", result.output)
        }
    }

    private fun runSu(su: String, command: String, timeoutMs: Long): RootResult {
        val process = try {
            ProcessBuilder(su, "-c", command).redirectErrorStream(true).start()
        } catch (t: Throwable) {
            return RootResult(false, "failed to start su: ${t.message}")
        }
        return try {
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor(1, TimeUnit.SECONDS)
                RootResult(false, "root command timed out")
            } else {
                val output = runCatching {
                    process.inputStream.bufferedReader().use { it.readText().trim() }
                }.getOrDefault("")
                if (process.exitValue() == 0) {
                    RootResult(true, output.ifBlank { "ok" }, output)
                } else {
                    RootResult(false, output.ifBlank { "root command exited ${process.exitValue()}" }, output)
                }
            }
        } finally {
            process.destroy()
        }
    }

    private fun findSu(): String? {
        val candidates = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/data/adb/ksu/bin/su",
            "/debug_ramdisk/su",
        )
        return candidates.firstOrNull { File(it).canExecute() }
            ?: System.getenv("PATH").orEmpty()
                .split(File.pathSeparatorChar)
                .asSequence()
                .map { File(it, "su") }
                .firstOrNull { it.canExecute() }
                ?.absolutePath
    }

    private fun rootRequired(manifest: RuntimeDistributionManifest, detail: String) =
        RuntimeDistributionSnapshot(
            RuntimeDistributionCode.ROOT_REQUIRED,
            desiredVersion = manifest.runtimeVersion,
            detail = "Root authorization required: $detail",
        )

    private fun setState(snapshot: RuntimeDistributionSnapshot): RuntimeDistributionSnapshot {
        _state.value = snapshot
        return snapshot
    }

    private fun result(snapshot: RuntimeDistributionSnapshot): RuntimeDistributionResult =
        RuntimeDistributionResult(snapshot.ready, setState(snapshot))

    companion object {
        private const val ROOT_TIMEOUT_MS = 15_000L
        private const val SWITCH_TIMEOUT_MS = 240_000L
        private const val TOOLCHAIN_TIMEOUT_MS = 600_000L
        internal const val RUNTIME_ROOT = "/data/adb/minis/runtime"
        internal const val INSTALLED_MANIFEST = "$RUNTIME_ROOT/installed.json"
        internal const val PROVISION_MARKER = "$RUNTIME_ROOT/provision.rev"
        internal const val PENDING_MARKER = "/data/adb/minis/run/runtime-upgrade.pending"
        internal const val HOST_MINISD = "/data/adb/minis/bin/minisd"
        internal const val HOST_ROOTFS = "/data/adb/minis/rootfs"

        private val REQUIRED_ROOTFS_LAYOUT = listOf(
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

        @Volatile
        private var instance: RuntimeDistributionManager? = null

        fun getInstance(context: Context): RuntimeDistributionManager =
            instance ?: synchronized(this) {
                instance ?: RuntimeDistributionManager(context.applicationContext).also { instance = it }
            }

        internal data class HostProbe(
            val minisdSha256: String? = null,
            val installedVersion: String? = null,
            val installedMinisdSha256: String? = null,
            val installedRootfsSha256: String? = null,
            val provisionRevision: Int? = null,
            val pendingVersion: String? = null,
        )

        internal fun supportsArm64(abis: List<String>): Boolean =
            abis.any { it == "arm64-v8a" || it == "arm64" || it == "aarch64" }

        internal fun buildProbeCommand(): String = listOf(
            "BIN=${shellQuote(HOST_MINISD)}",
            "INSTALLED=${shellQuote(INSTALLED_MANIFEST)}",
            "PROVISION=${shellQuote(PROVISION_MARKER)}",
            "PENDING=${shellQuote(PENDING_MARKER)}",
            "if [ -x \"${'$'}BIN\" ]; then echo \"MINIS_DIST:MINISD:${'$'}(sha256sum \"${'$'}BIN\" | awk '{print ${'$'}1}')\"; else echo 'MINIS_DIST:MINISD:MISSING'; fi",
            "if [ -r \"${'$'}PROVISION\" ]; then echo \"MINIS_DIST:PROVISION:${'$'}(cat \"${'$'}PROVISION\" 2>/dev/null || true)\"; else echo 'MINIS_DIST:PROVISION:MISSING'; fi",
            "if [ -r \"${'$'}PENDING\" ]; then echo \"MINIS_DIST:PENDING:${'$'}(sed -n 's/^version=//p' \"${'$'}PENDING\" | head -1)\"; else echo 'MINIS_DIST:PENDING:NONE'; fi",
            "if [ -r \"${'$'}INSTALLED\" ]; then echo 'MINIS_DIST:MANIFEST_BEGIN'; cat \"${'$'}INSTALLED\"; echo; echo 'MINIS_DIST:MANIFEST_END'; else echo 'MINIS_DIST:MANIFEST_MISSING'; fi",
        ).joinToString("\n")

        internal fun evaluateHostProbe(output: String): HostProbe {
            val lines = output.lineSequence().map { it.trim() }.toList()
            val minisd = lines.firstOrNull { it.startsWith("MINIS_DIST:MINISD:") }
                ?.substringAfter("MINIS_DIST:MINISD:")
                ?.takeIf { it != "MISSING" && RuntimeDistributionManifest.isSha256(it) }
            val provision = lines.firstOrNull { it.startsWith("MINIS_DIST:PROVISION:") }
                ?.substringAfter("MINIS_DIST:PROVISION:")
                ?.trim()
                ?.toIntOrNull()
            val pending = lines.firstOrNull { it.startsWith("MINIS_DIST:PENDING:") }
                ?.substringAfter("MINIS_DIST:PENDING:")
                ?.takeIf { it.isNotBlank() && it != "NONE" }
            val begin = lines.indexOf("MINIS_DIST:MANIFEST_BEGIN")
            val end = lines.indexOf("MINIS_DIST:MANIFEST_END")
            val installed = if (begin >= 0 && end > begin) {
                runCatching { JSONObject(lines.subList(begin + 1, end).joinToString("\n")) }.getOrNull()
            } else {
                null
            }
            return HostProbe(
                minisdSha256 = minisd,
                installedVersion = installed?.optString("runtimeVersion")?.takeIf { it.isNotBlank() },
                installedMinisdSha256 = installed?.optJSONObject("minisd")?.optString("sha256")?.takeIf {
                    RuntimeDistributionManifest.isSha256(it)
                },
                installedRootfsSha256 = installed?.optJSONObject("rootfs")?.optString("sha256")?.takeIf {
                    RuntimeDistributionManifest.isSha256(it)
                },
                provisionRevision = provision,
                pendingVersion = pending,
            )
        }

        internal fun rootfsMatchesManifest(
            health: RootfsHealth,
            manifest: RuntimeDistributionManifest,
        ): Boolean {
            if (!health.healthy) return false
            val metadata = health.metadata ?: return false
            if (!metadata.optString("release").startsWith(manifest.rootfs.release)) return false
            if (metadata.optString("profile") != manifest.rootfs.profile) return false
            val expectedUpstream = manifest.rootfs.upstreamSha256
            return expectedUpstream == null ||
                metadata.optString("upstream_sha256").equals(expectedUpstream, ignoreCase = true)
        }

        internal fun buildArtifactVerificationCommand(manifest: RuntimeDistributionManifest): String {
            require(manifest.deployable)
            val minisdSha = requireNotNull(manifest.minisd.sha256)
            val rootfsSha = requireNotNull(manifest.rootfs.sha256)
            return listOf(
                "MINISD_SRC=${shellQuote(manifest.minisd.stagedPath)}",
                "ROOTFS_SRC=${shellQuote(manifest.rootfs.stagedPath)}",
                "[ -s \"${'$'}MINISD_SRC\" ] || { echo 'staged minisd missing or empty' >&2; exit 61; }",
                "[ -s \"${'$'}ROOTFS_SRC\" ] || { echo 'staged rootfs missing or empty' >&2; exit 62; }",
                "actual_minisd=${'$'}(sha256sum \"${'$'}MINISD_SRC\" | awk '{print ${'$'}1}')",
                "[ \"${'$'}actual_minisd\" = ${shellQuote(minisdSha)} ] || { echo 'staged minisd digest mismatch' >&2; exit 63; }",
                "actual_rootfs=${'$'}(sha256sum \"${'$'}ROOTFS_SRC\" | awk '{print ${'$'}1}')",
                "[ \"${'$'}actual_rootfs\" = ${shellQuote(rootfsSha)} ] || { echo 'staged rootfs digest mismatch' >&2; exit 64; }",
                "echo 'MINIS_DIST:ARTIFACTS_VERIFIED'",
            ).joinToString("\n")
        }

        internal fun buildSwitchCommand(
            manifest: RuntimeDistributionManifest,
            rawManifest: String,
            appSocket: String,
        ): String {
            require(manifest.deployable)
            val version = safeVersion(manifest.runtimeVersion)
            val minisdSha = requireNotNull(manifest.minisd.sha256)
            val rootfsSha = requireNotNull(manifest.rootfs.sha256)
            val root = "/data/adb/minis"
            val newRoot = "$root/rootfs.next-$version"
            val oldRoot = "$root/rootfs.previous-$version"
            val newBin = "$root/bin/minisd.next-$version"
            val oldBin = "$root/bin/minisd.previous-$version"
            val newManifest = "$RUNTIME_ROOT/installed.next-$version.json"
            val oldManifest = "$RUNTIME_ROOT/installed.previous-$version.json"
            val oldProvision = "$RUNTIME_ROOT/provision.previous-$version.rev"
            val lines = mutableListOf<String>()
            lines += "set -eu"
            lines += "umask 077"
            lines += "ROOT=${shellQuote(root)}"
            lines += "MINISD_SRC=${shellQuote(manifest.minisd.stagedPath)}"
            lines += "ROOTFS_SRC=${shellQuote(manifest.rootfs.stagedPath)}"
            lines += "NEW_ROOT=${shellQuote(newRoot)}"
            lines += "OLD_ROOT=${shellQuote(oldRoot)}"
            lines += "NEW_BIN=${shellQuote(newBin)}"
            lines += "OLD_BIN=${shellQuote(oldBin)}"
            lines += "NEW_MANIFEST=${shellQuote(newManifest)}"
            lines += "OLD_MANIFEST=${shellQuote(oldManifest)}"
            lines += "OLD_PROVISION=${shellQuote(oldProvision)}"
            lines += "PENDING=${shellQuote(PENDING_MARKER)}"
            lines += "[ ! -e \"${'$'}PENDING\" ] || { echo 'runtime transaction already pending' >&2; exit 65; }"
            lines += "[ -s \"${'$'}MINISD_SRC\" ] && [ -s \"${'$'}ROOTFS_SRC\" ] || { echo 'runtime artifact missing' >&2; exit 66; }"
            lines += "[ \"${'$'}(sha256sum \"${'$'}MINISD_SRC\" | awk '{print ${'$'}1}')\" = ${shellQuote(minisdSha)} ] || { echo 'minisd digest changed after preflight' >&2; exit 67; }"
            lines += "[ \"${'$'}(sha256sum \"${'$'}ROOTFS_SRC\" | awk '{print ${'$'}1}')\" = ${shellQuote(rootfsSha)} ] || { echo 'rootfs digest changed after preflight' >&2; exit 68; }"
            lines += "mkdir -p \"${'$'}ROOT/bin\" ${shellQuote(RUNTIME_ROOT)} \"${'$'}ROOT/run\""
            lines += "rm -rf \"${'$'}NEW_ROOT\" \"${'$'}NEW_BIN\" \"${'$'}NEW_MANIFEST\" \"${'$'}OLD_ROOT\" \"${'$'}OLD_BIN\" \"${'$'}OLD_MANIFEST\" \"${'$'}OLD_PROVISION\""
            lines += "cp \"${'$'}MINISD_SRC\" \"${'$'}NEW_BIN\" && chmod 0755 \"${'$'}NEW_BIN\""
            lines += "[ \"${'$'}(sha256sum \"${'$'}NEW_BIN\" | awk '{print ${'$'}1}')\" = ${shellQuote(minisdSha)} ] || { echo 'prepared minisd digest mismatch' >&2; exit 69; }"
            lines += "mkdir -p \"${'$'}NEW_ROOT\""
            lines += "tar -xzf \"${'$'}ROOTFS_SRC\" -C \"${'$'}NEW_ROOT\" || { rm -rf \"${'$'}NEW_ROOT\"; exit 70; }"
            REQUIRED_ROOTFS_LAYOUT.forEach { rel ->
                lines += "[ -e \"${'$'}NEW_ROOT/$rel\" ] || { echo 'new rootfs missing $rel' >&2; rm -rf \"${'$'}NEW_ROOT\"; exit 71; }"
            }
            lines += "if [ ! -x \"${'$'}NEW_ROOT/bin/bash\" ] && [ ! -x \"${'$'}NEW_ROOT/usr/bin/bash\" ] && [ ! -x \"${'$'}NEW_ROOT/bin/sh\" ]; then echo 'new rootfs shell missing' >&2; rm -rf \"${'$'}NEW_ROOT\"; exit 72; fi"
            lines += "META=\"${'$'}NEW_ROOT/etc/minis/rootfs.json\""
            lines += "grep -Eq '\"distro\"[[:space:]]*:[[:space:]]*\"ubuntu\"' \"${'$'}META\" || exit 73"
            lines += "grep -Eq '\"release\"[[:space:]]*:[[:space:]]*\"${ereLiteral(manifest.rootfs.release)}' \"${'$'}META\" || exit 74"
            lines += "grep -Eq '\"profile\"[[:space:]]*:[[:space:]]*\"${ereLiteral(manifest.rootfs.profile)}\"' \"${'$'}META\" || exit 75"
            manifest.rootfs.upstreamSha256?.let { upstream ->
                lines += "grep -Eq '\"upstream_sha256\"[[:space:]]*:[[:space:]]*\"$upstream\"' \"${'$'}META\" || exit 76"
            }
            lines += "printf '%s\\n' ${shellQuote(rawManifest)} > \"${'$'}NEW_MANIFEST\""
            lines += MinisdBootstrap.runtimeSwitchShutdownCommand(appSocket)
            lines += "had_root=0; [ -e ${shellQuote(HOST_ROOTFS)} ] && had_root=1"
            lines += "had_bin=0; [ -e ${shellQuote(HOST_MINISD)} ] && had_bin=1"
            lines += "had_manifest=0; [ -e ${shellQuote(INSTALLED_MANIFEST)} ] && had_manifest=1"
            lines += "had_provision=0; [ -e ${shellQuote(PROVISION_MARKER)} ] && had_provision=1"
            lines += "printf 'version=%s\\nhad_root=%s\\nhad_bin=%s\\nhad_manifest=%s\\nhad_provision=%s\\n' ${shellQuote(version)} \"${'$'}had_root\" \"${'$'}had_bin\" \"${'$'}had_manifest\" \"${'$'}had_provision\" > \"${'$'}PENDING.tmp\""
            lines += "chmod 0600 \"${'$'}PENDING.tmp\" && mv \"${'$'}PENDING.tmp\" \"${'$'}PENDING\""
            lines += "[ \"${'$'}had_root\" = 0 ] || mv ${shellQuote(HOST_ROOTFS)} \"${'$'}OLD_ROOT\""
            lines += "[ \"${'$'}had_bin\" = 0 ] || mv ${shellQuote(HOST_MINISD)} \"${'$'}OLD_BIN\""
            lines += "[ \"${'$'}had_manifest\" = 0 ] || mv ${shellQuote(INSTALLED_MANIFEST)} \"${'$'}OLD_MANIFEST\""
            lines += "[ \"${'$'}had_provision\" = 0 ] || mv ${shellQuote(PROVISION_MARKER)} \"${'$'}OLD_PROVISION\""
            lines += "mv \"${'$'}NEW_ROOT\" ${shellQuote(HOST_ROOTFS)}"
            lines += "mv \"${'$'}NEW_BIN\" ${shellQuote(HOST_MINISD)}"
            lines += "mv \"${'$'}NEW_MANIFEST\" ${shellQuote(INSTALLED_MANIFEST)}"
            lines += "rm -f /data/adb/minis/run/minisd.pid /data/adb/minis/run/minisd.sock ${shellQuote(appSocket)}"
            lines += "echo 'MINIS_DIST:SWITCHED'"
            return lines.joinToString("\n")
        }

        internal fun buildRollbackCommand(): String = listOf(
            "set -eu",
            "PENDING=${shellQuote(PENDING_MARKER)}",
            "[ -r \"${'$'}PENDING\" ] || { echo 'MINIS_DIST:NO_PENDING_TRANSACTION'; exit 0; }",
            "version=${'$'}(sed -n 's/^version=//p' \"${'$'}PENDING\" | head -1)",
            "case \"${'$'}version\" in ''|*[!A-Za-z0-9._-]*) echo 'invalid pending runtime version' >&2; exit 90 ;; esac",
            "had_root=${'$'}(sed -n 's/^had_root=//p' \"${'$'}PENDING\" | head -1)",
            "had_bin=${'$'}(sed -n 's/^had_bin=//p' \"${'$'}PENDING\" | head -1)",
            "had_manifest=${'$'}(sed -n 's/^had_manifest=//p' \"${'$'}PENDING\" | head -1)",
            "had_provision=${'$'}(sed -n 's/^had_provision=//p' \"${'$'}PENDING\" | head -1)",
            "OLD_ROOT=\"/data/adb/minis/rootfs.previous-${'$'}version\"",
            "OLD_BIN=\"/data/adb/minis/bin/minisd.previous-${'$'}version\"",
            "OLD_MANIFEST=\"/data/adb/minis/runtime/installed.previous-${'$'}version.json\"",
            "OLD_PROVISION=\"/data/adb/minis/runtime/provision.previous-${'$'}version.rev\"",
            "if [ \"${'$'}had_root\" = 1 ]; then [ -e \"${'$'}OLD_ROOT\" ] || { echo 'rootfs rollback backup missing' >&2; exit 91; }; rm -rf ${shellQuote(HOST_ROOTFS)}; mv \"${'$'}OLD_ROOT\" ${shellQuote(HOST_ROOTFS)}; else rm -rf ${shellQuote(HOST_ROOTFS)}; fi",
            "if [ \"${'$'}had_bin\" = 1 ]; then [ -e \"${'$'}OLD_BIN\" ] || { echo 'minisd rollback backup missing' >&2; exit 92; }; rm -f ${shellQuote(HOST_MINISD)}; mv \"${'$'}OLD_BIN\" ${shellQuote(HOST_MINISD)}; else rm -f ${shellQuote(HOST_MINISD)}; fi",
            "if [ \"${'$'}had_manifest\" = 1 ]; then [ -e \"${'$'}OLD_MANIFEST\" ] || { echo 'manifest rollback backup missing' >&2; exit 93; }; rm -f ${shellQuote(INSTALLED_MANIFEST)}; mv \"${'$'}OLD_MANIFEST\" ${shellQuote(INSTALLED_MANIFEST)}; else rm -f ${shellQuote(INSTALLED_MANIFEST)}; fi",
            "if [ \"${'$'}had_provision\" = 1 ]; then [ -e \"${'$'}OLD_PROVISION\" ] || { echo 'provision rollback backup missing' >&2; exit 94; }; rm -f ${shellQuote(PROVISION_MARKER)}; mv \"${'$'}OLD_PROVISION\" ${shellQuote(PROVISION_MARKER)}; else rm -f ${shellQuote(PROVISION_MARKER)}; fi",
            "rm -rf \"/data/adb/minis/rootfs.next-${'$'}version\" \"/data/adb/minis/bin/minisd.next-${'$'}version\" \"/data/adb/minis/runtime/installed.next-${'$'}version.json\"",
            "rm -f /data/adb/minis/run/minisd.pid /data/adb/minis/run/minisd.sock",
            "rm -f \"${'$'}PENDING\"",
            "echo 'MINIS_DIST:ROLLED_BACK'",
        ).joinToString("\n")

        internal fun buildCommitCommand(runtimeVersion: String): String {
            val version = safeVersion(runtimeVersion)
            return listOf(
                "set -eu",
                "PENDING=${shellQuote(PENDING_MARKER)}",
                "[ -r \"${'$'}PENDING\" ] || { echo 'runtime transaction marker missing' >&2; exit 95; }",
                "pending=${'$'}(sed -n 's/^version=//p' \"${'$'}PENDING\" | head -1)",
                "[ \"${'$'}pending\" = ${shellQuote(version)} ] || { echo 'runtime transaction version changed' >&2; exit 96; }",
                "rm -rf ${shellQuote("/data/adb/minis/rootfs.previous-$version")}",
                "rm -f ${shellQuote("/data/adb/minis/bin/minisd.previous-$version")}",
                "rm -f ${shellQuote("/data/adb/minis/runtime/installed.previous-$version.json")}",
                "rm -f ${shellQuote("/data/adb/minis/runtime/provision.previous-$version.rev")}",
                "rm -f \"${'$'}PENDING\"",
                "echo 'MINIS_DIST:COMMITTED'",
            ).joinToString("\n")
        }

        internal fun buildProvisionMarkerCommand(revision: Int): String {
            require(revision > 0)
            return listOf(
                "mkdir -p ${shellQuote(RUNTIME_ROOT)}",
                "umask 077",
                "printf '%s\\n' ${shellQuote(revision.toString())} > ${shellQuote("$PROVISION_MARKER.tmp")}",
                "mv ${shellQuote("$PROVISION_MARKER.tmp")} ${shellQuote(PROVISION_MARKER)}",
            ).joinToString("\n")
        }

        private fun safeVersion(value: String): String {
            require(value.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$"))) { "unsafe runtimeVersion" }
            return value
        }

        private fun ereLiteral(value: String): String = value.replace(".", "\\.")

        private fun shellWord(value: String): String {
            require(value.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._+-]{0,63}$")))
            return value
        }

        private fun shellQuote(value: String): String =
            "'" + value.replace("'", "'\"'\"'") + "'"
    }
}
