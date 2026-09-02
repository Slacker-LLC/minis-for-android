package com.openminis.app.tools.android

import android.content.Context
import android.util.Log
import com.openminis.app.runtime.minisd.MinisdClient
import com.openminis.app.runtime.minisd.MinisdProtocol
import com.openminis.app.runtime.minisd.MinisdResponse
import com.openminis.app.runtime.ubuntu.UbuntuRuntime
import com.openminis.app.tools.ApprovalSeam
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** Backend selected for one privileged Android command. */
enum class PrivilegedBackend { ROOT, SHIZUKU, NONE }

/** Risk of one privileged command, used to keep mutation approval explicit. */
enum class CommandRisk { READ_ONLY, USER_VISIBLE, MUTATING, ROOT_SETUP }

/** Result of one argv-based Android command. */
data class AndroidCommandResult(
    val backend: PrivilegedBackend,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    val unavailableReason: String? = null,
) {
    val success: Boolean get() = exitCode == 0 && unavailableReason == null
}

/** Actual identity and kernel-policy facts returned by an active `su` probe. */
data class RootProbeResult(
    val authorized: Boolean,
    val effectiveUid: Int? = null,
    val effectiveGid: Int? = null,
    val groups: List<String> = emptyList(),
    val effectiveCapabilitiesHex: String? = null,
    val selinuxContext: String? = null,
    val selinuxMode: String? = null,
    val error: String? = null,
) {
    fun hasCapability(bit: Int): Boolean = LinuxCapabilityParser.hasBit(effectiveCapabilitiesHex, bit)
}

/** Pure parsers for `id`, `/proc/self/status`, and SELinux probe output. */
object RootProbeParser {
    private val uidRegex = Regex("""uid=(\d+)(?:\(([^)]*)\))?""")
    private val gidRegex = Regex("""gid=(\d+)(?:\(([^)]*)\))?""")
    private val groupsRegex = Regex("""groups=([^\n]+)""")
    private val capRegex = Regex("""(?m)^CapEff:\s*([0-9a-fA-F]+)\s*$""")
    private val contextRegex = Regex("""(?m)^__CONTEXT__\s*\n([^\n]+)""")
    private val modeRegex = Regex("""(?m)^__MODE__\s*\n([^\n]+)""")

    fun parse(stdout: String, exitCode: Int, stderr: String = ""): RootProbeResult {
        val uid = uidRegex.find(stdout)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val gid = gidRegex.find(stdout)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val groups = groupsRegex.find(stdout)?.groupValues?.getOrNull(1)
            ?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
        val cap = capRegex.find(stdout)?.groupValues?.getOrNull(1)?.lowercase()
        val context = contextRegex.find(stdout)?.groupValues?.getOrNull(1)?.trim()
            ?.takeUnless { it.isBlank() || it.equals("unknown", true) }
        val mode = modeRegex.find(stdout)?.groupValues?.getOrNull(1)?.trim()
            ?.takeUnless { it.isBlank() || it.equals("unknown", true) }
        val authorized = exitCode == 0 && uid == 0
        val error = when {
            authorized -> null
            stderr.isNotBlank() -> stderr.trim().take(500)
            exitCode != 0 -> "su probe exited with code $exitCode"
            uid != 0 -> "su returned effective uid=${uid ?: "unknown"}, not uid 0"
            else -> "root authorization was not established"
        }
        return RootProbeResult(
            authorized = authorized,
            effectiveUid = uid,
            effectiveGid = gid,
            groups = groups,
            effectiveCapabilitiesHex = cap,
            selinuxContext = context,
            selinuxMode = mode,
            error = error,
        )
    }
}

/** Linux effective-capability bit parser; never equates uid 0 with all bits. */
object LinuxCapabilityParser {
    fun hasBit(hex: String?, bit: Int): Boolean {
        if (hex.isNullOrBlank() || bit !in 0..63) return false
        return runCatching {
            val value = java.lang.Long.parseUnsignedLong(hex, 16)
            (value and (1L shl bit)) != 0L
        }.getOrDefault(false)
    }
}

/** Passive Root discovery and the last broker-backed probe result. */
object RootCommandRunner {
    private val knownPaths = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
        "/data/adb/ksu/bin/su", "/debug_ramdisk/su",
    )

    @Volatile private var lastProbe: RootProbeResult? = null

    fun passiveSuPath(): String? {
        knownPaths.firstOrNull { File(it).canExecute() }?.let { return it }
        val path = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
            .map { File(it, "su") }.firstOrNull { it.canExecute() }
        return path?.absolutePath
    }

    fun cachedProbe(): RootProbeResult? = lastProbe

    internal fun updateProbe(probe: RootProbeResult) {
        lastProbe = probe
    }
}

