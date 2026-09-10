package com.openminis.app.runtime.ubuntu

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One persistent, privilege-dropped Ubuntu shell for one chat session.
 *
 * This is the chroot equivalent of upstream PersistentShell: cwd, exported
 * environment, package/user state and background processes survive between
 * commands until the session is cancelled or dies.
 */
internal class RootPersistentShell(private val sessionId: String) {
    data class CommandResult(val output: String, val exitCode: Int)

    private data class Pending(
        val marker: String,
        val output: StringBuilder,
        val lineCallback: ((String) -> Unit)?,
        val completion: CompletableDeferred<CommandResult>,
    )

    @Volatile
    private var process: Process? = null
    @Volatile
    private var writer: BufferedWriter? = null
    @Volatile
    private var pending: Pending? = null
    @Volatile
    private var launch: UbuntuKernel.Launch? = null

    /** Explicit Stop permanently closes this shell instance. */
    private val closed = AtomicBoolean(false)

    val isAlive: Boolean get() = process?.isAlive == true

    suspend fun ensureStarted() {
        if (isAlive) return
        check(!closed.get()) { "direct Ubuntu shell is stopped" }
        try {
            withContext(Dispatchers.IO) {
                if (isAlive) return@withContext
                check(!closed.get()) { "direct Ubuntu shell is stopped" }
                stopInternal()
                check(!closed.get()) { "direct Ubuntu shell is stopped" }

                val prepared = UbuntuKernel.prepareLaunch(sessionId, interactive = false)
                check(!closed.get()) { "direct Ubuntu shell was stopped during startup" }
                val spawned = ProcessBuilder(prepared.argv)
                    .redirectErrorStream(true)
                    .start()
                // Publish the owned process before checking [closed]. If Stop
                // raced between the pre-spawn check and ProcessBuilder.start(),
                // it may have seen no process; this post-spawn check then owns
                // cleanup of the newly created Root process tree.
                process = spawned
                launch = prepared
                if (closed.get()) {
                    stopInternal()
                    error("direct Ubuntu shell was stopped during startup")
                }
                writer = BufferedWriter(OutputStreamWriter(spawned.outputStream, StandardCharsets.UTF_8))
                startReader(spawned)
                // Give su/unshare/chroot a short window to fail synchronously. A
                // healthy persistent shell stays alive indefinitely.
                Thread.sleep(180)
                if (closed.get()) {
                    stopInternal()
                    error("direct Ubuntu shell was stopped during startup")
                }
                if (!spawned.isAlive) {
                    val exit = runCatching { spawned.exitValue() }.getOrNull()
                    stopInternal()
                    error("direct Ubuntu shell exited during startup${exit?.let { " (exit=$it)" }.orEmpty()}")
                }
            }
        } catch (failure: Throwable) {
            // Cancellation can be delivered when withContext returns even after
            // ProcessBuilder.start() succeeded. Always clean the Root process
            // tree before propagating the original failure/cancellation.
            stopInternal()
            throw failure
        }
    }

    suspend fun applyEnvironment(
        environment: Map<String, String>,
        previousKeys: Set<String> = emptySet(),
    ) {
        val validName = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
        val commands = mutableListOf<String>()
        for (removed in previousKeys - environment.keys) {
            if (validName.matches(removed)) commands += "unset $removed"
        }
        for ((key, value) in environment) {
            if (!validName.matches(key)) continue
            commands += "export $key=${DirectRootRunner.shellQuote(value)}"
        }
        if (commands.isNotEmpty()) {
            val result = executeCommand(commands.joinToString("\n"), timeoutMs = 30_000L)
            check(result.exitCode == 0) { "failed to update shell environment: ${result.output.take(300)}" }
        }
    }

    suspend fun executeCommand(
        command: String,
        timeoutMs: Long,
        lineCallback: ((String) -> Unit)? = null,
    ): CommandResult {
        ensureStarted()
        val currentWriter = writer ?: error("Ubuntu shell stdin is unavailable")
        check(pending == null) { "only one command may run in a persistent shell" }
        val marker = UUID.randomUUID().toString().replace("-", "")
        val completion = CompletableDeferred<CommandResult>()
        val state = Pending(marker, StringBuilder(), lineCallback, completion)
        pending = state
        try {
            withContext(Dispatchers.IO) {
                currentWriter.write(command)
                if (!command.endsWith('\n')) currentWriter.newLine()
                currentWriter.write("__minis_ec=\$?; printf '\\n__MINIS_DONE_${marker}_EXIT_%s__\\n' \"\$__minis_ec\"")
                currentWriter.newLine()
                currentWriter.flush()
            }
            return try {
                withTimeout(timeoutMs.coerceAtLeast(1L)) { completion.await() }
            } catch (_: TimeoutCancellationException) {
                stop()
                CommandResult("command timed out after ${timeoutMs}ms", 124)
            }
        } catch (cancelled: CancellationException) {
            stop()
            throw cancelled
        } finally {
            if (pending === state) pending = null
        }
    }

    fun stop() {
        closed.set(true)
        stopInternal()
    }

    private fun startReader(spawned: Process) {
        Thread({
            try {
                spawned.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        val state = pending
                        if (state == null) continue
                        val prefix = "__MINIS_DONE_${state.marker}_EXIT_"
                        if (line.startsWith(prefix) && line.endsWith("__")) {
                            val code = line.removePrefix(prefix).removeSuffix("__").toIntOrNull() ?: 1
                            state.completion.complete(
                                CommandResult(state.output.toString().trimEnd('\n'), code),
                            )
                            if (pending === state) pending = null
                        } else {
                            state.output.append(line).append('\n')
                            try {
                                state.lineCallback?.invoke(line)
                            } catch (_: Exception) {
                                // Tool-output observers must not terminate the shell reader.
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                Log.d(TAG, "[$sessionId] reader ended: ${error.message}")
            } finally {
                val state = pending
                if (state != null && !state.completion.isCompleted) {
                    val exit = runCatching { spawned.exitValue() }.getOrNull() ?: 1
                    state.completion.complete(
                        CommandResult(
                            state.output.toString().trimEnd('\n') +
                                if (state.output.isNotEmpty()) "\n[shell exited]" else "[shell exited]",
                            exit,
                        ),
                    )
                }
            }
        }, "ubuntu-shell-$sessionId").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    private fun stopInternal() {
        val current = process
        process = null
        runCatching { writer?.close() }
        writer = null
        runCatching { current?.destroy() }
        if (current?.isAlive == true) runCatching { current.destroyForcibly() }

        val prepared = launch
        launch = null
        val pid = runCatching {
            prepared?.pidFile?.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
        }.getOrNull()
        if (pid != null && pid > 1) {
            val su = DirectRootRunner.findSu()
            if (su != null) {
                runCatching {
                    val killer = ProcessBuilder(
                        su,
                        "-c",
                        "kill -TERM -$pid 2>/dev/null || kill -TERM $pid 2>/dev/null || true; " +
                            "sleep 0.05; kill -KILL -$pid 2>/dev/null || kill -KILL $pid 2>/dev/null || true",
                    ).start()
                    killer.waitFor(600, java.util.concurrent.TimeUnit.MILLISECONDS)
                    killer.destroy()
                }
            }
        }
        prepared?.pidFile?.delete()
        pending?.let { state ->
            if (!state.completion.isCompleted) {
                state.completion.complete(CommandResult("shell stopped", 130))
            }
        }
        pending = null
    }

    companion object {
        private const val TAG = "RootPersistentShell"
    }
}
