package com.openminis.app.runtime.guest

import android.util.Log
import com.openminis.app.runtime.ubuntu.UbuntuPaths
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.tools.ToolFailureKind

/**
 * Registry of the Android-side command handlers behind the guest CLIs (`android-*`, `minis-*`).
 *
 * The guest reaches them through [GuestCommandBridge] (loopback + per-launch token). The
 * abstract-socket listener this object used to run was the PRoot-era `native_offload` transport:
 * no current guest tool speaks it, and it accepted any local caller without authentication, so it
 * is gone. Only the registry remains.
 *
 * This bridge does not own Ubuntu process, mount-namespace, or chroot lifecycle. Those boundaries
 * belong to [UbuntuKernel] and [com.openminis.app.runtime.ExecutionCoordinator].
 */
data class NativeOffloadRequest(
    val pid: Int,
    val argv: List<String>,
    val env: Map<String, String>,
    val cwd: String,
    /**
     * T340: chat session id forwarded by the agent shell via the
     * `MINIS_CHAT_SESSION_ID` env var. Lets [OffloadPermissionManager]
     * scope ASK_ONCE grants/denials per-chat-session instead of using
     * a single process-wide bucket. Null when the offload originates
     * outside a chat (e.g. interactive terminal) — handlers fall back
     * to `OFFLOAD_GLOBAL_SESSION_ID` in that case.
     */
    val sessionId: String? = null,
    val stdin: String? = null,
)

data class NativeOffloadResult(
    val exitCode: Int,
    val output: String,
    val failureKind: ToolFailureKind? = null,
    val executionStarted: Boolean? = null,
) {
    fun toToolExecutionResult(): ToolExecutionResult = ToolExecutionResult(
        output.trimEnd(), exitCode == 0, failureKind = failureKind, executionStarted = executionStarted,
    ).classifiedAfterExecution()

    companion object {
        fun invalidArguments(output: String): NativeOffloadResult =
            NativeOffloadResult(2, output, ToolFailureKind.INVALID_ARGUMENTS, false)

        fun permissionDenied(output: String, exitCode: Int = 77): NativeOffloadResult =
            NativeOffloadResult(exitCode, output, ToolFailureKind.PERMISSION_DENIED, false)
    }
}

fun interface NativeOffloadHandler {
    fun handle(request: NativeOffloadRequest): NativeOffloadResult
}

object NativeOffloadServer {
    private const val TAG = "NativeOffloadServer"
    private const val LEGACY_REPLY_PREFIX = ".native-offload-"

    private val handlers = ConcurrentHashMap<String, NativeOffloadHandler>()

    class ShellOutcome(val executionId: String, val sessionId: String? = null) {
        @Volatile var unknownKind: ToolFailureKind? = null
            private set
        fun observe(result: NativeOffloadResult) {
            result.failureKind?.takeIf { it.outcomeUnknown }?.let { unknownKind = it }
        }
    }
    private val shellOutcomes = ConcurrentHashMap<String, ShellOutcome>()
    private class NativeExecution(val sessionId: String?, val count: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger())
    private val nativeExecutions = ConcurrentHashMap<String, NativeExecution>()
    fun beginShell(sessionId: String, executionId: String): ShellOutcome = ShellOutcome(executionId, sessionId).also {
        check(shellOutcomes.putIfAbsent(sessionId, it) == null) { "shell_execution_already_owned" }
    }
    fun currentShell(sessionId: String?): ShellOutcome? = sessionId?.let(shellOutcomes::get)
    fun endShell(sessionId: String, outcome: ShellOutcome) { shellOutcomes.remove(sessionId, outcome) }
    fun beginNative(outcome: ShellOutcome?) { if (outcome != null) nativeExecutions.compute(outcome.executionId) { _, count ->
        (count ?: NativeExecution(outcome.sessionId)).also { it.count.incrementAndGet() }
    } }
    fun endNative(outcome: ShellOutcome?) { if (outcome != null) nativeExecutions.computeIfPresent(outcome.executionId) { _, count ->
        if (count.count.decrementAndGet() == 0) null else count
    } }
    fun nativeInFlight(executionId: String): Boolean = nativeExecutions[executionId]?.count?.get()?.let { it > 0 } == true

    fun nativeInFlightForSession(sessionId: String): Boolean = nativeExecutions.values.any { it.sessionId == sessionId && it.count.get() > 0 }

    val registeredHandlers: Set<String> get() = handlers.keys.toSet()

    fun getHandler(name: String): NativeOffloadHandler? = handlers[name]

    fun register(name: String, handler: NativeOffloadHandler) {
        require(name.isNotEmpty())
        handlers[name] = handler
        Log.d(TAG, "register '$name' (total=${handlers.size})")
    }

    /**
     * Deletes the `.native-offload-*` reply files the old transport left in workspace/offloads
     * (App-owned, one per call, never removed). Nothing writes them any more, so everything with
     * the prefix is garbage. Failures are logged and ignored.
     */
    fun deleteLegacyReplyFiles() {
        val dirs = buildList {
            add(File(UbuntuPaths.hostWorkspace, "offloads"))
            File(UbuntuPaths.hostSessions).listFiles()?.forEach { add(File(it, "offloads")) }
        }
        for (dir in dirs) {
            try {
                dir.listFiles { f -> f.isFile && f.name.startsWith(LEGACY_REPLY_PREFIX) }?.forEach { it.delete() }
            } catch (e: Exception) {
                Log.w(TAG, "deleteLegacyReplyFiles(${dir.name}) failed: ${e.message}")
            }
        }
    }
}
