package com.openminis.app.runtime.ubuntu

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.io.Reader
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded facts retained when a persistent guest shell exits unexpectedly. */
internal data class ShellExitDiagnostics(
    val exitCode: Int,
    val signal: String?,
    val durationMs: Long,
    val capturedOutput: String,
    val readerError: String? = null,
)

/** Unix shells conventionally report signal deaths as 128 + signal number. */
internal object ShellExitClassifier {
    private val signalNames = mapOf(
        1 to "SIGHUP",
        2 to "SIGINT",
        3 to "SIGQUIT",
        6 to "SIGABRT",
        9 to "SIGKILL",
        11 to "SIGSEGV",
        13 to "SIGPIPE",
        15 to "SIGTERM",
        24 to "SIGXCPU",
        31 to "SIGSYS",
    )

    fun signalName(exitCode: Int): String? {
        val number = exitCode - 128
        return if (number in 1..64) signalNames[number] ?: "SIG$number" else null
    }
}

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
        val output: BoundedCommandOutput,
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
    @Volatile
    private var startedAtMs: Long = 0L
    @Volatile
    private var lastDeath: ShellExitDiagnostics? = null

    /** Explicit Stop permanently closes this shell instance. */
    private val closed = AtomicBoolean(false)
    /** Serializes command writes with environment broadcasts on this shell. */
    private val commandLock = Mutex()

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

                val prepared = UbuntuRuntime.prepareLaunch(sessionId, interactive = false)
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
                startedAtMs = System.currentTimeMillis()
                lastDeath = null
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
                    val signal = exit?.let(ShellExitClassifier::signalName)
                    error(
                        "direct Ubuntu shell exited during startup" +
                            exit?.let {
                                " (exit=$it" + signal?.let { name -> ", signal=$name" }.orEmpty() + ")"
                            }.orEmpty(),
                    )
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
    ): CommandResult = commandLock.withLock {
        ensureStarted()
        val currentWriter = writer ?: error("Ubuntu shell stdin is unavailable")
        check(pending == null) { "only one command may run in a persistent shell" }
        val marker = UUID.randomUUID().toString().replace("-", "")
        val completion = CompletableDeferred<CommandResult>()
        val state = Pending(
            marker = marker,
            output = BoundedCommandOutput(MAX_CAPTURE_CHARS),
            lineCallback = lineCallback,
            completion = completion,
        )
        pending = state
        try {
            withContext(Dispatchers.IO) {
                currentWriter.write(command)
                if (!command.endsWith('\n')) currentWriter.newLine()
                currentWriter.write("__minis_ec=\$?; printf '\\n__MINIS_DONE_${marker}_EXIT_%s__\\n' \"\$__minis_ec\"")
                currentWriter.newLine()
                currentWriter.flush()
            }
            val result = withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) { completion.await() }
            if (result != null) return@withLock result
            stop()
            CommandResult("command timed out after ${timeoutMs}ms", 124)
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

    /**
     * Returns the last bounded unexpected-exit record, if any. The record is
     * intentionally kept in memory only and is replaced when this shell is
     * started again; callers must not treat it as durable log storage.
     */
    internal fun lastDeathDiagnostics(): ShellExitDiagnostics? = lastDeath

    private fun startReader(spawned: Process) {
        Thread({
            val deathCapture = BoundedCommandOutput(MAX_DEATH_CAPTURE_CHARS)
            var readerError: String? = null
            try {
                spawned.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                    val lines = BoundedLineReader(reader, MAX_LINE_CHARS)
                    while (true) {
                        val read = lines.readLine() ?: break
                        deathCapture.appendLine(read.text)
                        val state = pending
                        if (state == null) continue
                        val line = read.text
                        val prefix = "__MINIS_DONE_${state.marker}_EXIT_"
                        if (!read.truncated && line.startsWith(prefix) && line.endsWith("__")) {
                            val code = line.removePrefix(prefix).removeSuffix("__").toIntOrNull() ?: 1
                            state.completion.complete(
                                CommandResult(state.output.value().trimEnd('\n'), code),
                            )
                            if (pending === state) pending = null
                        } else {
                            state.output.appendLine(line)
                            try {
                                state.lineCallback?.invoke(line)
                            } catch (_: Exception) {
                                // Tool-output observers must not terminate the shell reader.
                            }
                            if (read.truncated) {
                                val notice = "[... ${read.omittedChars} characters omitted from overlong line ...]"
                                state.output.appendLine(notice)
                                try {
                                    state.lineCallback?.invoke(notice)
                                } catch (_: Exception) {
                                    // Tool-output observers must not terminate the shell reader.
                                }
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                readerError = error.message ?: error::class.java.simpleName
                Log.d(TAG, "[$sessionId] reader ended: $readerError")
            } finally {
                val exit = runCatching { spawned.exitValue() }.getOrNull() ?: 1
                val diagnostics = ShellExitDiagnostics(
                    exitCode = exit,
                    signal = ShellExitClassifier.signalName(exit),
                    durationMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L),
                    capturedOutput = deathCapture.value().trimEnd('\n'),
                    readerError = readerError,
                )
                val unexpectedExit = process === spawned && !closed.get()
                if (unexpectedExit) lastDeath = diagnostics
                // If Stop already detached this process, its exit is expected;
                // only unexpected exits are emitted as warnings. The bounded
                // head/tail remains available to the owning coordinator for a
                // later diagnostic without dumping guest output into Logcat.
                if (unexpectedExit) {
                    Log.w(
                        TAG,
                        "[$sessionId] Ubuntu shell exited unexpectedly " +
                            "exit=${diagnostics.exitCode} " +
                            "signal=${diagnostics.signal ?: "none"} " +
                            "durationMs=${diagnostics.durationMs} " +
                            "captureChars=${diagnostics.capturedOutput.length}" +
                            diagnostics.readerError?.let { " readerError=$it" }.orEmpty(),
                    )
                }
                val state = pending
                if (state != null && !state.completion.isCompleted) {
                    val captured = state.output.value().trimEnd('\n')
                    val exitDetail = buildString {
                        append("[shell exited: exit=")
                        append(diagnostics.exitCode)
                        diagnostics.signal?.let {
                            append(", signal=")
                            append(it)
                        }
                        append("]")
                    }
                    state.completion.complete(
                        CommandResult(
                            captured + if (captured.isNotEmpty()) "\n$exitDetail" else exitDetail,
                            diagnostics.exitCode,
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
        DirectRootRunner.cleanupProcessGroup(prepared?.pidFile)
        pending?.let { state ->
            if (!state.completion.isCompleted) {
                state.completion.complete(CommandResult("shell stopped", 130))
            }
        }
        pending = null
    }

    companion object {
        private const val TAG = "RootPersistentShell"
        internal const val MAX_CAPTURE_CHARS = 100_000
        internal const val MAX_LINE_CHARS = 100_000
        private const val MAX_DEATH_CAPTURE_CHARS = 8_192
    }
}

/**
 * Retains a bounded head and tail while the reader continues draining stdout.
 * This prevents a high-volume guest command from growing the Android heap until
 * its timeout, while preserving the most useful beginning and ending output.
 */
internal class BoundedCommandOutput(private val maxChars: Int) {
    init {
        require(maxChars >= 2) { "maxChars must be at least 2" }
    }

    private val headLimit = maxChars / 2
    private val tailLimit = maxChars - headLimit
    private val head = StringBuilder(headLimit)
    private val tail = ArrayDeque<String>()
    private var tailChars = 0
    private var totalChars = 0L

    fun appendLine(line: String) {
        append(line)
        append("\n")
    }

    private fun append(value: String) {
        if (value.isEmpty()) return
        totalChars += value.length
        var offset = 0
        if (head.length < headLimit) {
            val count = minOf(headLimit - head.length, value.length)
            head.append(value, 0, count)
            offset = count
        }
        if (offset >= value.length) return

        var suffix = value.substring(offset)
        if (suffix.length >= tailLimit) {
            suffix = suffix.takeLast(tailLimit)
            tail.clear()
            tail.addLast(suffix)
            tailChars = suffix.length
            return
        }
        tail.addLast(suffix)
        tailChars += suffix.length
        while (tailChars > tailLimit && tail.isNotEmpty()) {
            val first = tail.removeFirst()
            val excess = tailChars - tailLimit
            if (first.length <= excess) {
                tailChars -= first.length
            } else {
                val kept = first.substring(excess)
                tail.addFirst(kept)
                tailChars -= excess
            }
        }
    }

    fun value(): String {
        val omitted = (totalChars - head.length - tailChars).coerceAtLeast(0L)
        return buildString(head.length + tailChars + 64) {
            append(head)
            if (omitted > 0L) {
                if (isNotEmpty() && last() != '\n') append('\n')
                append("[... ")
                append(omitted)
                append(" characters omitted ...]\n")
            }
            tail.forEach { this.append(it) }
        }
    }
}

internal data class BoundedLine(
    val text: String,
    val omittedChars: Long,
) {
    val truncated: Boolean get() = omittedChars > 0L
}

/** Chunked line reader with an explicit per-line allocation ceiling. */
internal class BoundedLineReader(
    private val source: Reader,
    private val maxChars: Int,
) {
    init {
        require(maxChars > 0) { "maxChars must be positive" }
    }

    private val buffer = CharArray(8 * 1024)
    private var offset = 0
    private var available = 0
    private var eof = false

    fun readLine(): BoundedLine? {
        if (eof && offset >= available) return null
        val out = StringBuilder(minOf(maxChars, 1024))
        var omitted = 0L
        var sawAny = false
        while (true) {
            if (offset >= available) {
                val count = source.read(buffer)
                if (count < 0) {
                    eof = true
                    if (!sawAny) return null
                    return BoundedLine(stripTrailingCr(out), omitted)
                }
                if (count == 0) continue
                offset = 0
                available = count
            }

            val ch = buffer[offset++]
            sawAny = true
            if (ch == '\n') {
                return BoundedLine(stripTrailingCr(out), omitted)
            }
            if (out.length < maxChars) {
                out.append(ch)
            } else {
                omitted++
            }
        }
    }

    private fun stripTrailingCr(value: StringBuilder): String {
        if (value.isNotEmpty() && value.last() == '\r') {
            value.setLength(value.length - 1)
        }
        return value.toString()
    }
}