/** App-UID process fallback for public commands such as logcat; never invokes `su`. */
internal object AppCommandRunner {
    private const val MAX_CAPTURE_CHARS = 1_048_576

    suspend fun run(argv: List<String>, timeoutMs: Long): AndroidCommandResult = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder(argv).start()
            val stdout = BoundedText(MAX_CAPTURE_CHARS)
            val stderr = BoundedText(MAX_CAPTURE_CHARS)
            val outThread = Thread {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { stdout.appendLine(it) }
                }
            }
            val errThread = Thread {
                process.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { stderr.appendLine(it) }
                }
            }
            outThread.isDaemon = true
            errThread.isDaemon = true
            outThread.start()
            errThread.start()
            val exited = process.waitFor(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
            if (!exited) process.destroyForcibly()
            outThread.join(2_000L)
            errThread.join(2_000L)
            AndroidCommandResult(
                backend = PrivilegedBackend.NONE,
                exitCode = if (exited) process.exitValue() else 124,
                stdout = stdout.value(),
                stderr = stderr.value(),
                timedOut = !exited,
            )
        } catch (t: Throwable) {
            AndroidCommandResult(PrivilegedBackend.NONE, -1, "", t.message.orEmpty())
        }
    }

    private class BoundedText(private val maxChars: Int) {
        private val value = StringBuilder()

        @Synchronized
        fun appendLine(line: String) {
            if (value.length >= maxChars) return
            val remaining = maxChars - value.length
            value.append(line.take((remaining - 1).coerceAtLeast(0)))
            if (remaining > 0) value.append('\n')
        }

        @Synchronized
        fun value(): String = value.toString().trimEnd()
    }
}

/** Unified seam only for operations that genuinely need shell privilege. */
object PrivilegedCommandRunner {
    private const val TAG = "PrivilegedCommand"

    suspend fun run(
        context: Context,
        sessionId: String,
        argv: List<String>,
        operation: String,
        risk: CommandRisk = CommandRisk.READ_ONLY,
        timeoutMs: Long = 30_000L,
        rootOnly: Boolean = false,
    ): AndroidCommandResult {
        require(argv.isNotEmpty()) { "privileged command argv must not be empty" }
        val mode = PrivilegedAccessModeStore.get(context)
        val tool = argv.first()
        val commandArgs = argv.drop(1)
        val executionId = MinisdClient.newExecutionId("root")
        Log.i(TAG, "dispatch mode=${mode.wireValue} operation=$operation tool=$tool executionId=$executionId")

        if (!UbuntuRuntime.isInitialized) UbuntuRuntime.init(context)
        val broker = UbuntuRuntime.ensureBrokerReady()
        if (!broker.ok) return broker.toCommandResult(rootOnly)

        return try {
            when (mode) {
                PrivilegedAccessMode.STANDARD -> {
                    val standard = UbuntuRuntime.client.rootExec(
                        tool = tool,
                        args = commandArgs,
                        timeoutMs = timeoutMs,
                        executionId = executionId,
                    )
                    if (standard.code != MinisdProtocol.ERROR_POLICY_DENIED) {
                        standard.toCommandResult(rootOnly)
                    } else {
                        executeFull(
                            context = context,
                            sessionId = sessionId,
                            tool = tool,
                            args = commandArgs,
                            operation = operation,
                            risk = risk,
                            timeoutMs = timeoutMs,
                            executionId = executionId,
                            requireUserApproval = true,
                            rootOnly = rootOnly,
                        )
                    }
                }
                PrivilegedAccessMode.FULL_ACCESS -> executeFull(
                    context = context,
                    sessionId = sessionId,
                    tool = tool,
                    args = commandArgs,
                    operation = operation,
                    risk = risk,
                    timeoutMs = timeoutMs,
                    executionId = executionId,
                    requireUserApproval = false,
                    rootOnly = rootOnly,
                )
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                runCatching { UbuntuRuntime.client.cancelExecution(executionId) }
            }
            throw cancelled
        }
    }

    /** Explicit root authorization probe. Never called by passive capability reads. */
    suspend fun requestActiveRootProbe(context: Context, sessionId: String): RootProbeResult {
        if (RootCommandRunner.passiveSuPath() == null) {
            return RootProbeResult(false, error = "su executable not found")
        }
        val decision = ApprovalSeam.request(
            context,
            sessionId,
            "android_capabilities",
            "主动请求 Root 授权并读取 uid/gid/capabilities/SELinux 状态",
        )
        if (decision.decision != "allowed-once") {
            return RootProbeResult(false, error = "root probe was not approved (${decision.decision})")
        }
        if (!UbuntuRuntime.isInitialized) UbuntuRuntime.init(context)
        val broker = UbuntuRuntime.ensureBrokerReady()
        val probe = if (broker.ok) {
            UbuntuRuntime.client.rootProbe().toRootProbe()
        } else {
            RootProbeResult(
                authorized = false,
                error = "${broker.error?.code ?: MinisdProtocol.ERROR_RUNTIME_UNAVAILABLE}: " +
                    (broker.error?.detail ?: "minisd broker unavailable"),
            )
        }
        RootCommandRunner.updateProbe(probe)
        return probe
    }

