package io.github.slackerllc.minis.runtime.distribution

import io.github.slackerllc.minis.runtime.minisd.MinisdProtocol
import org.json.JSONObject

/** One authoritative APK-declared runtime identity for Issue #51. */
data class RuntimeDistributionManifest(
    val schemaVersion: Int,
    val runtimeVersion: String,
    val minisdVersion: String,
    val minisdSha256: String,
    val protocolVersion: Int,
    val layoutVersion: Int,
    val abi: String,
    val rootfsVersion: String,
    val rootfsSha256: String,
    val rootfsRelease: String,
    val rootfsProfile: String,
    val rootfsUpstreamSha256: String,
    val provisionRevision: Int,
    val requiredCommands: List<String>,
) {
    companion object {
        const val ASSET_PATH = "minis-runtime/runtime-manifest.json"
        const val ROOTFS_ASSET_PATH = "minis-runtime/ubuntu-arm64-rootfs.tar.gz"
        const val CURRENT_SCHEMA_VERSION = 2
        const val CURRENT_LAYOUT_VERSION = 2
        const val SUPPORTED_ABI = "arm64-v8a"

        val PROTECTED_DATA_ROOTS = listOf(
            "/data/adb/minis/workspace",
            "/data/adb/minis/sessions",
            "/data/adb/minis/memory",
            "/data/adb/minis/skills",
            "/data/adb/minis/shared",
            "/data/adb/minis/home",
        )

        private val SHA256 = Regex("^[0-9a-f]{64}$")
        private val VERSION = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,95}$")
        private val ROOTFS_VERSION = Regex("^ubuntu-24\\.04-r[1-9][0-9]*-[0-9a-f]{16}$")
        private val COMMAND = Regex("^[A-Za-z0-9][A-Za-z0-9._+-]{0,63}$")

        fun parse(raw: String): RuntimeDistributionManifest {
            val obj = JSONObject(raw)
            val schemaVersion = obj.requirePositiveInt("schemaVersion")
            require(schemaVersion == CURRENT_SCHEMA_VERSION) {
                "unsupported runtime manifest schemaVersion=$schemaVersion"
            }
            val runtimeVersion = obj.requireVersion("runtimeVersion")
            val minisdVersion = obj.requireVersion("minisdVersion")
            val minisdSha256 = obj.requireSha("minisdSha256")
            val protocolVersion = obj.requirePositiveInt("protocolVersion")
            require(protocolVersion == MinisdProtocol.PROTOCOL_V) {
                "runtime protocolVersion=$protocolVersion does not match APK protocol=${MinisdProtocol.PROTOCOL_V}"
            }
            val layoutVersion = obj.requirePositiveInt("layoutVersion")
            require(layoutVersion == CURRENT_LAYOUT_VERSION) {
                "unsupported runtime layoutVersion=$layoutVersion"
            }
            val abi = obj.requireString("abi")
            require(abi == SUPPORTED_ABI) { "unsupported runtime ABI: $abi" }

            val rootfsVersion = obj.requireString("rootfsVersion")
            require(ROOTFS_VERSION.matches(rootfsVersion)) { "invalid rootfsVersion" }
            val rootfsSha256 = obj.requireSha("rootfsSha256")
            require(rootfsVersion.endsWith(rootfsSha256.take(16))) {
                "rootfsVersion must be derived from the final rootfs artifact SHA-256"
            }
            val rootfsRelease = obj.requireVersion("rootfsRelease")
            require(rootfsRelease.startsWith("24.04")) { "unsupported rootfsRelease: $rootfsRelease" }
            val rootfsProfile = obj.requireVersion("rootfsProfile")
            require(rootfsProfile == "base") { "unsupported rootfsProfile: $rootfsProfile" }
            val rootfsUpstreamSha256 = obj.requireSha("rootfsUpstreamSha256")
            val provisionRevision = obj.requirePositiveInt("provisionRevision")

            val commands = obj.optJSONArray("requiredCommands")
                ?: throw IllegalArgumentException("missing requiredCommands")
            val requiredCommands = (0 until commands.length()).map { index ->
                commands.getString(index).also { command ->
                    require(COMMAND.matches(command)) { "invalid required command: $command" }
                }
            }.distinct()
            require(requiredCommands.isNotEmpty()) { "requiredCommands must not be empty" }

            return RuntimeDistributionManifest(
                schemaVersion = schemaVersion,
                runtimeVersion = runtimeVersion,
                minisdVersion = minisdVersion,
                minisdSha256 = minisdSha256,
                protocolVersion = protocolVersion,
                layoutVersion = layoutVersion,
                abi = abi,
                rootfsVersion = rootfsVersion,
                rootfsSha256 = rootfsSha256,
                rootfsRelease = rootfsRelease,
                rootfsProfile = rootfsProfile,
                rootfsUpstreamSha256 = rootfsUpstreamSha256,
                provisionRevision = provisionRevision,
                requiredCommands = requiredCommands,
            )
        }

        fun isSha256(value: String): Boolean = SHA256.matches(value)

        private fun JSONObject.requireString(key: String): String =
            optString(key).trim().takeIf { it.isNotEmpty() && it != "managed" }
                ?: throw IllegalArgumentException("missing or placeholder string: $key")

        private fun JSONObject.requireVersion(key: String): String = requireString(key).also {
            require(VERSION.matches(it)) { "invalid $key" }
        }

        private fun JSONObject.requireSha(key: String): String = requireString(key).lowercase().also {
            require(SHA256.matches(it)) { "invalid $key" }
        }

        private fun JSONObject.requirePositiveInt(key: String): Int {
            require(has(key) && !isNull(key)) { "missing integer: $key" }
            return getInt(key).also { require(it > 0) { "$key must be positive" } }
        }
    }
}
