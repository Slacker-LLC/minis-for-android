package com.openminis.app.sandbox

import android.content.Context
import android.util.Log
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.sandbox.minisd.MinisdResponse
import com.openminis.app.sandbox.ubuntu.UbuntuPaths
import com.openminis.app.sandbox.ubuntu.UbuntuRuntime
import com.openminis.app.tools.DangerousCommandPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Executes `shell_execute` through minisd with per-session serialization. */
object ExecutionCoordinator {

    private const val TAG = "ExecutionCoordinator"
    private const val SESSION_MUTEX_LIMIT = 256

    enum class FailureKind {
        TOOL_TIMEOUT,
        TRANSPORT_TIMEOUT,
        PROCESS_KILLED,
        CLEANUP_FAILURE,
        RUNTIME_FAILURE,
    }

    data class CommandResult(
        val output: String,
        val exitCode: Int,
        val durationMs: Long,
        val fullOutput: String? = null,
        val failureKind: FailureKind? = null,
        val errorCode: String? = null,
    )

    private lateinit var appContext: Context
    var envVarRepository: EnvVarRepository? = null

    private val mutexes = ConcurrentHashMap<String, Mutex>()
    private val cancellationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    suspend fun execute(
        sessionId: String,
        command: String,
        timeout: Long = 600_000L,
        lineCallback: ((String) -> Unit)? = null,
    ): CommandResult {
        val mutex = mutexes.getOrPut(sessionId) { Mutex() }
        if (mutexes.size > SESSION_MUTEX_LIMIT) mutexes.clear()
        return mutex.withLock {
            val startTime = System.currentTimeMillis()
            if (!UbuntuRuntime.isInitialized) {
                return@withLock failure("Ubuntu runtime is not initialized", startTime, "RUNTIME_UNAVAILABLE")
            }
            val ready = UbuntuRuntime.ensureReady()
            if (!ready.running) {
                return@withLock failure(
                    "ubuntu unavailable: ${ready.lastError ?: "not running"}",
                    startTime,
                    "RUNTIME_UNAVAILABLE",
                )
            }
            if (UbuntuPaths.ensureSessionDirs(appContext.filesDir, sessionId) == null) {
                return@withLock failure("invalid or unavailable session workspace: $sessionId", startTime, "BAD_SESSION")
            }
            Log.i(TAG, "[$sessionId] ubuntu.exec ${command.take(80)}")
            val danger = DangerousCommandPolicy.dangerousReason(command)
            if (danger != null) {
                Log.w(TAG, "[$sessionId] blocked dangerous command: $danger")
                return@withLock failure("blocked: $danger", startTime, "POLICY_DENIED")
            }
            val envVars = (envVarRepository?.allAsDict() ?: emptyMap()) +
                ("MINIS_CHAT_SESSION_ID" to sessionId)

            val resp = try {
                UbuntuRuntime.client.ubuntuExec(
                    argv = listOf("/bin/bash", "-lc", command),
                    timeoutMs = timeout,
                    cwd = com.openminis.app.sandbox.minisd.MinisdProtocol.GUEST_WORKSPACE,
                    env = envVars,
                    sessionId = sessionId,
                )
            } catch (cancelled: CancellationException) {
                // User Stop closes the request transport synchronously and sends
                // exec.cancel on a separate IO scope. Preserve cancellation so
                // the agent loop stops instead of fabricating a tool timeout.
                throw cancelled
            }

            if (resp.code == "USER_CANCELLATION") {
                throw CancellationException(resp.error?.detail ?: "shell execution cancelled by user")
            }
            val rawOutput = combineOutput(resp)
            val sanitized = TerminalSanitizer.sanitize(rawOutput)
            val truncated = TerminalSanitizer.truncateIfNeeded(sanitized)
            val failureKind = failureKind(resp)
            val exitCode = when {
                resp.ok -> resp.result?.optInt("exit_code", 1) ?: 1
                failureKind == FailureKind.TOOL_TIMEOUT -> 124
                failureKind == FailureKind.TRANSPORT_TIMEOUT -> 125
                failureKind == FailureKind.PROCESS_KILLED -> 137
                else -> 1
            }
            val output = if (exitCode != 0 && exitCode != 124) "$truncated\n(exit code: $exitCode)" else truncated
            if (lineCallback != null && output.isNotEmpty()) output.lineSequence().forEach(lineCallback)
            CommandResult(
                output = output,
                exitCode = exitCode,
                durationMs = System.currentTimeMillis() - startTime,
                fullOutput = sanitized,
                failureKind = failureKind,
                errorCode = resp.code,
            )
        }
    }

    private fun combineOutput(resp: MinisdResponse): String {
        val stdout = resp.result?.optString("stdout").orEmpty()
        val stderr = resp.result?.optString("stderr").orEmpty()
        val body = when {
            stdout.isEmpty() -> stderr
            stderr.isEmpty() -> stdout
            else -> stdout + stderr
        }
        if (resp.ok) return body
        val detail = resp.error?.detail ?: "ubuntu.exec failed"
        return if (body.isEmpty()) detail else "$body\n$detail"
    }

    private fun failureKind(resp: MinisdResponse): FailureKind? = when (resp.code) {
        "TOOL_TIMEOUT", "TIMEOUT" -> FailureKind.TOOL_TIMEOUT
        "TRANSPORT_TIMEOUT" -> FailureKind.TRANSPORT_TIMEOUT
        "PROCESS_KILLED" -> FailureKind.PROCESS_KILLED
        "CLEANUP_FAILURE" -> FailureKind.CLEANUP_FAILURE
        null -> if (resp.ok) null else FailureKind.RUNTIME_FAILURE
        else -> FailureKind.RUNTIME_FAILURE
    }

    private fun failure(message: String, startTime: Long, code: String) = CommandResult(
        output = message,
        exitCode = 1,
        durationMs = System.currentTimeMillis() - startTime,
        fullOutput = message,
        failureKind = FailureKind.RUNTIME_FAILURE,
        errorCode = code,
    )

    fun sessionDidTerminate(sessionId: String) {
        mutexes.remove(sessionId)
    }

    /**
     * User-facing Stop path. Transport close is synchronous so a blocked socket
     * unblocks immediately; the idempotent broker kill runs on a separate scope
     * and targets only the captured execution id.
     */
    fun stopCurrentCommand(sessionId: String? = null) {
        if (sessionId == null) {
            mutexes.clear()
            return
        }
        val executionId = UbuntuRuntime.client.cancelSessionTransport(sessionId)
        mutexes.remove(sessionId)
        if (executionId != null) {
            cancellationScope.launch {
                runCatching { UbuntuRuntime.client.cancelExecution(executionId) }
                    .onFailure { Log.w(TAG, "[$sessionId] exec.cancel failed: ${it.message}") }
            }
        }
    }

    fun stopCurrentCommand() = stopCurrentCommand(sessionId = null)

    suspend fun broadcastTimezoneChange() {
        Log.d(TAG, "broadcastTimezoneChange: no-op (minisd resolves TZ per exec)")
    }

    suspend fun broadcastProxyChange() {
        Log.d(TAG, "broadcastProxyChange: no-op (minisd resolves proxy per exec)")
    }
}
