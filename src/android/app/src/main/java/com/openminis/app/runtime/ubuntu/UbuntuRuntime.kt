package com.openminis.app.runtime.ubuntu

import android.content.Context
import android.util.Log
import com.openminis.app.sandbox.RootfsManager
import com.openminis.app.runtime.distribution.RuntimePayloadVerifier
import com.openminis.app.runtime.minisd.MinisdBootstrap
import com.openminis.app.runtime.minisd.MinisdClient
import com.openminis.app.runtime.minisd.MinisdError
import com.openminis.app.runtime.minisd.MinisdProtocol
import com.openminis.app.runtime.minisd.MinisdResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * App-side Ubuntu Runtime. [init] is lazy (no su). [ensureReady] starts the
 * minisd broker independently, validates/repairs rootfs, then creates a keeper.
 */
object UbuntuRuntime {
    private const val TAG = "UbuntuRuntime"
    private const val ROOT_AUTH_TIMEOUT_MS = 15_000L
    private const val PROVISION_TIMEOUT_MS = 600_000L
    private val WHITESPACE = Regex("\\s+")
    private val SHA256_TOKEN = Regex("^[0-9a-fA-F]{64}$")

    data class Snapshot(
        val running: Boolean = false,
        val available: Boolean = false,
        val pid: Int? = null,
        val version: String? = null,
        val provisioned: Boolean = false,
        val guestUid: Int? = null,
        val sessionsRoot: String? = null,
        val layoutKnown: Boolean = false,
        val hostWorkspace: String? = null,
        val hostMemory: String? = null,
        val hostSkills: String? = null,
        val hostShared: String? = null,
        val lastError: String? = null,
        val mock: Boolean = false,
    )

    data class ShellResult(
        val output: String,
        val exitCode: Int,
        val durationMs: Long,
    )

    class RuntimeInfrastructureException(val runtimeError: MinisdError) :
        IllegalStateException("${runtimeError.code}: ${runtimeError.detail}")

    private data class BrokerStartResult(
        val ok: Boolean,
        val error: String? = null,
    )

    private data class RootCommandResult(
        val completed: Boolean,
        val exitCode: Int,
        val output: String,
        val error: String? = null,
    )

    @Volatile
    var isInitialized: Boolean = false
        private set

    @Volatile
    var redirectPaths: Boolean = false
        private set

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var verifiedPackagedBrokerSha256: String? = null

    @Volatile
    var client: MinisdClient = MinisdClient()
        private set

