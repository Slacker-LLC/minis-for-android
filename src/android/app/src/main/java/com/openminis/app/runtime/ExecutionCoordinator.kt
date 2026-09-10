package com.openminis.app.runtime

import android.content.Context
import android.util.Log
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.runtime.terminal.TerminalSanitizer
import com.openminis.app.runtime.ubuntu.RootNetworkProxy
import com.openminis.app.runtime.ubuntu.RootPersistentShell
import com.openminis.app.runtime.ubuntu.UbuntuRuntime
import com.openminis.app.sandbox.TerminalSession
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

    enum class FailureKind {
        TOOL_TIMEOUT,
        RUNTIME_FAILURE,
    }

    data class CommandResult(
        val output: String,
        val exitCode: Int,
        val durationMs: Long,
        val failureKind: FailureKind? = null,
    )

    private lateinit var appContext: Context
    var envVarRepository: EnvVarRepository? = null

    private val shells = ConcurrentHashMap<String, RootPersistentShell>()
    private val mutexes = ConcurrentHashMap<String, Mutex>()
    private val lastInjectedKeys = ConcurrentHashMap<String, Set<String>>()

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
            return failure("execution coordinator is not initialized", startTime)
        }
        if (!UbuntuRuntime.isInitialized) UbuntuRuntime.init(appContext)
        val ready = UbuntuRuntime.ensureReady()
        if (ready.running) return null
        return failure(
            "ubuntu unavailable: ${ready.lastError ?: "not ready"}",
            startTime,
        )
    }

    suspend fun execute(
        sessionId: String,
        command: String,
        timeout: Long = 600_000L,
        lineCallback: ((String) -> Unit)? = null,
    ): CommandResult {
        // The mutex stays registered for the session lifetime. Opportunistically
        // evicting an unlocked-looking entry here is unsafe: another coroutine
        // may already hold a reference but not yet have entered withLock, which
        // would let a replacement mutex admit a second concurrent command.
        val mutex = mutexes.getOrPut(sessionId) { Mutex() }
        return mutex.withLock {
            val startTime = System.currentTimeMillis()
            ensureRuntimeReady(startTime)?.let { return@withLock it }

            val danger = DangerousCommandPolicy.dangerousReason(command)
            if (danger != null) {
                Log.w(TAG, "[$sessionId] blocked dangerous command: $danger")
                return@withLock failure("blocked: $danger", startTime)
            }

            try {
                val shell = getOrCreateShell(sessionId)
                val userEnv = envVarRepository?.allAsDict().orEmpty()
                val runtimeProxy = RootNetworkProxy.proxyEnv()
                val runtimeProxyKeys = runtimeProxy.keys
                val env = if (userEnv.keys.any { it in runtimeProxyKeys }) {
                    userEnv.toMutableMap().apply { putAll(runtimeProxy) }
                } else {
                    userEnv
                }
                // Runtime-owned proxy variables must never be unset by the
                // user-env delta path. They are established by prepareLaunch
                // and, when a user key conflicts, overwritten above with the
                // required Root loopback proxy value.
                val previous = lastInjectedKeys[sessionId].orEmpty()
                    .filterNot { it in runtimeProxyKeys }
                    .toSet()
                if (env.isNotEmpty() || previous.isNotEmpty()) {
                    shell.applyEnvironment(env, previous)
                    lastInjectedKeys[sessionId] = userEnv.keys.toSet()
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
                    failureKind = if (ran.exitCode == 124) FailureKind.TOOL_TIMEOUT else null,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                shells.remove(sessionId)?.stop()
                lastInjectedKeys.remove(sessionId)
                failure(
                    error.message ?: error::class.java.simpleName,
                    startTime,
                )
            }
        }
    }

    /** Called only while [execute] holds this session's mutex. */
    private suspend fun getOrCreateShell(sessionId: String): RootPersistentShell {
        shells[sessionId]?.takeIf { it.isAlive }?.let { return it }
        shells.remove(sessionId)?.stop()
        return RootPersistentShell(sessionId).also { shell ->
            shell.ensureStarted()
            shells[sessionId] = shell
        }
    }

    private fun failure(message: String, startTime: Long): CommandResult {
        val sanitized = TerminalSanitizer.sanitize(message)
        return CommandResult(
            output = sanitized,
            exitCode = 1,
            durationMs = System.currentTimeMillis() - startTime,
            failureKind = FailureKind.RUNTIME_FAILURE,
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
        val tz = RuntimePathRegistry.posixTz()
        val env = mapOf("TZ" to tz)
        shells.forEach { (sessionId, shell) ->
            if (shell.isAlive) runCatching { shell.applyEnvironment(env) }
                .onFailure { Log.d(TAG, "[$sessionId] timezone update failed: ${it.message}") }
        }
        TerminalSession.broadcastTimezone(tz)
    }

    suspend fun broadcastProxyChange() {
        // Direct Ubuntu always exits through the Root loopback proxy. Android's
        // system HTTP proxy must not replace these variables in an already-live
        // shell or its networking diverges from newly launched sessions.
        val env = RootNetworkProxy.proxyEnv()
        shells.forEach { (sessionId, shell) ->
            if (shell.isAlive) runCatching { shell.applyEnvironment(env) }
                .onFailure { Log.d(TAG, "[$sessionId] proxy update failed: ${it.message}") }
        }
        TerminalSession.broadcastProxy(env)
    }
}
