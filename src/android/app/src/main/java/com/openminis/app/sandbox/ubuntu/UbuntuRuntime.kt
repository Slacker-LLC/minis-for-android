package com.openminis.app.sandbox.ubuntu

import android.content.Context
import android.util.Log
import com.openminis.app.sandbox.RootfsManager
import com.openminis.app.sandbox.minisd.MinisdBootstrap
import com.openminis.app.sandbox.minisd.MinisdClient
import com.openminis.app.sandbox.minisd.MinisdProtocol
import com.openminis.app.sandbox.minisd.MinisdResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * App-side Ubuntu runtime with bounded self-healing.
 *
 * Self-check entry points:
 * - app process start: [init] schedules a background full check;
 * - chat open: [kickSelfCheck] is called by ChatViewModelStore;
 * - shell use: [ensureReady] is the mandatory pre-exec gate.
 *
 * Recovery is intentionally performed before the user's command executes. A
 * harmless `/usr/bin/true` probe can therefore distinguish helper/namespace
 * failures from command failures without risking duplicate side effects.
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

    private data class BrokerStartResult(
        val ok: Boolean,
        val error: String? = null,
    )

    private data class BinarySyncResult(
        val ok: Boolean,
        val changed: Boolean = false,
        val error: String? = null,
    )

    private data class GuestProbe(
        val ok: Boolean,
        val detail: String,
        val rootfsSuspect: Boolean = false,
    )

    private data class SuResult(
        val exitCode: Int,
        val output: String,
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
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    @Volatile
    private var lastHealthyProbeAtMs: Long = 0L

    @Volatile
    private var lastHealthyProbePid: Int? = null

    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        UbuntuPaths.init(ctx)
        val dir = File(ctx.filesDir, "minis")
        dir.mkdirs()
        client = MinisdClient(appSocketPath = File(dir, "minisd.sock").absolutePath)
        isInitialized = true
        redirectPaths = true
        Log.i(TAG, "initialized appSocket=${dir}/minisd.sock uid=${ctx.applicationInfo.uid}")

        // Never block Application.onCreate on root auth, archive verification,
        // extraction or broker startup. The full check runs immediately in the
        // background and later shell calls wait on the same startLock if needed.
        kickSelfCheck("app_start")
    }

    /** Schedule a full, cache-bypassing health check without blocking the caller. */
    fun kickSelfCheck(reason: String) {
        if (!isInitialized) return
        runtimeScope.launch {
            val result = runCatching { ensureReady(forceCheck = true) }
            result.onSuccess { snap ->
                if (snap.running) {
                    Log.i(TAG, "self-check[$reason] healthy pid=${snap.pid} version=${snap.version}")
                } else {
                    Log.w(TAG, "self-check[$reason] unavailable: ${snap.lastError}")
                }
            }.onFailure { t ->
                Log.e(TAG, "self-check[$reason] crashed: ${t.message}", t)
            }
        }
    }

    suspend fun refresh(): Snapshot {
        val resp = client.ubuntuStatus()
        return apply(resp)
    }

    suspend fun ensureReady(forceCheck: Boolean = false): Snapshot = startLock.withLock {
        val ctx = appContext
            ?: return@withLock fail("UbuntuRuntime.init(context) has not been called")
        val expectedUid = ctx.applicationInfo.uid
        var cur = refresh()

        // A full self-check also verifies the on-disk privileged broker against
        // the APK's pinned checksum. If an app update carries a new minisd (or
        // the old file is missing/corrupt), replace it and restart the watchdog
        // even when the old in-memory broker still answers status calls.
        if (forceCheck && cur.guestUid == expectedUid) {
            val synced = syncBundledMinisd(force = false)
            if (!synced.ok) {
                return@withLock fail(synced.error ?: "minisd integrity check failed")
            }
            if (synced.changed) {
                Log.w(TAG, "minisd binary changed during self-check; restarting broker")
                val restarted = ensureMinisdUp(forceRestart = true)
                if (!restarted.ok) {
                    return@withLock fail(
                        "minisd update restart failed: ${restarted.error ?: "unknown error"}",
                    )
                }
                cur = awaitBroker(expectedUid)
            }
        }

        // Broker recovery comes first. minisd must be usable even when rootfs is
        // absent, because it is part of the trusted recovery path.
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

        // One forced recovery covers a wedged/corrupt/legacy broker. Forced
        // recovery also reinstalls the bundled minisd binary before restart.
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
                    "broker uid=${cur.guestUid ?: "unknown"}",
            )
        }

        // A keeper recovered from a stale pidfile cannot prove which host paths
        // were bound into /workspace, /memory, /skills and /shared. Likewise a
        // keeper started by an older app may point at /data/adb/minis/* instead
        // of this install's app-private filesDir. Never guess: stop it and build
        // a fresh keeper with the current authoritative paths.
        if (cur.running && !runtimeLayoutMatches(cur)) {
            Log.w(
                TAG,
                "keeper layout stale/unknown; rebuilding " +
                    "reported=${cur.hostWorkspace},${cur.hostMemory},${cur.hostSkills},${cur.hostShared}",
            )
            val stopped = apply(client.ubuntuStop())
            if (stopped.running) {
                return@withLock fail("failed to stop keeper with stale runtime layout")
            }
            clearProbeCache()
            cur = stopped
        }

        val rootfs = RootfsManager.getInstance(ctx)
        var rootfsRepaired = false
        val health = rootfs.checkHealth(force = forceCheck)
        if (!health.healthy) {
            Log.w(TAG, "rootfs unhealthy: ${health.detail}; attempting automatic repair")
            if (!rootfs.repairIfNeeded(client)) {
                return@withLock fail("rootfs automatic repair failed: ${rootfs.checkHealth(true).detail}")
            }
            rootfsRepaired = true
            clearProbeCache()
            cur = refresh()
        }

        // Status alone is not enough: a live keeper PID can have an unusable
        // mount namespace. Probe the guest with a no-side-effect command.
        var keeperRebuilt = false
        if (cur.running) {
            val probe = probeGuest(cur, forceCheck)
            if (probe.ok) {
                redirectPaths = true
                return@withLock cur
            }
            Log.w(TAG, "guest probe failed on existing keeper: ${probe.detail}")
            if (probe.rootfsSuspect && !rootfsRepaired) {
                if (!rootfs.repairIfNeeded(client, force = true)) {
                    return@withLock fail("guest probe indicated rootfs failure: ${probe.detail}")
                }
                rootfsRepaired = true
            } else {
                runCatching { client.ubuntuStop() }
            }
            clearProbeCache()
            keeperRebuilt = true
        }

        var started = startKeeper(expectedUid)
        if (!started.running) {
            // A startup failure can expose deeper rootfs damage than the static
            // file check did. Revalidate once and repair once; never loop.
            val deepHealth = rootfs.checkHealth(force = true)
            if (!deepHealth.healthy && !rootfsRepaired) {
                Log.w(TAG, "ubuntu.start exposed rootfs failure: ${deepHealth.detail}")
                if (rootfs.repairIfNeeded(client, force = true)) {
                    rootfsRepaired = true
                    clearProbeCache()
                    started = startKeeper(expectedUid)
                }
            }
        }
        if (!started.running) {
            return@withLock fail(started.lastError ?: "ubuntu.start failed after recovery")
        }
        if (!runtimeLayoutMatches(started)) {
            runCatching { client.ubuntuStop() }
            return@withLock fail("fresh keeper did not confirm the requested runtime bind layout")
        }

        var probe = probeGuest(started, force = true)
        if (!probe.ok && probe.rootfsSuspect && !rootfsRepaired) {
            Log.w(TAG, "fresh keeper probe indicates rootfs damage: ${probe.detail}")
            if (rootfs.repairIfNeeded(client, force = true)) {
                rootfsRepaired = true
                clearProbeCache()
                started = startKeeper(expectedUid)
                if (started.running) probe = probeGuest(started, force = true)
            }
        }

        // If a stale mount namespace was the problem, one fresh keeper is the
        // bounded automatic repair. If the first start above was not already a
        // rebuild, permit exactly one restart now. No user command has run yet.
        if (!probe.ok && !keeperRebuilt && !probe.rootfsSuspect) {
            Log.w(TAG, "fresh guest probe failed: ${probe.detail}; rebuilding keeper once")
            runCatching { client.ubuntuStop() }
            clearProbeCache()
            keeperRebuilt = true
            started = startKeeper(expectedUid)
            if (started.running) probe = probeGuest(started, force = true)
        }

        if (!started.running || !runtimeLayoutMatches(started) || !probe.ok) {
            return@withLock fail(
                when {
                    !started.running -> started.lastError ?: "keeper rebuild failed"
                    !runtimeLayoutMatches(started) -> "keeper runtime bind layout mismatch after recovery"
                    else -> "runtime probe failed after bounded recovery: ${probe.detail}"
                },
            )
        }

        redirectPaths = true
        started
    }

    private fun runtimeLayoutMatches(snapshot: Snapshot): Boolean =
        snapshot.layoutKnown &&
            snapshot.hostWorkspace == UbuntuPaths.hostWorkspace &&
            snapshot.hostMemory == UbuntuPaths.hostMemory &&
            snapshot.hostSkills == UbuntuPaths.hostSkills &&
            snapshot.hostShared == UbuntuPaths.hostShared

    private suspend fun startKeeper(expectedUid: Int): Snapshot {
        val raw = apply(
            client.ubuntuStart(
                workspace = UbuntuPaths.hostWorkspace,
                memory = UbuntuPaths.hostMemory,
                skills = UbuntuPaths.hostSkills,
                shared = UbuntuPaths.hostShared,
            ),
        )
        val started = if (raw.running) refresh() else raw
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
        return started
    }

    private suspend fun probeGuest(snapshot: Snapshot, force: Boolean): GuestProbe {
        val now = System.currentTimeMillis()
        if (!force && snapshot.pid != null && snapshot.pid == lastHealthyProbePid &&
            now - lastHealthyProbeAtMs < PROBE_CACHE_MS
        ) {
            return GuestProbe(true, "cached")
        }

        val resp = client.ubuntuExec(
            argv = listOf("/usr/bin/true"),
            timeoutMs = PROBE_TIMEOUT_MS,
            cwd = "/",
        )
        if (!resp.ok) {
            val code = resp.error?.code ?: "RUNTIME_UNAVAILABLE"
            val detail = resp.error?.detail ?: "probe RPC failed"
            val rootfsSuspect = code == "CHROOT_UNAVAILABLE" ||
                code == "GUEST_EXECVE_FAILED" ||
                code == "ROOTFS_INVALID" ||
                detail.contains("rootfs", ignoreCase = true)
            return GuestProbe(false, "$code: $detail", rootfsSuspect = rootfsSuspect)
        }

        val exit = resp.result?.optInt("exit_code", 255) ?: 255
        val stderr = resp.result?.optString("stderr").orEmpty().trim()
        if (exit == 0) {
            lastHealthyProbeAtMs = now
            lastHealthyProbePid = snapshot.pid
            return GuestProbe(true, "ok")
        }

        // Compatibility fallback for a legacy broker that still flattened
        // helper failures into exit codes. New minisd returns structured errors
        // before this branch is reached.
        val stage = when (exit) {
            4 -> "KEEPER_NAMESPACE_LOST"
            5 -> "CHROOT_UNAVAILABLE"
            6 -> "GUEST_PRIVILEGE_SETUP_FAILED"
            7 -> "GUEST_EXECVE_FAILED"
            else -> "GUEST_PROBE_FAILED"
        }
        val rootfsSuspect = exit == 5 || exit == 7 ||
            stderr.contains("No such file", ignoreCase = true) ||
            stderr.contains("rootfs", ignoreCase = true)
        return GuestProbe(
            false,
            "$stage(exit=$exit)${if (stderr.isNotEmpty()) ": $stderr" else ""}",
            rootfsSuspect = rootfsSuspect,
        )
    }

    private fun clearProbeCache() {
        lastHealthyProbeAtMs = 0L
        lastHealthyProbePid = null
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

    private suspend fun syncBundledMinisd(force: Boolean): BinarySyncResult =
        withContext(Dispatchers.IO) {
            val ctx = appContext
                ?: return@withContext BinarySyncResult(false, error = "no app context")
            val su = resolveSu()
                ?: return@withContext BinarySyncResult(false, error = "no executable su binary")
            ensureBundledMinisdInstalled(ctx, su, force)
        }

    /**
     * Ensure the APK-bundled minisd exists before starting the watchdog. On a
     * forced recovery the binary is replaced even if its marker claims it is
     * current, which heals silent on-disk corruption as well as missing files.
     */
    private suspend fun ensureMinisdUp(forceRestart: Boolean): BrokerStartResult =
        withContext(Dispatchers.IO) {
            val ctx = appContext
                ?: return@withContext BrokerStartResult(false, "no app context")
            val su = resolveSu()
                ?: return@withContext BrokerStartResult(false, "no executable su binary")

            val binary = ensureBundledMinisdInstalled(ctx, su, force = forceRestart)
            if (!binary.ok) {
                return@withContext BrokerStartResult(false, binary.error ?: "cannot install minisd")
            }

            val appSock = File(ctx.filesDir, "minis/minisd.sock").absolutePath
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

            val result = runSuCommand(su, cmd, BROKER_BOOT_TIMEOUT_MS)
                ?: return@withContext BrokerStartResult(false, "failed to invoke su for minisd bootstrap")
            if (result.exitCode != 0) {
                val detail = result.output.ifBlank { "su exited ${result.exitCode}" }
                Log.w(TAG, "ensureMinisdUp failed: $detail")
                return@withContext BrokerStartResult(false, detail)
            }
            Log.i(
                TAG,
                "ensureMinisdUp forceRestart=$forceRestart binaryChanged=${binary.changed} " +
                    "uid=${ctx.applicationInfo.uid} ${result.output.take(200)}",
            )
            BrokerStartResult(true)
        }

    private fun ensureBundledMinisdInstalled(
        ctx: Context,
        su: String,
        force: Boolean,
    ): BinarySyncResult {
        val expected = try {
            ctx.assets.open(MINISD_SHA_ASSET).bufferedReader().use { reader ->
                reader.readLine()?.trim()?.substringBefore(' ')
            }?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
                ?: return BinarySyncResult(false, error = "invalid bundled minisd SHA-256 manifest")
        } catch (t: Throwable) {
            return BinarySyncResult(false, error = "bundled minisd checksum missing: ${t.message}")
        }

        if (!force) {
            val verifyScript = """
                BIN=${shellQuote(MinisdProtocol.DEFAULT_BIN)}
                if [ -x "${'$'}BIN" ]; then
                  if command -v sha256sum >/dev/null 2>&1; then
                    sha256sum "${'$'}BIN" | awk '{print ${'$'}1}'
                  elif command -v toybox >/dev/null 2>&1; then
                    toybox sha256sum "${'$'}BIN" | awk '{print ${'$'}1}'
                  fi
                fi
            """.trimIndent()
            val actual = runSuCommand(su, verifyScript, 5_000L)
            if (actual?.exitCode == 0 && actual.output.trim().equals(expected, ignoreCase = true)) {
                return BinarySyncResult(true, changed = false)
            }
        }

        val asset = try {
            materializeVerifiedMinisdAsset(ctx, expected)
        } catch (t: Throwable) {
            return BinarySyncResult(false, error = "cannot materialize bundled minisd: ${t.message}")
        }

        val installScript = """
            set -eu
            BIN=${shellQuote(MinisdProtocol.DEFAULT_BIN)}
            SHA=${shellQuote(MINISD_SHA_PATH)}
            mkdir -p /data/adb/minis/bin /data/adb/minis/run
            TMP="${'$'}BIN.installing"
            rm -f "${'$'}TMP"
            cat > "${'$'}TMP"
            chmod 0755 "${'$'}TMP"
            mv -f "${'$'}TMP" "${'$'}BIN"
            printf '%s\n' ${shellQuote(expected.lowercase())} > "${'$'}SHA.tmp"
            mv -f "${'$'}SHA.tmp" "${'$'}SHA"
            echo MINISD_INSTALLED
        """.trimIndent()

        val result = streamFileToSu(su, installScript, asset, MINISD_INSTALL_TIMEOUT_MS)
            ?: return BinarySyncResult(false, error = "failed to invoke su for minisd install")
        if (result.exitCode != 0) {
            return BinarySyncResult(
                false,
                error = result.output.ifBlank { "minisd install exited ${result.exitCode}" },
            )
        }
        return BinarySyncResult(true, changed = true)
    }

    private fun materializeVerifiedMinisdAsset(ctx: Context, expected: String): File {
        val dir = File(ctx.filesDir, "minis/recovery").apply { mkdirs() }
        val target = File(dir, "minisd-aarch64")
        if (target.isFile && sha256(target).equals(expected, ignoreCase = true)) return target
        val tmp = File(dir, "minisd-aarch64.tmp")
        tmp.delete()
        ctx.assets.open(MINISD_ASSET).use { input ->
            tmp.outputStream().buffered().use { output -> input.copyTo(output) }
        }
        val actual = sha256(tmp)
        check(actual.equals(expected, ignoreCase = true)) {
            "bundled minisd checksum mismatch: expected=$expected actual=$actual"
        }
        if (target.exists() && !target.delete()) error("cannot replace stale bundled minisd")
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        return target
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun streamFileToSu(
        su: String,
        command: String,
        file: File,
        timeoutMs: Long,
    ): SuResult? {
        val process = try {
            ProcessBuilder(su, "-c", command).redirectErrorStream(true).start()
        } catch (t: Throwable) {
            Log.w(TAG, "su stream spawn failed: ${t.message}")
            return null
        }
        val output = AtomicReference("")
        val readThread = Thread({
            output.set(runCatching { process.inputStream.bufferedReader().use { it.readText() } }.getOrDefault(""))
        }, "minisd-install-output").apply { isDaemon = true; start() }
        val writeError = AtomicReference<Throwable?>(null)
        val writeThread = Thread({
            try {
                file.inputStream().use { input -> process.outputStream.use { out -> input.copyTo(out) } }
            } catch (t: Throwable) {
                writeError.set(t)
                runCatching { process.outputStream.close() }
            }
        }, "minisd-install-input").apply { isDaemon = true; start() }

        val finished = try {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(1, TimeUnit.SECONDS)
        }
        writeThread.join(1_000)
        readThread.join(1_000)
        if (!finished) return SuResult(124, "minisd install timed out")
        val text = buildString {
            append(output.get())
            writeError.get()?.let {
                if (isNotEmpty()) append('\n')
                append("minisd asset stream failed: ${it.message}")
            }
        }
        return SuResult(process.exitValue(), text.trim())
    }

    private fun runSuCommand(su: String, command: String, timeoutMs: Long): SuResult? {
        val process = try {
            ProcessBuilder(su, "-c", command).redirectErrorStream(true).start()
        } catch (t: Throwable) {
            Log.d(TAG, "su command spawn failed: ${t.message}")
            return null
        }
        val output = AtomicReference("")
        val reader = Thread({
            output.set(runCatching { process.inputStream.bufferedReader().use { it.readText() } }.getOrDefault(""))
        }, "minisd-su-output").apply { isDaemon = true; start() }
        val finished = try {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(1, TimeUnit.SECONDS)
        }
        reader.join(1_000)
        return if (finished) SuResult(process.exitValue(), output.get().trim())
        else SuResult(124, "su command timed out")
    }

    private fun resolveSu(): String? = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/debug_ramdisk/su",
    ).firstOrNull { File(it).canExecute() }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    suspend fun start(): Snapshot = ensureReady(forceCheck = true)

    suspend fun stop(): Snapshot {
        val resp = client.ubuntuStop()
        redirectPaths = false
        clearProbeCache()
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
        val startedAt = System.currentTimeMillis()
        val ready = ensureReady()
        if (!ready.running) {
            val detail = ready.lastError ?: "ubuntu runtime unavailable after self-check"
            return ShellResult(detail, 1, System.currentTimeMillis() - startedAt)
        }

        suspend fun runOnce(): MinisdResponse = client.ubuntuExec(
            argv = listOf("/bin/bash", "-lc", command),
            timeoutMs = timeoutMs,
            cwd = MinisdProtocol.GUEST_WORKSPACE,
            env = env,
        )

        var resp = runOnce()
        if (isSafePreExecFailure(resp)) {
            // Structured errors from minisd guarantee execve was never reached,
            // so retrying after one bounded recovery cannot duplicate user side
            // effects. Ordinary command exits and timeouts are never retried.
            Log.w(TAG, "shell pre-exec failure ${resp.error?.code}; recovering once")
            clearProbeCache()
            val recovered = ensureReady(forceCheck = true)
            if (recovered.running) {
                resp = runOnce()
            }
        }

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
            val detail = resp.error?.let { "${it.code}: ${it.detail}" } ?: "ubuntu.exec failed"
            if (combined.isEmpty()) detail else "$combined\n$detail"
        }
        if (lineCallback != null && output.isNotEmpty()) {
            output.lineSequence().forEach { lineCallback(it) }
        }
        val exit = if (resp.ok) resp.result?.optInt("exit_code", 1) ?: 1 else 1
        return ShellResult(
            output = output,
            exitCode = exit,
            durationMs = System.currentTimeMillis() - startedAt,
        )
    }

    private fun isSafePreExecFailure(resp: MinisdResponse): Boolean =
        !resp.ok && resp.error?.code?.let { it in SAFE_PREEXEC_ERRORS } == true

    fun paths(): JSONObject = JSONObject()
        .put("hostWorkspace", UbuntuPaths.hostWorkspace)
        .put("hostMemory", UbuntuPaths.hostMemory)
        .put("hostSkills", UbuntuPaths.hostSkills)
        .put("hostShared", UbuntuPaths.hostShared)
        .put("guestWorkspace", MinisdProtocol.GUEST_WORKSPACE)
        .put("rootfs", MinisdProtocol.DEFAULT_ROOTFS)
        .put("socket", MinisdProtocol.DEFAULT_SOCKET)
        .put("guestUid", appContext?.applicationInfo?.uid ?: MinisdProtocol.GUEST_UID)

    private fun fail(detail: String): Snapshot {
        val next = Snapshot(lastError = detail)
        _snapshot.value = next
        redirectPaths = false
        clearProbeCache()
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

    private val SAFE_PREEXEC_ERRORS = setOf(
        "KEEPER_NAMESPACE_LOST",
        "CHROOT_UNAVAILABLE",
        "GUEST_PRIVILEGE_SETUP_FAILED",
        "GUEST_EXECVE_FAILED",
        "ROOTFS_INVALID",
        "RUNTIME_LAYOUT_MISMATCH",
    )

    private const val MINISD_ASSET = "runtime/minisd-aarch64"
    private const val MINISD_SHA_ASSET = "runtime/minisd-aarch64.sha256"
    private const val MINISD_SHA_PATH = "/data/adb/minis/bin/minisd.sha256"
    private const val PROBE_CACHE_MS = 15_000L
    private const val PROBE_TIMEOUT_MS = 5_000L
    private const val BROKER_BOOT_TIMEOUT_MS = 8_000L
    private const val MINISD_INSTALL_TIMEOUT_MS = 20_000L
}
