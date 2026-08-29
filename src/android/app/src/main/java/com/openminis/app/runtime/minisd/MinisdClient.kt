package com.openminis.app.runtime.minisd

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** App-side client for the already-installed minisd. */
class MinisdClient(
    private val minisdPath: String = MinisdProtocol.DEFAULT_BIN,
    private val socketPath: String = MinisdProtocol.DEFAULT_SOCKET,
    private val appSocketPath: String? = null,
    private val suPath: String = "/system/bin/su",
) {
    private val nextId = AtomicLong(1)
    private val activeSockets = ConcurrentHashMap<String, LocalSocket>()
    private val activeHelpers = ConcurrentHashMap<String, Process>()
    private val cancellations = ExecutionCancellationRegistry()

    suspend fun ping(): MinisdResponse = call(MinisdProtocol.ping(nextId()))
    suspend fun ubuntuStatus(): MinisdResponse = call(MinisdProtocol.ubuntuStatus(nextId()))

    suspend fun ubuntuStart(
        rootfs: String = MinisdProtocol.DEFAULT_ROOTFS,
        workspace: String = "",
        memory: String = "",
        skills: String = "",
        shared: String = "",
        sessionsRoot: String = "",
    ): MinisdResponse = call(
        MinisdProtocol.ubuntuStart(nextId(), rootfs, workspace, memory, skills, shared, sessionsRoot),
        timeoutMs = 20_000,
    )

    suspend fun ubuntuStop(): MinisdResponse = call(MinisdProtocol.ubuntuStop(nextId()))

    suspend fun ubuntuExec(
        argv: List<String>,
        timeoutMs: Long = 30_000,
        cwd: String = MinisdProtocol.GUEST_WORKSPACE,
        env: Map<String, String> = emptyMap(),
        sessionId: String? = null,
        executionId: String = newExecutionId("ubuntu"),
    ): MinisdResponse {
        if (sessionId != null) cancellations.register(sessionId, executionId)
        return try {
            call(
                MinisdProtocol.ubuntuExec(argv, timeoutMs, cwd, env, nextId(), sessionId, executionId),
                timeoutMs + 5_000,
                cancellationKey = executionId,
            )
        } finally {
            if (sessionId != null) cancellations.unregister(sessionId, executionId)
            else cancellations.clearExecution(executionId)
        }
    }

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

    suspend fun rootExec(
        tool: String,
        args: List<String> = emptyList(),
        timeoutMs: Long = 30_000,
        executionId: String = newExecutionId("root"),
    ): MinisdResponse = try {
        call(
            MinisdProtocol.rootExec(tool, args, timeoutMs, nextId(), executionId),
            timeoutMs + 5_000,
            cancellationKey = executionId,
        )
    } finally {
        cancellations.clearExecution(executionId)
    }

    fun cancelTransport(executionId: String) {
        cancellations.requestExecutionCancellation(executionId)
        closeTransport(executionId)
    }

    private fun closeTransport(executionId: String) {
        activeSockets.remove(executionId)?.runCatching { close() }
        activeHelpers.remove(executionId)?.runCatching { destroyForcibly() }
    }

    fun cancelSessionTransport(sessionId: String): String? {
        val target = cancellations.requestSessionCancellation(sessionId) ?: return null
        closeTransport(target.executionId)
        return target.executionId
    }

    internal fun cancelAllSessionTransports(): List<ExecutionCancellationRegistry.Target> =
        cancellations.requestAllSessionCancellations().also { targets ->
            targets.forEach { closeTransport(it.executionId) }
        }

    suspend fun cancelSessionExecution(sessionId: String): MinisdResponse? {
        val executionId = cancelSessionTransport(sessionId) ?: return null
        return cancelExecution(executionId)
    }

    suspend fun cancelExecution(executionId: String): MinisdResponse {
        closeTransport(executionId)
        var last: MinisdResponse? = null
        repeat(8) {
            currentCoroutineContext().ensureActive()
            val response = call(MinisdProtocol.execCancel(executionId, nextId()), timeoutMs = 5_000)
            last = response
            if (!response.ok || response.result?.optBoolean("found") == true) return response
            kotlinx.coroutines.delay(25)
        }
        return last ?: errorResponse("USER_CANCELLATION", "execution cancel was not acknowledged")
    }

    suspend fun call(
        request: MinisdRequest,
        timeoutMs: Long = 30_000,
        cancellationKey: String? = null,
    ): MinisdResponse = withContext(Dispatchers.IO) {
        val payload = MinisdProtocol.encodeRequest(request)
        appSocketPath?.let { path ->
            if (File(path).exists()) {
                val local = callLocal(path, payload, timeoutMs, cancellationKey)
                if (local != null) {
                    if (local.code != "NOT_AUTHORIZED") return@withContext local
                    Log.w(TAG, "local minisd rejected app identity; retrying through su")
                }
                currentCoroutineContext().ensureActive()
                if (cancellationKey != null && cancellations.isCancellationRequested(cancellationKey)) {
                    return@withContext userCancellation("execution cancelled while waiting for local minisd")
                }
            }
        }
        if (cancellationKey != null && cancellations.isCancellationRequested(cancellationKey)) {
            return@withContext userCancellation("execution cancelled before minisd fallback")
        }
        val su = resolveSu() ?: return@withContext unavailable("no executable su; minisd --call needs root")
        val cmd = "$minisdPath --call --socket $socketPath"
        val proc = try {
            ProcessBuilder(su, "-c", cmd).redirectErrorStream(false).start()
        } catch (t: Throwable) {
            return@withContext unavailable("failed to start su: ${t.message}")
        }
        if (cancellationKey != null) activeHelpers[cancellationKey] = proc
        if (cancellationKey != null && cancellations.isCancellationRequested(cancellationKey)) {
            activeHelpers.remove(cancellationKey, proc)
            proc.destroyForcibly()
            return@withContext userCancellation("execution cancelled before minisd helper dispatch")
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
            proc.outputStream.bufferedWriter().use { it.write(payload); it.flush() }
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            var finished = false
            while (!finished && System.nanoTime() < deadline) {
                currentCoroutineContext().ensureActive()
                finished = proc.waitFor(100, TimeUnit.MILLISECONDS)
            }
            if (!finished) {
                proc.destroyForcibly()
                proc.waitFor(1_000, TimeUnit.MILLISECONDS)
                stdoutThread.join(1_000)
                stderrThread.join(1_000)
                return@withContext transportTimeout("minisd --call timed out after ${timeoutMs}ms")
            }
            stdoutThread.join(1_000)
            stderrThread.join(1_000)
            if (cancellationKey != null && cancellations.isCancellationRequested(cancellationKey)) {
                return@withContext userCancellation("execution cancelled while waiting for minisd helper")
            }
            val stdout = stdoutRef.get()
            val stderr = stderrRef.get()
            if (stdout.isBlank()) {
                return@withContext unavailable("empty minisd response (exit=${proc.exitValue()} stderr=${stderr.take(300)})")
            }
            MinisdProtocol.decodeResponse(stdout)
        } catch (cancelled: CancellationException) {
            proc.destroyForcibly()
            throw cancelled
        } catch (t: Throwable) {
            Log.w(TAG, "minisd call ${request.method} failed: ${t.message}")
            unavailable(t.message ?: "minisd call failed")
        } finally {
            if (cancellationKey != null) activeHelpers.remove(cancellationKey, proc)
            proc.destroy()
        }
    }

    private fun callLocal(
        path: String,
        payload: String,
        timeoutMs: Long,
        cancellationKey: String?,
    ): MinisdResponse? {
        val sock = LocalSocket()
        if (cancellationKey != null) activeSockets[cancellationKey] = sock
        return try {
            if (cancellationKey != null && cancellations.isCancellationRequested(cancellationKey)) {
                return userCancellation("execution cancelled before local minisd dispatch")
            }
            val bytes = payload.toByteArray(Charsets.UTF_8)
            if (bytes.isEmpty() || bytes.size > MinisdProtocol.MAX_REQUEST_BYTES) return null
            sock.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
            if (cancellationKey != null && cancellations.isCancellationRequested(cancellationKey)) {
                return userCancellation("execution cancelled before local minisd write")
            }
            sock.soTimeout = timeoutMs.toInt().coerceAtMost(Int.MAX_VALUE)
            val output = DataOutputStream(sock.outputStream)
            output.writeInt(bytes.size); output.write(bytes); output.flush()
            val input = DataInputStream(sock.inputStream)
            val responseSize = input.readInt()
            if (responseSize !in 1..MinisdProtocol.MAX_RESPONSE_BYTES) {
                Log.w(TAG, "invalid minisd frame size: $responseSize")
                return null
            }
            val response = ByteArray(responseSize)
            input.readFully(response)
            MinisdProtocol.decodeResponse(response.toString(Charsets.UTF_8))
        } catch (_: SocketTimeoutException) {
            transportTimeout("local minisd transport timed out after ${timeoutMs}ms")
        } catch (t: Throwable) {
            Log.d(TAG, "local $path: ${t.message}")
            null
        } finally {
            if (cancellationKey != null) activeSockets.remove(cancellationKey, sock)
            runCatching { sock.close() }
        }
    }

    private fun resolveSu(): String? {
        val candidates = listOf(suPath, "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/data/adb/ksu/bin/su", "/debug_ramdisk/su")
        return candidates.firstOrNull { File(it).canExecute() }
            ?: System.getenv("PATH").orEmpty().split(File.pathSeparatorChar).asSequence()
                .map { File(it, "su") }.firstOrNull { it.canExecute() }?.absolutePath
    }

    private fun unavailable(detail: String) = errorResponse("RUNTIME_UNAVAILABLE", detail)
    private fun transportTimeout(detail: String) = errorResponse("TRANSPORT_TIMEOUT", detail)
    private fun userCancellation(detail: String) = errorResponse("USER_CANCELLATION", detail)

    private fun errorResponse(code: String, detail: String) = MinisdResponse(
        v = MinisdProtocol.PROTOCOL_V,
        id = 0,
        ok = false,
        result = null,
        error = MinisdError(code = code, detail = detail),
    )

    private fun nextId(): Long = nextId.getAndIncrement()

    companion object {
        private const val TAG = "MinisdClient"
        fun newExecutionId(prefix: String): String = "$prefix:${UUID.randomUUID()}"
    }
}
