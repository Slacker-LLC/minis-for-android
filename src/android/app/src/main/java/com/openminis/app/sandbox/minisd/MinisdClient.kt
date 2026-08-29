package com.openminis.app.sandbox.minisd

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * App-side client for the already-installed minisd.
 *
 * The current fallback hop uses `su -c 'minisd --call'` when the app-private
 * socket is unavailable. Direct app-socket transport is preferred when present.
 *
 * Never starts `su` unless a caller explicitly invokes [call].
 */
class MinisdClient(
    private val minisdPath: String = MinisdProtocol.DEFAULT_BIN,
    private val socketPath: String = MinisdProtocol.DEFAULT_SOCKET,
    private val appSocketPath: String? = null,
    private val suPath: String = "/system/bin/su",
) {
    private val nextId = AtomicLong(1)

    suspend fun ping(): MinisdResponse = call(MinisdProtocol.ping(nextId()))

    suspend fun ubuntuStatus(): MinisdResponse = call(MinisdProtocol.ubuntuStatus(nextId()))

    /** Persistent source paths are fixed by minisd and are not client inputs. */
    suspend fun ubuntuStart(): MinisdResponse = call(
        MinisdProtocol.ubuntuStart(id = nextId()),
        timeoutMs = 20_000,
    )

    suspend fun ubuntuStop(): MinisdResponse = call(MinisdProtocol.ubuntuStop(nextId()))

    suspend fun ubuntuExec(
        argv: List<String>,
        timeoutMs: Long = 30_000,
        cwd: String = MinisdProtocol.GUEST_WORKSPACE,
        env: Map<String, String> = emptyMap(),
        sessionId: String? = null,
    ): MinisdResponse = call(
        MinisdProtocol.ubuntuExec(
            argv = argv,
            timeoutMs = timeoutMs,
            cwd = cwd,
            env = env,
            id = nextId(),
            sessionId = sessionId,
        ),
        timeoutMs + 5_000,
    )

    suspend fun ubuntuProvision(timeoutMs: Long = 600_000): MinisdResponse =
        call(MinisdProtocol.ubuntuProvision(nextId()), timeoutMs + 5_000)

    suspend fun ubuntuAdminExec(
        argv: List<String>,
        timeoutMs: Long = 120_000,
        confirmId: String? = null,
    ): MinisdResponse = call(
        MinisdProtocol.ubuntuAdminExec(argv, timeoutMs, confirmId, nextId()),
        timeoutMs + 5_000,
    )

    /** Structured Android-root execution; minisd validates its tool allowlist. */
    suspend fun rootExec(
        tool: String,
        args: List<String> = emptyList(),
        timeoutMs: Long = 30_000,
    ): MinisdResponse = call(
        MinisdProtocol.rootExec(tool, args, timeoutMs, nextId()),
        timeoutMs + 5_000,
    )

    suspend fun call(request: MinisdRequest, timeoutMs: Long = 30_000): MinisdResponse =
        withContext(Dispatchers.IO) {
            val payload = MinisdProtocol.encodeRequest(request)
            appSocketPath?.let { path ->
                if (File(path).exists()) {
                    callLocal(path, payload, timeoutMs)?.let { local ->
                        if (local.code != "NOT_AUTHORIZED") {
                            return@withContext local
                        }
                        // A stale watchdog may still be enforcing a policy for
                        // an old installation UID. Root fallback is required to
                        // inspect that broker and repair the policy/watchdog.
                        Log.w(TAG, "local minisd rejected app identity; retrying through su")
                    }
                }
            }
            val su = resolveSu()
                ?: return@withContext unavailable("no executable su; minisd --call needs root")
            val cmd = "$minisdPath --call --socket $socketPath"
            val pb = ProcessBuilder(su, "-c", cmd)
            pb.redirectErrorStream(false)
            val proc = try {
                pb.start()
            } catch (t: Throwable) {
                return@withContext unavailable("failed to start su: ${t.message}")
            }

            val stdoutRef = AtomicReference("")
            val stderrRef = AtomicReference("")
            val stdoutThread = Thread({
                runCatching { proc.inputStream.bufferedReader().use { it.readText() } }
                    .onSuccess(stdoutRef::set)
                    .onFailure { Log.d(TAG, "minisd helper stdout: ${it.message}") }
            }, "minisd-stdout")
            val stderrThread = Thread({
                runCatching { proc.errorStream.bufferedReader().use { it.readText() } }
                    .onSuccess(stderrRef::set)
                    .onFailure { Log.d(TAG, "minisd helper stderr: ${it.message}") }
            }, "minisd-stderr")
            stdoutThread.isDaemon = true
            stderrThread.isDaemon = true
            stdoutThread.start()
            stderrThread.start()

            try {
                proc.outputStream.bufferedWriter().use {
                    it.write(payload)
                    it.flush()
                }
                val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                if (!finished) {
                    proc.destroyForcibly()
                    proc.waitFor(1_000, TimeUnit.MILLISECONDS)
                    stdoutThread.join(1_000)
                    stderrThread.join(1_000)
                    return@withContext unavailable("minisd --call timed out after ${timeoutMs}ms")
                }
                stdoutThread.join(1_000)
                stderrThread.join(1_000)
                val stdout = stdoutRef.get()
                val stderr = stderrRef.get()
                if (stdout.isBlank()) {
                    return@withContext unavailable(
                        "empty minisd response (exit=${proc.exitValue()} stderr=${stderr.take(300)})",
                    )
                }
                MinisdProtocol.decodeResponse(stdout)
            } catch (t: Throwable) {
                Log.w(TAG, "minisd call ${request.method} failed: ${t.message}")
                unavailable(t.message ?: "minisd call failed")
            } finally {
                proc.destroy()
            }
        }

    private fun callLocal(path: String, payload: String, timeoutMs: Long): MinisdResponse? {
        val sock = LocalSocket()
        return try {
            val bytes = payload.toByteArray(Charsets.UTF_8)
            if (bytes.isEmpty() || bytes.size > MinisdProtocol.MAX_REQUEST_BYTES) {
                return null
            }
            sock.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
            sock.soTimeout = timeoutMs.toInt().coerceAtMost(Int.MAX_VALUE)

            val output = DataOutputStream(sock.outputStream)
            output.writeInt(bytes.size)
            output.write(bytes)
            output.flush()

            val input = DataInputStream(sock.inputStream)
            val responseSize = input.readInt()
            if (responseSize !in 1..MinisdProtocol.MAX_RESPONSE_BYTES) {
                Log.w(TAG, "invalid minisd frame size: $responseSize")
                return null
            }
            val response = ByteArray(responseSize)
            input.readFully(response)
            MinisdProtocol.decodeResponse(response.toString(Charsets.UTF_8))
        } catch (t: Throwable) {
            Log.d(TAG, "local $path: ${t.message}")
            null
        } finally {
            try {
                sock.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun resolveSu(): String? {
        val candidates = listOf(
            suPath,
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/data/adb/ksu/bin/su",
            "/debug_ramdisk/su",
        )
        return candidates.firstOrNull { File(it).canExecute() }
            ?: System.getenv("PATH").orEmpty()
                .split(File.pathSeparatorChar)
                .asSequence()
                .map { File(it, "su") }
                .firstOrNull { it.canExecute() }
                ?.absolutePath
    }

    private fun unavailable(detail: String) = MinisdResponse(
        v = MinisdProtocol.PROTOCOL_V,
        id = 0,
        ok = false,
        result = null,
        error = MinisdError(code = "RUNTIME_UNAVAILABLE", detail = detail),
    )

    private fun nextId(): Long = nextId.getAndIncrement()

    companion object {
        private const val TAG = "MinisdClient"
    }
}