    private val startLock = Mutex()
    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        verifiedPackagedBrokerSha256 = null
        UbuntuPaths.init(ctx)
        val dir = java.io.File(ctx.filesDir, "minis")
        dir.mkdirs()
        client = MinisdClient(appSocketPath = java.io.File(dir, "minisd.sock").absolutePath)
        isInitialized = true
        redirectPaths = true
        Log.i(TAG, "initialized (lazy) appSocket=${dir}/minisd.sock uid=${ctx.applicationInfo.uid}")
    }

    suspend fun refresh(): Snapshot = apply(client.ubuntuStatus())

    /** Ensure only the Root broker is reachable; Ubuntu/rootfs health is not required. */
    suspend fun ensureBrokerReady(): MinisdResponse = startLock.withLock {
        val ctx = appContext ?: return@withLock MinisdProtocol.runtimeError(
            MinisdProtocol.ERROR_RUNTIME_UNAVAILABLE,
            "UbuntuRuntime.init(context) has not been called",
        )
        ensureBrokerReadyLocked(ctx).also { if (it.ok) apply(it) }
    }

    suspend fun ensureReady(): Snapshot = startLock.withLock {
        val ctx = appContext
            ?: return@withLock fail(
                "${MinisdProtocol.ERROR_RUNTIME_UNAVAILABLE}: UbuntuRuntime.init(context) has not been called",
            )
        val expectedUid = ctx.applicationInfo.uid
        val broker = ensureBrokerReadyLocked(ctx)
        if (!broker.ok) {
            return@withLock failStructured(
                broker.error?.code ?: MinisdProtocol.ERROR_RUNTIME_UNAVAILABLE,
                broker.error?.detail ?: "failed to start minisd",
            )
        }
        var cur = apply(broker)

        // RuntimeDistributionManager is the single owner of runtime deployment,
        // upgrade, and rollback. It consumes the APK manifest, recovers an
        // interrupted switch from pending.json, and never touches user data.
        val deployment = com.openminis.app.runtime.distribution.RuntimeDistributionManager
            .ensureDeployed(
                context = ctx,
                maintainer = com.openminis.app.runtime.distribution.RuntimeDistributionManager.RuntimeMaintainer {
                        operation, params ->
                    client.runtimeMaintenance(operation, params)
                },
                stopKeeper = {
                    stopForDeployment()
                },
                startKeeper = {
                    val started = apply(
                        client.ubuntuStart(
                            workspace = UbuntuPaths.hostWorkspace,
                            memory = UbuntuPaths.hostMemory,
                            skills = UbuntuPaths.hostSkills,
                            shared = UbuntuPaths.hostShared,
                            sessionsRoot = UbuntuPaths.hostSessions,
                        ),
                    )
                    started.running
                },
                provision = {
                    val response = runCatching {
                        client.ubuntuProvision(PROVISION_TIMEOUT_MS)
                    }.getOrElse {
                        MinisdProtocol.runtimeError(
                            MinisdProtocol.ERROR_RUNTIME_UNAVAILABLE,
                            "provision transport failed: ${it.message}",
                        )
                    }
                    if (!response.ok) {
                        Log.w(
                            TAG,
                            "ubuntu.provision failed code=${response.error?.code} detail=${response.error?.detail}",
                        )
                    }
                    response.ok
                },
            )
        when (deployment.outcome) {
            com.openminis.app.runtime.distribution.RuntimeDistributionManager.DeploymentOutcome
                .ROOT_UNAVAILABLE,
            com.openminis.app.runtime.distribution.RuntimeDistributionManager.DeploymentOutcome
                .PAYLOAD_INVALID,
            -> return@withLock failStructured(
                MinisdProtocol.ERROR_RUNTIME_UNAVAILABLE,
                deployment.detail,
            )
            com.openminis.app.runtime.distribution.RuntimeDistributionManager.DeploymentOutcome
                .FAILED,
            -> return@withLock failStructured(
                MinisdProtocol.ERROR_ROOTFS_INVALID,
                deployment.detail,
            )
            else -> Unit
        }

        cur = refresh()
        if (cur.running && runtimeLayoutMatches(cur)) {
            redirectPaths = true
            return@withLock cur
        }

        if (cur.running) {
            val stopped = apply(client.ubuntuStop())
            if (stopped.running) {
                return@withLock failStructured(
                    MinisdProtocol.ERROR_RUNTIME_LAYOUT_MISMATCH,
                    "failed to stop keeper with stale runtime bind layout",
                )
            }
        }

        val raw = apply(
            client.ubuntuStart(
                workspace = UbuntuPaths.hostWorkspace,
                memory = UbuntuPaths.hostMemory,
                skills = UbuntuPaths.hostSkills,
                shared = UbuntuPaths.hostShared,
                sessionsRoot = UbuntuPaths.hostSessions,
            ),
        )
        val started = if (raw.running) refresh() else raw
        if (!started.running) {
            return@withLock failStructured(
                MinisdProtocol.ERROR_RUNTIME_UNAVAILABLE,
                started.lastError ?: "ubuntu.start failed",
            )
        }
        if (!runtimeLayoutMatches(started)) {
            runCatching { client.ubuntuStop() }
            return@withLock failStructured(
                MinisdProtocol.ERROR_RUNTIME_LAYOUT_MISMATCH,
                layoutMismatchDetail(
                    started,
                    UbuntuPaths.hostWorkspace,
                    UbuntuPaths.hostMemory,
                    UbuntuPaths.hostSkills,
                    UbuntuPaths.hostShared,
                ),
            )
        }
        val migrated = UbuntuPaths.migrateLegacyFilesDir(ctx.filesDir)
        if (migrated.error != null) {
            Log.w(TAG, "legacy filesDir migration: ${migrated.error}")
        }
        redirectPaths = true
        Log.i(
            TAG,
            "ubuntu.start ok pid=${started.pid} version=${started.version} uid=$expectedUid layoutKnown=${started.layoutKnown} migrated=${migrated.copied}",
        )
        started
    }

    private fun runtimeLayoutMatches(snapshot: Snapshot): Boolean =
        runtimeLayoutMatches(
            snapshot,
            UbuntuPaths.hostWorkspace,
            UbuntuPaths.hostMemory,
            UbuntuPaths.hostSkills,
            UbuntuPaths.hostShared,
        )

    internal fun runtimeLayoutMatches(
        snapshot: Snapshot,
        expectedWorkspace: String,
        expectedMemory: String,
        expectedSkills: String,
        expectedShared: String,
    ): Boolean = snapshot.layoutKnown &&
        snapshot.hostWorkspace == expectedWorkspace &&
        snapshot.hostMemory == expectedMemory &&
        snapshot.hostSkills == expectedSkills &&
        snapshot.hostShared == expectedShared

    internal fun layoutMismatchDetail(
        snapshot: Snapshot,
        expectedWorkspace: String,
        expectedMemory: String,
        expectedSkills: String,
        expectedShared: String,
    ): String = "runtime layout mismatch: " +
        "workspace=${snapshot.hostWorkspace ?: "unknown"} expected=$expectedWorkspace, " +
        "memory=${snapshot.hostMemory ?: "unknown"} expected=$expectedMemory, " +
        "skills=${snapshot.hostSkills ?: "unknown"} expected=$expectedSkills, " +
        "shared=${snapshot.hostShared ?: "unknown"} expected=$expectedShared, " +
        "layoutKnown=${snapshot.layoutKnown}"

    internal fun brokerIdentityMatches(snapshot: Snapshot, expectedUid: Int): Boolean =
        snapshot.guestUid == expectedUid

    internal fun parseSha256sum(output: String): String? = output
        .lineSequence()
        .map { it.trim().split(WHITESPACE, limit = 2).firstOrNull().orEmpty() }
        .firstOrNull { it.matches(SHA256_TOKEN) }
        ?.lowercase()

    internal fun brokerBinaryMatches(expectedSha256: String, sha256sumOutput: String): Boolean =
        parseSha256sum(sha256sumOutput) == expectedSha256.lowercase()

    internal fun shouldRetryAfterPreExecFailure(error: MinisdError?, attempt: Int): Boolean =
        attempt == 0 && error?.code == MinisdProtocol.ERROR_KEEPER_NAMESPACE_LOST

    internal fun shellStartMarker(seed: Long): String =
        "__MINIS_EXEC_STARTED_${seed.toString(16)}__"

    internal fun wrapShellCommand(command: String, marker: String): String {
        require(marker.matches(Regex("^[A-Za-z0-9_]+$"))) { "invalid shell start marker" }
        return "printf '%s\\n' '$marker' >&2\n$command"
    }

    internal fun didUserCommandStart(response: MinisdResponse, marker: String): Boolean {
        val stderr = response.result?.optString("stderr").orEmpty()
        return stderr.split('\n').any { it.trimEnd('\r') == marker }
    }

    internal fun stripShellStartMarker(stderr: String, marker: String): String =
        stderr.split('\n')
            .filterNot { it.trimEnd('\r') == marker }
            .joinToString("\n")

    private suspend fun ensureBrokerReadyLocked(ctx: Context): MinisdResponse {
        val expectedUid = ctx.applicationInfo.uid
        var response = client.ubuntuStatus()
        var uid = response.brokerUid()
        val packagedBinaryMatches = if (response.ok && uid == expectedUid) {
            packagedBrokerMatchesInstalled(ctx)
        } else {
            null
        }
        if (response.ok && uid == expectedUid && packagedBinaryMatches != false) return response
        if (packagedBinaryMatches == false) {
            Log.w(TAG, "running minisd broker binary differs from packaged APK; restarting")
        }

        val forcedRestart = uid != null
        if (uid != null) {
            Log.w(TAG, "stale minisd identity brokerUid=$uid appUid=$expectedUid")
        }
        var started = ensureMinisdUp(forceRestart = forcedRestart)
        if (!started.ok) {
            return MinisdProtocol.runtimeError(
                if (forcedRestart) {
                    MinisdProtocol.ERROR_RUNTIME_LAYOUT_MISMATCH
                } else {
                    MinisdProtocol.ERROR_RUNTIME_UNAVAILABLE
                },
                started.error ?: "failed to start minisd",
            )
        }
        response = awaitBrokerResponse(expectedUid)
        uid = response.brokerUid()

        // A normal spawn may have found a stale pidfile/server. Retry once with
        // verified broker replacement; never loop indefinitely.
        if (uid != expectedUid && !forcedRestart) {
            started = ensureMinisdUp(forceRestart = true)
            if (!started.ok) {
                return MinisdProtocol.runtimeError(
                    MinisdProtocol.ERROR_RUNTIME_LAYOUT_MISMATCH,
                    started.error ?: "minisd recovery failed",
                )
            }
            response = awaitBrokerResponse(expectedUid)
            uid = response.brokerUid()
        }
        return if (response.ok && uid == expectedUid) {
            response
        } else {
            MinisdProtocol.runtimeError(
                MinisdProtocol.ERROR_RUNTIME_LAYOUT_MISMATCH,
                "minisd app identity mismatch: expected uid=$expectedUid, broker uid=${uid ?: "unknown"}",
            )
        }
    }

    private suspend fun packagedBrokerMatchesInstalled(ctx: Context): Boolean? {
        val expected = packagedBrokerSha256(ctx) ?: return null
        if (verifiedPackagedBrokerSha256 == expected) return true

        val installed = runRoot(
            "sha256sum ${MinisdBootstrap.shellQuote(MinisdProtocol.DEFAULT_BIN)}",
        )
        if (!installed.completed || installed.exitCode != 0) return null
        val actual = parseSha256sum(installed.output) ?: return null
        return (actual == expected).also { if (it) verifiedPackagedBrokerSha256 = expected }
    }

    private suspend fun packagedBrokerSha256(ctx: Context): String? {
        val packaged = File(
            ctx.applicationInfo.nativeLibraryDir,
            RuntimeProvision.PACKAGED_BROKER_NAME,
        )
        return runCatching {
            withContext(Dispatchers.IO) {
                if (!packaged.isFile) {
                    null
                } else {
                    packaged.inputStream().use { RuntimePayloadVerifier.sha256(it) }
                }
            }
        }.getOrNull()
    }

    private suspend fun awaitBrokerResponse(expectedUid: Int): MinisdResponse {
        var response = client.ubuntuStatus()
        repeat(10) {
            delay(300)
            response = client.ubuntuStatus()
            if (response.ok && response.brokerUid() == expectedUid) return response
        }
        return response
    }

    private fun MinisdResponse.brokerUid(): Int? {
        val result = result ?: return null
        return if (ok && result.has("uid") && !result.isNull("uid")) result.optInt("uid") else null
    }

    private fun findSu(): String? = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/data/adb/ksu/bin/su",
        "/debug_ramdisk/su",
    ).firstOrNull { java.io.File(it).canExecute() }
        ?: System.getenv("PATH").orEmpty()
            .split(java.io.File.pathSeparatorChar)
            .asSequence()
            .map { java.io.File(it, "su") }
            .firstOrNull { it.canExecute() }
            ?.absolutePath

    private suspend fun runRoot(
        command: String,
        timeoutMs: Long = 30_000,
    ): RootCommandResult =
        withContext(Dispatchers.IO) {
            val su = findSu()
                ?: return@withContext RootCommandResult(
                        completed = false,
                        exitCode = -1,
                        output = "",
                        error = "no executable su found",
                    )
            var process: Process? = null
            try {
                process = ProcessBuilder(su, "-c", command)
                    .redirectErrorStream(true)
                    .start()
                if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(1, TimeUnit.SECONDS)
                    return@withContext RootCommandResult(
                            completed = false,
                            exitCode = -1,
                            output = "",
                            error = "root command timed out",
                        )
                }
                val output = runCatching {
                    process.inputStream.bufferedReader().use { it.readText().trim() }
                }.getOrDefault("")
                RootCommandResult(true, process.exitValue(), output)
            } catch (t: Throwable) {
                RootCommandResult(false, -1, "", t.message)
            } finally {
                process?.destroy()
            }
        }

    private suspend fun ensureMinisdUp(forceRestart: Boolean): BrokerStartResult =
        withContext(Dispatchers.IO) {
            val ctx = appContext
                ?: return@withContext BrokerStartResult(false, "no app context")
            if (forceRestart) {
                runCatching { client.ubuntuStop() }
            }
            val su = findSu()
                ?: return@withContext BrokerStartResult(
                    false,
                    "Root unavailable: no executable su found; grant this app access in KernelSU/Magisk",
                )

            val rootProbe = try {
                ProcessBuilder(su, "-c", "id -u")
                    .redirectErrorStream(true)
                    .start()
            } catch (t: Throwable) {
                return@withContext BrokerStartResult(false, "failed to start su: ${t.message}")
            }
            if (!rootProbe.waitFor(ROOT_AUTH_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                rootProbe.destroyForcibly()
                rootProbe.waitFor(1, TimeUnit.SECONDS)
                return@withContext BrokerStartResult(
                    false,
                    "Root authorization timed out; approve this app in KernelSU/Magisk and retry",
                )
            }
            val rootOutput = runCatching {
                rootProbe.inputStream.bufferedReader().use { it.readText().trim() }
            }.getOrDefault("")
            if (rootProbe.exitValue() != 0) {
                val detail = rootOutput.take(300).ifBlank { "su exited ${rootProbe.exitValue()}" }
                return@withContext BrokerStartResult(
                    false,
                    "Root authorization denied or unavailable: $detail",
                )
            }
            val effectiveUid = MinisdBootstrap.parseEffectiveUid(rootOutput)
            if (effectiveUid != 0) {
                return@withContext BrokerStartResult(
                    false,
                    "Root authorization invalid: su returned uid=${effectiveUid ?: "unknown"}, expected 0",
                )
            }

            val appSock = java.io.File(ctx.filesDir, "minis/minisd.sock").absolutePath
            val template = try {
                ctx.assets.open(MinisdBootstrap.POLICY_ASSET)
                    .bufferedReader()
                    .use { it.readText() }
            } catch (t: Throwable) {
                return@withContext BrokerStartResult(
                    false,
                    "cannot read ${MinisdBootstrap.POLICY_ASSET}: ${t.message}",
                )
            }
            val policy = try {
                MinisdBootstrap.policyForUid(template, ctx.applicationInfo.uid)
            } catch (t: Throwable) {
                return@withContext BrokerStartResult(false, "invalid minisd policy template: ${t.message}")
            }
            val packagedBroker = java.io.File(
                ctx.applicationInfo.nativeLibraryDir,
                com.openminis.app.runtime.ubuntu.RuntimeProvision.PACKAGED_BROKER_NAME,
            ).absolutePath
            val cmd = MinisdBootstrap.watchdogCommand(
                appSocket = appSock,
                policyJson = policy,
                forceRestart = forceRestart,
                packagedBroker = packagedBroker,
            )

            val proc = try {
                ProcessBuilder(su, "-c", cmd)
                    .redirectErrorStream(true)
                    .start()
            } catch (t: Throwable) {
                return@withContext BrokerStartResult(false, "failed to start su: ${t.message}")
            }

            try {
                val finished = proc.waitFor(6, TimeUnit.SECONDS)
                if (!finished) {
                    proc.destroyForcibly()
                    proc.waitFor(1, TimeUnit.SECONDS)
                    return@withContext BrokerStartResult(
                        false,
                        "minisd bootstrap timed out while invoking su",
                    )
                }
                val output = runCatching {
                    proc.inputStream.bufferedReader().use { it.readText().trim() }
                }.getOrDefault("")
                if (proc.exitValue() != 0) {
                    val detail = output.ifBlank { "su exited ${proc.exitValue()}" }
                    Log.w(TAG, "ensureMinisdUp failed: $detail")
                    return@withContext BrokerStartResult(false, detail)
                }
                Log.i(
                    TAG,
                    "ensureMinisdUp forceRestart=$forceRestart uid=${ctx.applicationInfo.uid} ${output.take(200)}",
                )
                packagedBrokerSha256(ctx)?.let { verifiedPackagedBrokerSha256 = it }
                BrokerStartResult(true)
            } finally {
                proc.destroy()
            }
        }

    suspend fun start(): Snapshot = ensureReady()

    suspend fun stop(): Snapshot {
        val resp = client.ubuntuStop()
        redirectPaths = false
        return apply(resp)
    }

    suspend fun resetRootfs(): com.openminis.app.runtime.distribution.RuntimeDistributionManager.DeploymentResult =
        startLock.withLock {
            val ctx = appContext
                ?: return@withLock com.openminis.app.runtime.distribution.RuntimeDistributionManager.DeploymentResult(
                    com.openminis.app.runtime.distribution.RuntimeDistributionManager.DeploymentOutcome.ROOT_UNAVAILABLE,
                    "UbuntuRuntime.init(context) has not been called",
                )
            val broker = ensureBrokerReadyLocked(ctx)
            if (!broker.ok) {
                return@withLock com.openminis.app.runtime.distribution.RuntimeDistributionManager.DeploymentResult(
                    com.openminis.app.runtime.distribution.RuntimeDistributionManager.DeploymentOutcome.ROOT_UNAVAILABLE,
                    broker.error?.detail ?: "failed to start minisd",
                )
            }
            val result = com.openminis.app.runtime.distribution.RuntimeDistributionManager.resetRootfs(
                maintainer = com.openminis.app.runtime.distribution.RuntimeDistributionManager.RuntimeMaintainer {
                        operation, params ->
                    client.runtimeMaintenance(operation, params)
                },
                stopKeeper = { stopForDeployment() },
            )
            if (result.outcome == com.openminis.app.runtime.distribution.RuntimeDistributionManager.DeploymentOutcome.RESET) {
                redirectPaths = false
            }
            result
        }

    suspend fun exec(
        argv: List<String>,
        timeoutMs: Long = 30_000,
        sessionId: String? = null,
    ): MinisdResponse = execWithStructuredRecovery {
        client.ubuntuExec(argv, timeoutMs, sessionId = sessionId)
    }

    suspend fun adminExec(
        argv: List<String>,
        timeoutMs: Long = 120_000,
        confirmId: String? = null,
    ): MinisdResponse = MinisdProtocol.promoteExecInfrastructureFailure(
        client.ubuntuAdminExec(argv, timeoutMs, confirmId),
        userCommandStarted = null,
    )

    suspend fun provision(timeoutMs: Long = 600_000): MinisdResponse =
        client.ubuntuProvision(timeoutMs)

    /**
     * Raw argv execution cannot safely infer legacy helper pre-exec state from
     * exit code alone. It retries only a broker-provided structured
     * KEEPER_NAMESPACE_LOST error. Numeric 4/5/6 remain user exits here.
     */
    private suspend fun execWithStructuredRecovery(
        call: suspend () -> MinisdResponse,
    ): MinisdResponse {
        var attempt = 0
        var response = MinisdProtocol.promoteExecInfrastructureFailure(
            call(),
            userCommandStarted = null,
        )
        if (!shouldRetryAfterPreExecFailure(response.error, attempt)) return response

        attempt += 1
        runCatching { client.ubuntuStop() }
        val ready = ensureReady()
        if (!ready.running) {
            return MinisdProtocol.runtimeError(
                MinisdProtocol.ERROR_RUNTIME_UNAVAILABLE,
                ready.lastError ?: "keeper recovery failed",
            )
        }
        response = MinisdProtocol.promoteExecInfrastructureFailure(
            call(),
            userCommandStarted = null,
        )
        return response
    }

    /**
     * shell_execute has an additional start-proof marker emitted by bash as
     * the first script statement after helper execve. If the helper exits with
     * a reserved 4/5/6 before that marker appears, the user command provably
     * did not start and the failure can be structured. Only namespace loss is
     * retried, once. If the marker exists, even exit 4/5/6 is a user result.
     */
    private suspend fun execShellWithRecovery(
        marker: String,
        call: suspend () -> MinisdResponse,
    ): MinisdResponse {
        var attempt = 0
        var raw = call()
        var response = MinisdProtocol.promoteExecInfrastructureFailure(
            raw,
            userCommandStarted = didUserCommandStart(raw, marker),
        )
        if (!shouldRetryAfterPreExecFailure(response.error, attempt)) return response

        attempt += 1
        runCatching { client.ubuntuStop() }
        val ready = ensureReady()
        if (!ready.running) {
            return MinisdProtocol.runtimeError(
                MinisdProtocol.ERROR_RUNTIME_UNAVAILABLE,
                ready.lastError ?: "keeper recovery failed",
            )
        }
        raw = call()
        response = MinisdProtocol.promoteExecInfrastructureFailure(
            raw,
            userCommandStarted = didUserCommandStart(raw, marker),
        )
        return response
    }

    suspend fun shell(
        command: String,
        sessionId: String? = null,
        timeoutMs: Long = 600_000,
        env: Map<String, String> = emptyMap(),
        lineCallback: ((String) -> Unit)? = null,
    ): ShellResult {
        val start = System.currentTimeMillis()
        val scopedEnv = if (sessionId == null) {
            env
        } else {
            env + ("MINIS_CHAT_SESSION_ID" to sessionId)
        }
        val marker = shellStartMarker(System.nanoTime())
        val wrappedCommand = wrapShellCommand(command, marker)
        val resp = execShellWithRecovery(marker) {
            client.ubuntuExec(
                argv = listOf("/bin/bash", "-lc", wrappedCommand),
                timeoutMs = timeoutMs,
                cwd = MinisdProtocol.GUEST_WORKSPACE,
                env = scopedEnv,
                sessionId = sessionId,
            )
        }

        if (!resp.ok) {
            throw RuntimeInfrastructureException(
                resp.error ?: MinisdError(
                    MinisdProtocol.ERROR_RUNTIME_UNAVAILABLE,
                    "ubuntu.exec failed without structured error",
                ),
            )
        }

        val stdout = resp.result?.optString("stdout").orEmpty()
        val stderr = stripShellStartMarker(
            resp.result?.optString("stderr").orEmpty(),
            marker,
        )
        val output = when {
            stdout.isEmpty() -> stderr
            stderr.isEmpty() -> stdout
            else -> stdout + stderr
        }
        if (lineCallback != null && output.isNotEmpty()) {
            output.lineSequence().forEach { lineCallback(it) }
        }
        return ShellResult(
            output = output,
            exitCode = resp.result?.optInt("exit_code", 1) ?: 1,
            durationMs = System.currentTimeMillis() - start,
        )
    }

    fun paths(): JSONObject = JSONObject()
        .put("hostWorkspace", UbuntuPaths.hostWorkspace)
        .put("hostSessions", UbuntuPaths.hostSessions)
        .put("guestWorkspace", MinisdProtocol.GUEST_WORKSPACE)
        .put("rootfs", MinisdProtocol.DEFAULT_ROOTFS)
        .put("socket", MinisdProtocol.DEFAULT_SOCKET)
        .put("guestUid", appContext?.applicationInfo?.uid ?: MinisdProtocol.GUEST_UID)

    private fun failStructured(code: String, detail: String): Snapshot = fail("$code: $detail")

    private suspend fun stopForDeployment(): Boolean {
        val response = runCatching { client.ubuntuStop() }.getOrElse {
            Log.w(TAG, "ubuntu.stop transport failed before rootfs switch: ${it.message}")
            return false
        }
        if (!response.ok) {
            Log.w(
                TAG,
                "ubuntu.stop failed before rootfs switch code=${response.error?.code} detail=${response.error?.detail}",
            )
            apply(response)
            return false
        }
        val stopped = apply(response)
        return response.result?.optBoolean("running", true) == false && !stopped.running
    }

    private fun fail(detail: String): Snapshot {
        val next = Snapshot(lastError = detail)
        _snapshot.value = next
        redirectPaths = false
        Log.w(TAG, detail)
        return next
    }

    private fun apply(resp: MinisdResponse): Snapshot {
        val result = resp.result
        val next = if (resp.ok && result != null) {
            Snapshot(
                running = result.optBoolean("running") ||
                    result.optBoolean("provisioned") && _snapshot.value.running,
                available = result.optBoolean("available", result.optBoolean("running")),
                pid = if (result.has("pid") && !result.isNull("pid")) {
                    result.optInt("pid")
                } else {
                    _snapshot.value.pid
                },
                version = result.optString("version").ifEmpty { _snapshot.value.version },
                provisioned = result.optBoolean("provisioned") || _snapshot.value.provisioned,
                guestUid = if (result.has("uid") && !result.isNull("uid")) {
                    result.optInt("uid")
                } else {
                    _snapshot.value.guestUid
                },
                sessionsRoot = result.optString("sessions_root")
                    .ifEmpty { _snapshot.value.sessionsRoot.orEmpty() }
                    .takeIf { it.isNotEmpty() },
                layoutKnown = result.optBoolean("layout_known", false),
                hostWorkspace = result.optNullableString("workspace"),
                hostMemory = result.optNullableString("memory"),
                hostSkills = result.optNullableString("skills"),
                hostShared = result.optNullableString("shared"),
                lastError = result.optString("last_error").ifEmpty { null },
                mock = result.optBoolean("mock"),
            )
        } else {
            Snapshot(
                running = false,
                available = false,
                lastError = resp.error?.let { "${it.code}: ${it.detail}" } ?: "ubuntu rpc failed",
            )
        }
        _snapshot.value = next
        return next
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null
}
