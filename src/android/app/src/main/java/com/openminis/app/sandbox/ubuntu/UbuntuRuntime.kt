package com.openminis.app.sandbox.ubuntu

import android.content.Context
import android.util.Log
import com.openminis.app.sandbox.minisd.MinisdBootstrap
import com.openminis.app.sandbox.minisd.MinisdClient
import com.openminis.app.sandbox.minisd.MinisdProtocol
import com.openminis.app.sandbox.minisd.MinisdResponse
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
 * App-side Ubuntu Runtime. [init] is lazy (no su). [ensureReady] starts
 * minisd's keeper. [shell] is what [com.openminis.app.sandbox.ExecutionCoordinator]
 * uses for `shell_execute`.
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
        val brokerMountNamespace: String? = null,
        val lastError: String? = null,
        val mock: Boolean = false,
    )

    data class ShellResult(
        val output: String,
        val exitCode: Int,
        val durationMs: Long,
    )

    private data class BrokerStartResult(
        val ok: Boolean,
        val error: String? = null,
    )

    @Volatile
    var isInitialized: Boolean = false
        private set

    /** Once Ubuntu is the live backend, file tools resolve via [UbuntuPaths]. */
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
        val dir = java.io.File(ctx.filesDir, "minis")
        dir.mkdirs()
        client = MinisdClient(appSocketPath = java.io.File(dir, "minisd.sock").absolutePath)
        isInitialized = true
        redirectPaths = true
        Log.i(TAG, "initialized (lazy) appSocket=${dir}/minisd.sock uid=${ctx.applicationInfo.uid}")
    }

    suspend fun refresh(): Snapshot {
        val resp = client.ubuntuStatus()
        return apply(resp)
    }

    suspend fun ensureReady(): Snapshot = startLock.withLock {
        val ctx = appContext
            ?: return@withLock fail("UbuntuRuntime.init(context) has not been called")
        val expectedUid = ctx.applicationInfo.uid
        val expectedMountNamespace = currentMountNamespace()
            ?: return@withLock fail("cannot identify the app mount namespace")
        var cur = refresh()

        // App Data Isolation gives the root namespace a tmpfs_data view of
        // filesDir. The broker must join this App process's mount namespace so
        // the privileged runtime and Android file tools see the same f2fs data.
        if (!brokerIdentityMatches(cur, expectedUid, expectedMountNamespace)) {
            Log.w(
                TAG,
                "stale minisd identity brokerUid=${cur.guestUid} appUid=$expectedUid " +
                    "brokerMount=${cur.brokerMountNamespace} appMount=$expectedMountNamespace",
            )
            // An old broker in the global namespace is invisible through the
            // App-private socket but still owns the shared /data/adb pid lock.
            // Always retire that exact watchdog/server pair before spawning
            // the namespace-aware broker.
            val spawned = ensureMinisdUp(forceRestart = true)
            if (!spawned.ok) {
                return@withLock fail(spawned.error ?: cur.lastError ?: "failed to start minisd")
            }
            cur = awaitBroker(expectedUid, expectedMountNamespace)
        }

        // One forced recovery covers a wedged/stale broker whose pidfile was
        // present but whose status call did not expose the expected identity or
        // mount namespace.
        if (!brokerIdentityMatches(cur, expectedUid, expectedMountNamespace)) {
            val restarted = ensureMinisdUp(forceRestart = true)
            if (!restarted.ok) {
                return@withLock fail(
                    "minisd recovery failed: ${restarted.error ?: "unknown error"}",
                )
            }
            cur = awaitBroker(expectedUid, expectedMountNamespace)
        }
        if (!brokerIdentityMatches(cur, expectedUid, expectedMountNamespace)) {
            return@withLock fail(
                "minisd app identity mismatch: expected uid=$expectedUid " +
                    "mount=$expectedMountNamespace, broker uid=${cur.guestUid ?: "unknown"} " +
                    "mount=${cur.brokerMountNamespace ?: "unknown"}; " +
                    "update/restart the privileged runtime",
            )
        }

        if (cur.running && runtimeLayoutMatches(cur)) {
            redirectPaths = true
            return@withLock cur
        }

        if (cur.running) {
            // A keeper created before session isolation has its bind mounts
            // frozen in the old namespace. Merely updating broker state would
            // leave memory/skills/shared (and the compatibility workspace)
            // attached to stale host directories, so recreate the keeper.
            val stopped = apply(client.ubuntuStop())
            if (stopped.running) {
                return@withLock fail(
                    "failed to restart the privileged runtime with session workspace isolation",
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
        if (!runtimeLayoutMatches(started)) {
            return@withLock fail(
                "fresh keeper did not confirm the requested runtime bind layout",
            )
        }
        redirectPaths = started.running && runtimeLayoutMatches(started)
        if (started.running) {
            Log.i(
                TAG,
                "ubuntu.start ok pid=${started.pid} version=${started.version} " +
                    "uid=$expectedUid layoutKnown=${started.layoutKnown}",
            )
        } else {
            Log.w(TAG, "ubuntu.start failed: ${started.lastError}")
        }
        started
    }

    private fun runtimeLayoutMatches(snapshot: Snapshot): Boolean =
        snapshot.layoutKnown &&
            snapshot.hostWorkspace == UbuntuPaths.hostWorkspace &&
            snapshot.hostMemory == UbuntuPaths.hostMemory &&
            snapshot.hostSkills == UbuntuPaths.hostSkills &&
            snapshot.hostShared == UbuntuPaths.hostShared &&
            snapshot.brokerMountNamespace == currentMountNamespace()

    internal fun brokerIdentityMatches(
        snapshot: Snapshot,
        expectedUid: Int,
        expectedMountNamespace: String,
    ): Boolean = snapshot.guestUid == expectedUid &&
        snapshot.brokerMountNamespace == expectedMountNamespace

    private suspend fun awaitBroker(expectedUid: Int, expectedMountNamespace: String): Snapshot {
        var cur = _snapshot.value
        repeat(10) {
            delay(300)
            cur = refresh()
            if (brokerIdentityMatches(cur, expectedUid, expectedMountNamespace)) return cur
        }
        return cur
    }

    /**
     * Materializes a policy for the current installation UID and starts the
     * watchdog via su. When [forceRestart] is true, a stale watchdog parent
     * and its server child are terminated through the broker pidfile first.
     */
    private suspend fun ensureMinisdUp(forceRestart: Boolean): BrokerStartResult =
        withContext(Dispatchers.IO) {
            val ctx = appContext
                ?: return@withContext BrokerStartResult(false, "no app context")
            if (forceRestart) {
                // A broker restart alone does not stop a keeper started by an
                // older minisd. Stop the live runtime first so the next
                // ubuntu.start builds a fresh keeper with current mounts.
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
            val cmd = MinisdBootstrap.watchdogCommand(
                appSocket = appSock,
                policyJson = policy,
                forceRestart = forceRestart,
                appMountNamespacePid = android.os.Process.myPid(),
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
                    "ensureMinisdUp forceRestart=$forceRestart uid=${ctx.applicationInfo.uid} " +
                        output.take(200),
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
    ): MinisdResponse {
        return client.ubuntuExec(argv, timeoutMs, sessionId = sessionId)
    }

    suspend fun adminExec(argv: List<String>, timeoutMs: Long = 120_000, confirmId: String? = null): MinisdResponse {
        return client.ubuntuAdminExec(argv, timeoutMs, confirmId)
    }

    suspend fun provision(timeoutMs: Long = 600_000): MinisdResponse {
        return client.ubuntuProvision(timeoutMs)
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
                return ShellResult(
                    output = "invalid or unavailable session workspace: $sessionId",
                    exitCode = 1,
                    durationMs = System.currentTimeMillis() - start,
                )
            }
        }
        val scopedEnv = if (sessionId == null) {
            env
        } else {
            env + ("MINIS_CHAT_SESSION_ID" to sessionId)
        }
        val resp = client.ubuntuExec(
            argv = listOf("/bin/bash", "-lc", command),
            timeoutMs = timeoutMs,
            cwd = MinisdProtocol.GUEST_WORKSPACE,
            env = scopedEnv,
            sessionId = sessionId,
        )
        val stdout = resp.result?.optString("stdout").orEmpty()
        val stderr = resp.result?.optString("stderr").orEmpty()
        val combined = when {
            stdout.isEmpty() -> stderr
            stderr.isEmpty() -> stdout
            else -> stdout + stderr
        }
        val output = if (resp.ok) {
            combined
        } else {
            val detail = resp.error?.detail ?: "ubuntu.exec failed"
            if (combined.isEmpty()) detail else "$combined\n$detail"
        }
        if (lineCallback != null && output.isNotEmpty()) {
            output.lineSequence().forEach { lineCallback(it) }
        }
        val exit = if (resp.ok) resp.result?.optInt("exit_code", 1) ?: 1 else 1
        return ShellResult(
            output = output,
            exitCode = exit,
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
        .put("appMountNamespace", currentMountNamespace())
        .put("brokerMountNamespace", _snapshot.value.brokerMountNamespace)

    internal fun currentMountNamespace(): String? = runCatching {
        java.nio.file.Files.readSymbolicLink(
            java.nio.file.Paths.get("/proc/self/ns/mnt"),
        ).toString()
    }.getOrNull()

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
                brokerMountNamespace = result.optNullableString("broker_mount_namespace"),
                lastError = result.optString("last_error").ifEmpty { null },
                mock = result.optBoolean("mock"),
            )
        } else {
            Snapshot(
                running = false,
                available = false,
                lastError = resp.error?.detail ?: "ubuntu rpc failed",
            )
        }
        _snapshot.value = next
        return next
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null
}
