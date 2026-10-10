package com.openminis.app.offload

import com.openminis.app.integrity.ProtectedRoot
import com.openminis.app.runtime.ubuntu.DirectRootRunner
import com.openminis.app.runtime.ubuntu.RootAccess
import com.openminis.app.runtime.ubuntu.RootAccessStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Run one argv as root and collect its output.
 *
 * Two doors, one `su` launcher ([DirectRootRunner]):
 *  - [run] is for `android-root-cli`, whose handler runs synchronously on the offload IPC worker
 *    thread and takes commands from the agent. It goes through [ProtectedRoot], so the device-integrity
 *    policy and the protected view apply (Issue #182).
 *  - [exec] and [runRaw] are for commands the app builds itself (accessibility repair, restricted
 *    settings, the su liveness check); they are not subject to the policy.
 * Arguments are quoted word by word; nothing is parsed by a shell unless the caller explicitly runs
 * `sh -c`.
 */
object RootProcess {
    const val DEFAULT_TIMEOUT_MS = 5_000L

    data class Result(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    ) {
        val combined: String get() = if (stderr.isEmpty()) stdout else "$stdout\n$stderr".trimEnd()
    }

    /** True once a probe has seen su answer with uid 0 for this app. */
    fun isGranted(): Boolean = RootAccess.isGranted

    /** A definite reason root cannot be used, with the error code the guest CLIs report for it. */
    data class Unavailable(val code: String, val message: String)

    /**
     * Why a root call cannot succeed right now, or null when it is worth trying. Only definite
     * answers block: an unknown or not-yet-probed state still tries, and su itself decides.
     */
    fun unavailable(): Unavailable? = unavailableFor(RootAccess.state.value.status)

    internal fun unavailableFor(status: RootAccessStatus): Unavailable? = when (status) {
        RootAccessStatus.UNAVAILABLE -> Unavailable("SERVICE_NOT_RUNNING", "Root (su) is not available on this device")
        RootAccessStatus.DENIED -> Unavailable("PERMISSION_DENIED", "Minis is not authorized for Root")
        else -> null
    }

    fun unavailableReason(): String? = unavailable()?.message

    /** Session of the guest request being served on this thread, for the integrity audit. */
    val callerSession = ThreadLocal<String?>()

    /** Agent-originated command, subject to device protection. Never call it on the main thread. */
    fun run(argv: Array<String>, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Result =
        runBlocking(Dispatchers.IO) {
            require(argv.isNotEmpty()) { "argv must not be empty" }
            val r = ProtectedRoot.runArgv("android-root-cli", callerSession.get(), argv.toList(), timeoutMs)
            toResult(r.exitCode, r.stdout, r.stderr, r.error)
        }

    /** App-built command that must run outside the protected view (a liveness check, say). */
    fun runRaw(argv: Array<String>, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Result =
        runBlocking(Dispatchers.IO) { exec(argv.toList(), timeoutMs) }

    suspend fun exec(argv: List<String>, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Result {
        require(argv.isNotEmpty()) { "argv must not be empty" }
        val result = DirectRootRunner.runArgv(argv, timeoutMs)
        return toResult(result.exitCode, result.stdout, result.stderr, result.error)
    }

    /** The runner's own failure (no su, timeout, start error) is reported on stderr after the command's. */
    internal fun toResult(exitCode: Int, stdout: String, stderr: String, error: String?): Result {
        val err = listOf(stderr, error.orEmpty()).filter { it.isNotBlank() }.joinToString("\n")
        return Result(exitCode, stdout, err)
    }
}
