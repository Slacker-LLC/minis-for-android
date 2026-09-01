package com.openminis.app.runtime.distribution

import com.openminis.app.runtime.distribution.RuntimeDistributionManager.DeployedIdentity
import com.openminis.app.runtime.distribution.RuntimeDistributionManager.PendingTransaction
import com.openminis.app.runtime.distribution.RuntimeDistributionManager.RecoveryDecision
import com.openminis.app.runtime.distribution.RuntimeDistributionManager.RootCommandResult
import com.openminis.app.runtime.distribution.RuntimeDistributionManager.RootRunner
import com.openminis.app.runtime.ubuntu.RootfsHealth
import com.openminis.app.runtime.ubuntu.RootfsHealthCode
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

    private class FakeRunner(
        var canonicalMetadata: String? = healthyMetadataJson(),
        var previousMetadata: String? = null,
        var deployedContent: String? = null,
        var pendingContent: String? = null,
        var deploySucceeds: Boolean = true,
        var stageSucceeds: Boolean = true,
        var verifySucceeds: Boolean = true,
        var rootOutput: String = "0",
        var canonicalBecomesHealthyAfterDeploy: Boolean = false,
        var rollbackSucceeds: Boolean = true,
    ) : RootRunner {
        val commands = mutableListOf<String>()

        override fun run(command: String): RootCommandResult {
            commands += command
            return when {
                command == "id -u" -> RootCommandResult(true, 0, rootOutput)
                command == "cat '${RuntimeDistributionManager.DEPLOYED_FILE}'" ->
                    if (deployedContent == null) RootCommandResult(true, 1, "") else RootCommandResult(true, 0, deployedContent!!)
                command == "cat '${RuntimeDistributionManager.PENDING_FILE}'" ->
                    if (pendingContent == null) RootCommandResult(true, 1, "") else RootCommandResult(true, 0, pendingContent!!)
                command.startsWith("ROOTFS='/data/adb/minis/rootfs'") && command.contains("MINIS_ROOTFS:MISSING") ->
                    if (canonicalMetadata == null) RootCommandResult(true, 0, "MINIS_ROOTFS:MISSING")
                    else RootCommandResult(true, 0, "MINIS_ROOTFS:METADATA\n$canonicalMetadata")
                command.startsWith("ROOTFS='/data/adb/minis/runtime/previous/rootfs'") && command.contains("MINIS_ROOTFS:MISSING") ->
                    if (previousMetadata == null) RootCommandResult(true, 0, "MINIS_ROOTFS:MISSING")
                    else RootCommandResult(true, 0, "MINIS_ROOTFS:METADATA\n$previousMetadata")
                command.contains("pm path") ->
                    if (stageSucceeds) RootCommandResult(true, 0, "staged") else RootCommandResult(false, -1, "", "stage failed")
                command.contains("STAGED_ARCHIVE_OK") ->
                    if (verifySucceeds) RootCommandResult(true, 0, "STAGED_ARCHIVE_OK: $rootfsSha") else RootCommandResult(true, 141, "STAGED_ARCHIVE_MISMATCH")
                command.contains("MINIS_ROOTFS:DEPLOYED") ->
                    if (deploySucceeds) {
                        if (canonicalBecomesHealthyAfterDeploy) canonicalMetadata = healthyMetadataJson()
                        RootCommandResult(true, 0, "MINIS_ROOTFS:DEPLOYED")
                    } else {
                        RootCommandResult(true, 91, "tar failed")
                    }
                command.contains("MINIS_ROOTFS:ROLLED_BACK") ->
                    if (rollbackSucceeds) {
                        RootCommandResult(true, 0, "MINIS_ROOTFS:ROLLED_BACK")
                    } else {
                        RootCommandResult(true, 121, "rollback failed")
                    }
                command.startsWith("DIR=") -> RootCommandResult(true, 0, "state written")
                command.startsWith("rm -f ") -> RootCommandResult(true, 0, "")
                else -> RootCommandResult(true, 0, "")
            }
        }
    }

    @Test
    fun `deploy command extracts then validates then swaps atomically`() {
        val command = RuntimeDistributionManager.deployRootfsCommand("tx-1")

        val extractAt = command.indexOf("tar -xzf")
        val layoutAt = command.indexOf("etc/minis/rootfs.json")
        val metadataAt = command.indexOf("grep -Eq '\"profile\"")
        val moveCurrent = command.indexOf("mv \"\$ROOTFS\" \"\$PREV\"")
        val moveStage = command.indexOf("mv \"\$STAGE\" \"\$ROOTFS\"")
        val rollbackGuard = command.indexOf("mv \"\$PREV\" \"\$ROOTFS\"")

        assertTrue(extractAt >= 0)
        assertTrue(layoutAt > extractAt)
        assertTrue(metadataAt > layoutAt)
        assertTrue(moveCurrent > metadataAt)
        assertTrue(moveStage > moveCurrent)
        assertTrue(rollbackGuard > moveStage)
        assertTrue(command.contains("MINIS_ROOTFS:DEPLOYED"))
    }

    @Test
    fun `deploy command never names user data directories`() {
        val command = RuntimeDistributionManager.deployRootfsCommand("tx-1")

        for (userPath in listOf(
            "/data/adb/minis/workspace",
            "/data/adb/minis/sessions",
            "/data/adb/minis/memory",
            "/data/adb/minis/skills",
            "/data/adb/minis/shared",
            "/data/adb/minis/home",
        )) {
            assertFalse("deploy command must not touch $userPath", command.contains(userPath))
        }
    }

    @Test
    fun `rollback command moves previous into canonical and never names user data`() {
        val command = RuntimeDistributionManager.rollbackRootfsCommand()

        assertTrue(command.contains("mv \"\$PREV\" \"\$ROOTFS\""))
        assertTrue(command.contains("MINIS_ROOTFS:ROLLED_BACK"))
        for (userPath in listOf(
            "/data/adb/minis/workspace",
            "/data/adb/minis/sessions",
            "/data/adb/minis/memory",
            "/data/adb/minis/skills",
            "/data/adb/minis/shared",
            "/data/adb/minis/home",
        )) {
            assertFalse("rollback command must not touch $userPath", command.contains(userPath))
        }
    }

    @Test
    fun `reset command only removes runtime-owned paths`() {
        val command = RuntimeDistributionManager.resetRootfsCommand()

        assertTrue(command.contains("/data/adb/minis/rootfs"))
        assertTrue(command.contains("RUNTIME='/data/adb/minis/runtime'"))
        assertTrue(command.contains("\"\$RUNTIME/staging\""))
        assertTrue(command.contains("\"\$RUNTIME/previous\""))
        assertTrue(command.contains("MINIS_ROOTFS:RESET"))
        for (userPath in listOf(
            "/data/adb/minis/workspace",
            "/data/adb/minis/sessions",
            "/data/adb/minis/memory",
            "/data/adb/minis/skills",
            "/data/adb/minis/shared",
            "/data/adb/minis/home",
        )) {
            assertFalse("reset command must not touch $userPath", command.contains(userPath))
        }
    }

    @Test
    fun `state file write is atomic and quoted`() {
        val command = RuntimeDistributionManager.writeStateFileCommand(
            "/data/adb/minis/runtime/pending.json",
            """{"a":"it's"}""",
        )

        assertTrue(command.contains("mkdir -p"))
        assertTrue(command.contains(".tmp."))
        assertTrue(command.contains("mv -f"))
        assertTrue(command.contains("it'\"'\"'s"))
    }

    @Test
    fun `verify staged archive compares exact digest`() {
        val command = RuntimeDistributionManager.verifyStagedArchiveCommand(rootfsSha)

        assertTrue(command.contains(rootfsSha))
        assertTrue(command.contains("sha256sum"))
        assertTrue(command.contains("STAGED_ARCHIVE_MISMATCH"))
        assertTrue(command.contains("STAGED_ARCHIVE_OK"))
    }

    @Test
    fun `stage command passes the app package name`() {
        val command = RuntimeDistributionManager.stageArchiveCommand("dev.openminispet.android")

        assertTrue(command.contains("dev.openminispet.android"))
        assertTrue(command.contains("pm path"))
    }

    @Test
    fun `deployed identity round trips and matches manifest`() {
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
    fun `deployed identity rejects invalid fields`() {
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
    fun `pending transaction round trips and matches manifest`() {
        val manifest = manifest()
        val pending = PendingTransaction(
            transactionId = "tx-1",
            targetRootfsVersion = manifest.rootfsVersion,
            targetRootfsSha256 = rootfsSha,
            targetMinisdSha256 = minisdSha,
            targetProvisionRevision = manifest.provisionRevision,
            previousRootfsVersion = "ubuntu-24.04-r0-0000000000000000",
        )

        val parsed = PendingTransaction.parse(pending.toJson())
        assertTrue(parsed.matches(manifest))
        assertEquals("tx-1", parsed.transactionId)
        assertEquals("ubuntu-24.04-r0-0000000000000000", parsed.previousRootfsVersion)
    }

    @Test
    fun `pending transaction rejects corrupt schema`() {
        val bad = JSONObject().put("schemaVersion", 1).put("transactionId", "x").toString()
        assertThrows(IllegalArgumentException::class.java) { PendingTransaction.parse(bad) }
    }

    @Test
    fun `pending transaction with a different target fails closed`() = runBlocking {
        val current = manifest()
        val pendingManifest = manifest(rootfsSha256 = "0".repeat(64))
        val runner = FakeRunner(
            pendingContent = PendingTransaction(
                transactionId = "tx-old",
                targetRootfsVersion = pendingManifest.rootfsVersion,
                targetRootfsSha256 = pendingManifest.rootfsSha256,
                targetMinisdSha256 = pendingManifest.minisdSha256,
                targetProvisionRevision = pendingManifest.provisionRevision,
            ).toJson(),
        )

        val result = RuntimeDistributionManager.ensureDeployedCore(
            current,
            "dev.openminispet.android",
            runner,
            stopKeeper = { true },
        )

        assertEquals(RuntimeDistributionManager.DeploymentOutcome.FAILED, result.outcome)
        assertTrue(result.detail.contains("different runtime"))
        assertFalse(runner.commands.any { it.contains("MINIS_ROOTFS:DEPLOYED") })
    }

    @Test
    fun `recovery decides complete when canonical matches target`() {
        assertEquals(
            RecoveryDecision.COMPLETE,
            RuntimeDistributionManager.decideRecovery(true, true, false),
        )
        assertEquals(
            RecoveryDecision.COMPLETE,
            RuntimeDistributionManager.decideRecovery(true, true, true),
        )
    }

    @Test
    fun `recovery rolls back when canonical is broken and previous exists`() {
        assertEquals(
            RecoveryDecision.ROLLBACK,
            RuntimeDistributionManager.decideRecovery(false, false, true),
        )
        assertEquals(
            RecoveryDecision.ROLLBACK,
            RuntimeDistributionManager.decideRecovery(true, false, true),
        )
    }

    @Test
    fun `recovery redeploys when neither canonical nor previous is valid`() {
        assertEquals(
            RecoveryDecision.REDEPLOY,
            RuntimeDistributionManager.decideRecovery(false, false, false),
        )
    }

    @Test
    fun `rootfs matches manifest only on full identity`() {
        val manifest = manifest()
        assertTrue(RuntimeDistributionManager.rootfsMatchesManifest(healthyRootfsHealth(), manifest))
        assertFalse(
            RuntimeDistributionManager.rootfsMatchesManifest(
                RootfsHealth(RootfsHealthCode.HEALTHY, "ok", JSONObject(
                    JSONObject(healthyMetadataJson()).put("release", "22.04"),
                )),
                manifest,
            ),
        )
        assertFalse(
            RuntimeDistributionManager.rootfsMatchesManifest(
                RootfsHealth(RootfsHealthCode.CORRUPT, "corrupt"),
                manifest,
            ),
        )
        assertFalse(
            RuntimeDistributionManager.rootfsMatchesManifest(
                RootfsHealth(
                    RootfsHealthCode.HEALTHY,
                    "wrong revision",
                    JSONObject(healthyMetadataJson()).put("revision", 2),
                ),
                manifest,
            ),
        )
    }

    @Test
    fun `fresh install deploys and commits`() = runBlocking {
        val runner = FakeRunner(
            canonicalMetadata = null,
            canonicalBecomesHealthyAfterDeploy = true,
        )
        val stopCalls = mutableListOf<Boolean>()

        val result = RuntimeDistributionManager.ensureDeployedCore(
            manifest(),
            "dev.openminispet.android",
            runner,
            stopKeeper = { stopCalls += true; true },
        )

        assertEquals(RuntimeDistributionManager.DeploymentOutcome.DEPLOYED, result.outcome)
        val joined = runner.commands.joinToString("\n")
        assertTrue(joined.contains("FILE='/data/adb/minis/runtime/pending.json'"))
        assertTrue(joined.contains("dev.openminispet.android"))
        assertTrue(joined.contains("STAGED_ARCHIVE_OK"))
        assertTrue(joined.contains("MINIS_ROOTFS:DEPLOYED"))
        assertTrue(joined.contains("FILE='/data/adb/minis/runtime/deployed.json'"))
        assertTrue(joined.contains("rm -f '/data/adb/minis/runtime/pending.json'"))
        assertEquals(1, stopCalls.size)
        val deployAt = joined.indexOf("MINIS_ROOTFS:DEPLOYED")
        assertTrue(deployAt >= 0)
    }

    @Test
    fun `deployed matching manifest with healthy canonical is matched`() = runBlocking {
        val manifest = manifest()
        val runner = FakeRunner(
            canonicalMetadata = healthyMetadataJson(),
            deployedContent = JSONObject()
                .put("schemaVersion", 2)
                .put("rootfsVersion", manifest.rootfsVersion)
                .put("rootfsSha256", rootfsSha)
                .put("minisdSha256", minisdSha)
                .put("provisionRevision", 1)
                .toString(),
        )

        val result = RuntimeDistributionManager.ensureDeployedCore(
            manifest,
            "dev.openminispet.android",
            runner,
            stopKeeper = { true },
        )

        assertEquals(RuntimeDistributionManager.DeploymentOutcome.MATCHED, result.outcome)
        assertFalse(runner.commands.any { it.contains("MINIS_ROOTFS:DEPLOYED") })
    }

    @Test
    fun `deployed matching manifest with corrupt canonical redeploys`() = runBlocking {
        val manifest = manifest()
        val runner = FakeRunner(
            canonicalMetadata = null,
            canonicalBecomesHealthyAfterDeploy = true,
            deployedContent = JSONObject()
                .put("schemaVersion", 2)
                .put("rootfsVersion", manifest.rootfsVersion)
                .put("rootfsSha256", rootfsSha)
                .put("minisdSha256", minisdSha)
                .put("provisionRevision", 1)
                .toString(),
        )

        val result = RuntimeDistributionManager.ensureDeployedCore(
            manifest,
            "dev.openminispet.android",
            runner,
            stopKeeper = { true },
        )

        assertEquals(RuntimeDistributionManager.DeploymentOutcome.DEPLOYED, result.outcome)
    }

    @Test
    fun `interrupted upgrade with healthy canonical target completes and commits`() = runBlocking {
        val manifest = manifest()
        val runner = FakeRunner(
            canonicalMetadata = healthyMetadataJson(),
            pendingContent = PendingTransaction(
                transactionId = "tx-9",
                targetRootfsVersion = manifest.rootfsVersion,
                targetRootfsSha256 = rootfsSha,
                targetMinisdSha256 = minisdSha,
                targetProvisionRevision = manifest.provisionRevision,
            ).toJson(),
        )

        val result = RuntimeDistributionManager.ensureDeployedCore(
            manifest,
            "dev.openminispet.android",
            runner,
            stopKeeper = { true },
        )

        assertEquals(RuntimeDistributionManager.DeploymentOutcome.RECOVERED, result.outcome)
        val joined = runner.commands.joinToString("\n")
        assertTrue(joined.contains("FILE='/data/adb/minis/runtime/deployed.json'"))
        assertTrue(joined.contains("rm -f '/data/adb/minis/runtime/pending.json'"))
        assertFalse(joined.contains("MINIS_ROOTFS:DEPLOYED"))
    }

    @Test
    fun `interrupted upgrade with broken canonical and valid previous rolls back`() = runBlocking {
        val manifest = manifest()
        val runner = FakeRunner(
            canonicalMetadata = null,
            previousMetadata = healthyMetadataJson(),
            pendingContent = PendingTransaction(
                transactionId = "tx-9",
                targetRootfsVersion = manifest.rootfsVersion,
                targetRootfsSha256 = rootfsSha,
                targetMinisdSha256 = minisdSha,
                targetProvisionRevision = manifest.provisionRevision,
            ).toJson(),
        )

        val result = RuntimeDistributionManager.ensureDeployedCore(
            manifest,
            "dev.openminispet.android",
            runner,
            stopKeeper = { true },
        )

        assertEquals(RuntimeDistributionManager.DeploymentOutcome.ROLLED_BACK, result.outcome)
        assertTrue(runner.commands.any { it.contains("MINIS_ROOTFS:ROLLED_BACK") })
        assertTrue(runner.commands.any { it == "rm -f '${RuntimeDistributionManager.PENDING_FILE}'" })
    }

    @Test
    fun `interrupted upgrade with no usable rootfs redeploys`() = runBlocking {
        val manifest = manifest()
        val runner = FakeRunner(
            canonicalMetadata = null,
            canonicalBecomesHealthyAfterDeploy = true,
            pendingContent = PendingTransaction(
                transactionId = "tx-9",
                targetRootfsVersion = manifest.rootfsVersion,
                targetRootfsSha256 = rootfsSha,
                targetMinisdSha256 = minisdSha,
                targetProvisionRevision = manifest.provisionRevision,
            ).toJson(),
        )

        val result = RuntimeDistributionManager.ensureDeployedCore(
            manifest,
            "dev.openminispet.android",
            runner,
            stopKeeper = { true },
        )

        assertEquals(RuntimeDistributionManager.DeploymentOutcome.DEPLOYED, result.outcome)
        assertTrue(runner.commands.any { it.contains("MINIS_ROOTFS:DEPLOYED") })
    }

    @Test
    fun `corrupt pending transaction fails closed`() = runBlocking {
        val runner = FakeRunner(pendingContent = """{"broken": true}""")

        val result = RuntimeDistributionManager.ensureDeployedCore(
            manifest(),
            "dev.openminispet.android",
            runner,
            stopKeeper = { true },
        )

        assertEquals(RuntimeDistributionManager.DeploymentOutcome.FAILED, result.outcome)
        assertTrue(result.detail.contains("pending transaction"))
        assertFalse(runner.commands.any { it.contains("MINIS_ROOTFS:DEPLOYED") })
    }

    @Test
    fun `missing root fails closed`() = runBlocking {
        val runner = FakeRunner(rootOutput = "1")

        val result = RuntimeDistributionManager.ensureDeployedCore(
            manifest(),
            "dev.openminispet.android",
            runner,
            stopKeeper = { true },
        )

        assertEquals(RuntimeDistributionManager.DeploymentOutcome.ROOT_UNAVAILABLE, result.outcome)
    }

    @Test
    fun `staging failure aborts and clears pending`() = runBlocking {
        val runner = FakeRunner(canonicalMetadata = null, stageSucceeds = false)

        val result = RuntimeDistributionManager.ensureDeployedCore(
            manifest(),
            "dev.openminispet.android",
            runner,
            stopKeeper = { true },
        )

        assertEquals(RuntimeDistributionManager.DeploymentOutcome.FAILED, result.outcome)
        assertTrue(runner.commands.any { it == "rm -f '${RuntimeDistributionManager.PENDING_FILE}'" })
    }

    @Test
    fun `deploy failure aborts and clears pending`() = runBlocking {
        val runner = FakeRunner(canonicalMetadata = null, deploySucceeds = false)

        val result = RuntimeDistributionManager.ensureDeployedCore(
            manifest(),
            "dev.openminispet.android",
            runner,
            stopKeeper = { true },
        )

        assertEquals(RuntimeDistributionManager.DeploymentOutcome.FAILED, result.outcome)
        assertTrue(runner.commands.any { it == "rm -f '${RuntimeDistributionManager.PENDING_FILE}'" })
    }

    @Test
    fun `keeper stop failure aborts and clears pending`() = runBlocking {
        val runner = FakeRunner(canonicalMetadata = null)

        val result = RuntimeDistributionManager.ensureDeployedCore(
            manifest(),
            "dev.openminispet.android",
            runner,
            stopKeeper = { false },
        )

        assertEquals(RuntimeDistributionManager.DeploymentOutcome.FAILED, result.outcome)
        assertFalse(runner.commands.any { it.contains("MINIS_ROOTFS:DEPLOYED") })
        assertTrue(runner.commands.any { it == "rm -f '${RuntimeDistributionManager.PENDING_FILE}'" })
    }

    @Test
    fun `rootfs reset refuses to run when keeper cannot stop`() = runBlocking {
        val runner = FakeRunner()

        val result = RuntimeDistributionManager.resetRootfs(runner) { false }

        assertEquals(RuntimeDistributionManager.DeploymentOutcome.FAILED, result.outcome)
        assertTrue(result.detail.contains("stop keeper"))
        assertFalse(runner.commands.any { it.contains("MINIS_ROOTFS:RESET") })
    }

    @Test
    fun `canonical mismatch after deploy rolls back and fails`() = runBlocking {
        val runner = FakeRunner(
            canonicalMetadata = null,
            previousMetadata = healthyMetadataJson(),
        )
        runner.canonicalMetadata = null

        val result = RuntimeDistributionManager.ensureDeployedCore(
            manifest(),
            "dev.openminispet.android",
            runner,
            stopKeeper = { true },
        )

        assertEquals(RuntimeDistributionManager.DeploymentOutcome.FAILED, result.outcome)
    }

    @Test
    fun `deployment order stages before stopping keeper before switching`() = runBlocking {
        val runner = FakeRunner(
            canonicalMetadata = null,
            canonicalBecomesHealthyAfterDeploy = true,
        )
        var stopRan = false

        val result = RuntimeDistributionManager.ensureDeployedCore(
            manifest(),
            "dev.openminispet.android",
            runner,
            stopKeeper = { stopRan = true; true },
        )

        assertEquals(RuntimeDistributionManager.DeploymentOutcome.DEPLOYED, result.outcome)
        val stageAt = runner.commands.indexOfFirst { it.contains("pm path") }
        val deployAt = runner.commands.indexOfFirst { it.contains("MINIS_ROOTFS:DEPLOYED") }
        assertTrue(stageAt in 0 until deployAt)
        assertTrue(stopRan)
    }

    @Test
    fun `provision failure on fresh deploy rolls back and clears pending`() = runBlocking {
        val runner = FakeRunner(
            canonicalMetadata = null,
            canonicalBecomesHealthyAfterDeploy = true,
        )
        var started = false
        var stopped = false

        val result = RuntimeDistributionManager.ensureDeployedCore(
            manifest(),
            "dev.openminispet.android",
            runner,
            stopKeeper = { stopped = true; true },
            startKeeper = { started = true; true },
            provision = { false },
        )

        assertEquals(RuntimeDistributionManager.DeploymentOutcome.FAILED, result.outcome)
        assertTrue(result.detail.contains("provision failed"))
        assertTrue(runner.commands.any { it.contains("MINIS_ROOTFS:ROLLED_BACK") })
        assertTrue(runner.commands.any { it == "rm -f '${RuntimeDistributionManager.PENDING_FILE}'" })
        assertFalse(runner.commands.any { it.contains("FILE='/data/adb/minis/runtime/deployed.json'") })
        assertTrue(started)
        assertTrue(stopped)
    }

    @Test
    fun `rollback failure retains pending transaction`() = runBlocking {
        val runner = FakeRunner(
            canonicalMetadata = null,
            canonicalBecomesHealthyAfterDeploy = true,
            rollbackSucceeds = false,
        )

        val result = RuntimeDistributionManager.ensureDeployedCore(
            manifest(),
            "dev.openminispet.android",
            runner,
            stopKeeper = { true },
            startKeeper = { true },
            provision = { false },
        )

        assertEquals(RuntimeDistributionManager.DeploymentOutcome.FAILED, result.outcome)
        assertTrue(result.detail.contains("rollback not confirmed"))
        assertFalse(runner.commands.any { it == "rm -f '${RuntimeDistributionManager.PENDING_FILE}'" })
    }

    @Test
    fun `start keeper failure on fresh deploy rolls back and clears pending`() = runBlocking {
        val runner = FakeRunner(
            canonicalMetadata = null,
            canonicalBecomesHealthyAfterDeploy = true,
        )

        val result = RuntimeDistributionManager.ensureDeployedCore(
            manifest(),
            "dev.openminispet.android",
            runner,
            stopKeeper = { true },
            startKeeper = { false },
        )

        assertEquals(RuntimeDistributionManager.DeploymentOutcome.FAILED, result.outcome)
        assertTrue(runner.commands.any { it.contains("MINIS_ROOTFS:ROLLED_BACK") })
        assertTrue(runner.commands.any { it == "rm -f '${RuntimeDistributionManager.PENDING_FILE}'" })
    }

    @Test
    fun `interrupted upgrade completes only after provision succeeds`() = runBlocking {
        val manifest = manifest()
        val runner = FakeRunner(
            canonicalMetadata = healthyMetadataJson(),
            pendingContent = PendingTransaction(
                transactionId = "tx-9",
                targetRootfsVersion = manifest.rootfsVersion,
                targetRootfsSha256 = rootfsSha,
                targetMinisdSha256 = minisdSha,
                targetProvisionRevision = manifest.provisionRevision,
            ).toJson(),
        )
        var provisionCalls = 0

        val result = RuntimeDistributionManager.ensureDeployedCore(
            manifest,
            "dev.openminispet.android",
            runner,
            stopKeeper = { true },
            startKeeper = { true },
            provision = { provisionCalls += 1; true },
        )

        assertEquals(RuntimeDistributionManager.DeploymentOutcome.RECOVERED, result.outcome)
        assertEquals(1, provisionCalls)
        assertTrue(runner.commands.any { it.contains("FILE='/data/adb/minis/runtime/deployed.json'") })
        assertTrue(runner.commands.any { it == "rm -f '${RuntimeDistributionManager.PENDING_FILE}'" })
    }

    @Test
    fun `interrupted upgrade rolls back when provision fails`() = runBlocking {
        val manifest = manifest()
        val runner = FakeRunner(
            canonicalMetadata = healthyMetadataJson(),
            pendingContent = PendingTransaction(
                transactionId = "tx-9",
                targetRootfsVersion = manifest.rootfsVersion,
                targetRootfsSha256 = rootfsSha,
                targetMinisdSha256 = minisdSha,
                targetProvisionRevision = manifest.provisionRevision,
            ).toJson(),
        )

        val result = RuntimeDistributionManager.ensureDeployedCore(
            manifest,
            "dev.openminispet.android",
            runner,
            stopKeeper = { true },
            startKeeper = { true },
            provision = { false },
        )

        assertEquals(RuntimeDistributionManager.DeploymentOutcome.FAILED, result.outcome)
        assertTrue(runner.commands.any { it.contains("MINIS_ROOTFS:ROLLED_BACK") })
        assertTrue(runner.commands.any { it == "rm -f '${RuntimeDistributionManager.PENDING_FILE}'" })
        assertFalse(runner.commands.any { it.contains("FILE='/data/adb/minis/runtime/deployed.json'") })
    }
}

private val upstream = RuntimeDistributionManifest.PINNED_UPSTREAM_SHA256
private val rootfsSha = "c0e6a145b3c8eb401f5e55eb7545f431c599290541fe61739985fb5fd1b464d7"
private val minisdSha = "ab".repeat(32)

private fun healthyMetadataJson(release: String = "24.04.3"): String = JSONObject()
    .put("distro", "ubuntu")
    .put("version", "24.04")
    .put("release", release)
    .put("arch", "arm64")
    .put("profile", "base")
    .put("revision", 1)
    .put("upstream_sha256", upstream)
    .toString()

private fun healthyRootfsHealth(): RootfsHealth =
    RootfsHealth(RootfsHealthCode.HEALTHY, "ok", JSONObject(healthyMetadataJson()))
