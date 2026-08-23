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
        // minisd down (e.g. after reboot) → spawn the watchdog via root, then
        // retry. B13's pidfile flock makes a duplicate spawn a harmless no-op.
        val spawned = ensureMinisdUp()
        if (spawned) {
            for (i in 0 until 10) {
                delay(300)
                cur = refresh()
                if (cur.running) break
            }
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
     * Spawns minisd --watchdog via su when it is not running. Safe to call
     * blindly: B13 pidfile flock makes a second instance exit immediately.
     * The subshell `( … & )` detaches minisd from the su session so it
     * survives su exit (adopted by init).
     */
    private suspend fun ensureMinisdUp(): Boolean = withContext(Dispatchers.IO) {
        val su = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su")
            .firstOrNull { java.io.File(it).canExecute() }
        if (su == null) {
            Log.w(TAG, "ensureMinisdUp: no su binary")
            return@withContext false
        }
        val appSock = appContext
            ?.let { java.io.File(it.filesDir, "minis/minisd.sock").absolutePath }
        if (appSock == null) {
            Log.w(TAG, "ensureMinisdUp: no app context")
            return@withContext false
        }
        val cmd = "(/data/adb/minis/bin/minisd --watchdog --policy " +
            "/data/adb/minis/policy/policy.json --app-socket $appSock >/dev/null 2>&1 &)"
        try {
            val proc = ProcessBuilder(su, "-c", cmd).redirectErrorStream(true).start()
            val finished = proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            if (finished) {
                Log.i(TAG, "ensureMinisdUp: su exited=${proc.exitValue()}")
            } else {
                Log.i(TAG, "ensureMinisdUp: su still running (spawned)")
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "ensureMinisdUp failed: ${t.message}")
            false
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
