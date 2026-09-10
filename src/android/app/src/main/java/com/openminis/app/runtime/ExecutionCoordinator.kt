package com.openminis.app.runtime

import android.content.Context
import android.util.Log
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.runtime.terminal.TerminalSanitizer
import com.openminis.app.runtime.ubuntu.RootPersistentShell
import com.openminis.app.runtime.ubuntu.UbuntuRuntime
import com.openminis.app.tools.DangerousCommandPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-session guest execution coordinator.
 *
 * This intentionally follows upstream Android's ExecutionCoordinator shape:
 * one persistent shell per session, one mutex per session, and direct process
 * ownership in the App. The only backend difference is that our shell enters a
 * Root-created Ubuntu chroot instead of a PRoot Alpine userspace.
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

    private val shells = ConcurrentHashMap<String, RootPersistentShell>()
    private val mutexes = ConcurrentHashMap<String, Mutex>()
    private val lastInjectedKeys = ConcurrentHashMap<String, Set<String>>()
    private val globalLock = Mutex()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Verify the direct Ubuntu runtime before a caller performs guest-command
     * side effects such as creating a temporary script in canonical storage.
     * Returns null when ready, otherwise the same structured failure used by
     * [execute].
     */
    suspend fun ensureRuntimeReady(): CommandResult? = ensureRuntimeReady(System.currentTimeMillis())

    private suspend fun ensureRuntimeReady(startTime: Long): CommandResult? {
        if (!::appContext.isInitialized) {
            return failure(
                "execution coordinator is not initialized",
                startTime,
                "RUNTIME_UNAVAILABLE",
            )
        }
        if (!UbuntuRuntime.isInitialized) UbuntuRuntime.init(appContext)
        val ready = UbuntuRuntime.ensureReady()
        if (ready.running) return null
        return failure(
            "ubuntu unavailable: ${ready.lastError ?: "not ready"}",
            startTime,
            "RUNTIME_UNAVAILABLE",
        )
    }

    suspend fun execute(
        sessionId: String,
        command: String,
        timeout: Long = 600_000L,
        lineCallback: ((String) -> Unit)? = null,
    ): CommandResult {
        val mutex = mutexes.getOrPut(sessionId) { Mutex() }
        if (mutexes.size > SESSION_MUTEX_LIMIT) {
            mutexes.keys.filter { it !in shells.keys }.take(mutexes.size - SESSION_MUTEX_LIMIT).forEach(mutexes::remove)
        }
        return mutex.withLock {
            val startTime = System.currentTimeMillis()
            ensureRuntimeReady(startTime)?.let { return@withLock it }

            val danger = DangerousCommandPolicy.dangerousReason(command)
            if (danger != null) {
                Log.w(TAG, "[$sessionId] blocked dangerous command: $danger")
                return@withLock failure("blocked: $danger", startTime, "POLICY_DENIED")
            }

            try {
                val shell = getOrCreateShell(sessionId)
                val env = envVarRepository?.allAsDict().orEmpty()
                val previous = lastInjectedKeys[sessionId].orEmpty()
                if (env.isNotEmpty() || previous.isNotEmpty()) {
                    shell.applyEnvironment(env, previous)
                    lastInjectedKeys[sessionId] = env.keys.toSet()
                }
                Log.i(TAG, "[$sessionId] direct ubuntu shell ${command.take(80)}")
                val ran = shell.executeCommand(command, timeout, lineCallback)
                val sanitized = TerminalSanitizer.sanitize(ran.output)
                val truncated = TerminalSanitizer.truncateIfNeeded(sanitized)
                val output = if (ran.exitCode != 0 && ran.exitCode != 124 && ran.exitCode != 130) {
                    "$truncated\n(exit code: ${ran.exitCode})"
                } else {
                    truncated
                }
                CommandResult(
                    output = output,
                    exitCode = ran.exitCode,
                    durationMs = System.currentTimeMillis() - startTime,
                    fullOutput = sanitized,
                    failureKind = if (ran.exitCode == 124) FailureKind.TOOL_TIMEOUT else null,
                    errorCode = if (ran.exitCode == 124) "TOOL_TIMEOUT" else null,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                shells.remove(sessionId)?.stop()
                lastInjectedKeys.remove(sessionId)
                failure(
                    error.message ?: error::class.java.simpleName,
                    startTime,
                    "RUNTIME_FAILURE",
                )
            }
        }
    }

    private suspend fun getOrCreateShell(sessionId: String): RootPersistentShell {
        shells[sessionId]?.takeIf { it.isAlive }?.let { return it }
        return globalLock.withLock {
            shells[sessionId]?.takeIf { it.isAlive }?.let { return@withLock it }
            shells.remove(sessionId)?.stop()
            RootPersistentShell(sessionId).also { shell ->
                shell.ensureStarted()
                shells[sessionId] = shell
            }
        }
    }

    private fun failure(message: String, startTime: Long, code: String): CommandResult {
        val kind = when (code) {
            "TOOL_TIMEOUT", "TIMEOUT" -> FailureKind.TOOL_TIMEOUT
            else -> FailureKind.RUNTIME_FAILURE
        }
        val exitCode = if (kind == FailureKind.TOOL_TIMEOUT) 124 else 1
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
        shells.remove(sessionId)?.stop()
        mutexes.remove(sessionId)
        lastInjectedKeys.remove(sessionId)
    }

    /** User-facing Stop. Kill the session shell; next command recreates it. */
    fun stopCurrentCommand(sessionId: String) {
        shells.remove(sessionId)?.stop()
        lastInjectedKeys.remove(sessionId)
    }

    /** Stop every live session before mount-layout or rootfs changes. */
    fun stopCurrentCommand() {
        shells.values.forEach { it.stop() }
        shells.clear()
        lastInjectedKeys.clear()
    }

    suspend fun broadcastTimezoneChange() {
        val env = mapOf("TZ" to RuntimePathRegistry.posixTz())
        shells.forEach { (sessionId, shell) ->
            if (shell.isAlive) runCatching { shell.applyEnvironment(env) }
                .onFailure { Log.d(TAG, "[$sessionId] timezone update failed: ${it.message}") }
        }
    }

    suspend fun broadcastProxyChange() {
        val env = RuntimePathRegistry.systemProxyEnv(appContext)
        shells.forEach { (sessionId, shell) ->
            if (shell.isAlive) runCatching { shell.applyEnvironment(env) }
                .onFailure { Log.d(TAG, "[$sessionId] proxy update failed: ${it.message}") }
        }
    }
}
