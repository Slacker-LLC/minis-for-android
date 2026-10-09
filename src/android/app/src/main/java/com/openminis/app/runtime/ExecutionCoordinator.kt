package com.openminis.app.runtime

import android.content.Context
import android.util.Log
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.runtime.terminal.TerminalSanitizer
import com.openminis.app.runtime.ubuntu.RootNetworkProxy
import com.openminis.app.runtime.ubuntu.RootPersistentShell
import com.openminis.app.runtime.ubuntu.UbuntuRuntime
import com.openminis.app.sandbox.TerminalSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-session guest execution coordinator.
 *
 * This intentionally follows upstream Android's ExecutionCoordinator shape:
 * one persistent shell per session, one mutex per session, and direct process
 * ownership in the App. Each shell enters a Root-created Ubuntu chroot.
 */
object ExecutionCoordinator {
    private const val TAG = "ExecutionCoordinator"

    data class CommandResult(
        val output: String,
        val exitCode: Int,
        val durationMs: Long,
    )

    private lateinit var appContext: Context
    var envVarRepository: EnvVarRepository? = null

    private class SessionState {
        val mutex = Mutex()
        val generation = AtomicLong(0L)
        var activeExecutions: Int = 0
        var terminated: Boolean = false
    }

    private val shells = ConcurrentHashMap<String, RootPersistentShell>()
    private val sessionStates = ConcurrentHashMap<String, SessionState>()
    private val sessionStateLock = Any()
    private val lastInjectedKeys = ConcurrentHashMap<String, Set<String>>()
    private val globalLock = Mutex()
    /**
     * Invalidates shell startup that is racing a runtime-wide Stop. Shells are
     * published before startup so Stop can reach them; this epoch also covers
     * the gap before a newly requested shell is published.
     */
    private val stopGeneration = AtomicLong(0L)

