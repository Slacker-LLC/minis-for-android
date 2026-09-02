package com.openminis.app.runtime.distribution

import android.content.Context
import com.openminis.app.runtime.minisd.MinisdProtocol
import com.openminis.app.runtime.minisd.MinisdResponse
import com.openminis.app.runtime.ubuntu.RootfsHealth
import com.openminis.app.runtime.ubuntu.RootfsHealthCode
import org.json.JSONObject

/**
 * Single active owner of runtime deployment, upgrade, and rollback.
 *
 * The App owns the transaction decisions. The privileged broker owns every
 * operation that touches `/data/adb/minis/runtime` or the canonical rootfs.
 */
object RuntimeDistributionManager {
    const val RUNTIME_DIR = "/data/adb/minis/runtime"
    const val STAGING_DIR = "$RUNTIME_DIR/staging"
    const val PREVIOUS_ROOTFS = "$RUNTIME_DIR/previous/rootfs"
    const val PENDING_FILE = "$RUNTIME_DIR/pending.json"
    const val DEPLOYED_FILE = "$RUNTIME_DIR/deployed.json"
    const val STATE_SCHEMA_VERSION = 2
    private const val PENDING_SCHEMA_VERSION = 4
    private val SHA256 = Regex("^[0-9a-f]{64}$")
    private val TRANSACTION_ID = Regex("^[A-Za-z0-9_.-]{1,128}$")
    private val ROOTFS_VERSION = Regex("^ubuntu-24\\.04-r[1-9][0-9]*-[0-9a-f]{16}$")

    private const val STATE_PENDING = "pending"
    private const val STATE_DEPLOYED = "deployed"
    private const val TARGET_CANONICAL = "canonical"
    private const val TARGET_PREVIOUS = "previous"

    fun interface RuntimeMaintainer {
        suspend fun call(operation: String, params: JSONObject): MinisdResponse
    }

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

        internal fun toJsonObject(): JSONObject = JSONObject()
            .put("schemaVersion", STATE_SCHEMA_VERSION)
            .put("rootfsVersion", rootfsVersion)
            .put("rootfsSha256", rootfsSha256)
            .put("minisdSha256", minisdSha256)
            .put("provisionRevision", provisionRevision)

