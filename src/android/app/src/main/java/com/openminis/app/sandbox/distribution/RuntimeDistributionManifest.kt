package com.openminis.app.sandbox.distribution

import org.json.JSONObject

/**
 * APK-declared desired runtime. Artifact hashes are trusted only when they are
 * part of the packaged manifest; a staged sidecar manifest is never accepted
 * as authority for replacement.
 */
data class RuntimeDistributionManifest(
    val schemaVersion: Int,
    val runtimeVersion: String,
    val protocolVersion: Int,
    val layoutVersion: Int,
    val abi: String,
    val distributionReady: Boolean,
    val minisd: Artifact,
    val rootfs: RootfsArtifact,
    val provisionRevision: Int,
    val requiredCommands: List<String>,
) {
    data class Artifact(
        val source: Source,
        val file: String,
        val stagedPath: String,
        val sha256: String?,
    )

    data class RootfsArtifact(
        val source: Source,
        val file: String,
        val stagedPath: String,
        val sha256: String?,
        val version: String,
        val release: String,
        val profile: String,
        val upstreamSha256: String?,
    )

    enum class Source(val wireName: String) {
        EXTERNAL_STAGED("external_staged");

        companion object {
            fun parse(raw: String): Source =
                entries.firstOrNull { it.wireName == raw }
                    ?: throw IllegalArgumentException("unsupported runtime artifact source: $raw")
        }
    }

    val deployable: Boolean
        get() = distributionReady && minisd.sha256 != null && rootfs.sha256 != null

    companion object {
        const val ASSET_PATH = "runtime-distribution.json"
        const val CURRENT_SCHEMA_VERSION = 1
        const val SUPPORTED_ABI = "arm64"
        const val STAGING_ROOT = "/data/local/tmp/minis-runtime"

        val PROTECTED_DATA_ROOTS = listOf(
            "/data/adb/minis/workspace",
            "/data/adb/minis/sessions",
            "/data/adb/minis/memory",
            "/data/adb/minis/skills",
            "/data/adb/minis/shared",
            "/data/adb/minis/home",
        )

        private val SHA256 = Regex("^[0-9a-fA-F]{64}$")
        private val VERSION = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
        private val COMMAND = Regex("^[A-Za-z0-9][A-Za-z0-9._+-]{0,63}$")

        fun parse(raw: String): RuntimeDistributionManifest {
            val obj = JSONObject(raw)
            val schemaVersion = obj.requirePositiveInt("schemaVersion")
            require(schemaVersion == CURRENT_SCHEMA_VERSION) {
                "unsupported runtime manifest schemaVersion=$schemaVersion"
            }
            val runtimeVersion = obj.requireString("runtimeVersion")
            require(VERSION.matches(runtimeVersion)) { "invalid runtimeVersion" }
            val protocolVersion = obj.requirePositiveInt("protocolVersion")
            val layoutVersion = obj.requirePositiveInt("layoutVersion")
            val abi = obj.requireString("abi")
            require(abi == SUPPORTED_ABI) { "unsupported runtime ABI: $abi" }
            val distributionReady = obj.optBoolean("distributionReady", false)

            val minisdObj = obj.requireObject("minisd")
            val minisd = Artifact(
                source = Source.parse(minisdObj.requireString("source")),
                file = minisdObj.requireSafeFile("file"),
                stagedPath = minisdObj.requireStagedPath("stagedPath"),
                sha256 = minisdObj.optionalSha("sha256"),
            )

            val rootfsObj = obj.requireObject("rootfs")
            val rootfs = RootfsArtifact(
                source = Source.parse(rootfsObj.requireString("source")),
                file = rootfsObj.requireSafeFile("file"),
                stagedPath = rootfsObj.requireStagedPath("stagedPath"),
                sha256 = rootfsObj.optionalSha("sha256"),
                version = rootfsObj.requireString("version"),
                release = rootfsObj.requireString("release"),
                profile = rootfsObj.requireString("profile"),
                upstreamSha256 = rootfsObj.optionalSha("upstreamSha256"),
            )
            require(rootfs.release.startsWith("24.04")) { "unsupported rootfs release: ${rootfs.release}" }
            require(rootfs.profile == "base") { "unsupported rootfs profile: ${rootfs.profile}" }

            val provisionRevision = obj.requirePositiveInt("provisionRevision")
            val commandsArray = obj.optJSONArray("requiredCommands")
                ?: throw IllegalArgumentException("missing requiredCommands")
            val requiredCommands = (0 until commandsArray.length()).map { index ->
                commandsArray.getString(index).also { command ->
                    require(COMMAND.matches(command)) { "invalid required command: $command" }
                }
            }.distinct()
            require(requiredCommands.isNotEmpty()) { "requiredCommands must not be empty" }

            if (distributionReady) {
                require(minisd.sha256 != null) { "distributionReady requires minisd sha256" }
                require(rootfs.sha256 != null) { "distributionReady requires rootfs sha256" }
            }

            return RuntimeDistributionManifest(
                schemaVersion = schemaVersion,
                runtimeVersion = runtimeVersion,
                protocolVersion = protocolVersion,
                layoutVersion = layoutVersion,
                abi = abi,
                distributionReady = distributionReady,
                minisd = minisd,
                rootfs = rootfs,
                provisionRevision = provisionRevision,
                requiredCommands = requiredCommands,
            )
        }

        internal fun isSha256(value: String): Boolean = SHA256.matches(value)

        private fun JSONObject.requireObject(key: String): JSONObject =
            optJSONObject(key) ?: throw IllegalArgumentException("missing object: $key")

        private fun JSONObject.requireString(key: String): String =
            optString(key).trim().takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("missing string: $key")

        private fun JSONObject.requirePositiveInt(key: String): Int {
            require(has(key)) { "missing integer: $key" }
            return getInt(key).also { require(it > 0) { "$key must be positive" } }
        }

        private fun JSONObject.requireSafeFile(key: String): String {
            val value = requireString(key)
            require(!value.contains('/') && value != "." && value != "..") { "unsafe artifact file: $value" }
            return value
        }

        private fun JSONObject.requireStagedPath(key: String): String {
            val value = requireString(key)
            require(value.startsWith("$STAGING_ROOT/") && !value.contains("/../")) {
                "artifact stagedPath must stay under $STAGING_ROOT"
            }
            return value
        }

        private fun JSONObject.optionalSha(key: String): String? {
            if (!has(key) || isNull(key)) return null
            val value = getString(key).trim().lowercase()
            require(SHA256.matches(value)) { "invalid $key" }
            return value
        }
    }
}
