package com.openminis.app.sandbox

import android.content.Context
import android.util.Log
import com.openminis.app.runtime.ubuntu.UbuntuKernel
import com.openminis.app.runtime.ubuntu.UbuntuPaths
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

    val rootfsDir: File = File(UbuntuPaths.HOST_ROOTFS)

    val isInstalled: Boolean
        get() = _installState.value is RootfsInstallState.Installed

    private val _installState = MutableStateFlow<RootfsInstallState>(RootfsInstallState.Idle)
    val installState: StateFlow<RootfsInstallState> = _installState.asStateFlow()

    suspend fun checkHealth(): RootfsHealth = withContext(Dispatchers.IO) {
        if (!UbuntuRuntime.isInitialized) UbuntuRuntime.init(context)
        UbuntuKernel.inspectRootfs()
    }

    suspend fun installIfNeeded() = withContext(Dispatchers.IO) {
        if (!UbuntuRuntime.isInitialized) UbuntuRuntime.init(context)
        _installState.value = RootfsInstallState.Preparing
        val before = UbuntuKernel.inspectRootfs()
        if (before.healthy) {
            _installState.value = RootfsInstallState.Installed
            return@withContext
        }
        if (before.code == RootfsHealthCode.ROOT_UNAVAILABLE) {
            _installState.value = RootfsInstallState.Failed(before.detail)
            return@withContext
        }
        _installState.value = RootfsInstallState.Extracting(0f)
        // Recovery replaces Root-owned runtime state. No interactive PTY may
        // keep using a namespace backed by the old rootfs while that happens.
        val after = UbuntuRuntime.withRuntimeStopped { UbuntuKernel.ensureRootfs() }
        _installState.value = if (after.healthy) {
            RootfsInstallState.Installed
        } else {
            RootfsInstallState.Failed(after.detail)
        }
    }

    suspend fun reset(keepUserData: Boolean = false): File? = withContext(Dispatchers.IO) {
        if (keepUserData) Log.i(TAG, "reset: app-owned persistent user data will be preserved")
        if (!UbuntuRuntime.isInitialized) UbuntuRuntime.init(context)
        val reset = UbuntuRuntime.withRuntimeStopped { UbuntuKernel.resetRootfs() }
        if (!reset) {
            val detail = "failed to reset Ubuntu rootfs"
            _installState.value = RootfsInstallState.Failed(detail)
            throw IllegalStateException(detail)
        }
        _installState.value = RootfsInstallState.Idle
        null
    }

    suspend fun getRootfsSize(): Long = checkHealth().sizeBytes ?: 0L

    suspend fun restoreUserData(backupDir: File) = withContext(Dispatchers.IO) {
        Log.i(TAG, "restoreUserData ignored for ${backupDir.path}: persistent data is not stored in rootfs")
    }

    fun getSystemDnsServers(): List<String> {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                ?: return FALLBACK_DNS_SERVERS
            val activeNetwork = cm.activeNetwork ?: return FALLBACK_DNS_SERVERS
            val linkProps = cm.getLinkProperties(activeNetwork) ?: return FALLBACK_DNS_SERVERS
            val servers = linkProps.dnsServers.mapNotNull { it.hostAddress }.filter { it.isNotBlank() }
            if (servers.isEmpty()) FALLBACK_DNS_SERVERS else servers
        } catch (t: Throwable) {
            Log.w(TAG, "failed to get system DNS servers: ${t.message}")
            FALLBACK_DNS_SERVERS
        }
    }

    suspend fun refreshDns(servers: List<String>? = null): Boolean = withContext(Dispatchers.IO) {
        dnsRefreshCoordinator.refresh({ servers ?: getSystemDnsServers() }) { nameservers ->
            if (!UbuntuRuntime.isInitialized) UbuntuRuntime.init(context)
            UbuntuKernel.refreshDns(nameservers)
        }
    }

    /**
     * Update one of the small, user-selectable Ubuntu configuration files.
     * These files are Root-owned with the rootfs, so the write must go through
     * the bounded Root infrastructure path rather than an Android-side File.
     */
    suspend fun writeManagedRootfsConfig(relativePath: String, content: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!isManagedRootfsConfig(relativePath) ||
                content.toByteArray(Charsets.UTF_8).size > MAX_MANAGED_CONFIG_BYTES ||
                content.contains('\u0000')
            ) {
                return@withContext false
            }
            if (!UbuntuRuntime.isInitialized) UbuntuRuntime.init(context)
            UbuntuRuntime.withRuntimeStopped {
                UbuntuKernel.writeManagedRootfsConfig(relativePath, content)
            }
        }

    /** Restore a previously backed-up managed config through Root. */
    suspend fun restoreManagedRootfsConfig(relativePath: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!isManagedRootfsConfig(relativePath)) return@withContext false
            if (!UbuntuRuntime.isInitialized) UbuntuRuntime.init(context)
            UbuntuRuntime.withRuntimeStopped {
                UbuntuKernel.restoreManagedRootfsConfig(relativePath)
            }
        }

    private val dnsRefreshCoordinator = com.openminis.app.runtime.ubuntu.DnsRefreshCoordinator()

    companion object {
        private const val TAG = "RootfsManager"
        val FALLBACK_DNS_SERVERS = listOf("223.5.5.5", "1.1.1.1", "8.8.8.8")

        fun formatResolvConf(nameservers: List<String>): String {
            val servers = if (nameservers.isEmpty()) FALLBACK_DNS_SERVERS else nameservers
            return servers.joinToString(separator = "\n", postfix = "\n") { "nameserver $it" }
        }

        internal const val MAX_MANAGED_CONFIG_BYTES = 64 * 1024
        private val MANAGED_ROOTFS_CONFIGS = setOf(
            "etc/apt/sources.list.d/ubuntu.sources",
            "etc/pip/pip.conf",
            "root/.npmrc",
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

        internal fun isManagedRootfsConfig(relativePath: String): Boolean =
            relativePath in MANAGED_ROOTFS_CONFIGS

        internal fun buildProbeCommand(rootfs: String): String {
            val commands = mutableListOf<String>()
            commands += "ROOTFS=${shellQuote(rootfs)}"
            commands += "[ -d \"\$ROOTFS\" ] && [ ! -L \"\$ROOTFS\" ] || { echo 'MINIS_ROOTFS:MISSING'; exit 0; }"
            REQUIRED_LAYOUT.forEach { rel ->
                val checks = if (rel == "etc/os-release") {
                    osReleaseLayoutChecks(rootfs)
                } else {
                    noSymlinkLayoutChecks(rootfs, rel)
                }
                commands += "$checks || { echo 'MINIS_ROOTFS:CORRUPT:$rel'; exit 0; }"
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
                val checks = if (rel == "etc/os-release") {
                    osReleaseVariableLayoutChecks("NEW")
                } else {
                    noSymlinkVariableLayoutChecks("NEW", rel)
                }
                commands += "$checks || { echo 'recovery rootfs missing $rel' >&2; rm -rf \"\$NEW\"; exit 74; }"
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

        /**
         * Check every component of a required rootfs entry without following a
         * symlink in the rootfs tree. The rootfs is Root-owned, but a corrupt or
         * partially replaced image must fail closed before chroot/mount setup.
         */
        private fun noSymlinkLayoutChecks(rootfs: String, relativePath: String): String {
            val root = rootfs.trimEnd('/')
            var current = root
            val checks = mutableListOf(
                "[ -d ${shellQuote(current)} ]",
                "[ ! -L ${shellQuote(current)} ]",
            )
            for (component in relativePath.split('/').filter { it.isNotEmpty() }) {
                current = "$current/$component"
                checks += "[ -e ${shellQuote(current)} ]"
                checks += "[ ! -L ${shellQuote(current)} ]"
            }
            return checks.joinToString(" && ")
        }

        /**
         * Ubuntu's standard /etc/os-release is a relative symlink into /usr.
         * Permit only that exact in-tree target; every other rootfs path keeps
         * the strict no-symlink validation above.
         */
        private fun osReleaseLayoutChecks(rootfs: String): String {
            val root = rootfs.trimEnd('/')
            val etc = "$root/etc"
            val link = "$etc/os-release"
            val target = "$root/usr/lib/os-release"
            return listOf(
                "[ -d ${shellQuote(root)} ]",
                "[ ! -L ${shellQuote(root)} ]",
                "[ -d ${shellQuote(etc)} ]",
                "[ ! -L ${shellQuote(etc)} ]",
                "(if [ -L ${shellQuote(link)} ]; then " +
                    "[ \"\$(readlink ${shellQuote(link)})\" = '../usr/lib/os-release' ] && " +
                    "[ -f ${shellQuote(target)} ] && [ ! -L ${shellQuote(target)} ]; " +
                    "else [ -e ${shellQuote(link)} ] && [ ! -L ${shellQuote(link)} ]; fi)",
            ).joinToString(" && ")
        }

        private fun noSymlinkVariableLayoutChecks(variableName: String, relativePath: String): String {
            var current = "\$$variableName"
            val checks = mutableListOf(
                "[ -d \"$current\" ]",
                "[ ! -L \"$current\" ]",
            )
            for (component in relativePath.split('/').filter { it.isNotEmpty() }) {
                current = "$current/$component"
                checks += "[ -e \"$current\" ]"
                checks += "[ ! -L \"$current\" ]"
            }
            return checks.joinToString(" && ")
        }

        private fun osReleaseVariableLayoutChecks(variableName: String): String {
            val root = "\$$variableName"
            val link = "\"$root/etc/os-release\""
            val target = "\"$root/usr/lib/os-release\""
            return listOf(
                "[ -d \"$root\" ]",
                "[ ! -L \"$root\" ]",
                "[ -d \"$root/etc\" ]",
                "[ ! -L \"$root/etc\" ]",
                "(if [ -L $link ]; then " +
                    "[ \"\$(readlink $link)\" = '../usr/lib/os-release' ] && " +
                    "[ -f $target ] && [ ! -L $target ]; " +
                    "else [ -e $link ] && [ ! -L $link ]; fi)",
            ).joinToString(" && ")
        }
    }
}
