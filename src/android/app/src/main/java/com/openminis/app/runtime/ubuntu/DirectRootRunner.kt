package com.openminis.app.runtime.ubuntu

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Minimal Root launcher for the direct Ubuntu backend.
 *
 * This replaces minisd's transport role only. Policy remains in the Android
 * tool/runtime layer; guest commands are never sent through this class as Root.
 */
internal object DirectRootRunner {
    private const val MAX_CAPTURE_CHARS = 1_048_576
    private const val RUNNER_STATE_DIR = "/data/adb/minis/runtime"
    private const val CLEANUP_TIMEOUT_MS = 900L

    data class Result(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean = false,
        val error: String? = null,
    ) {
        val success: Boolean get() = error == null && !timedOut && exitCode == 0
    }

    private val knownSuPaths = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/data/adb/ksu/bin/su",
        "/debug_ramdisk/su",
    )

    fun findSu(): String? {
        knownSuPaths.firstOrNull { File(it).canExecute() }?.let { return it }
        return System.getenv("PATH").orEmpty()
            .split(File.pathSeparatorChar)
            .asSequence()
            .map { File(it, "su") }
            .firstOrNull { it.canExecute() }
            ?.absolutePath
    }

    suspend fun runArgv(argv: List<String>, timeoutMs: Long = 30_000L): Result {
        require(argv.isNotEmpty()) { "argv must not be empty" }
        return runScript("exec " + argv.joinToString(" ") { shellQuote(it) }, timeoutMs)
    }

    suspend fun runScript(script: String, timeoutMs: Long = 30_000L): Result =
        withContext(Dispatchers.IO) {
            val su = findSu()
                ?: return@withContext Result(126, "", "", error = "su executable not found")
            val runId = UUID.randomUUID().toString().replace("-", "")
            val pidFile = "$RUNNER_STATE_DIR/runner-$runId.pid"
            val wrappedScript = buildProcessGroupCommand(script, pidFile)
            val process = try {
                ProcessBuilder(su, "-c", wrappedScript).redirectErrorStream(false).start()
            } catch (error: Exception) {
                return@withContext Result(
                    exitCode = 126,
                    stdout = "",
                    stderr = "",
                    error = "failed to start su: ${error.message}",
                )
            }

            val stdout = BoundedText(MAX_CAPTURE_CHARS)
            val stderr = BoundedText(MAX_CAPTURE_CHARS)
            val outThread = Thread({
                runCatching {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach(stdout::appendLine)
                    }
                }
            }, "direct-root-stdout").apply { isDaemon = true; start() }
            val errThread = Thread({
                runCatching {
                    process.errorStream.bufferedReader().useLines { lines ->
                        lines.forEach(stderr::appendLine)
                    }
                }
            }, "direct-root-stderr").apply { isDaemon = true; start() }

            try {
                val deadline = System.nanoTime() +
                    TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(1L))
                var finished = false
                while (!finished && System.nanoTime() < deadline) {
                    currentCoroutineContext().ensureActive()
                    finished = process.waitFor(100, TimeUnit.MILLISECONDS)
                }
                if (!finished) {
                    terminateProcessGroup(su, pidFile, process)
                    process.waitFor(1_000, TimeUnit.MILLISECONDS)
                    outThread.join(1_000)
                    errThread.join(1_000)
                    return@withContext Result(
                        exitCode = 124,
                        stdout = stdout.value(),
                        stderr = stderr.value(),
                        timedOut = true,
                        error = "Root command timed out after ${timeoutMs}ms",
                    )
                }
                outThread.join(1_000)
                errThread.join(1_000)
                Result(
                    exitCode = process.exitValue(),
                    stdout = stdout.value(),
                    stderr = stderr.value(),
                )
            } catch (cancelled: CancellationException) {
                terminateProcessGroup(su, pidFile, process)
                throw cancelled
            } catch (error: Exception) {
                terminateProcessGroup(su, pidFile, process)
                Result(
                    exitCode = 126,
                    stdout = stdout.value(),
                    stderr = stderr.value(),
                    error = error.message ?: error::class.java.simpleName,
                )
            } finally {
                runCatching { process.destroy() }
            }
        }

    internal fun buildProcessGroupCommand(script: String, pidFile: String): String {
        val grouped = buildString {
            append("umask 077; ")
            append("mkdir -p ${shellQuote(RUNNER_STATE_DIR)} || exit 126; ")
            append("echo \$\$ > ${shellQuote(pidFile)} || exit 126; ")
            append("/system/bin/sh -c ${shellQuote(script)}; ")
            append("__minis_status=\$?; ")
            append("rm -f -- ${shellQuote(pidFile)}; ")
            append("exit \$__minis_status")
        }
        return "if command -v setsid >/dev/null 2>&1; then " +
            "exec setsid /system/bin/sh -c ${shellQuote(grouped)}; " +
            "else echo 'setsid is required for isolated Root maintenance' >&2; exit 125; fi"
    }

    internal fun buildProcessGroupCleanupCommand(pidFile: String): String =
        "PID=\$(cat ${shellQuote(pidFile)} 2>/dev/null || true); " +
            "case \"\$PID\" in ''|*[!0-9]*) ;; *) " +
            "if [ \"\$PID\" -gt 1 ]; then " +
            "kill -TERM -\$PID 2>/dev/null || kill -TERM \$PID 2>/dev/null || true; " +
            "sleep 0.05; " +
            "kill -KILL -\$PID 2>/dev/null || kill -KILL \$PID 2>/dev/null || true; " +
            "fi ;; esac; rm -f -- ${shellQuote(pidFile)}"

    private fun terminateProcessGroup(su: String, pidFile: String, process: Process) {
        try {
            val killer = ProcessBuilder(
                su,
                "-c",
                buildProcessGroupCleanupCommand(pidFile),
            ).redirectErrorStream(true).start()
            if (!killer.waitFor(CLEANUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                killer.destroyForcibly()
            } else {
                killer.destroy()
            }
        } catch (_: Exception) {
            // Fall through to killing the directly owned launcher process.
        }
        runCatching { process.destroyForcibly() }
    }

    fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private class BoundedText(private val maxChars: Int) {
        private val text = StringBuilder()

        @Synchronized
        fun appendLine(line: String) {
            if (text.length >= maxChars) return
            val remaining = maxChars - text.length
            if (remaining <= 0) return
            text.append(line.take((remaining - 1).coerceAtLeast(0)))
            if (text.length < maxChars) text.append('\n')
        }

        @Synchronized
        fun value(): String = text.toString().trimEnd()
    }
}
