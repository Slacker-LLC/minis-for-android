package com.openminis.app.sandbox.minisd

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

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

    suspend fun ubuntuStart(
        rootfs: String = MinisdProtocol.DEFAULT_ROOTFS,
        workspace: String = "",
        memory: String = "",
        skills: String = "",
        shared: String = "",
    ): MinisdResponse = call(
        MinisdProtocol.ubuntuStart(nextId(), rootfs, workspace, memory, skills, shared),
        timeoutMs = 20_000,
    )

    suspend fun ubuntuStop(): MinisdResponse = call(MinisdProtocol.ubuntuStop(nextId()))

    suspend fun ubuntuExec(
        argv: List<String>,
        timeoutMs: Long = 30_000,
        cwd: String = MinisdProtocol.GUEST_WORKSPACE,
        env: Map<String, String> = emptyMap(),
    ): MinisdResponse = call(
        MinisdProtocol.ubuntuExec(argv, timeoutMs, cwd, env, nextId()),
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
                    callLocal(path, payload, timeoutMs)?.let { return@withContext it }
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
            try {
                proc.outputStream.bufferedWriter().use { it.write(payload); it.flush() }
                proc.outputStream.close()
                val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                if (!finished) {
                    proc.destroyForcibly()
                    return@withContext unavailable("minisd --call timed out after ${timeoutMs}ms")
                }
                val stdout = proc.inputStream.bufferedReader().readText()
                val stderr = proc.errorStream.bufferedReader().readText()
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
            sock.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
            sock.soTimeout = timeoutMs.toInt().coerceAtMost(Int.MAX_VALUE)
            sock.outputStream.write(payload.toByteArray())
            sock.shutdownOutput()
            val out = sock.inputStream.bufferedReader().readText()
            if (out.isBlank()) null else MinisdProtocol.decodeResponse(out)
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
            "/debug_ramdisk/su",
        )
        return candidates.firstOrNull { File(it).canExecute() }
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
