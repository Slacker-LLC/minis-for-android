package io.github.slackerllc.minis.runtime.ubuntu

import android.content.Context
import android.util.Log
import io.github.slackerllc.minis.sandbox.RootfsManager
import io.github.slackerllc.minis.runtime.minisd.MinisdBootstrap
import io.github.slackerllc.minis.runtime.minisd.MinisdClient
import io.github.slackerllc.minis.runtime.minisd.MinisdError
import io.github.slackerllc.minis.runtime.minisd.MinisdProtocol
import io.github.slackerllc.minis.runtime.minisd.MinisdResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * App-side Ubuntu Runtime. [init] is lazy (no su). [ensureReady] starts the
 * minisd broker independently, validates/repairs rootfs, then creates a keeper.
 */
object UbuntuRuntime {
    private const val TAG = "UbuntuRuntime"
    private const val ROOT_AUTH_TIMEOUT_MS = 15_000L

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

    @Volatile
    var isInitialized: Boolean = false
        private set

    @Volatile
    var redirectPaths: Boolean = false
        private set

    @Volatile
    private var appContext: Context? = null

    @Volatile
    var client: MinisdClient = MinisdClient()
        private set

    private val startLock = Mutex()
    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        UbuntuPaths.init(ctx)
        val appUid = ctx.applicationInfo.uid
        val appSocket = MinisdBootstrap.appSocketName(appUid)
        val brokerSocket = MinisdBootstrap.brokerSocketName(appUid)
        client = MinisdClient(
            minisdPath = MinisdBootstrap.nativeBinaryPath(ctx).absolutePath,
            socketPath = brokerSocket,
            appSocketPath = appSocket,
        )
        isInitialized = true
        redirectPaths = true
        Log.i(TAG, "initialized (lazy) appSocket=$appSocket uid=$appUid")
    }

    suspend fun refresh(): Snapshot = apply(client.ubuntuStatus())

    suspend fun ensureReady(): Snapshot = startLock.withLock {
        val ctx = appContext
            ?: return@withLock fail(
                "${MinisdProtocol.ERROR_RUNTIME_UNAVAILABLE}: UbuntuRuntime.init(context) has not been called",
            )
        val expectedUid = ctx.applicationInfo.uid
        var cur = refresh()

        // Broker recovery is deliberately before rootfs health. A broken or
        // missing rootfs must never prevent the privileged broker from starting.
        if (cur.guestUid != null && cur.guestUid != expectedUid) {
            Log.w(TAG, "stale minisd identity brokerUid=${cur.guestUid} appUid=$expectedUid")
            val restarted = ensureMinisdUp(forceRestart = true)
            if (!restarted.ok) {
                return@withLock failStructured(
                    MinisdProtocol.ERROR_RUNTIME_LAYOUT_MISMATCH,
                    "minisd identity restart failed: ${restarted.error ?: "unknown error"}",
                )
            }
            cur = awaitBroker(expectedUid)
        } else if (cur.guestUid == null) {
            val spawned = ensureMinisdUp(forceRestart = false)
            if (!spawned.ok) {
                return@withLock failStructured(
                    MinisdProtocol.ERROR_RUNTIME_UNAVAILABLE,
                    spawned.error ?: cur.lastError ?: "failed to start minisd",
                )
            }
            cur = awaitBroker(expectedUid)
        }

        // One forced broker restart covers stale pidfile / old installation UID.
        if (cur.guestUid != expectedUid) {
            val restarted = ensureMinisdUp(forceRestart = true)
            if (!restarted.ok) {
                return@withLock failStructured(
                    MinisdProtocol.ERROR_RUNTIME_LAYOUT_MISMATCH,
                    "minisd recovery failed: ${restarted.error ?: "unknown error"}",
                )
            }
            cur = awaitBroker(expectedUid)
        }
        if (!brokerIdentityMatches(cur, expectedUid)) {
            return@withLock failStructured(
                MinisdProtocol.ERROR_RUNTIME_LAYOUT_MISMATCH,
                "minisd app identity mismatch: expected uid=$expectedUid, broker uid=${cur.guestUid ?: "unknown"}",
            )
        }

        // Rootfs health is authoritative and metadata/layout based. If a keeper
        // is still alive on a damaged tree, stop it before an atomic replacement.
        val rootfs = RootfsManager.getInstance(ctx)
        var health = rootfs.checkHealth()
        if (!health.healthy) {
            if (cur.running) {
                cur = apply(client.ubuntuStop())
                if (cur.running) {
                    return@withLock failStructured(
                        MinisdProtocol.ERROR_ROOTFS_INVALID,
                        "cannot stop keeper before rootfs recovery",
                    )
                }
            }
            rootfs.installIfNeeded()
            health = rootfs.checkHealth()
            if (!health.healthy) {
                val code = if (health.code == RootfsHealthCode.ROOT_UNAVAILABLE) {
                    MinisdProtocol.ERROR_RUNTIME_UNAVAILABLE
                } else {
                    MinisdProtocol.ERROR_ROOTFS_INVALID
                }
                return@withLock failStructured(code, health.detail)
            }
            cur = refresh()
        }

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
        redirectPaths = true
        Log.i(
            TAG,
            "ubuntu.start ok pid=${started.pid} version=${started.version} uid=$expectedUid layoutKnown=${started.layoutKnown}",
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

    private suspend fun awaitBroker(expectedUid: Int): Snapshot {
        var cur = _snapshot.value
        repeat(10) {
            delay(300)
            cur = refresh()
            if (cur.guestUid == expectedUid) return cur
        }
        return cur
    }

    private suspend fun ensureMinisdUp(forceRestart: Boolean): BrokerStartResult =
        withContext(Dispatchers.IO) {
            val ctx = appContext
                ?: return@withContext BrokerStartResult(false, "no app context")
            if (forceRestart) {
                runCatching { client.ubuntuStop() }
            }
            val su = listOf(
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

            val appUid = ctx.applicationInfo.uid
            val appSock = MinisdBootstrap.appSocketName(appUid)
            val brokerSock = MinisdBootstrap.brokerSocketName(appUid)
            val leasePid = android.os.Process.myPid()
            val leaseStartTime = MinisdBootstrap.processStartTime(leasePid)
                ?: return@withContext BrokerStartResult(
                    false,
                    "cannot establish app process lease identity: /proc/$leasePid/stat unavailable",
                )
            val binary = MinisdBootstrap.nativeBinaryPath(ctx)
            if (!binary.isFile || !binary.canExecute()) {
                return@withContext BrokerStartResult(
                    false,
                    "APK native minisd is missing or not executable: ${binary.absolutePath}",
                )
            }
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
            val cmd = MinisdBootstrap.watchdogCommand(
                appSocket = appSock,
                policyJson = policy,
                forceRestart = forceRestart,
                binaryPath = binary.absolutePath,
                socketPath = brokerSock,
                leasePid = leasePid,
                leaseStartTime = leaseStartTime,
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
        if (sessionId != null) {
            val ctx = appContext
            if (ctx == null || UbuntuPaths.ensureSessionDirs(ctx.filesDir, sessionId) == null) {
                throw RuntimeInfrastructureException(
                    MinisdError(
                        MinisdProtocol.ERROR_RUNTIME_LAYOUT_MISMATCH,
                        "invalid or unavailable session workspace: $sessionId",
                    ),
                )
            }
        }
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
        .put(
            "socket",
            appContext?.let { MinisdBootstrap.brokerSocketName(it.applicationInfo.uid) }
                ?: MinisdProtocol.DEFAULT_SOCKET,
        )
        .put("guestUid", appContext?.applicationInfo?.uid ?: MinisdProtocol.GUEST_UID)

    private fun failStructured(code: String, detail: String): Snapshot = fail("$code: $detail")

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
