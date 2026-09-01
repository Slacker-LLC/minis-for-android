package com.openminis.app.runtime.distribution

import android.content.Context
import com.openminis.app.runtime.minisd.MinisdBootstrap
import com.openminis.app.runtime.minisd.MinisdProtocol
import com.openminis.app.runtime.ubuntu.RootfsHealth
import com.openminis.app.runtime.ubuntu.RootfsHealthCode
import com.openminis.app.runtime.ubuntu.RuntimeProvision
import com.openminis.app.sandbox.RootfsManager
import org.json.JSONObject

/**
 * Single active owner of runtime deployment, upgrade, and rollback.
 *
 * The canonical guest rootfs remains `/data/adb/minis/rootfs` per the storage
 * contract. Versioned state lives under `/data/adb/minis/runtime/`:
 *
 * - `staging/` holds the extracted APK rootfs before the atomic switch;
 * - `previous/rootfs` is the single rollback slot;
 * - `pending.json` records an in-flight switch so a killed App can resume or
 *   roll back on the next launch;
 * - `deployed.json` records the committed runtime identity.
 *
 * User data directories are never named in any delete or replace command.
 */
object RuntimeDistributionManager {
    const val RUNTIME_DIR = "/data/adb/minis/runtime"
    const val STAGING_DIR = "$RUNTIME_DIR/staging"
    const val PREVIOUS_ROOTFS = "$RUNTIME_DIR/previous/rootfs"
    const val PENDING_FILE = "$RUNTIME_DIR/pending.json"
    const val DEPLOYED_FILE = "$RUNTIME_DIR/deployed.json"
    const val STATE_SCHEMA_VERSION = 2
    private const val PENDING_SCHEMA_VERSION = 3

    enum class DeploymentOutcome {
        MATCHED,
        DEPLOYED,
        RECOVERED,
        ROLLED_BACK,
        RESET,
        FAILED,
        ROOT_UNAVAILABLE,
        PAYLOAD_INVALID,
    }

    data class DeploymentResult(val outcome: DeploymentOutcome, val detail: String)

    fun interface RootRunner {
        fun run(command: String): RootCommandResult
    }

    data class RootCommandResult(
        val completed: Boolean,
        val exitCode: Int,
        val output: String,
        val error: String? = null,
    )

    data class DeployedIdentity(
        val rootfsVersion: String,
        val rootfsSha256: String,
        val minisdSha256: String,
        val provisionRevision: Int,
    ) {
        fun matches(manifest: RuntimeDistributionManifest): Boolean =
            rootfsVersion == manifest.rootfsVersion &&
                rootfsSha256 == manifest.rootfsSha256 &&
                minisdSha256 == manifest.minisdSha256 &&
                provisionRevision == manifest.provisionRevision

        companion object {
            fun parse(raw: String): DeployedIdentity {
                val root = try {
                    JSONObject(raw)
                } catch (t: Throwable) {
                    throw IllegalArgumentException("deployed identity is not valid JSON: ${t.message}")
                }
                val schema = root.optInt("schemaVersion", -1)
                require(schema == STATE_SCHEMA_VERSION) { "deployed identity schemaVersion mismatch" }
                val rootfsVersion = root.optString("rootfsVersion")
                val rootfsSha256 = root.optString("rootfsSha256")
                val minisdSha256 = root.optString("minisdSha256")
                val revision = root.optInt("provisionRevision", 0)
                require(
                    rootfsVersion.isNotEmpty() &&
                        rootfsSha256.matches(Regex("^[0-9a-f]{64}$")) &&
                        minisdSha256.matches(Regex("^[0-9a-f]{64}$")) &&
                        revision > 0,
                ) { "deployed identity has invalid fields" }
                return DeployedIdentity(rootfsVersion, rootfsSha256, minisdSha256, revision)
            }
        }
    }

