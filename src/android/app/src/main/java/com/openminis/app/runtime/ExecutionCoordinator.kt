package com.openminis.app.runtime

import android.content.Context
import android.util.Log
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.runtime.terminal.TerminalSanitizer
import com.openminis.app.runtime.ubuntu.UbuntuRuntime
import com.openminis.app.tools.DangerousCommandPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Android orchestration boundary for guest command execution. It serializes
 * commands per chat session and delegates runtime readiness plus execution to
 * [UbuntuRuntime]; minisd owns privileged broker, mount namespace and chroot
 * infrastructure.
 */
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
            Log.i(TAG, "[$sessionId] ubuntu.exec ${command.take(80)}")
            val danger = DangerousCommandPolicy.dangerousReason(command)
            if (danger != null) {
                Log.w(TAG, "[$sessionId] blocked dangerous command: $danger")
                return@withLock failure("blocked: $danger", startTime, "POLICY_DENIED")
            }
            val envVars = envVarRepository?.allAsDict() ?: emptyMap()
            val ran = try {
                UbuntuRuntime.shell(
                    command = command,
                    sessionId = sessionId,
                    timeoutMs = timeout,
                    env = envVars,
                    lineCallback = lineCallback,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (infra: UbuntuRuntime.RuntimeInfrastructureException) {
                val error = infra.runtimeError
                if (error.code == "USER_CANCELLATION") {
                    throw CancellationException(error.detail.ifBlank { "shell execution cancelled by user" })
                }
                return@withLock failure(error.detail, startTime, error.code)
            }
            val sanitized = TerminalSanitizer.sanitize(ran.output)
            val truncated = TerminalSanitizer.truncateIfNeeded(sanitized)
            val output = if (ran.exitCode != 0 && ran.exitCode != 124) {
                "$truncated\n(exit code: ${ran.exitCode})"
            } else {
                truncated
            }
            CommandResult(
                output = output,
                exitCode = ran.exitCode,
                durationMs = System.currentTimeMillis() - startTime,
                fullOutput = sanitized,
            )
        }
    }

    private fun failure(message: String, startTime: Long, code: String): CommandResult {
        val kind = when (code) {
            "TOOL_TIMEOUT", "TIMEOUT" -> FailureKind.TOOL_TIMEOUT
            "TRANSPORT_TIMEOUT" -> FailureKind.TRANSPORT_TIMEOUT
            "PROCESS_KILLED" -> FailureKind.PROCESS_KILLED
            "CLEANUP_FAILURE" -> FailureKind.CLEANUP_FAILURE
            else -> FailureKind.RUNTIME_FAILURE
        }
        val exitCode = when (kind) {
            FailureKind.TOOL_TIMEOUT -> 124
            FailureKind.TRANSPORT_TIMEOUT -> 125
            FailureKind.PROCESS_KILLED -> 137
            else -> 1
        }
        val sanitized = TerminalSanitizer.sanitize(message)
        return CommandResult(
            output = sanitized,
            exitCode = exitCode,
            durationMs = System.currentTimeMillis() - startTime,
            fullOutput = sanitized,
            failureKind = kind,
            errorCode = code,
        )
    }

    fun sessionDidTerminate(sessionId: String) {
        mutexes.remove(sessionId)
    }

    /**
     * User-facing Stop path. Transport close is synchronous so a blocked socket
     * unblocks immediately; the idempotent broker kill runs on a separate scope
     * and targets only the captured execution id.
     */
    fun stopCurrentCommand(sessionId: String) {
        val executionId = UbuntuRuntime.client.cancelSessionTransport(sessionId)
        if (executionId != null) {
            cancellationScope.launch {
                runCatching { UbuntuRuntime.client.cancelExecution(executionId) }
                    .onFailure { Log.w(TAG, "[$sessionId] exec.cancel failed: ${it.message}") }
            }
        }
    }

    /** Stops every currently tracked session, for runtime-wide mount/config changes. */
    fun stopCurrentCommand() {
        UbuntuRuntime.client.cancelAllSessionTransports().forEach { target ->
            cancellationScope.launch {
                runCatching { UbuntuRuntime.client.cancelExecution(target.executionId) }
                    .onFailure {
                        Log.w(TAG, "[${target.sessionId}] global exec.cancel failed: ${it.message}")
                    }
            }
        }
    }

    suspend fun broadcastTimezoneChange() {
        Log.d(TAG, "broadcastTimezoneChange: no-op (minisd resolves TZ per exec)")
    }

    suspend fun broadcastProxyChange() {
        Log.d(TAG, "broadcastProxyChange: no-op (minisd resolves proxy per exec)")
    }
}
