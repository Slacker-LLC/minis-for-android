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
 * This is an internal launcher for fixed Root infrastructure scripts. Policy
 * remains in the Android tool/runtime layer; guest commands are never sent
 * through this class as Root.
 */
internal object DirectRootRunner {
    private const val MAX_CAPTURE_CHARS = 1_048_576
    /** Root-owned runtime state; App-owned guest data never lives here. */
    internal const val ROOT_STATE_DIR = "/data/adb/minis/runtime"
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
            val pidFile = "$ROOT_STATE_DIR/runner-$runId.pid"
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
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach(stdout::appendLine)
                    }
                } catch (_: Exception) {
                    // Stream closure during process teardown is expected.
                }
            }, "direct-root-stdout").apply { isDaemon = true; start() }
            val errThread = Thread({
                try {
                    process.errorStream.bufferedReader().useLines { lines ->
                        lines.forEach(stderr::appendLine)
                    }
                } catch (_: Exception) {
                    // Stream closure during process teardown is expected.
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
                try {
                    process.destroy()
                } catch (_: Exception) {
                    // Best effort after group cleanup / normal process exit.
                }
            }
        }

    internal fun buildProcessGroupCommand(script: String, pidFile: String): String {
        val grouped = buildString {
            append("MINIS_DIRECT_ROOT_RUNNER=1; export MINIS_DIRECT_ROOT_RUNNER; ")
            append("umask 077; ")
            append("mkdir -p ${shellQuote(ROOT_STATE_DIR)} || exit 126; ")
            append("chmod 711 ${shellQuote(ROOT_STATE_DIR)} || exit 126; ")
            append("echo \$\$ > ${shellQuote(pidFile)} || exit 126; ")
            append("/system/bin/sh -c ${shellQuote(script)}; ")
            append("__minis_status=\$?; ")
            append("rm -f -- ${shellQuote(pidFile)}; ")
            append("exit \$__minis_status")
        }
        return "if [ -x /system/bin/setsid ]; then " +
            "exec /system/bin/setsid /system/bin/sh -c ${shellQuote(grouped)}; " +
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

    /** Best-effort cleanup for a prepared Root process tree owned by a caller. */
    internal fun cleanupProcessGroup(pidFile: File?) {
        if (pidFile == null) return
        val su = findSu()
        if (su != null) {
            runCatching {
                val killer = ProcessBuilder(
                    su,
                    "-c",
                    buildProcessGroupCleanupCommand(pidFile.absolutePath),
                ).start()
                if (!killer.waitFor(CLEANUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    killer.destroyForcibly()
                } else {
                    killer.destroy()
                }
            }
        }
        // Root owns the marker directory; this succeeds only in writable test
        // layouts, but is harmless as a fallback after Root cleanup.
        runCatching { pidFile.delete() }
    }

    /**
     * Reap process groups left behind by a previous app process. Markers live
     * in Root-owned directories, and the command-line check prevents a reused
     * PID from turning recovery into a signal to an unrelated process.
     */
    internal fun buildStaleProcessCleanupCommand(
        markerDir: String,
        markerGlob: String,
        commandNeedle: String,
        environmentVariable: String? = null,
    ): String =
        "set -eu; " +
            "DIR=${shellQuote(markerDir)}; " +
            "[ -d \"\$DIR\" ] || exit 0; " +
            "for marker in \"\$DIR\"/$markerGlob; do " +
            "[ -f \"\$marker\" ] || continue; " +
            "PID=\$(sed -n '1p' \"\$marker\" 2>/dev/null || true); " +
            "case \"\$PID\" in ''|*[!0-9]*) rm -f -- \"\$marker\"; continue;; esac; " +
            "if [ \"\$PID\" -le 1 ]; then rm -f -- \"\$marker\"; continue; fi; " +
            // The marker stores the setsid group leader, while this cleanup
            // script runs in a child shell within that same process group.
            // Comparing only against $$ would therefore let the current
            // maintenance group kill itself with SIGTERM (exit 143).
            "CURRENT_PGID=\$(awk '{print \$5}' /proc/\$\$/stat 2>/dev/null || true); " +
            "if [ \"\$PID\" -eq \"\$\$\" ] || " +
            "[ -n \"\$CURRENT_PGID\" ] && [ \"\$PID\" -eq \"\$CURRENT_PGID\" ]; then continue; fi; " +
            // A process can exit between reading its marker and inspecting
            // /proc. Read through cat so that a disappearing proc entry is a
            // benign stale-marker race, not a failed readiness check.
            "CMD=\$(cat \"/proc/\$PID/cmdline\" 2>/dev/null | tr '\\000' ' ' || true); " +
            "MATCH=0; case \"\$CMD\" in *${shellQuote(commandNeedle)}*) MATCH=1;; esac; " +
            if (environmentVariable != null) {
                "TOKEN=\$(sed -n '2p' \"\$marker\" 2>/dev/null || true); " +
                    "if [ \"\$MATCH\" -eq 0 ] && [ -n \"\$TOKEN\" ]; then " +
                    "ENV=\$(cat \"/proc/\$PID/environ\" 2>/dev/null | tr '\\000' '\\n' || true); " +
                    "if printf '%s\\n' \"\$ENV\" | grep -F -x -- ${shellQuote("$environmentVariable=")}\"\$TOKEN\" >/dev/null 2>&1; then MATCH=1; fi; fi; "
            } else {
                ""
            } +
            "if [ \"\$MATCH\" -eq 1 ]; then " +
            "kill -TERM -\$PID 2>/dev/null || kill -TERM \$PID 2>/dev/null || true; " +
            "sleep 0.05; " +
            "kill -KILL -\$PID 2>/dev/null || kill -KILL \$PID 2>/dev/null || true; fi; " +
            "rm -f -- \"\$marker\"; done"

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
        try {
            process.destroyForcibly()
        } catch (_: Exception) {
            // Best-effort fallback when Root group cleanup could not complete.
        }
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