    data class PendingTransaction(
        val transactionId: String,
        val targetRootfsVersion: String,
        val targetRootfsSha256: String,
        val targetMinisdSha256: String,
        val targetProvisionRevision: Int,
        val previousRootfsVersion: String? = null,
    ) {
        fun matches(manifest: RuntimeDistributionManifest): Boolean =
            targetRootfsVersion == manifest.rootfsVersion &&
                targetRootfsSha256 == manifest.rootfsSha256 &&
                targetMinisdSha256 == manifest.minisdSha256 &&
                targetProvisionRevision == manifest.provisionRevision

        fun toJson(): String = JSONObject()
            .put("schemaVersion", PENDING_SCHEMA_VERSION)
            .put("transactionId", transactionId)
            .put("targetRootfsVersion", targetRootfsVersion)
            .put("targetRootfsSha256", targetRootfsSha256)
            .put("targetMinisdSha256", targetMinisdSha256)
            .put("targetProvisionRevision", targetProvisionRevision)
            .put("previousRootfsVersion", previousRootfsVersion ?: JSONObject.NULL)
            .toString()

        companion object {
            fun parse(raw: String): PendingTransaction {
                val root = try {
                    JSONObject(raw)
                } catch (t: Throwable) {
                    throw IllegalArgumentException("pending transaction is not valid JSON: ${t.message}")
                }
                val schema = root.optInt("schemaVersion", -1)
                require(schema == PENDING_SCHEMA_VERSION) { "pending transaction schemaVersion mismatch" }
                val transactionId = root.optString("transactionId")
                val targetRootfsVersion = root.optString("targetRootfsVersion")
                val targetRootfsSha256 = root.optString("targetRootfsSha256")
                val targetMinisdSha256 = root.optString("targetMinisdSha256")
                val targetProvisionRevision = root.optInt("targetProvisionRevision", 0)
                require(
                    transactionId.isNotEmpty() &&
                        targetRootfsVersion.isNotEmpty() &&
                        targetRootfsSha256.matches(Regex("^[0-9a-f]{64}$")) &&
                        targetMinisdSha256.matches(Regex("^[0-9a-f]{64}$")) &&
                        targetProvisionRevision > 0,
                ) { "pending transaction has invalid fields" }
                val previous = if (root.isNull("previousRootfsVersion")) {
                    null
                } else {
                    root.optString("previousRootfsVersion").takeIf { it.isNotEmpty() }
                }
                return PendingTransaction(
                    transactionId = transactionId,
                    targetRootfsVersion = targetRootfsVersion,
                    targetRootfsSha256 = targetRootfsSha256,
                    targetMinisdSha256 = targetMinisdSha256,
                    targetProvisionRevision = targetProvisionRevision,
                    previousRootfsVersion = previous,
                )
            }
        }
    }

    internal enum class RecoveryDecision { COMPLETE, ROLLBACK, REDEPLOY }

    internal fun decideRecovery(
        canonicalHealthy: Boolean,
        canonicalMatchesTarget: Boolean,
        previousHealthy: Boolean,
    ): RecoveryDecision = when {
        canonicalHealthy && canonicalMatchesTarget -> RecoveryDecision.COMPLETE
        previousHealthy -> RecoveryDecision.ROLLBACK
        else -> RecoveryDecision.REDEPLOY
    }

    internal fun rootfsMatchesManifest(
        health: RootfsHealth,
        manifest: RuntimeDistributionManifest,
    ): Boolean {
        if (!health.healthy) return false
        val metadata = health.metadata ?: return false
        val revision = manifest.rootfsVersion
            .substringAfter("-r")
            .substringBefore("-")
            .toIntOrNull()
            ?: return false
        return metadata.optString("release") == manifest.rootfsRelease &&
            metadata.optString("profile") == manifest.rootfsProfile &&
            metadata.optString("upstream_sha256").lowercase() == manifest.rootfsUpstreamSha256 &&
            metadata.optInt("revision", -1) == revision
    }

    internal fun stageArchiveCommand(packageName: String): String =
        RuntimeProvision.stageRootfsFromApkCommand(packageName)

    internal fun verifyStagedArchiveCommand(expectedSha256: String): String = """
ARCHIVE='${RuntimeProvision.STAGED_ROOTFS_ARCHIVE}'
[ -s "${'$'}ARCHIVE" ] || { echo 'STAGED_ARCHIVE_EMPTY' >&2; exit 140; }
ACTUAL=${'$'}(sha256sum "${'$'}ARCHIVE" | awk '{print ${'$'}1}')
[ "${'$'}ACTUAL" = '${expectedSha256}' ] || { echo "STAGED_ARCHIVE_MISMATCH: ${'$'}ACTUAL" >&2; exit 141; }
echo "STAGED_ARCHIVE_OK: ${'$'}ACTUAL"
    """.trimIndent()