    private suspend fun executeFull(
        context: Context,
        sessionId: String,
        tool: String,
        args: List<String>,
        operation: String,
        risk: CommandRisk,
        timeoutMs: Long,
        executionId: String,
        requireUserApproval: Boolean,
        rootOnly: Boolean,
    ): AndroidCommandResult {
        val challenged = UbuntuRuntime.client.rootFullExec(
            tool = tool,
            args = args,
            timeoutMs = timeoutMs,
            executionId = executionId,
        )
        if (challenged.code != MinisdProtocol.ERROR_CONFIRM_REQUIRED) {
            return challenged.toCommandResult(rootOnly)
        }
        val confirmId = challenged.error?.confirmId
            ?: return AndroidCommandResult(
                PrivilegedBackend.NONE,
                126,
                "",
                "",
                unavailableReason = "CONFIRM_REQUIRED response omitted confirm_id",
            )
        if (requireUserApproval) {
            val decision = ApprovalSeam.request(
                context,
                sessionId,
                "android_privileged",
                "$operation (${risk.name.lowercase()})\n\ntool=$tool\nargs=${args.joinToString(prefix = "[", postfix = "]")}",
            )
            if (decision.decision != "allowed-once") {
                return AndroidCommandResult(
                    PrivilegedBackend.NONE,
                    126,
                    "",
                    "",
                    unavailableReason = "operation was not approved (${decision.decision})",
                )
            }
        }
        return UbuntuRuntime.client.rootFullExec(
            tool = tool,
            args = args,
            timeoutMs = timeoutMs,
            confirmId = confirmId,
            executionId = executionId,
        ).toCommandResult(rootOnly)
    }

    private fun MinisdResponse.toCommandResult(rootOnly: Boolean): AndroidCommandResult {
        if (!ok) {
            val code = error?.code ?: MinisdProtocol.ERROR_RUNTIME_UNAVAILABLE
            return AndroidCommandResult(
                backend = PrivilegedBackend.NONE,
                exitCode = 126,
                stdout = "",
                stderr = "",
                timedOut = code == "TOOL_TIMEOUT" || code == "TIMEOUT",
                unavailableReason = "$code: ${error?.detail ?: if (rootOnly) "required Root capability is unavailable" else "Root execution failed"}",
            )
        }
        val payload = result
            ?: return AndroidCommandResult(
                PrivilegedBackend.NONE,
                126,
                "",
                "",
                unavailableReason = "malformed minisd Root response",
            )
        return AndroidCommandResult(
            backend = PrivilegedBackend.ROOT,
            exitCode = payload.optInt("exit_code", 1),
            stdout = payload.optString("stdout"),
            stderr = payload.optString("stderr"),
        )
    }

    private fun MinisdResponse.toRootProbe(): RootProbeResult {
        if (!ok) {
            return RootProbeResult(
                authorized = false,
                error = "${error?.code ?: MinisdProtocol.ERROR_RUNTIME_UNAVAILABLE}: ${error?.detail ?: "Root probe failed"}",
            )
        }
        val payload = result ?: return RootProbeResult(false, error = "malformed minisd root.probe response")
        val groupsJson = payload.optJSONArray("groups")
        val groups = buildList {
            if (groupsJson != null) {
                for (index in 0 until groupsJson.length()) add(groupsJson.optInt(index).toString())
            }
        }
        val uid = payload.optInt("uid", -1).takeIf { it >= 0 }
        val selinuxEnforcing = if (payload.has("enforcing") && !payload.isNull("enforcing")) {
            payload.optBoolean("enforcing")
        } else {
            null
        }
        return RootProbeResult(
            authorized = uid == 0,
            effectiveUid = uid,
            effectiveGid = payload.optInt("gid", -1).takeIf { it >= 0 },
            groups = groups,
            effectiveCapabilitiesHex = payload.optString("capEff").ifBlank { null },
            selinuxContext = payload.optString("selinux").ifBlank { null },
            selinuxMode = selinuxEnforcing?.let { if (it) "Enforcing" else "Permissive" },
            error = if (uid == 0) null else "minisd broker is not running as uid 0",
        )
    }
}
