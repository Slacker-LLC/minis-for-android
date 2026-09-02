package com.openminis.app.runtime.distribution

import com.openminis.app.runtime.distribution.RuntimeDistributionManager.DeployedIdentity
import com.openminis.app.runtime.distribution.RuntimeDistributionManager.PendingTransaction
import com.openminis.app.runtime.distribution.RuntimeDistributionManager.RecoveryDecision
import com.openminis.app.runtime.distribution.RuntimeDistributionManager.RuntimeMaintainer
import com.openminis.app.runtime.ubuntu.RootfsHealth
import com.openminis.app.runtime.ubuntu.RootfsHealthCode
import com.openminis.app.runtime.minisd.MinisdError
import com.openminis.app.runtime.minisd.MinisdProtocol
import com.openminis.app.runtime.minisd.MinisdResponse
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeDistributionManagerTest {

    private fun manifest(
        rootfsSha256: String = rootfsSha,
        minisdSha256: String = minisdSha,
        provisionRevision: Int = 1,
    ): RuntimeDistributionManifest {
        val text = JSONObject()
            .put("schemaVersion", 2)
            .put("protocolVersion", 1)
            .put("layoutVersion", 2)
            .put("abi", "arm64-v8a")
            .put("minisdVersion", "0.1.0")
            .put("minisdSha256", minisdSha256)
            .put("rootfsVersion", "ubuntu-24.04-r1-${rootfsSha256.take(16)}")
            .put("rootfsSha256", rootfsSha256)
            .put("rootfsRelease", "24.04.3")
            .put("rootfsProfile", "base")
            .put("rootfsUpstreamSha256", upstream)
            .put("provisionRevision", provisionRevision)
            .put("requiredCommands", org.json.JSONArray(listOf("python3", "git", "curl")))
            .toString()
        return RuntimeDistributionManifest.parse(text)
    }

    private class FakeMaintainer(
        var canonicalMetadata: String? = healthyMetadataJson(),
        var previousMetadata: String? = null,
        var deployedContent: String? = null,
        var pendingContent: String? = null,
        var stageSucceeds: Boolean = true,
        var verifySucceeds: Boolean = true,
        var deploySucceeds: Boolean = true,
        var deployErrorCode: String = MinisdProtocol.ERROR_RUNTIME_UNAVAILABLE,
        var canonicalBecomesHealthyAfterDeploy: Boolean = false,
        var canonicalProvisioned: Boolean = true,
        var previousProvisioned: Boolean = true,
        var malformedProbe: Boolean = false,
        var rollbackSucceeds: Boolean = true,
        var stateReadSucceeds: Boolean = true,
        var stateWriteSucceeds: Boolean = true,
    ) : RuntimeMaintainer {
        val operations = mutableListOf<Pair<String, JSONObject>>()

        override suspend fun call(operation: String, params: JSONObject): MinisdResponse {
            operations += operation to JSONObject(params.toString())
            val id = operations.size.toLong()
            return when (operation) {
                MinisdProtocol.RUNTIME_OP_READ_STATE -> {
                    if (!stateReadSucceeds) return error(id, "state read failed")
                    val name = params.optString("name")
                    val content = if (name == "pending") pendingContent else deployedContent
                    ok(id, JSONObject().put("name", name).put("present", content != null).apply {
                        content?.let { put("content", it) }
                    })
                }
                MinisdProtocol.RUNTIME_OP_WRITE_STATE -> {
                    if (!stateWriteSucceeds) return error(id, "state write failed")
                    val name = params.optString("name")
                    if (name == "pending") pendingContent = params.getString("content")
                    if (name == "deployed") deployedContent = params.getString("content")
                    ok(id)
                }
                MinisdProtocol.RUNTIME_OP_CLEAR_STATE -> {
                    if (params.optString("name") == "pending") pendingContent = null
                    if (params.optString("name") == "deployed") deployedContent = null
                    ok(id)
                }
                MinisdProtocol.RUNTIME_OP_PROBE -> {
                    if (malformedProbe) {
                        return ok(id, JSONObject().put("code", "HEALTHY").put("healthy", true))
                    }
                    val metadata = if (params.optString("target") == "previous") {
                        previousMetadata
                    } else {
                        canonicalMetadata
                    }
                    if (metadata == null) {
                        ok(id, JSONObject()
                            .put("code", "MISSING")
                            .put("healthy", false)
                            .put("detail", "missing"))
                    } else {
                        ok(id, JSONObject()
                            .put("code", "HEALTHY")
                            .put("healthy", true)
                            .put("detail", "healthy")
                            .put("metadata", JSONObject(metadata))
                            .put(
                                "provisioned",
                                if (params.optString("target") == "previous") {
                                    previousProvisioned
                                } else {
                                    canonicalProvisioned
                                },
                            ))
                    }
                }
                MinisdProtocol.RUNTIME_OP_STAGE ->
                    if (stageSucceeds) ok(id) else error(id, "stage failed")
                MinisdProtocol.RUNTIME_OP_VERIFY ->
                    if (verifySucceeds) ok(id, JSONObject().put("verified", true))
                    else error(id, "digest mismatch")
                MinisdProtocol.RUNTIME_OP_SWITCH -> {
                    if (!deploySucceeds) return error(id, "switch failed", deployErrorCode)
                    if (canonicalBecomesHealthyAfterDeploy) canonicalMetadata = healthyMetadataJson()
                    ok(id, JSONObject().put("switched", true))
                }
                MinisdProtocol.RUNTIME_OP_ROLLBACK ->
                    if (rollbackSucceeds) ok(id) else error(id, "rollback failed")
                MinisdProtocol.RUNTIME_OP_RESET -> ok(id, JSONObject().put("reset", true))
                else -> error(id, "unknown operation")
            }
        }

        private fun ok(id: Long, result: JSONObject = JSONObject()) = MinisdResponse(
            v = 1,
            id = id,
            ok = true,
            result = result,
            error = null,
        )

        private fun error(
            id: Long,
            detail: String,
            code: String = MinisdProtocol.ERROR_RUNTIME_UNAVAILABLE,
        ) = MinisdResponse(
            v = 1,
            id = id,
            ok = false,
            result = null,
            error = MinisdError(code, detail),
        )
    }

    @Test
    fun deployedIdentityRoundTripsAndMatchesManifest() {
        val json = JSONObject()
            .put("schemaVersion", 2)
            .put("rootfsVersion", manifest().rootfsVersion)
            .put("rootfsSha256", rootfsSha)
            .put("minisdSha256", minisdSha)
            .put("provisionRevision", 1)
            .toString()

        val identity = DeployedIdentity.parse(json)
        assertTrue(identity.matches(manifest()))
        assertFalse(identity.matches(manifest(rootfsSha256 = "0".repeat(64))))
    }

    @Test
    fun deployedIdentityRejectsInvalidFields() {
        val bad = JSONObject()
            .put("schemaVersion", 2)
            .put("rootfsVersion", "x")
            .put("rootfsSha256", "short")
            .put("minisdSha256", minisdSha)
            .put("provisionRevision", 1)
            .toString()
        assertThrows(IllegalArgumentException::class.java) { DeployedIdentity.parse(bad) }
    }

    @Test
    fun pendingTransactionRoundTripsAndMatchesManifest() {
        val manifest = manifest()
        val pending = PendingTransaction(
            transactionId = "tx-1",
            targetRootfsVersion = manifest.rootfsVersion,
            targetRootfsSha256 = rootfsSha,
            targetMinisdSha256 = minisdSha,
            targetProvisionRevision = manifest.provisionRevision,
            previousIdentity = DeployedIdentity(
                rootfsVersion = "ubuntu-24.04-r1-0000000000000000",
                rootfsSha256 = "0".repeat(64),
                minisdSha256 = minisdSha,
                provisionRevision = 1,
            ),
        )

        val parsed = PendingTransaction.parse(pending.toJson())
        assertTrue(parsed.matches(manifest))
        assertEquals("tx-1", parsed.transactionId)
        assertEquals("ubuntu-24.04-r1-0000000000000000", parsed.previousIdentity?.rootfsVersion)
        assertEquals(RuntimeDistributionManager.PendingPhase.PREPARED, parsed.phase)
    }

    @Test
    fun pendingTransactionRejectsCorruptSchema() {
        val bad = JSONObject().put("schemaVersion", 1).put("transactionId", "x").toString()
        assertThrows(IllegalArgumentException::class.java) { PendingTransaction.parse(bad) }
    }

    @Test
    fun recoveryDecisionsAreFailClosed() {
        assertEquals(
            RecoveryDecision.COMPLETE,
            RuntimeDistributionManager.decideRecovery(
                RuntimeDistributionManager.PendingPhase.SWITCHING,
                canonicalMatchesTarget = true,
                previousMatchesExpected = false,
            ),
        )
        assertEquals(
            RecoveryDecision.ROLLBACK,
            RuntimeDistributionManager.decideRecovery(
                RuntimeDistributionManager.PendingPhase.SWITCHING,
                canonicalMatchesTarget = false,
                previousMatchesExpected = true,
            ),
        )
        assertEquals(
            RecoveryDecision.REDEPLOY,
            RuntimeDistributionManager.decideRecovery(
                RuntimeDistributionManager.PendingPhase.PREPARED,
                canonicalMatchesTarget = false,
                previousMatchesExpected = true,
            ),
        )
        assertEquals(
            RecoveryDecision.REFUSE,
            RuntimeDistributionManager.decideRecovery(
                RuntimeDistributionManager.PendingPhase.SWITCHING,
                canonicalMatchesTarget = false,
                previousMatchesExpected = false,
            ),
        )
    }

    @Test
    fun rootfsMatchesManifestOnlyOnFullIdentity() {
        val manifest = manifest()
        assertTrue(RuntimeDistributionManager.rootfsMatchesManifest(healthyRootfsHealth(), manifest))
        assertFalse(
            RuntimeDistributionManager.rootfsMatchesManifest(
                RootfsHealth(
                    RootfsHealthCode.HEALTHY,
                    "wrong release",
                    JSONObject(healthyMetadataJson()).put("release", "22.04"),
                ),
                manifest,
            ),
        )
        assertFalse(RuntimeDistributionManager.rootfsMatchesManifest(
            RootfsHealth(RootfsHealthCode.CORRUPT, "corrupt"),
            manifest,
        ))
    }

    @Test
    fun freshInstallUsesOnlyStructuredMaintenanceOperations() = runBlocking {
        val maintainer = FakeMaintainer(
            canonicalMetadata = null,
            canonicalBecomesHealthyAfterDeploy = true,
        )
        val result = RuntimeDistributionManager.ensureDeployedCore(
            manifest(),
            "dev.openminispet.android",
            maintainer,
            stopKeeper = { true },
        )

        assertEquals(RuntimeDistributionManager.DeploymentOutcome.DEPLOYED, result.outcome)
        val names = maintainer.operations.map { it.first }
        assertTrue(names.containsAll(listOf(
            MinisdProtocol.RUNTIME_OP_STAGE,
            MinisdProtocol.RUNTIME_OP_VERIFY,
            MinisdProtocol.RUNTIME_OP_SWITCH,
            MinisdProtocol.RUNTIME_OP_WRITE_STATE,
            MinisdProtocol.RUNTIME_OP_CLEAR_STATE,
        )))
        assertFalse(maintainer.operations.any { it.second.has("command") || it.second.has("cmd") })
        assertFalse(maintainer.operations.any { it.second.toString().contains("/data/user/0") })
    }

    @Test
    fun matchingHealthyDeploymentDoesNotSwitch() = runBlocking {
        val current = manifest()
        val maintainer = FakeMaintainer(
            deployedContent = JSONObject()
                .put("schemaVersion", 2)
                .put("rootfsVersion", current.rootfsVersion)
                .put("rootfsSha256", rootfsSha)
                .put("minisdSha256", minisdSha)
                .put("provisionRevision", 1)
                .toString(),
        )

        val result = RuntimeDistributionManager.ensureDeployedCore(
            current,
            "dev.openminispet.android",
            maintainer,
            stopKeeper = { true },
        )

        assertEquals(RuntimeDistributionManager.DeploymentOutcome.MATCHED, result.outcome)
        assertFalse(maintainer.operations.any { it.first == MinisdProtocol.RUNTIME_OP_SWITCH })
    }

    @Test
    fun pendingDifferentTargetRefusesRecovery() = runBlocking {
        val current = manifest()
        val old = manifest(rootfsSha256 = "0".repeat(64))
        val maintainer = FakeMaintainer(
            pendingContent = PendingTransaction(
                transactionId = "tx-old",
                targetRootfsVersion = old.rootfsVersion,
                targetRootfsSha256 = old.rootfsSha256,
                targetMinisdSha256 = old.minisdSha256,
                targetProvisionRevision = old.provisionRevision,
            ).toJson(),
        )

        val result = RuntimeDistributionManager.ensureDeployedCore(
            current,
            "dev.openminispet.android",
            maintainer,
            stopKeeper = { true },
        )

        assertEquals(RuntimeDistributionManager.DeploymentOutcome.FAILED, result.outcome)
        assertTrue(result.detail.contains("different runtime"))
        assertFalse(maintainer.operations.any { it.first == MinisdProtocol.RUNTIME_OP_SWITCH })
    }

    @Test
    fun interruptedHealthyUpgradeCompletesAfterProvision() = runBlocking {
        val current = manifest()
        val maintainer = FakeMaintainer(
            pendingContent = PendingTransaction(
                transactionId = "tx-9",
                targetRootfsVersion = current.rootfsVersion,
                targetRootfsSha256 = current.rootfsSha256,
                targetMinisdSha256 = current.minisdSha256,
                targetProvisionRevision = current.provisionRevision,
            ).toJson(),
        )
        var provisionCalls = 0

        val result = RuntimeDistributionManager.ensureDeployedCore(
            current,
            "dev.openminispet.android",
            maintainer,
            stopKeeper = { true },
            startKeeper = { true },
            provision = { provisionCalls += 1; true },
        )

        assertEquals(RuntimeDistributionManager.DeploymentOutcome.RECOVERED, result.outcome)
        assertEquals(1, provisionCalls)
        assertTrue(maintainer.operations.any { it.first == MinisdProtocol.RUNTIME_OP_CLEAR_STATE })
    }

    @Test
    fun interruptedTargetProvisionFailureWithoutPreviousIdentityRetainsPending() = runBlocking {
        val current = manifest()
        val maintainer = FakeMaintainer(
            pendingContent = PendingTransaction(
                transactionId = "tx-no-previous",
                targetRootfsVersion = current.rootfsVersion,
                targetRootfsSha256 = current.rootfsSha256,
                targetMinisdSha256 = current.minisdSha256,
                targetProvisionRevision = current.provisionRevision,
                phase = RuntimeDistributionManager.PendingPhase.SWITCHING,
            ).toJson(),
        )

        val result = RuntimeDistributionManager.ensureDeployedCore(
            current,
            "dev.openminispet.android",
            maintainer,
            stopKeeper = { true },
            startKeeper = { true },
            provision = { false },
        )

        assertEquals(RuntimeDistributionManager.DeploymentOutcome.FAILED, result.outcome)
        assertFalse(maintainer.operations.any { it.first == MinisdProtocol.RUNTIME_OP_ROLLBACK })
        assertFalse(maintainer.operations.any {
            it.first == MinisdProtocol.RUNTIME_OP_CLEAR_STATE && it.second.optString("name") == "pending"
        })
    }

    @Test
    fun interruptedBrokenUpgradeRollsBackAndClearsPending() = runBlocking {
        val current = manifest()
        val previous = manifest(rootfsSha256 = "0".repeat(64))
        val maintainer = FakeMaintainer(
            canonicalMetadata = null,
            previousMetadata = healthyMetadataJson(archiveSha256 = previous.rootfsSha256),
            pendingContent = PendingTransaction(
                transactionId = "tx-9",
                targetRootfsVersion = current.rootfsVersion,
                targetRootfsSha256 = current.rootfsSha256,
                targetMinisdSha256 = current.minisdSha256,
                targetProvisionRevision = current.provisionRevision,
                previousIdentity = DeployedIdentity(
                    rootfsVersion = previous.rootfsVersion,
                    rootfsSha256 = previous.rootfsSha256,
                    minisdSha256 = previous.minisdSha256,
                    provisionRevision = previous.provisionRevision,
                ),
                phase = RuntimeDistributionManager.PendingPhase.SWITCHING,
            ).toJson(),
        )

        val result = RuntimeDistributionManager.ensureDeployedCore(
            current,
            "dev.openminispet.android",
            maintainer,
            stopKeeper = { true },
        )

        assertEquals(RuntimeDistributionManager.DeploymentOutcome.ROLLED_BACK, result.outcome)
        assertTrue(maintainer.operations.any { it.first == MinisdProtocol.RUNTIME_OP_ROLLBACK })
        assertTrue(maintainer.operations.any { it.first == MinisdProtocol.RUNTIME_OP_CLEAR_STATE })
    }

    @Test
    fun preparedUpgradeNeverRollsBackAnUnrelatedPreviousSlot() = runBlocking {
        val current = manifest()
        val old = manifest(rootfsSha256 = "0".repeat(64))
        val maintainer = FakeMaintainer(
            canonicalMetadata = healthyMetadataJson(archiveSha256 = old.rootfsSha256),
            previousMetadata = healthyMetadataJson(archiveSha256 = "1".repeat(64)),
            canonicalBecomesHealthyAfterDeploy = true,
            pendingContent = PendingTransaction(
                transactionId = "tx-prepared",
                targetRootfsVersion = current.rootfsVersion,
                targetRootfsSha256 = current.rootfsSha256,
                targetMinisdSha256 = current.minisdSha256,
                targetProvisionRevision = current.provisionRevision,
                previousIdentity = DeployedIdentity(
                    rootfsVersion = old.rootfsVersion,
                    rootfsSha256 = old.rootfsSha256,
                    minisdSha256 = old.minisdSha256,
                    provisionRevision = old.provisionRevision,
                ),
            ).toJson(),
        )

        val result = RuntimeDistributionManager.ensureDeployedCore(
            current,
            "dev.openminispet.android",
            maintainer,
            stopKeeper = { true },
        )

        assertEquals(RuntimeDistributionManager.DeploymentOutcome.DEPLOYED, result.outcome)
        assertFalse(maintainer.operations.any { it.first == MinisdProtocol.RUNTIME_OP_ROLLBACK })
    }

    @Test
    fun stagingFailureClearsPending() = runBlocking {
        val maintainer = FakeMaintainer(canonicalMetadata = null, stageSucceeds = false)
        val result = RuntimeDistributionManager.ensureDeployedCore(
            manifest(), "dev.openminispet.android", maintainer, stopKeeper = { true },
        )
        assertEquals(RuntimeDistributionManager.DeploymentOutcome.FAILED, result.outcome)
        assertTrue(maintainer.operations.any { it.first == MinisdProtocol.RUNTIME_OP_CLEAR_STATE })
    }

    @Test
    fun switchFailureBeforeExchangeClearsPending() = runBlocking {
        val maintainer = FakeMaintainer(canonicalMetadata = null, deploySucceeds = false)
        val result = RuntimeDistributionManager.ensureDeployedCore(
            manifest(), "dev.openminispet.android", maintainer, stopKeeper = { true },
        )
        assertEquals(RuntimeDistributionManager.DeploymentOutcome.FAILED, result.outcome)
        assertTrue(maintainer.operations.any { it.first == MinisdProtocol.RUNTIME_OP_CLEAR_STATE })
    }

    @Test
    fun unknownSwitchOutcomeRetainsPendingForRecovery() = runBlocking {
        val maintainer = FakeMaintainer(
            canonicalMetadata = null,
            deploySucceeds = false,
            deployErrorCode = MinisdProtocol.ERROR_RUNTIME_SWITCH_UNKNOWN,
        )
        val result = RuntimeDistributionManager.ensureDeployedCore(
            manifest(), "dev.openminispet.android", maintainer, stopKeeper = { true },
        )
        assertEquals(RuntimeDistributionManager.DeploymentOutcome.FAILED, result.outcome)
        assertTrue(result.detail.contains("pending transaction retained"))
        assertFalse(maintainer.operations.any { it.first == MinisdProtocol.RUNTIME_OP_CLEAR_STATE })
    }

    @Test
    fun keeperStopFailureClearsPendingBeforeSwitch() = runBlocking {
        val maintainer = FakeMaintainer(canonicalMetadata = null)
        val result = RuntimeDistributionManager.ensureDeployedCore(
            manifest(), "dev.openminispet.android", maintainer, stopKeeper = { false },
        )
        assertEquals(RuntimeDistributionManager.DeploymentOutcome.FAILED, result.outcome)
        assertFalse(maintainer.operations.any { it.first == MinisdProtocol.RUNTIME_OP_SWITCH })
        assertTrue(maintainer.operations.any { it.first == MinisdProtocol.RUNTIME_OP_CLEAR_STATE })
    }

    @Test
    fun provisionFailureRollsBackAndKeepsDeployedIdentityUnchanged() = runBlocking {
        val previous = manifest(rootfsSha256 = "0".repeat(64))
        val maintainer = FakeMaintainer(
            canonicalMetadata = null,
            previousMetadata = healthyMetadataJson(archiveSha256 = previous.rootfsSha256),
            deployedContent = JSONObject()
                .put("schemaVersion", 2)
                .put("rootfsVersion", previous.rootfsVersion)
                .put("rootfsSha256", previous.rootfsSha256)
                .put("minisdSha256", previous.minisdSha256)
                .put("provisionRevision", previous.provisionRevision)
                .toString(),
            canonicalBecomesHealthyAfterDeploy = true,
        )
        val result = RuntimeDistributionManager.ensureDeployedCore(
            manifest(),
            "dev.openminispet.android",
            maintainer,
            stopKeeper = { true },
            startKeeper = { true },
            provision = { false },
        )
        assertEquals(RuntimeDistributionManager.DeploymentOutcome.FAILED, result.outcome)
        assertTrue(maintainer.operations.any { it.first == MinisdProtocol.RUNTIME_OP_ROLLBACK })
        assertFalse(maintainer.operations.any {
            it.first == MinisdProtocol.RUNTIME_OP_WRITE_STATE && it.second.optString("name") == "deployed"
        })
    }

    @Test
    fun provisionFailureWithoutPreviousIdentityRetainsPendingTransaction() = runBlocking {
        val maintainer = FakeMaintainer(
            canonicalMetadata = null,
            canonicalBecomesHealthyAfterDeploy = true,
        )
        val result = RuntimeDistributionManager.ensureDeployedCore(
            manifest(),
            "dev.openminispet.android",
            maintainer,
            stopKeeper = { true },
            startKeeper = { true },
            provision = { false },
        )
        assertEquals(RuntimeDistributionManager.DeploymentOutcome.FAILED, result.outcome)
        assertTrue(result.detail.contains("no matching previous identity"))
        assertFalse(maintainer.operations.any { it.first == MinisdProtocol.RUNTIME_OP_ROLLBACK })
        assertFalse(maintainer.operations.any {
            it.first == MinisdProtocol.RUNTIME_OP_CLEAR_STATE && it.second.optString("name") == "pending"
        })
    }

    @Test
    fun rollbackFailureRetainsPendingTransaction() = runBlocking {
        val previous = manifest(rootfsSha256 = "0".repeat(64))
        val maintainer = FakeMaintainer(
            canonicalMetadata = null,
            previousMetadata = healthyMetadataJson(archiveSha256 = previous.rootfsSha256),
            deployedContent = JSONObject()
                .put("schemaVersion", 2)
                .put("rootfsVersion", previous.rootfsVersion)
                .put("rootfsSha256", previous.rootfsSha256)
                .put("minisdSha256", previous.minisdSha256)
                .put("provisionRevision", previous.provisionRevision)
                .toString(),
            canonicalBecomesHealthyAfterDeploy = true,
            rollbackSucceeds = false,
        )
        val result = RuntimeDistributionManager.ensureDeployedCore(
            manifest(),
            "dev.openminispet.android",
            maintainer,
            stopKeeper = { true },
            startKeeper = { true },
            provision = { false },
        )
        assertEquals(RuntimeDistributionManager.DeploymentOutcome.FAILED, result.outcome)
        assertTrue(result.detail.contains("rollback not confirmed"))
        assertFalse(maintainer.operations.any {
            it.first == MinisdProtocol.RUNTIME_OP_CLEAR_STATE && it.second.optString("name") == "pending"
        })
    }

    @Test
    fun stateReadFailureDoesNotRedeploy() = runBlocking {
        val maintainer = FakeMaintainer(stateReadSucceeds = false)
        val result = RuntimeDistributionManager.ensureDeployedCore(
            manifest(), "dev.openminispet.android", maintainer, stopKeeper = { true },
        )
        assertEquals(RuntimeDistributionManager.DeploymentOutcome.FAILED, result.outcome)
        assertFalse(maintainer.operations.any { it.first == MinisdProtocol.RUNTIME_OP_STAGE })
    }

    @Test
    fun malformedProbeDoesNotRedeploy() = runBlocking {
        val maintainer = FakeMaintainer(malformedProbe = true)
        val result = RuntimeDistributionManager.ensureDeployedCore(
            manifest(), "dev.openminispet.android", maintainer, stopKeeper = { true },
        )
        assertEquals(RuntimeDistributionManager.DeploymentOutcome.FAILED, result.outcome)
        assertFalse(maintainer.operations.any { it.first == MinisdProtocol.RUNTIME_OP_STAGE })
    }

    @Test
    fun matchingUnprovisionedRootfsRunsProvisionWithoutSwitching() = runBlocking {
        val current = manifest()
        val maintainer = FakeMaintainer(
            deployedContent = JSONObject()
                .put("schemaVersion", 2)
                .put("rootfsVersion", current.rootfsVersion)
                .put("rootfsSha256", rootfsSha)
                .put("minisdSha256", minisdSha)
                .put("provisionRevision", 1)
                .toString(),
            canonicalProvisioned = false,
        )
        var provisionCalls = 0

        val result = RuntimeDistributionManager.ensureDeployedCore(
            current,
            "dev.openminispet.android",
            maintainer,
            stopKeeper = { true },
            startKeeper = { true },
            provision = { provisionCalls += 1; true },
        )

        assertEquals(RuntimeDistributionManager.DeploymentOutcome.DEPLOYED, result.outcome)
        assertEquals(1, provisionCalls)
        assertFalse(maintainer.operations.any { it.first == MinisdProtocol.RUNTIME_OP_SWITCH })
    }

    @Test
    fun rootfsResetRequiresStoppedKeeperAndUsesBrokerOperation() = runBlocking {
        val maintainer = FakeMaintainer()
        val blocked = RuntimeDistributionManager.resetRootfs(maintainer) { false }
        assertEquals(RuntimeDistributionManager.DeploymentOutcome.FAILED, blocked.outcome)
        assertFalse(maintainer.operations.any { it.first == MinisdProtocol.RUNTIME_OP_RESET })

        val reset = RuntimeDistributionManager.resetRootfs(maintainer) { true }
        assertEquals(RuntimeDistributionManager.DeploymentOutcome.RESET, reset.outcome)
        assertTrue(maintainer.operations.any { it.first == MinisdProtocol.RUNTIME_OP_RESET })
    }
}

private val upstream = RuntimeDistributionManifest.PINNED_UPSTREAM_SHA256
private val rootfsSha = "c0e6a145b3c8eb401f5e55eb7545f431c599290541fe61739985fb5fd1b464d7"
private val minisdSha = "ab".repeat(32)

private fun healthyMetadataJson(
    release: String = "24.04.3",
    archiveSha256: String = rootfsSha,
): String = JSONObject()
    .put("distro", "ubuntu")
    .put("version", "24.04")
    .put("release", release)
    .put("arch", "arm64")
    .put("profile", "base")
    .put("revision", 1)
    .put("upstream_sha256", upstream)
    .put("archive_sha256", archiveSha256)
    .toString()

private fun healthyRootfsHealth(): RootfsHealth =
    RootfsHealth(RootfsHealthCode.HEALTHY, "ok", JSONObject(healthyMetadataJson()), provisioned = true)
