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

    data class Snapshot(
        val running: Boolean = false,
        val available: Boolean = false,
        val pid: Int? = null,
        val version: String? = null,
        val provisioned: Boolean = false,
        val guestUid: Int? = null,
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
        var cur = refresh()

        // A previous installation may have left a watchdog whose policy still
        // names a different Android UID. Minisd status exposes the guest UID;
        // root fallback in MinisdClient lets us inspect and repair that stale
        // broker even when its app-private socket rejects the current process.
        if (cur.guestUid != null && cur.guestUid != expectedUid) {
            Log.w(TAG, "stale minisd identity brokerUid=${cur.guestUid} appUid=$expectedUid")
            val restarted = ensureMinisdUp(forceRestart = true)
            if (!restarted.ok) {
                return@withLock fail(
                    "minisd identity restart failed: ${restarted.error ?: "unknown error"}",
                )
            }
            cur = awaitBroker(expectedUid)
        } else if (cur.guestUid == null) {
            val spawned = ensureMinisdUp(forceRestart = false)
            if (!spawned.ok) {
                return@withLock fail(spawned.error ?: cur.lastError ?: "failed to start minisd")
            }
            cur = awaitBroker(expectedUid)
        }

        // One forced recovery covers a wedged/stale broker whose pidfile was
        // present but whose status call did not expose the expected identity.
        if (cur.guestUid != expectedUid) {
            val restarted = ensureMinisdUp(forceRestart = true)
            if (!restarted.ok) {
                return@withLock fail(
                    "minisd recovery failed: ${restarted.error ?: "unknown error"}",
                )
            }
            cur = awaitBroker(expectedUid)
        }
        if (cur.guestUid != expectedUid) {
            return@withLock fail(
                "minisd app identity mismatch: expected uid=$expectedUid, " +
                    "broker uid=${cur.guestUid ?: "unknown"}; update/restart the privileged runtime",
            )
        }

        if (cur.running) {
            redirectPaths = true
            return@withLock cur
        }

        val started = apply(
            client.ubuntuStart(
                workspace = UbuntuPaths.hostWorkspace,
                memory = UbuntuPaths.hostMemory,
                skills = UbuntuPaths.hostSkills,
                shared = UbuntuPaths.hostShared,
            ),
        )
        redirectPaths = started.running
        if (started.running) {
            Log.i(
                TAG,
                "ubuntu.start ok pid=${started.pid} version=${started.version} uid=$expectedUid",
            )
        } else {
            Log.w(TAG, "ubuntu.start failed: ${started.lastError}")
        }
        started
    }

    private suspend fun awaitBroker(expectedUid: Int): Snapshot {
        var cur = _snapshot.value
        repeat(10) {
            delay(300)
            cur = refresh()
            if (cur.guestUid == expectedUid) return cur
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
            val su = listOf(
                "/system/bin/su",
                "/system/xbin/su",
                "/sbin/su",
                "/debug_ramdisk/su",
            ).firstOrNull { java.io.File(it).canExecute() }
                ?: return@withContext BrokerStartResult(false, "no executable su binary")

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

    suspend fun exec(argv: List<String>, timeoutMs: Long = 30_000): MinisdResponse {
        return client.ubuntuExec(argv, timeoutMs)
    }

    suspend fun adminExec(argv: List<String>, timeoutMs: Long = 120_000, confirmId: String? = null): MinisdResponse {
        return client.ubuntuAdminExec(argv, timeoutMs, confirmId)
    }

    suspend fun provision(timeoutMs: Long = 600_000): MinisdResponse {
        return client.ubuntuProvision(timeoutMs)
    }

    suspend fun shell(
        command: String,
        timeoutMs: Long = 600_000,
        env: Map<String, String> = emptyMap(),
        lineCallback: ((String) -> Unit)? = null,
    ): ShellResult {
        val start = System.currentTimeMillis()
        val resp = client.ubuntuExec(
            argv = listOf("/bin/bash", "-lc", command),
            timeoutMs = timeoutMs,
            cwd = MinisdProtocol.GUEST_WORKSPACE,
            env = env,
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
        .put("hostWorkspace", MinisdProtocol.HOST_WORKSPACE)
        .put("guestWorkspace", MinisdProtocol.GUEST_WORKSPACE)
        .put("rootfs", MinisdProtocol.DEFAULT_ROOTFS)
        .put("socket", MinisdProtocol.DEFAULT_SOCKET)
        .put("guestUid", appContext?.applicationInfo?.uid ?: MinisdProtocol.GUEST_UID)

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
}
