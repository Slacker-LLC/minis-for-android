package com.openminis.app.sandbox.ubuntu

import android.content.Context
import android.util.Log
import com.openminis.app.sandbox.minisd.MinisdClient
import com.openminis.app.sandbox.minisd.MinisdProtocol
import com.openminis.app.sandbox.minisd.MinisdResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * App-side Ubuntu Runtime. [init] is lazy (no su). [ensureReady] starts
 * minisd's keeper. [shell] is what [com.openminis.app.sandbox.ExecutionCoordinator]
 * uses for `shell_execute`.
 */
object UbuntuRuntime {
    private const val TAG = "UbuntuRuntime"
    private const val ROOT_AUTH_TIMEOUT_MS = 15_000L
    private const val MINISD_SPAWN_TIMEOUT_MS = 5_000L

    data class Snapshot(
        val running: Boolean = false,
        val available: Boolean = false,
        val pid: Int? = null,
        val version: String? = null,
        val provisioned: Boolean = false,
        val lastError: String? = null,
        val mock: Boolean = false,
    )

    data class ShellResult(
        val output: String,
        val exitCode: Int,
        val durationMs: Long,
    )

    private data class MinisdSpawnResult(
        val started: Boolean,
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
        Log.i(TAG, "initialized (lazy) appSocket=${dir}/minisd.sock")
    }

    suspend fun refresh(): Snapshot {
        val resp = client.ubuntuStatus()
        return apply(resp)
    }

    suspend fun ensureReady(): Snapshot = startLock.withLock {
        var cur = refresh()
        if (cur.running) {
            redirectPaths = true
            return cur
        }
        // minisd down (e.g. after reboot) → actively establish root authorization,
        // then spawn the watchdog. B13's pidfile flock makes a duplicate spawn harmless.
        val spawn = ensureMinisdUp()
        if (!spawn.started) {
            val failed = Snapshot(
                running = false,
                available = false,
                lastError = spawn.error ?: "minisd watchdog could not be started",
            )
            _snapshot.value = failed
            redirectPaths = false
            Log.w(TAG, "ensureReady failed: ${failed.lastError}")
            return failed
        }
        for (i in 0 until 10) {
            delay(300)
            cur = refresh()
            if (cur.running) break
        }
        if (cur.running) {
            redirectPaths = true
            return cur
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
            Log.i(TAG, "ubuntu.start ok pid=${started.pid} version=${started.version}")
        } else {
            Log.w(TAG, "ubuntu.start failed: ${started.lastError}")
        }
        started
    }

    /**
     * Establishes a real uid-0 `su` session first, so KernelSU can grant or
     * reject the current install explicitly. The app UID is then injected as a
     * trusted root-process environment override; the watchdog and every child
     * inherit it, so policy reload/reparse cannot fall back to a device UID.
     */
    private suspend fun ensureMinisdUp(): MinisdSpawnResult = withContext(Dispatchers.IO) {
        val su = resolveSu()
            ?: return@withContext MinisdSpawnResult(
                false,
                "Root unavailable: no executable su found (KernelSU/Magisk not installed or not exposed to this app)",
            )
        val appSock = appContext
            ?.let { java.io.File(it.filesDir, "minis/minisd.sock").absolutePath }
            ?: return@withContext MinisdSpawnResult(false, "minisd startup failed: app context unavailable")

        val rootProbe = try {
            ProcessBuilder(su, "-c", "id -u").redirectErrorStream(true).start()
        } catch (t: Throwable) {
            return@withContext MinisdSpawnResult(false, "Root unavailable: failed to start su: ${t.message}")
        }
        val authorized = rootProbe.waitFor(ROOT_AUTH_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!authorized) {
            rootProbe.destroyForcibly()
            rootProbe.waitFor(1_000, java.util.concurrent.TimeUnit.MILLISECONDS)
            return@withContext MinisdSpawnResult(
                false,
                "Root authorization timed out after ${ROOT_AUTH_TIMEOUT_MS}ms; approve this app in KernelSU and retry",
            )
        }
        val rootOutput = runCatching { rootProbe.inputStream.bufferedReader().use { it.readText() } }
            .getOrDefault("").trim()
        if (rootProbe.exitValue() != 0) {
            val detail = rootOutput.take(300).ifBlank { "su exited without diagnostic output" }
            return@withContext MinisdSpawnResult(
                false,
                "Root authorization denied or unavailable (exit=${rootProbe.exitValue()}): $detail",
            )
        }
        val effectiveUid = rootOutput.lineSequence().map(String::trim).lastOrNull { it.isNotEmpty() }
        if (effectiveUid != "0") {
            return@withContext MinisdSpawnResult(
                false,
                "Root authorization invalid: su returned uid=${effectiveUid ?: "unknown"}, expected 0",
            )
        }

        val appUid = android.os.Process.myUid()
        val cmd = "(MINIS_APP_UID=$appUid /data/adb/minis/bin/minisd --watchdog --policy " +
            "/data/adb/minis/policy/policy.json --app-socket ${shellQuote(appSock)} >/dev/null 2>&1 &)"
        try {
            val proc = ProcessBuilder(su, "-c", cmd).redirectErrorStream(true).start()
            val finished = proc.waitFor(MINISD_SPAWN_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                proc.waitFor(1_000, java.util.concurrent.TimeUnit.MILLISECONDS)
                return@withContext MinisdSpawnResult(
                    false,
                    "Root command timed out while starting minisd watchdog",
                )
            }
            val output = runCatching { proc.inputStream.bufferedReader().use { it.readText() } }
                .getOrDefault("").trim()
            if (proc.exitValue() != 0) {
                val detail = output.take(300).ifBlank { "su exited without diagnostic output" }
                return@withContext MinisdSpawnResult(
                    false,
                    "minisd watchdog start failed (exit=${proc.exitValue()}): $detail",
                )
            }
            Log.i(TAG, "ensureMinisdUp: watchdog spawned appUid=$appUid")
            MinisdSpawnResult(true)
        } catch (t: Throwable) {
            Log.w(TAG, "ensureMinisdUp failed: ${t.message}")
            MinisdSpawnResult(false, "minisd watchdog start failed: ${t.message}")
        }
    }

    private fun resolveSu(): String? {
        val candidates = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/data/adb/ksu/bin/su",
            "/debug_ramdisk/su",
        )
        candidates.firstOrNull { java.io.File(it).canExecute() }?.let { return it }
        return System.getenv("PATH").orEmpty()
            .split(java.io.File.pathSeparatorChar)
            .asSequence()
            .map { java.io.File(it, "su") }
            .firstOrNull { it.canExecute() }
            ?.absolutePath
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

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
        .put("guestUid", MinisdProtocol.GUEST_UID)

    private fun apply(resp: MinisdResponse): Snapshot {
        val result = resp.result
        val next = if (resp.ok && result != null) {
            Snapshot(
                running = result.optBoolean("running") || result.optBoolean("provisioned") && _snapshot.value.running,
                available = result.optBoolean("available", result.optBoolean("running")),
                pid = if (result.has("pid") && !result.isNull("pid")) result.optInt("pid") else _snapshot.value.pid,
                version = result.optString("version").ifEmpty { _snapshot.value.version },
                provisioned = result.optBoolean("provisioned") || _snapshot.value.provisioned,
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
