package com.openminis.app.runtime.distribution

import org.json.JSONObject

/**
 * Strict schema-v2 runtime manifest consumed from the APK payload. Parsing
 * fails closed on any unknown, missing, or stale field so a mismatched payload
 * can never be treated as the active runtime identity.
 */
data class RuntimeDistributionManifest(
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
        const val SCHEMA_VERSION = 2
        const val PROTOCOL_VERSION = 1
        const val LAYOUT_VERSION = 2
        const val ABI_ARM64 = "arm64-v8a"
        const val ROOTFS_PROFILE = "base"
        const val PINNED_UPSTREAM_SHA256 =
            "7b2dced6dd56ad5e4a813fa25c8de307b655fdabc6ea9213175a92c48dabb048"
        val REQUIRED_COMMANDS = listOf("python3", "git", "curl")

        private val SHA256 = Regex("^[0-9a-f]{64}$")
        private val ROOTFS_VERSION = Regex("^ubuntu-24\\.04-r[1-9][0-9]*-[0-9a-f]{16}$")
        private val SEMANTIC_VERSION = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$")

        fun parse(raw: String): RuntimeDistributionManifest {
            val root = try {
                JSONObject(raw)
            } catch (t: Throwable) {
                throw IllegalArgumentException("runtime manifest is not valid JSON: ${t.message}")
            }
            val schema = root.optInt("schemaVersion", -1)
            require(schema == SCHEMA_VERSION) {
                "runtime manifest schemaVersion must be $SCHEMA_VERSION"
            }
            val protocol = root.optInt("protocolVersion", -1)
            require(protocol == PROTOCOL_VERSION) {
                "runtime manifest protocolVersion must be $PROTOCOL_VERSION"
            }
            val layout = root.optInt("layoutVersion", -1)
            require(layout == LAYOUT_VERSION) {
                "runtime manifest layoutVersion must be $LAYOUT_VERSION"
            }
            val abi = root.optString("abi")
            require(abi == ABI_ARM64) { "runtime manifest abi must be $ABI_ARM64" }

            val minisdSha = requireSha256(root, "minisdSha256")
            val rootfsSha = requireSha256(root, "rootfsSha256")
            val minisdVersion = root.optString("minisdVersion")
            require(SEMANTIC_VERSION.matches(minisdVersion)) {
                "runtime manifest has invalid minisdVersion: $minisdVersion"
            }
            val rootfsVersion = root.optString("rootfsVersion")
            require(ROOTFS_VERSION.matches(rootfsVersion)) {
                "runtime manifest has invalid rootfsVersion: $rootfsVersion"
            }
            require(rootfsVersion.endsWith(rootfsSha.take(16))) {
                "runtime rootfsVersion must be derived from rootfsSha256"
            }
            val release = root.optString("rootfsRelease")
            require(release.startsWith("24.04")) {
                "runtime manifest has unsupported rootfsRelease: $release"
            }
            val profile = root.optString("rootfsProfile")
            require(profile == ROOTFS_PROFILE) {
                "runtime manifest rootfsProfile must be $ROOTFS_PROFILE"
            }
            val upstream = root.optString("rootfsUpstreamSha256").lowercase()
            require(upstream == PINNED_UPSTREAM_SHA256) {
                "runtime manifest does not use the pinned Ubuntu upstream SHA-256"
            }
            val provisionRevision = root.optInt("provisionRevision", 0)
            require(provisionRevision > 0) {
                "runtime manifest provisionRevision must be positive"
            }
            val commands = root.optJSONArray("requiredCommands")
            val requiredCommands = (0 until (commands?.length() ?: 0))
                .map { commands!!.getString(it) }
            require(requiredCommands == REQUIRED_COMMANDS) {
                "runtime manifest requiredCommands mismatch"
            }
            return RuntimeDistributionManifest(
                minisdVersion = minisdVersion,
                minisdSha256 = minisdSha,
                protocolVersion = protocol,
                layoutVersion = layout,
                abi = abi,
                rootfsVersion = rootfsVersion,
                rootfsSha256 = rootfsSha,
                rootfsRelease = release,
                rootfsProfile = profile,
                rootfsUpstreamSha256 = upstream,
                provisionRevision = provisionRevision,
                requiredCommands = requiredCommands,
            )
        }

        private fun requireSha256(root: JSONObject, key: String): String {
            val value = root.optString(key)
            require(SHA256.matches(value)) { "runtime manifest has invalid $key" }
            return value
        }
    }
}