        companion object {
            fun parse(raw: String): DeployedIdentity {
                val root = try {
                    JSONObject(raw)
                } catch (t: Throwable) {
                    throw IllegalArgumentException("deployed identity is not valid JSON: ${t.message}")
                }
                return parseObject(root)
            }

            internal fun parseObject(root: JSONObject): DeployedIdentity {
                val schema = root.optInt("schemaVersion", -1)
                require(schema == STATE_SCHEMA_VERSION) { "deployed identity schemaVersion mismatch" }
                val rootfsVersion = root.optString("rootfsVersion")
                val rootfsSha256 = root.optString("rootfsSha256")
                val minisdSha256 = root.optString("minisdSha256")
                val revision = root.optInt("provisionRevision", 0)
                require(
                    ROOTFS_VERSION.matches(rootfsVersion) &&
                        rootfsSha256.matches(Regex("^[0-9a-f]{64}$")) &&
                        minisdSha256.matches(Regex("^[0-9a-f]{64}$")) &&
                        revision > 0,
                ) { "deployed identity has invalid fields" }
                return DeployedIdentity(rootfsVersion, rootfsSha256, minisdSha256, revision)
            }
        }
    }

    enum class PendingPhase {
        PREPARED,
        SWITCHING,
    }

    data class PendingTransaction(
        val transactionId: String,
        val targetRootfsVersion: String,
        val targetRootfsSha256: String,
        val targetMinisdSha256: String,
        val targetProvisionRevision: Int,
        val previousIdentity: DeployedIdentity? = null,
        val phase: PendingPhase = PendingPhase.PREPARED,
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
            .put("previousIdentity", previousIdentity?.toJsonObject() ?: JSONObject.NULL)
            .put("phase", phase.name)
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
                    TRANSACTION_ID.matches(transactionId) &&
                        ROOTFS_VERSION.matches(targetRootfsVersion) &&
                        SHA256.matches(targetRootfsSha256) &&
                        SHA256.matches(targetMinisdSha256) &&
                        targetProvisionRevision > 0,
                ) { "pending transaction has invalid fields" }
                require(root.has("previousIdentity")) { "pending transaction previous identity is missing" }
                val previous = if (root.isNull("previousIdentity")) {
                    null
                } else {
                    root.optJSONObject("previousIdentity")?.let(DeployedIdentity::parseObject)
                        ?: throw IllegalArgumentException("pending transaction previous identity is invalid")
                }
                val phase = runCatching { PendingPhase.valueOf(root.optString("phase")) }
                    .getOrElse { throw IllegalArgumentException("pending transaction phase is invalid") }
                return PendingTransaction(
                    transactionId = transactionId,
                    targetRootfsVersion = targetRootfsVersion,
                    targetRootfsSha256 = targetRootfsSha256,
                    targetMinisdSha256 = targetMinisdSha256,
                    targetProvisionRevision = targetProvisionRevision,
                    previousIdentity = previous,
                    phase = phase,
                )
            }
        }
    }

    internal enum class RecoveryDecision { COMPLETE, ROLLBACK, REDEPLOY, REFUSE }

    internal fun decideRecovery(
        phase: PendingPhase,
        canonicalMatchesTarget: Boolean,
        previousMatchesExpected: Boolean,
    ): RecoveryDecision = when {
        canonicalMatchesTarget -> RecoveryDecision.COMPLETE
        phase == PendingPhase.PREPARED -> RecoveryDecision.REDEPLOY
        previousMatchesExpected -> RecoveryDecision.ROLLBACK
        else -> RecoveryDecision.REFUSE
    }

    internal fun rootfsMatchesManifest(
        health: RootfsHealth,
        manifest: RuntimeDistributionManifest,
    ): Boolean = rootfsMatchesIdentity(
        health,
        DeployedIdentity(
            rootfsVersion = manifest.rootfsVersion,
            rootfsSha256 = manifest.rootfsSha256,
            minisdSha256 = manifest.minisdSha256,
            provisionRevision = manifest.provisionRevision,
        ),
        manifest,
    )

    private fun rootfsMatchesIdentity(
        health: RootfsHealth,
        identity: DeployedIdentity,
        manifest: RuntimeDistributionManifest,
    ): Boolean {
        if (!health.healthy) return false
        val metadata = health.metadata ?: return false
        val revision = identity.rootfsVersion
            .substringAfter("-r")
            .substringBefore("-")
            .toIntOrNull()
            ?: return false
        return metadata.optString("release") == manifest.rootfsRelease &&
            metadata.optString("profile") == RuntimeDistributionManifest.ROOTFS_PROFILE &&
            metadata.optString("upstream_sha256").lowercase() ==
                RuntimeDistributionManifest.PINNED_UPSTREAM_SHA256 &&
            metadata.optInt("revision", -1) == revision &&
            metadata.optString("archive_sha256").lowercase() == identity.rootfsSha256
    }

    suspend fun ensureDeployed(
        context: Context,
        maintainer: RuntimeMaintainer,
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
        return ensureDeployedCore(manifest, context.packageName, maintainer, stopKeeper, startKeeper, provision)
    }

    suspend fun resetRootfs(
        maintainer: RuntimeMaintainer,
        stopKeeper: suspend () -> Boolean,
    ): DeploymentResult {
        if (!stopKeeper()) {
            return DeploymentResult(
                DeploymentOutcome.FAILED,
                "cannot stop keeper before rootfs reset",
            )
        }
        val reset = call(maintainer, MinisdProtocol.RUNTIME_OP_RESET)
        if (!reset.ok) {
            return DeploymentResult(
                DeploymentOutcome.FAILED,
                "rootfs reset failed: ${responseDetail(reset)}",
            )
        }
        return DeploymentResult(DeploymentOutcome.RESET, "rootfs reset; persistent user data preserved")
    }

    internal suspend fun ensureDeployedCore(
        manifest: RuntimeDistributionManifest,
        packageName: String,
        maintainer: RuntimeMaintainer,
        stopKeeper: suspend () -> Boolean,
        startKeeper: suspend () -> Boolean = { true },
        provision: suspend () -> Boolean = { true },
    ): DeploymentResult {
        val pending = try {
            readPending(maintainer)
        } catch (t: Throwable) {
            return DeploymentResult(DeploymentOutcome.FAILED, "cannot recover: ${t.message}")
        }
        if (pending != null) {
            if (!pending.matches(manifest)) {
                return DeploymentResult(
                    DeploymentOutcome.FAILED,
                    "pending transaction targets a different runtime; refusing recovery",
                )
            }
            val canonical = probeRootfs(maintainer, TARGET_CANONICAL)
            if (!canonical.isKnown()) {
                return DeploymentResult(
                    DeploymentOutcome.FAILED,
                    "runtime probe was inconclusive; pending transaction retained",
                )
            }
            val canonicalMatches = rootfsMatchesManifest(canonical, manifest)
            val previous = if (pending.phase == PendingPhase.SWITCHING) {
                probeRootfs(maintainer, TARGET_PREVIOUS)
            } else {
                null
            }
            if (previous != null && !previous.isKnown()) {
                return DeploymentResult(
                    DeploymentOutcome.FAILED,
                    "runtime probe was inconclusive; pending transaction retained",
                )
            }
            val previousMatches = pending.previousIdentity?.let { identity ->
                previous?.provisioned == true && rootfsMatchesIdentity(previous, identity, manifest)
            } == true
            return when (decideRecovery(
                phase = pending.phase,
                canonicalMatchesTarget = canonicalMatches,
                previousMatchesExpected = previousMatches,
            )) {
                RecoveryDecision.COMPLETE -> {
                    if (!completeInterruptedUpgrade(
                            maintainer,
                            manifest,
                            previousMatches,
                            stopKeeper,
                            startKeeper,
                            provision,
                        )
                    ) {
                        DeploymentResult(
                            DeploymentOutcome.FAILED,
                            "interrupted upgrade could not be provisioned; retry or inspect pending state",
                        )
                    } else {
                        DeploymentResult(
                            DeploymentOutcome.RECOVERED,
                            "completed interrupted upgrade to ${pending.targetRootfsVersion}",
                        )
                    }
                }
                RecoveryDecision.ROLLBACK -> {
                    if (!stopKeeper()) {
                        DeploymentResult(
                            DeploymentOutcome.FAILED,
                            "cannot stop keeper before interrupted-upgrade rollback",
                        )
                    } else {
                        val rollback = call(maintainer, MinisdProtocol.RUNTIME_OP_ROLLBACK)
                        if (!rollback.ok) {
                            DeploymentResult(
                                DeploymentOutcome.FAILED,
                                "rollback failed: ${responseDetail(rollback)}",
                            )
                        } else if (!clearState(maintainer, STATE_PENDING)) {
                            DeploymentResult(
                                DeploymentOutcome.FAILED,
                                "rollback completed but pending transaction could not be cleared",
                            )
                        } else {
                            DeploymentResult(
                                DeploymentOutcome.ROLLED_BACK,
                                "restored previous rootfs after interrupted upgrade",
                            )
                        }
                    }
                }
                RecoveryDecision.REDEPLOY -> {
                    if (!clearState(maintainer, STATE_PENDING)) {
                        DeploymentResult(
                            DeploymentOutcome.FAILED,
                            "cannot clear unusable pending transaction",
                        )
                    } else {
                        deployNew(manifest, packageName, maintainer, stopKeeper, startKeeper, provision)
                    }
                }
                RecoveryDecision.REFUSE -> DeploymentResult(
                    DeploymentOutcome.FAILED,
                    "pending transaction filesystem state is not safely recoverable; pending transaction retained",
                )
            }
        }

        val deployed = try {
            readDeployed(maintainer)
        } catch (t: Throwable) {
            return DeploymentResult(DeploymentOutcome.FAILED, "cannot read deployed identity: ${t.message}")
        }
        val health = probeRootfs(maintainer, TARGET_CANONICAL)
        if (!health.isKnown()) {
            return DeploymentResult(
                DeploymentOutcome.FAILED,
                "runtime probe was inconclusive; refusing deployment side effects",
            )
        }
        if (deployed != null && deployed.matches(manifest) && rootfsMatchesManifest(health, manifest)) {
            if (health.provisioned) {
                return DeploymentResult(
                    DeploymentOutcome.MATCHED,
                    "runtime identity matches manifest ${manifest.rootfsVersion}",
                )
            }
            return provisionExisting(maintainer, stopKeeper, startKeeper, provision)
        }
        return deployNew(manifest, packageName, maintainer, stopKeeper, startKeeper, provision)
    }

    private suspend fun provisionExisting(
        maintainer: RuntimeMaintainer,
        stopKeeper: suspend () -> Boolean,
        startKeeper: suspend () -> Boolean,
        provision: suspend () -> Boolean,
    ): DeploymentResult {
        if (!stopKeeper()) {
            return DeploymentResult(DeploymentOutcome.FAILED, "cannot stop keeper before provision")
        }
        if (!startKeeper()) {
            val stopped = stopKeeper()
            return DeploymentResult(
                DeploymentOutcome.FAILED,
                if (stopped) "cannot start keeper before provision"
                else "cannot start keeper before provision; keeper stop not confirmed",
            )
        }
        if (!provision()) {
            val stopped = stopKeeper()
            return DeploymentResult(
                DeploymentOutcome.FAILED,
                if (stopped) "provision failed on existing rootfs"
                else "provision failed on existing rootfs; keeper stop not confirmed",
            )
        }
        return DeploymentResult(DeploymentOutcome.DEPLOYED, "provisioned existing runtime rootfs")
    }

    private suspend fun deployNew(
        manifest: RuntimeDistributionManifest,
        packageName: String,
        maintainer: RuntimeMaintainer,
        stopKeeper: suspend () -> Boolean,
        startKeeper: suspend () -> Boolean,
        provision: suspend () -> Boolean,
    ): DeploymentResult {
        val previousIdentity = try {
            readDeployed(maintainer)
        } catch (t: Throwable) {
            return DeploymentResult(DeploymentOutcome.FAILED, "cannot read deployed identity: ${t.message}")
        }
        val transactionId = "tx-${System.currentTimeMillis()}-${(0..0xFFFFFF).random()}"
        val pending = PendingTransaction(
            transactionId = transactionId,
            targetRootfsVersion = manifest.rootfsVersion,
            targetRootfsSha256 = manifest.rootfsSha256,
            targetMinisdSha256 = manifest.minisdSha256,
            targetProvisionRevision = manifest.provisionRevision,
            previousIdentity = previousIdentity,
        )
        if (!writeState(maintainer, STATE_PENDING, pending.toJson())) {
            return DeploymentResult(
                DeploymentOutcome.FAILED,
                "cannot record pending transaction: runtime state write failed",
            )
        }

        suspend fun fail(detail: String): DeploymentResult {
            clearState(maintainer, STATE_PENDING)
            return DeploymentResult(DeploymentOutcome.FAILED, detail)
        }

        val staged = call(
            maintainer,
            MinisdProtocol.RUNTIME_OP_STAGE,
            JSONObject().put("package_name", packageName),
        )
        if (!staged.ok) return fail("rootfs staging failed: ${responseDetail(staged)}")

        val verify = call(
            maintainer,
            MinisdProtocol.RUNTIME_OP_VERIFY,
            JSONObject().put("expected_sha256", manifest.rootfsSha256),
        )
        if (!verify.ok) return fail("staged rootfs digest verification failed: ${responseDetail(verify)}")

        if (!stopKeeper()) return fail("cannot stop keeper before rootfs switch")

        if (!writeState(
                maintainer,
                STATE_PENDING,
                pending.copy(phase = PendingPhase.SWITCHING).toJson(),
            )
        ) {
            return DeploymentResult(
                DeploymentOutcome.FAILED,
                "cannot record switch phase; pending transaction retained",
            )
        }

        val deploy = call(
            maintainer,
            MinisdProtocol.RUNTIME_OP_SWITCH,
            JSONObject()
                .put("transaction_id", transactionId)
                .put("expected_sha256", manifest.rootfsSha256),
        )
        if (!deploy.ok) {
            if (deploy.code == MinisdProtocol.ERROR_RUNTIME_SWITCH_UNKNOWN) {
                // The broker reports an unknown outcome only after the first
                // namespace exchange. Keep pending for state-based recovery.
                return DeploymentResult(
                    DeploymentOutcome.FAILED,
                    "rootfs deploy outcome is unknown; pending transaction retained: ${responseDetail(deploy)}",
                )
            }
            return fail("rootfs deploy failed before switch: ${responseDetail(deploy)}")
        }

        val health = probeRootfs(maintainer, TARGET_CANONICAL)
        if (!health.isKnown()) {
            return DeploymentResult(
                DeploymentOutcome.FAILED,
                "post-switch rootfs probe was inconclusive; pending transaction retained",
            )
        }
        if (!rootfsMatchesManifest(health, manifest)) {
            return rollbackAfterSwitch(
                maintainer,
                pending,
                manifest,
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
            return rollbackAfterSwitch(
                maintainer,
                pending,
                manifest,
                "cannot start keeper after rootfs switch",
            )
        }
        if (!provision()) {
            if (!stopKeeper()) {
                return DeploymentResult(
                    DeploymentOutcome.FAILED,
                    "provision failed on deployed rootfs; cannot stop keeper; pending transaction retained",
                )
            }
            return rollbackAfterSwitch(
                maintainer,
                pending,
                manifest,
                "provision failed on deployed rootfs",
            )
        }
        if (!writeDeployed(maintainer, manifest)) {
            return DeploymentResult(
                DeploymentOutcome.FAILED,
                "cannot record deployed identity after successful switch",
            )
        }
        if (!clearState(maintainer, STATE_PENDING)) {
            return DeploymentResult(
                DeploymentOutcome.FAILED,
                "cannot clear pending transaction after successful deployment",
            )
        }
        return DeploymentResult(
            DeploymentOutcome.DEPLOYED,
            "deployed ${manifest.rootfsVersion}",
        )
    }

    private suspend fun completeInterruptedUpgrade(
        maintainer: RuntimeMaintainer,
        manifest: RuntimeDistributionManifest,
        previousMatchesExpected: Boolean,
        stopKeeper: suspend () -> Boolean,
        startKeeper: suspend () -> Boolean,
        provision: suspend () -> Boolean,
    ): Boolean {
        if (!stopKeeper()) return false
        if (!startKeeper()) {
            if (!stopKeeper()) return false
            if (previousMatchesExpected) rollbackAndClearPending(maintainer)
            return false
        }
        if (!provision()) {
            if (!stopKeeper()) return false
            if (previousMatchesExpected) rollbackAndClearPending(maintainer)
            return false
        }
        if (!writeDeployed(maintainer, manifest)) return false
        return clearState(maintainer, STATE_PENDING)
    }

    private suspend fun rollbackAfterSwitch(
        maintainer: RuntimeMaintainer,
        pending: PendingTransaction,
        manifest: RuntimeDistributionManifest,
        detail: String,
    ): DeploymentResult {
        val previousIdentity = pending.previousIdentity
        if (previousIdentity == null) {
            return DeploymentResult(
                DeploymentOutcome.FAILED,
                "$detail; no matching previous identity; pending transaction retained",
            )
        }
        val previous = probeRootfs(maintainer, TARGET_PREVIOUS)
        if (!previous.isKnown() || previous.provisioned != true ||
            !rootfsMatchesIdentity(previous, previousIdentity, manifest)
        ) {
            return DeploymentResult(
                DeploymentOutcome.FAILED,
                "$detail; previous identity was not confirmed; pending transaction retained",
            )
        }
        return if (rollbackAndClearPending(maintainer)) {
            DeploymentResult(DeploymentOutcome.FAILED, "$detail; rolled back to previous")
        } else {
            DeploymentResult(
                DeploymentOutcome.FAILED,
                "$detail; rollback not confirmed; pending transaction retained",
            )
        }
    }

    private suspend fun rollbackAndClearPending(maintainer: RuntimeMaintainer): Boolean =
        call(maintainer, MinisdProtocol.RUNTIME_OP_ROLLBACK).ok &&
            clearState(maintainer, STATE_PENDING)

    private suspend fun readPending(maintainer: RuntimeMaintainer): PendingTransaction? =
        readState(maintainer, STATE_PENDING)?.let { raw ->
            try {
                PendingTransaction.parse(raw)
            } catch (t: Throwable) {
                throw IllegalStateException("$PENDING_FILE is corrupt: ${t.message}")
            }
        }

    private suspend fun readDeployed(maintainer: RuntimeMaintainer): DeployedIdentity? =
        readState(maintainer, STATE_DEPLOYED)?.let { raw ->
            try {
                DeployedIdentity.parse(raw)
            } catch (t: Throwable) {
                throw IllegalStateException("$DEPLOYED_FILE is corrupt: ${t.message}")
            }
        }

    private suspend fun readState(maintainer: RuntimeMaintainer, name: String): String? {
        val response = call(
            maintainer,
            MinisdProtocol.RUNTIME_OP_READ_STATE,
            JSONObject().put("name", name),
        )
        if (!response.ok) throw IllegalStateException(responseDetail(response))
        val result = response.result ?: throw IllegalStateException("runtime state response is empty")
        val present = result.opt("present")
        if (present !is Boolean) throw IllegalStateException("runtime state $name has invalid presence")
        if (!present) return null
        val content = result.opt("content")
        if (content !is String || content.isEmpty()) {
            throw IllegalStateException("runtime state $name is empty")
        }
        return content
    }

    private suspend fun writeState(
        maintainer: RuntimeMaintainer,
        name: String,
        content: String,
    ): Boolean = call(
        maintainer,
        MinisdProtocol.RUNTIME_OP_WRITE_STATE,
        JSONObject().put("name", name).put("content", content),
    ).ok

    private suspend fun clearState(maintainer: RuntimeMaintainer, name: String): Boolean =
        call(
            maintainer,
            MinisdProtocol.RUNTIME_OP_CLEAR_STATE,
            JSONObject().put("name", name),
        ).ok

    private suspend fun writeDeployed(
        maintainer: RuntimeMaintainer,
        manifest: RuntimeDistributionManifest,
    ): Boolean {
        val json = JSONObject()
            .put("schemaVersion", STATE_SCHEMA_VERSION)
            .put("rootfsVersion", manifest.rootfsVersion)
            .put("rootfsSha256", manifest.rootfsSha256)
            .put("minisdSha256", manifest.minisdSha256)
            .put("provisionRevision", manifest.provisionRevision)
            .toString()
        return writeState(maintainer, STATE_DEPLOYED, json)
    }

    private suspend fun probeRootfs(maintainer: RuntimeMaintainer, target: String): RootfsHealth {
        val response = call(
            maintainer,
            MinisdProtocol.RUNTIME_OP_PROBE,
            JSONObject().put("target", target),
        )
        if (!response.ok) return RootfsHealth(RootfsHealthCode.ROOT_UNAVAILABLE, responseDetail(response))
        val result = response.result ?: return RootfsHealth(
            RootfsHealthCode.UNKNOWN,
            "runtime probe returned no result",
        )
        val code = runCatching { RootfsHealthCode.valueOf(result.optString("code")) }.getOrNull()
            ?: return RootfsHealth(RootfsHealthCode.UNKNOWN, "runtime probe returned an unknown health code")
        val healthy = result.opt("healthy")
        if (healthy !is Boolean || healthy != (code == RootfsHealthCode.HEALTHY)) {
            return RootfsHealth(RootfsHealthCode.UNKNOWN, "runtime probe returned an inconsistent health result")
        }
        if (code == RootfsHealthCode.HEALTHY && result.opt("metadata") !is JSONObject) {
            return RootfsHealth(RootfsHealthCode.UNKNOWN, "runtime probe returned no metadata")
        }
        val provisioned = result.opt("provisioned")
        if (code == RootfsHealthCode.HEALTHY && provisioned !is Boolean) {
            return RootfsHealth(RootfsHealthCode.UNKNOWN, "runtime probe returned no provision state")
        }
        return RootfsHealth(
            code = code,
            detail = result.optString("detail").ifEmpty { "runtime probe completed" },
            metadata = result.optJSONObject("metadata"),
            provisioned = provisioned as? Boolean ?: false,
        )
    }

    /** Read-only rootfs inspection through the authenticated broker channel. */
    suspend fun inspectRootfs(
        maintainer: RuntimeMaintainer,
        target: String = TARGET_CANONICAL,
    ): RootfsHealth = probeRootfs(maintainer, target)

    private fun RootfsHealth.isKnown(): Boolean = code != RootfsHealthCode.ROOT_UNAVAILABLE &&
        code != RootfsHealthCode.UNKNOWN

    private suspend fun call(
        maintainer: RuntimeMaintainer,
        operation: String,
        params: JSONObject = JSONObject(),
    ): MinisdResponse = runCatching {
        maintainer.call(operation, params)
    }.getOrElse {
        MinisdProtocol.runtimeError(
            MinisdProtocol.ERROR_RUNTIME_UNAVAILABLE,
            "runtime maintenance transport failed: ${it.message}",
        )
    }

    private fun responseDetail(response: MinisdResponse): String =
        response.error?.let { "${it.code}: ${it.detail}" } ?: "runtime maintenance failed"
}