    internal fun deployRootfsCommand(transactionId: String): String {
        val rootfs = shellQuote(MinisdProtocol.DEFAULT_ROOTFS)
        val stage = shellQuote("$STAGING_DIR/rootfs.$transactionId")
        val previous = shellQuote(PREVIOUS_ROOTFS)
        val archive = shellQuote(RuntimeProvision.STAGED_ROOTFS_ARCHIVE)
        val commands = mutableListOf<String>()
        commands += "ROOTFS=$rootfs"
        commands += "ARCHIVE=$archive"
        commands += "STAGE=$stage"
        commands += "PREV=$previous"
        commands += "PREV_PARENT='${RUNTIME_DIR}/previous'"
        commands += "rm -rf \"\$STAGE\""
        commands += "mkdir -p \"\$STAGE\" || { rm -rf \"\$STAGE\"; exit 90; }"
        commands += "tar -xzf \"\$ARCHIVE\" -C \"\$STAGE\" || { rm -rf \"\$STAGE\"; exit 91; }"
        REQUIRED_LAYOUT.forEachIndexed { index, rel ->
            commands += "[ -e \"\$STAGE/$rel\" ] || { rm -rf \"\$STAGE\"; exit $((92 + index)); }"
        }
        commands += "if [ ! -x \"\$STAGE/bin/bash\" ] && [ ! -x \"\$STAGE/usr/bin/bash\" ] && [ ! -x \"\$STAGE/bin/sh\" ]; then rm -rf \"\$STAGE\"; exit 106; fi"
        commands += "META=\"\$STAGE/etc/minis/rootfs.json\""
        commands += "grep -Eq '\"distro\"[[:space:]]*:[[:space:]]*\"ubuntu\"' \"\$META\" || { rm -rf \"\$STAGE\"; exit 107; }"
        commands += "grep -Eq '\"release\"[[:space:]]*:[[:space:]]*\"24\\.04' \"\$META\" || { rm -rf \"\$STAGE\"; exit 108; }"
        commands += "grep -Eq '\"arch\"[[:space:]]*:[[:space:]]*\"arm64\"' \"\$META\" || { rm -rf \"\$STAGE\"; exit 109; }"
        commands += "grep -Eq '\"profile\"[[:space:]]*:[[:space:]]*\"base\"' \"\$META\" || { rm -rf \"\$STAGE\"; exit 110; }"
        commands += "mkdir -p \"\$PREV_PARENT\" || { rm -rf \"\$STAGE\"; exit 111; }"
        commands += "rm -rf \"\$PREV\""
        commands += "if [ -e \"\$ROOTFS\" ]; then mv \"\$ROOTFS\" \"\$PREV\" || { rm -rf \"\$STAGE\"; exit 112; }; fi"
        commands += "if ! mv \"\$STAGE\" \"\$ROOTFS\"; then [ -e \"\$PREV\" ] && mv \"\$PREV\" \"\$ROOTFS\" 2>/dev/null || true; rm -rf \"\$STAGE\" 2>/dev/null || true; exit 113; fi"
        commands += "echo 'MINIS_ROOTFS:DEPLOYED'"
        return commands.joinToString("\n")
    }

    internal fun rollbackRootfsCommand(): String = """
ROOTFS='${MinisdProtocol.DEFAULT_ROOTFS}'
PREV='$PREVIOUS_ROOTFS'
OLD="${'$'}PREV.trash.${'$'}${'$'}"
[ -d "${'$'}PREV" ] || { echo 'no previous rootfs to restore' >&2; exit 120; }
[ -e "${'$'}ROOTFS" ] && mv "${'$'}ROOTFS" "${'$'}OLD" 2>/dev/null || true
mv "${'$'}PREV" "${'$'}ROOTFS" || { [ -e "${'$'}OLD" ] && mv "${'$'}OLD" "${'$'}ROOTFS" 2>/dev/null || true; exit 121; }
rm -rf "${'$'}OLD"
    echo 'MINIS_ROOTFS:ROLLED_BACK'
    """.trimIndent()

    internal fun resetRootfsCommand(): String = """
ROOTFS='${MinisdProtocol.DEFAULT_ROOTFS}'
RUNTIME='$RUNTIME_DIR'
rm -rf "${'$'}ROOTFS" "${'$'}RUNTIME/staging" "${'$'}RUNTIME/previous" || exit 150
rm -f "${'$'}RUNTIME/pending.json" "${'$'}RUNTIME/deployed.json" || exit 151
echo 'MINIS_ROOTFS:RESET'
    """.trimIndent()

