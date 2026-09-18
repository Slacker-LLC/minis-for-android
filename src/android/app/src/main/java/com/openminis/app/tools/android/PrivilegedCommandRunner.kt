package com.openminis.app.tools.android

import android.content.Context
import android.util.Log
import com.openminis.app.runtime.ubuntu.DirectRootRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Backend selected for one privileged Android command. */
enum class PrivilegedBackend { ROOT, SHIZUKU, NONE }

/** Risk classification retained for audit logging and conservative command analysis. */
enum class CommandRisk(val severity: Int) {
    READ_ONLY(0),
    USER_VISIBLE(1),
    MUTATING(2),
    ROOT_SETUP(3),
    ;

    companion object {
        fun max(first: CommandRisk, second: CommandRisk): CommandRisk =
            if (first.severity >= second.severity) first else second
    }
}

/**
 * Conservative classification for the generic local `root.shell` seam.
 * Product-owned handlers still declare their known risk, while arbitrary
 * tools/arguments must never be able to self-label as read-only.
 */
internal object PrivilegedCommandRisk {
    private val packageMutations = setOf(
        "install", "uninstall", "clear", "disable", "enable", "grant", "revoke",
        "suspend", "unsuspend", "trim-caches", "move-package", "set-installer",
        "install-create", "install-write", "install-commit", "install-abandon",
        "create-user", "remove-user", "set-user-restriction", "set-home-activity",
    )
    private val packageReads = setOf(
        "list", "path", "dump", "resolve-activity", "query-activities", "has-feature",
    )
    private val settingsMutations = setOf("put", "delete", "reset")
    private val settingsReads = setOf("get", "list")
    private val safeDumpsysTopics = setOf(
        "activity", "cpuinfo", "display", "gfxinfo", "input_method", "meminfo",
        "package", "procstats", "surfaceflinger", "window",
    )

    fun classify(tool: String, args: List<String>): CommandRisk {
        return when (tool.substringAfterLast('/').lowercase()) {
            "getprop", "pidof", "ps" -> CommandRisk.READ_ONLY
            "logcat" -> if (args.any { it == "-c" || it == "--clear" }) {
                CommandRisk.MUTATING
            } else {
                CommandRisk.READ_ONLY
            }
            "pm" -> classifyKnownReadOrMutation(args, packageReads, packageMutations)
            "settings" -> classifyKnownReadOrMutation(args, settingsReads, settingsMutations)
            "dumpsys" -> if (args.firstOrNull()?.lowercase()?.let { it in safeDumpsysTopics } == true) {
                CommandRisk.READ_ONLY
            } else {
                CommandRisk.MUTATING
            }
            "am", "input", "monkey" -> CommandRisk.USER_VISIBLE
            "mount", "umount" -> CommandRisk.ROOT_SETUP
            "sh", "su", "toybox", "busybox" -> CommandRisk.ROOT_SETUP
            "cmd" -> classifyCmd(args)
            else -> CommandRisk.MUTATING
        }
    }

    private fun classifyCmd(args: List<String>): CommandRisk {
        if (args.firstOrNull()?.lowercase() == "package" &&
            args.getOrNull(1)?.lowercase()?.let {
                it in setOf(
                    "dump", "path", "resolve-activity", "query-activities",
                )
            } == true
        ) {
            return CommandRisk.READ_ONLY
        }
        return CommandRisk.MUTATING
    }

    private fun classifyKnownReadOrMutation(
        args: List<String>,
        reads: Set<String>,
        mutations: Set<String>,
    ): CommandRisk {
        if (args.any { it.lowercase() in mutations }) return CommandRisk.MUTATING
        return if (args.any { it.lowercase() in reads }) {
            CommandRisk.READ_ONLY
        } else {
            CommandRisk.MUTATING
        }
    }
}

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

/** Explicit Root lifecycle states used by capability reporting and probes. */
enum class RootAccessState {
    SU_NOT_FOUND,
    AUTHORIZATION_REQUIRED,
    PROBING,
    AUTHORIZED,
    AUTHORIZATION_FAILED,
}

data class RootAccessSnapshot(
    val state: RootAccessState,
    val suPath: String?,
    val probe: RootProbeResult?,
)

/** Pure state transition logic; passive discovery never starts or retries su. */
object RootAccessStateResolver {
    fun resolve(
        suPath: String?,
        probe: RootProbeResult?,
        probing: Boolean = false,
    ): RootAccessState = when {
        suPath.isNullOrBlank() -> RootAccessState.SU_NOT_FOUND
        probing -> RootAccessState.PROBING
        probe?.authorized == true -> RootAccessState.AUTHORIZED
        probe != null -> RootAccessState.AUTHORIZATION_FAILED
        else -> RootAccessState.AUTHORIZATION_REQUIRED
    }
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

/** Passive Root discovery and the last probe result. */
object RootCommandRunner {
    private val knownPaths = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
        "/data/adb/ksu/bin/su", "/debug_ramdisk/su",
    )

    @Volatile private var lastProbe: RootProbeResult? = null
    private val probeInFlight = AtomicBoolean(false)

    fun passiveSuPath(): String? {
        knownPaths.firstOrNull { File(it).canExecute() }?.let { return it }
        val path = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
            .map { File(it, "su") }.firstOrNull { it.canExecute() }
        return path?.absolutePath
    }