    @Synchronized
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
        val startTime = System.currentTimeMillis()
        val session = acquireSessionState(sessionId)
            ?: return failure("session is being terminated", startTime)
        // Capture both epochs before waiting for the per-session mutex. A
        // queued command that predates Stop must fail after it reaches the
        // mutex; otherwise it could start a fresh shell after the old one was
        // deliberately torn down.
        val generation = stopGeneration.get()
        val sessionGeneration = session.generation.get()
        return try {
            session.mutex.withLock {
                checkGeneration(generation, session, sessionGeneration)
                ensureRuntimeReady(startTime)?.let { return@withLock it }

                val shell = getOrCreateShell(sessionId, generation, session, sessionGeneration)
                checkGeneration(generation, session, sessionGeneration)
                // A newly-created shell may already contain proxy variables
                // from its launch environment. Seed the delta state with all
                // runtime-owned keys so a helper that dies before the first
                // command cannot leave those values behind.
                lastInjectedKeys.putIfAbsent(sessionId, RootNetworkProxy.PROXY_ENV_KEYS)
                val userEnv = envVarRepository?.allAsDict().orEmpty()
                val runtimeProxy = RootNetworkProxy.proxyEnv()
                // The helper is an optional compatibility overlay. Preserve
                // explicit user proxy variables when it is absent, while the
                // live helper wins for the same names when it is available.
                val env = userEnv.toMutableMap().apply { putAll(runtimeProxy) }
                val previous = lastInjectedKeys[sessionId].orEmpty()
                if (env.isNotEmpty() || previous.isNotEmpty()) {
                    shell.applyEnvironment(env, previous)
                    lastInjectedKeys[sessionId] = env.keys.toSet()
                }
                checkGeneration(generation, session, sessionGeneration)
                Log.i(TAG, "[$sessionId] direct ubuntu shell ${command.take(80)}")
                val ran = shell.executeCommand(command, timeout, lineCallback)
                val truncated = shellOutputForModel(ran.output)
                val output = if (ran.exitCode != 0 && ran.exitCode != 124 && ran.exitCode != 130) {
                    "$truncated\n(exit code: ${ran.exitCode})"
                } else {
                    truncated
                }
                CommandResult(
                    output = output,
                    exitCode = ran.exitCode,
                    durationMs = System.currentTimeMillis() - startTime,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            shells.remove(sessionId)?.stop()
            lastInjectedKeys.remove(sessionId)
            // Whatever broke may be in the runtime itself: do not trust the "checked recently" window.
            UbuntuRuntime.invalidateReadiness()
            failure(
                error.message ?: error::class.java.simpleName,
                startTime,
            )
        } finally {
            releaseSessionState(sessionId, session)
        }
    }

    /**
     * A guest shell of its own for one background job: same chroot, user, workspace and environment as the
     * session's persistent shell, but a separate process, so it neither waits for that shell nor blocks it.
     * Output lines pass the same sanitising and Privacy-Mode masking as a foreground command's output.
     */
    fun newJobProcess(sessionId: String): ShellJobs.JobProcess = object : ShellJobs.JobProcess {
        private val shell = RootPersistentShell(sessionId)
        private var stdin: JobStdin? = null

        override suspend fun run(
            command: String,
            timeoutMs: Long,
            onLine: (String) -> Unit,
        ): ShellJobs.JobProcess.Result {
            val startTime = System.currentTimeMillis()
            ensureRuntimeReady(startTime)?.let { failed ->
                onLine(failed.output)
                return ShellJobs.JobProcess.Result(failed.exitCode)
            }
            shell.ensureStarted()
            val env = envVarRepository?.allAsDict().orEmpty().toMutableMap().apply {
                putAll(RootNetworkProxy.proxyEnv())
            }
            if (env.isNotEmpty()) shell.applyEnvironment(env)
            // The command reads its input from a pipe the app writes to (job_input), not from the shell's own stdin.
            val input = openStdin(sessionId)
            stdin = input
            val script = if (input == null) command else JobStdin.wrap(command, input.guestPath)
            val ran = shell.executeCommand(script, timeoutMs) { line -> onLine(shellOutputForModel(line)) }
            val timedOut = ran.exitCode == 124 && ran.output.startsWith("command timed out after")
            return ShellJobs.JobProcess.Result(ran.exitCode, timedOut)
        }

        override suspend fun writeInput(data: String, eof: Boolean): String? {
            // null from write() is success, so the missing pipe is decided before it, not with an elvis after it.
            val input = stdin ?: return "this job has no input pipe"
            return input.write(data, eof)
        }

        override fun stop() {
            shell.stop()
            stdin?.close()
        }
    }

    /** A new stdin pipe in the session's `/tmp` for a job; null when it cannot be made (the job then runs without one). */
    private fun openStdin(sessionId: String): JobStdin? {
        val name = ".minis-job-${java.util.UUID.randomUUID().toString().replace("-", "")}.in"
        val host = com.openminis.app.runtime.ubuntu.UbuntuPaths.resolveSessionHostPath(sessionId, "/tmp/$name") ?: return null
        host.parentFile?.mkdirs()
        return JobStdin(host, "/tmp/$name").takeIf { it.create() }
    }

    private fun acquireSessionState(sessionId: String): SessionState? = synchronized(sessionStateLock) {
        val existing = sessionStates[sessionId]
        if (existing == null) {
            return@synchronized SessionState().also {
                it.activeExecutions = 1
                sessionStates[sessionId] = it
            }
        }
        if (existing.terminated) return@synchronized null
        existing.activeExecutions++
        existing
    }

    private fun releaseSessionState(sessionId: String, session: SessionState) {
        synchronized(sessionStateLock) {
            if (session.activeExecutions > 0) session.activeExecutions--
            if (session.terminated && session.activeExecutions == 0) {
                sessionStates.remove(sessionId, session)
            }
        }
    }

    /** Called only while [execute] holds this session's mutex. */
    private suspend fun getOrCreateShell(
        sessionId: String,
        generation: Long,
        session: SessionState,
        sessionGeneration: Long,
    ): RootPersistentShell {
        checkGeneration(generation, session, sessionGeneration)
        shells[sessionId]?.takeIf { it.isAlive }?.let {
            checkGeneration(generation, session, sessionGeneration)
            return it
        }
        return globalLock.withLock {
            checkGeneration(generation, session, sessionGeneration)
            shells[sessionId]?.takeIf { it.isAlive }?.let {
                checkGeneration(generation, session, sessionGeneration)
                return@withLock it
            }
            shells.remove(sessionId)?.stop()

            // Match upstream's visibility rule: publish the shell before its
            // potentially slow startup so Stop/runtime shutdown can reach it.
            // Root startup has multiple failure points, so remove and
            // close the instance on every failed/cancelled startup.
            val shell = RootPersistentShell(sessionId)
            shells[sessionId] = shell
            try {
                shell.ensureStarted()
                checkGeneration(generation, session, sessionGeneration)
                shell
            } catch (failure: Throwable) {
                shells.remove(sessionId, shell)
                shell.stop()
                throw failure
            }
        }
    }

    private fun checkGeneration(
        generation: Long,
        session: SessionState,
        sessionGeneration: Long,
    ) {
        check(stopGeneration.get() == generation) {
            "direct Ubuntu shell startup was cancelled by runtime stop"
        }
        check(session.generation.get() == sessionGeneration && !session.terminated) {
            "direct Ubuntu shell was cancelled by session stop"
        }
    }

    private fun failure(message: String, startTime: Long): CommandResult {
        val sanitized = TerminalSanitizer.sanitize(message)
        return CommandResult(
            output = sanitized,
            exitCode = 1,
            durationMs = System.currentTimeMillis() - startTime,
        )
    }

    fun sessionDidTerminate(sessionId: String) {
        synchronized(sessionStateLock) {
            sessionStates[sessionId]?.let { session ->
                session.terminated = true
                session.generation.incrementAndGet()
                if (session.activeExecutions == 0) sessionStates.remove(sessionId, session)
            }
        }
        shells.remove(sessionId)?.stop()
        lastInjectedKeys.remove(sessionId)
        // A background job belongs to its session and ends with it.
        ShellJobs.killSession(sessionId)
        // So do its terminals.
        com.openminis.app.runtime.terminal.AgentTerminals.existing()?.closeSession(sessionId)
    }

    /** User-facing Stop. Kill the session shell; next command recreates it. Background jobs are left running on purpose. */
    fun stopCurrentCommand(sessionId: String) {
        synchronized(sessionStateLock) {
            sessionStates[sessionId]?.generation?.incrementAndGet()
        }
        shells.remove(sessionId)?.stop()
        lastInjectedKeys.remove(sessionId)
    }

    /** Stop every live session before mount-layout or rootfs changes. */
    fun stopCurrentCommand() {
        stopGeneration.incrementAndGet()
        ShellJobs.killAll()
        synchronized(sessionStateLock) {
            sessionStates.values.forEach { it.generation.incrementAndGet() }
        }
        val liveShells = shells.values.toList()
        liveShells.forEach { it.stop() }
        shells.clear()
        lastInjectedKeys.clear()
    }

    /**
     * What a command's output becomes before the model sees it: terminal escapes removed, the
     * user's environment-variable values masked when Privacy Mode is on (it is by default, and the
     * `minis-config` text promises it), then the size cap. Masking comes before truncation so a
     * value is never cut in half and left readable.
     */
    internal fun shellOutputForModel(
        raw: String,
        redact: (String) -> String = { com.openminis.app.data.EnvVarRedactor.redactIfEnabled(it).first },
    ): String = TerminalSanitizer.truncateIfNeeded(redact(TerminalSanitizer.sanitize(raw)))

    suspend fun broadcastTimezoneChange() {
        val tz = RuntimePathRegistry.posixTz()
        val env = mapOf("TZ" to tz)
        shells.forEach { (sessionId, shell) ->
            if (!shell.isAlive) return@forEach
            try {
                shell.applyEnvironment(env)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.d(TAG, "[$sessionId] timezone update failed: ${error.message}")
            }
        }
        // Interactive terminals are not updated from here: their foreground program may be an
        // editor, ssh or a stdin reader, and `export …` typed into it is input, not configuration.
        // A new terminal starts with the current values.
    }

    suspend fun broadcastProxyChange() {
        // When the optional Root loopback helper is active, it overlays the
        // same proxy names in an already-live shell. If it is unavailable,
        // explicit user variables remain and stale helper values are cleared.
        val env = envVarRepository?.allAsDict().orEmpty()
            .filterKeys { it in RootNetworkProxy.PROXY_ENV_KEYS }
            .toMutableMap().apply {
                putAll(RootNetworkProxy.proxyEnv())
            }
        shells.forEach { (sessionId, shell) ->
            if (!shell.isAlive) return@forEach
            try {
                val previous = lastInjectedKeys[sessionId].orEmpty()
                if (env.isNotEmpty() || previous.isNotEmpty()) {
                    shell.applyEnvironment(
                        env,
                        previous + RootNetworkProxy.PROXY_ENV_KEYS,
                    )
                    lastInjectedKeys[sessionId] = env.keys.toSet()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.d(TAG, "[$sessionId] proxy update failed: ${error.message}")
            }
        }
    }
}