    internal fun writeStateFileCommand(path: String, content: String): String {
        val dir = shellQuote(path.substringBeforeLast('/', path))
        val file = shellQuote(path)
        return """
DIR=$dir
FILE=$file
mkdir -p "${'$'}DIR" || { echo "cannot create ${'$'}DIR" >&2; exit 130; }
umask 077
TMP="${'$'}FILE.tmp.${'$'}${'$'}"
printf '%s' '${content.replace("'", "'\"'\"'")}' > "${'$'}TMP" || { rm -f "${'$'}TMP"; exit 131; }
mv -f "${'$'}TMP" "${'$'}FILE" || { rm -f "${'$'}TMP"; exit 132; }
    """.trimIndent()
    }

    internal fun readStateFileCommand(path: String): String = "cat '${path.replace("'", "'\"'\"'")}'"

    internal fun clearStateFileCommand(path: String): String =
        "rm -f '${path.replace("'", "'\"'\"'")}'"

    internal fun probeRootfs(runner: RootRunner, rootfs: String): RootfsHealth {
        val result = runner.run(RootfsManager.buildProbeCommand(rootfs))
        if (!result.completed || result.exitCode != 0) {
            return RootfsHealth(
                RootfsHealthCode.ROOT_UNAVAILABLE,
                result.error ?: result.output.ifBlank { "rootfs probe failed" },
            )
        }
        return RootfsManager.evaluateProbeOutput(result.output)
    }

    suspend fun ensureDeployed(
        context: Context,
        runner: RootRunner,
        stopKeeper: suspend () -> Boolean,
        startKeeper: suspend () -> Boolean,
        provision: suspend () -> Boolean,
    ): DeploymentResult {
        val manifest = try {
            context.assets.open(RuntimePayloadVerifier.MANIFEST_ASSET)
                .bufferedReader()
                .use { RuntimeDistributionManifest.parse(it.readText()) }
        } catch (t: Throwable) {
            return DeploymentResult(
                DeploymentOutcome.PAYLOAD_INVALID,
                "cannot read runtime manifest: ${t.message}",
            )
        }
        val payload = RuntimePayloadVerifier.verifyApkPayload(context)
        if (!payload.ok) {
            return DeploymentResult(DeploymentOutcome.PAYLOAD_INVALID, payload.error.orEmpty())
        }
        return ensureDeployedCore(manifest, context.packageName, runner, stopKeeper, startKeeper, provision)
    }

    suspend fun resetRootfs(
        runner: RootRunner,
        stopKeeper: suspend () -> Boolean,
    ): DeploymentResult {
        if (!stopKeeper()) {
            return DeploymentResult(
                DeploymentOutcome.FAILED,
                "cannot stop keeper before rootfs reset",
            )
        }
        val reset = runner.run(resetRootfsCommand())
        if (!reset.completed || reset.exitCode != 0) {
            return DeploymentResult(
                DeploymentOutcome.FAILED,
                "rootfs reset failed: ${reset.error ?: reset.output}",
            )
        }
        return DeploymentResult(DeploymentOutcome.RESET, "rootfs reset; persistent user data preserved")
    }

    internal suspend fun ensureDeployedCore(
        manifest: RuntimeDistributionManifest,
        packageName: String,
        runner: RootRunner,
        stopKeeper: suspend () -> Boolean,
        startKeeper: suspend () -> Boolean = { true },
        provision: suspend () -> Boolean = { true },
    ): DeploymentResult {
        val rootCheck = runner.run("id -u")
        if (!rootCheck.completed || rootCheck.exitCode != 0) {
            return DeploymentResult(
                DeploymentOutcome.ROOT_UNAVAILABLE,
                rootCheck.error ?: rootCheck.output.ifBlank { "root unavailable" },
            )
        }
        if (MinisdBootstrap.parseEffectiveUid(rootCheck.output) != 0) {
            return DeploymentResult(
                DeploymentOutcome.ROOT_UNAVAILABLE,
                "root authorization invalid: expected uid 0",
            )
        }

        val pending = try {
            readPending(runner)
        } catch (t: Throwable) {
            return DeploymentResult(
                DeploymentOutcome.FAILED,
                "cannot recover: ${t.message}",
            )
        }
        if (pending != null) {
            if (!pending.matches(manifest)) {
                return DeploymentResult(
                    DeploymentOutcome.FAILED,
                    "pending transaction targets a different runtime; refusing recovery",
                )
            }
            return when (decideRecovery(
                canonicalHealthy = probeRootfs(runner, MinisdProtocol.DEFAULT_ROOTFS).healthy,
                canonicalMatchesTarget = probeRootfs(runner, MinisdProtocol.DEFAULT_ROOTFS)
                    .let { rootfsMatchesManifest(it, manifest) },
                previousHealthy = probeRootfs(runner, PREVIOUS_ROOTFS).healthy,
            )) {
                RecoveryDecision.COMPLETE -> {
                    if (!completeInterruptedUpgrade(runner, manifest, stopKeeper, startKeeper, provision)) {
                        return DeploymentResult(
                            DeploymentOutcome.FAILED,
                            "interrupted upgrade could not be provisioned; retry or inspect pending state",
                        )
                    }
                    DeploymentResult(
                        DeploymentOutcome.RECOVERED,
                        "completed interrupted upgrade to ${pending.targetRootfsVersion}",
                    )
                }
                RecoveryDecision.ROLLBACK -> {
                    if (!stopKeeper()) {
                        return DeploymentResult(
                            DeploymentOutcome.FAILED,
                            "cannot stop keeper before interrupted-upgrade rollback",
                        )
                    }
                    val rollback = runner.run(rollbackRootfsCommand())
                    if (!rollback.completed || rollback.exitCode != 0) {
                        return DeploymentResult(
                            DeploymentOutcome.FAILED,
                            "rollback failed: ${rollback.error ?: rollback.output}",
                        )
                    }
                    runner.run(clearStateFileCommand(PENDING_FILE))
                    DeploymentResult(
                        DeploymentOutcome.ROLLED_BACK,
                        "restored previous rootfs after interrupted upgrade",
                    )
                }
                RecoveryDecision.REDEPLOY -> {
                    runner.run(clearStateFileCommand(PENDING_FILE))
                    deployNew(manifest, packageName, runner, stopKeeper, startKeeper, provision)
                }
            }
        }

        val deployed = readDeployed(runner)
        if (deployed != null && deployed.matches(manifest)) {
            val health = probeRootfs(runner, MinisdProtocol.DEFAULT_ROOTFS)
            if (rootfsMatchesManifest(health, manifest)) {
                return DeploymentResult(
                    DeploymentOutcome.MATCHED,
                    "runtime identity matches manifest ${manifest.rootfsVersion}",
                )
            }
        }
        return deployNew(manifest, packageName, runner, stopKeeper, startKeeper, provision)
    }

    private suspend fun deployNew(
        manifest: RuntimeDistributionManifest,
        packageName: String,
        runner: RootRunner,
        stopKeeper: suspend () -> Boolean,
        startKeeper: suspend () -> Boolean,
        provision: suspend () -> Boolean,
    ): DeploymentResult {
        val transactionId = "tx-${System.currentTimeMillis()}-${(0..0xFFFFFF).random()}"
        val pending = PendingTransaction(
            transactionId = transactionId,
            targetRootfsVersion = manifest.rootfsVersion,
            targetRootfsSha256 = manifest.rootfsSha256,
            targetMinisdSha256 = manifest.minisdSha256,
            targetProvisionRevision = manifest.provisionRevision,
            previousRootfsVersion = readDeployed(runner)?.rootfsVersion,
        )
        val pendingWrite = runner.run(writeStateFileCommand(PENDING_FILE, pending.toJson()))
        if (!pendingWrite.completed || pendingWrite.exitCode != 0) {
            return DeploymentResult(
                DeploymentOutcome.FAILED,
                "cannot record pending transaction: ${pendingWrite.error ?: pendingWrite.output}",
            )
        }

        fun fail(detail: String): DeploymentResult {
            runner.run(clearStateFileCommand(PENDING_FILE))
            return DeploymentResult(DeploymentOutcome.FAILED, detail)
        }

        val staged = runner.run(stageArchiveCommand(packageName))
        if (!staged.completed || staged.exitCode != 0) {
            return fail("rootfs staging failed: ${staged.error ?: staged.output}")
        }
        val verify = runner.run(verifyStagedArchiveCommand(manifest.rootfsSha256))
        if (!verify.completed || verify.exitCode != 0) {
            return fail(verify.output.ifBlank { "staged rootfs digest verification failed" })
        }
        if (!stopKeeper()) {
            return fail("cannot stop keeper before rootfs switch")
        }
        val deploy = runner.run(deployRootfsCommand(transactionId))
        if (!deploy.completed || deploy.exitCode != 0) {
            return fail("rootfs deploy failed: ${deploy.error ?: deploy.output}")
        }
        val health = probeRootfs(runner, MinisdProtocol.DEFAULT_ROOTFS)
        if (!rootfsMatchesManifest(health, manifest)) {
            return rollbackAfterSwitch(
                runner,
                "deployed rootfs does not match manifest: ${health.detail}",
            )
        }
        if (!startKeeper()) {
            if (!stopKeeper()) {
                return DeploymentResult(
                    DeploymentOutcome.FAILED,
                    "cannot stop keeper after failed start; pending transaction retained",
                )
            }
            return rollbackAfterSwitch(runner, "cannot start keeper after rootfs switch")
        }
        if (!provision()) {
            if (!stopKeeper()) {
                return DeploymentResult(
                    DeploymentOutcome.FAILED,
                    "provision failed on deployed rootfs; cannot stop keeper; pending transaction retained",
                )
            }
            return rollbackAfterSwitch(
                runner,
                "provision failed on deployed rootfs",
            )
        }
        if (!writeDeployed(runner, manifest)) {
            return DeploymentResult(
                DeploymentOutcome.FAILED,
                "cannot record deployed identity after successful switch",
            )
        }
        runner.run(clearStateFileCommand(PENDING_FILE))
        return DeploymentResult(
            DeploymentOutcome.DEPLOYED,
            "deployed ${manifest.rootfsVersion}",
        )
    }

    private suspend fun completeInterruptedUpgrade(
        runner: RootRunner,
        manifest: RuntimeDistributionManifest,
        stopKeeper: suspend () -> Boolean,
        startKeeper: suspend () -> Boolean,
        provision: suspend () -> Boolean,
    ): Boolean {
        if (!stopKeeper()) {
            return false
        }
        if (!startKeeper()) {
            if (!stopKeeper()) return false
            rollbackAndClearPending(runner)
            return false
        }
        if (!provision()) {
            if (!stopKeeper()) {
                return false
            }
            rollbackAndClearPending(runner)
            return false
        }
        if (!writeDeployed(runner, manifest)) {
            // Keep the pending transaction: the rootfs is already provisioned,
            // so the next launch can retry this durable commit safely.
            return false
        }
        runner.run(clearStateFileCommand(PENDING_FILE))
        return true
    }

    private fun rollbackAfterSwitch(
        runner: RootRunner,
        detail: String,
    ): DeploymentResult {
        return if (rollbackAndClearPending(runner)) {
            DeploymentResult(
                DeploymentOutcome.FAILED,
                "$detail; rolled back to previous",
            )
        } else {
            DeploymentResult(
                DeploymentOutcome.FAILED,
                "$detail; rollback not confirmed; pending transaction retained",
            )
        }
    }

    private fun rollbackAndClearPending(runner: RootRunner): Boolean {
        val rollback = runner.run(rollbackRootfsCommand())
        if (!rollback.completed || rollback.exitCode != 0) return false
        val clear = runner.run(clearStateFileCommand(PENDING_FILE))
        return clear.completed && clear.exitCode == 0
    }

    private fun readPending(runner: RootRunner): PendingTransaction? =
        readState(runner, PENDING_FILE)?.let { raw ->
            try {
                PendingTransaction.parse(raw)
            } catch (t: Throwable) {
                throw IllegalStateException("$PENDING_FILE is corrupt: ${t.message}")
            }
        }

    private fun readDeployed(runner: RootRunner): DeployedIdentity? =
        readState(runner, DEPLOYED_FILE)?.let { raw ->
            runCatching { DeployedIdentity.parse(raw) }.getOrNull()
        }

    private fun readState(runner: RootRunner, path: String): String? {
        val result = runner.run(readStateFileCommand(path))
        if (!result.completed || result.exitCode != 0) return null
        return result.output.ifBlank { null }
    }

    private fun writeDeployed(runner: RootRunner, manifest: RuntimeDistributionManifest): Boolean {
        val identity = DeployedIdentity(
            rootfsVersion = manifest.rootfsVersion,
            rootfsSha256 = manifest.rootfsSha256,
            minisdSha256 = manifest.minisdSha256,
            provisionRevision = manifest.provisionRevision,
        )
        val json = JSONObject()
            .put("schemaVersion", STATE_SCHEMA_VERSION)
            .put("rootfsVersion", identity.rootfsVersion)
            .put("rootfsSha256", identity.rootfsSha256)
            .put("minisdSha256", identity.minisdSha256)
            .put("provisionRevision", identity.provisionRevision)
            .toString()
        val result = runner.run(writeStateFileCommand(DEPLOYED_FILE, json))
        return result.completed && result.exitCode == 0
    }

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

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"
}
