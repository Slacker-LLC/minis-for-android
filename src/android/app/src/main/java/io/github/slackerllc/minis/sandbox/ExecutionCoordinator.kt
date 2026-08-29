package io.github.slackerllc.minis.sandbox

import android.content.Context
import android.util.Log
import io.github.slackerllc.minis.data.repository.EnvVarRepository
import io.github.slackerllc.minis.sandbox.ubuntu.UbuntuRuntime
import io.github.slackerllc.minis.tools.DangerousCommandPolicy
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Executes `shell_execute` in the on-device Ubuntu runtime (minisd chroot).
 * PRoot/PersistentShell paths removed at P2. Serialization per session is
 * kept via a simple mutex so same-session commands don't interleave.
 */
object ExecutionCoordinator {

    private const val TAG = "ExecutionCoordinator"
    private const val SESSION_MUTEX_LIMIT = 256

    data class CommandResult(
        /** Bounded display-oriented output retained for legacy callers. */
        val output: String,
        val exitCode: Int,
        val durationMs: Long,
        /** Full sanitized command output, before display truncation / exit-code decoration. */
        val fullOutput: String? = null,
    )

    private lateinit var appContext: Context
    var envVarRepository: EnvVarRepository? = null

    private val mutexes = ConcurrentHashMap<String, Mutex>()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Execute a command inside the Ubuntu runtime (via minisd chroot).
     * Same session commands are serialized; different sessions run concurrently.
     */
    suspend fun execute(
        sessionId: String,
        command: String,
        timeout: Long = 600_000L,
        lineCallback: ((String) -> Unit)? = null
    ): CommandResult {
        val mutex = mutexes.getOrPut(sessionId) { Mutex() }
        if (mutexes.size > SESSION_MUTEX_LIMIT) {
            mutexes.clear()
        }
        return mutex.withLock {
            val startTime = System.currentTimeMillis()

            if (!UbuntuRuntime.isInitialized) {
                return@withLock CommandResult(
                    output = "Ubuntu runtime is not initialized",
                    exitCode = 1,
                    durationMs = 0,
                    fullOutput = "Ubuntu runtime is not initialized",
                )
            }
            val ready = UbuntuRuntime.ensureReady()
            if (!ready.running) {
                val msg = "ubuntu unavailable: ${ready.lastError ?: "not running"}"
                return@withLock CommandResult(output = msg, exitCode = 1, durationMs = 0, fullOutput = msg)
            }
            Log.i(TAG, "[$sessionId] ubuntu.exec ${command.take(80)}")
            // Destructive-command guard: refuse before reaching the runtime.
            val danger = DangerousCommandPolicy.dangerousReason(command)
            if (danger != null) {
                Log.w(TAG, "[$sessionId] blocked dangerous command: $danger")
                return@withLock CommandResult(
                    output = "blocked: $danger",
                    exitCode = 1,
                    durationMs = 0,
                    fullOutput = "blocked: $danger",
                )
            }
            val envVars = envVarRepository?.allAsDict() ?: emptyMap()
            val ran = UbuntuRuntime.shell(
                command = command,
                sessionId = sessionId,
                timeoutMs = timeout,
                env = envVars,
                lineCallback = lineCallback,
            )
            val sanitized = TerminalSanitizer.sanitize(ran.output)
            val truncated = TerminalSanitizer.truncateIfNeeded(sanitized)
            val output = if (ran.exitCode != 0 && ran.exitCode != 124) {
                "$truncated\n(exit code: ${ran.exitCode})"
            } else {
                truncated
            }
            return@withLock CommandResult(
                output = output,
                exitCode = ran.exitCode,
                durationMs = System.currentTimeMillis() - startTime,
                fullOutput = sanitized,
            )
        }
    }

    fun sessionDidTerminate(sessionId: String) {
        mutexes.remove(sessionId)
    }

    fun stopCurrentCommand(sessionId: String? = null) {
        if (sessionId != null) mutexes.remove(sessionId) else mutexes.clear()
    }

    /** Legacy overload for callers without sessionId. */
    fun stopCurrentCommand() = stopCurrentCommand(sessionId = null)

    /**
     * TZ/proxy for the Ubuntu runtime is resolved per-exec by minisd from
     * system state; no live shells to broadcast to. Kept as a no-op so the
     * Android broadcast receivers still have a stable entry point.
     */
    suspend fun broadcastTimezoneChange() {
        Log.d(TAG, "broadcastTimezoneChange: no-op (minisd resolves TZ per exec)")
    }

    suspend fun broadcastProxyChange() {
        Log.d(TAG, "broadcastProxyChange: no-op (minisd resolves proxy per exec)")
    }
}