    fun cachedProbe(): RootProbeResult? = lastProbe

    fun accessState(): RootAccessState = RootAccessStateResolver.resolve(
        suPath = passiveSuPath(),
        probe = lastProbe,
        probing = probeInFlight.get(),
    )

    fun snapshot(): RootAccessSnapshot {
        val suPath = passiveSuPath()
        return RootAccessSnapshot(
            state = RootAccessStateResolver.resolve(suPath, lastProbe, probeInFlight.get()),
            suPath = suPath,
            probe = lastProbe,
        )
    }

    internal fun updateProbe(probe: RootProbeResult) {
        lastProbe = probe
    }

    internal fun beginProbe() {
        probeInFlight.set(true)
    }

    internal fun endProbe() {
        probeInFlight.set(false)
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

/** Unified seam for operations that genuinely need shell privilege. */
object PrivilegedCommandRunner {
    private const val TAG = "PrivilegedCommand"
    /** Match the upstream structured Root argv contract: 32 command args. */
    internal const val MAX_ROOT_ARGS = 32
    internal const val MAX_ROOT_ARG_BYTES = 4096
    private val trustedToolPrefixes = listOf(
        "/system/bin/",
        "/system/xbin/",
        "/vendor/bin/",
    )

    /**
     * Validate the part of the Root contract that is independent of the
     * device filesystem. Keeping this separate makes the negative cases
     * testable without pretending a JVM host has Android's /system tree.
     */
    internal fun validateRootArgv(argv: List<String>): String? {
        if (argv.isEmpty()) return "argv must not be empty"
        val tool = argv.first()
        if (tool.isEmpty()) return "tool is required"
        if (tool.contains('/')) return "tool must be an executable basename"
        if (tool.contains('\u0000')) return "tool contains NUL"
        if (argv.drop(1).size > MAX_ROOT_ARGS) return "too many arguments"
        argv.drop(1).forEachIndexed { index, arg ->
            if (arg.contains('\u0000')) return "args[$index] contains NUL"
            if (arg.toByteArray(Charsets.UTF_8).size > MAX_ROOT_ARG_BYTES) {
                return "args[$index] exceeds $MAX_ROOT_ARG_BYTES bytes"
            }
        }
        return null
    }

    /** Resolve only a basename from trusted Android system executable trees. */
    internal fun resolveTrustedToolPath(tool: String): String? {
        if (tool.isEmpty() || tool.contains('/') || tool.contains('\u0000')) return null
        return trustedToolPrefixes
            .asSequence()
            .map { "$it$tool" }
            .map(::File)
            .firstOrNull { it.isFile && it.canExecute() }
            ?.absolutePath
    }

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
        val tool = argv.first()
        val commandArgs = argv.drop(1)
        validateRootArgv(argv)?.let { detail ->
            return AndroidCommandResult(
                backend = PrivilegedBackend.NONE,
                exitCode = 126,
                stdout = "",
                stderr = "",
                unavailableReason = "BAD_PARAMS: $detail",
            )
        }
        val resolvedTool = resolveTrustedToolPath(tool)
            ?: return AndroidCommandResult(
                backend = PrivilegedBackend.NONE,
                exitCode = 126,
                stdout = "",
                stderr = "",
                unavailableReason = "BAD_PARAMS: trusted Android Root tool not found: $tool",
            )
        val effectiveRisk = CommandRisk.max(risk, PrivilegedCommandRisk.classify(tool, commandArgs))
        Log.i(
            TAG,
            "direct-root risk=${effectiveRisk.name.lowercase()} operation=$operation " +
                "tool=$tool session=$sessionId",
        )
        val result = try {
            DirectRootRunner.runArgv(listOf(resolvedTool) + commandArgs, timeoutMs)
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
        return AndroidCommandResult(
            backend = if (result.error == null) PrivilegedBackend.ROOT else PrivilegedBackend.NONE,
            exitCode = result.exitCode,
            stdout = result.stdout,
            stderr = result.stderr,
            timedOut = result.timedOut,
            unavailableReason = result.error?.let {
                if (rootOnly) "required Root capability is unavailable: $it" else it
            },
        )
    }

    /** Explicit Root authorization probe; passive capability reads never invoke su. */
    suspend fun requestActiveRootProbe(context: Context, sessionId: String): RootProbeResult {
        if (RootCommandRunner.passiveSuPath() == null) {
            return RootProbeResult(false, error = "su executable not found").also {
                RootCommandRunner.updateProbe(it)
            }
        }
        Log.i(TAG, "active root probe requested session=$sessionId")
        RootCommandRunner.beginProbe()
        val script = """
            id
            cat /proc/self/status 2>/dev/null || true
            echo __CONTEXT__
            id -Z 2>/dev/null || echo unknown
            echo __MODE__
            getenforce 2>/dev/null || echo unknown
        """.trimIndent()
        try {
            val result = DirectRootRunner.runScript(script, 15_000L)
            val probe = if (result.error != null || result.timedOut) {
                RootProbeResult(false, error = result.error ?: "Root probe timed out")
            } else {
                RootProbeParser.parse(result.stdout, result.exitCode, result.stderr)
            }
            RootCommandRunner.updateProbe(probe)
            return probe
        } finally {
            RootCommandRunner.endProbe()
        }
    }
}
