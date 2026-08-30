package com.openminis.app.tools.android

import android.content.Context
import com.openminis.app.runtime.minisd.MinisdProtocol
import com.openminis.app.runtime.minisd.MinisdResponse
import com.openminis.app.runtime.ubuntu.UbuntuRuntime
import com.openminis.app.tools.ApprovalSeam
import org.json.JSONArray
import java.io.File

/** Backend selected for one privileged Android command. */
enum class PrivilegedBackend { ROOT, SHIZUKU, NONE }

/** Risk of one privileged command, retained in the approval/audit description. */
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

/** Actual identity and kernel-policy facts returned by minisd's Root probe. */
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

/** Pure parser retained for compatibility tests and legacy probe diagnostics. */
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
            exitCode != 0 -> "root probe exited with code $exitCode"
            uid != 0 -> "root probe returned effective uid=${uid ?: "unknown"}, not uid 0"
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

/**
 * Passive Root discovery/cache only. There is intentionally no command runner
 * here: all privileged execution goes through minisd.
 */
object RootCommandRunner {
    private val knownPaths = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
        "/data/adb/ksu/bin/su", "/debug_ramdisk/su",
    )

    @Volatile private var lastProbe: RootProbeResult? = null

    fun passiveSuPath(): String? {
        knownPaths.firstOrNull { File(it).canExecute() }?.let { return it }
        return System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
            .map { File(it, "su") }
            .firstOrNull { it.canExecute() }
            ?.absolutePath
    }

    fun cachedProbe(): RootProbeResult? = lastProbe

    internal fun updateProbe(result: RootProbeResult) {
        lastProbe = result
    }
}

/** Unified policy seam for every Agent-triggered privileged Android command. */
object PrivilegedCommandRunner {
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
        val wireMode = mode.wireValue
        val tool = argv.first()
        val args = argv.drop(1)

        var response = UbuntuRuntime.client.rootExec(
            tool = tool,
            args = args,
            timeoutMs = timeoutMs,
            accessMode = wireMode,
        )

        if (
            mode == PrivilegedAccessMode.STANDARD &&
            response.code == MinisdProtocol.ERROR_CONFIRM_REQUIRED
        ) {
            val confirmId = response.error?.confirmId
                ?: return unavailable("minisd requested confirmation without confirm_id", rootOnly)
            val decision = ApprovalSeam.request(
                context = context,
                sessionId = sessionId,
                toolName = "android_privileged",
                summary = buildString {
                    append(operation)
                    append("\nrisk=")
                    append(risk.name)
                    append("\nargv=")
                    append(JSONArray(argv).toString())
                },
            )
            if (decision.decision != "allowed-once") {
                return AndroidCommandResult(
                    backend = PrivilegedBackend.NONE,
                    exitCode = 126,
                    stdout = "",
                    stderr = "",
                    unavailableReason = "operation was not approved (${decision.decision})",
                )
            }
            response = UbuntuRuntime.client.rootExec(
                tool = tool,
                args = args,
                timeoutMs = timeoutMs,
                accessMode = wireMode,
                confirmId = confirmId,
            )
        }

        return response.toAndroidCommandResult(rootOnly)
    }

    /** Explicit user-approved Root authorization probe, executed by minisd only. */
    suspend fun requestActiveRootProbe(context: Context, sessionId: String): RootProbeResult {
        if (RootCommandRunner.passiveSuPath() == null) {
            return RootProbeResult(false, error = "su executable not found")
        }
        val decision = ApprovalSeam.request(
            context,
            sessionId,
            "android_capabilities",
            "主动请求 Root 授权并由 minisd 读取 uid/gid/capabilities/SELinux 状态",
        )
        if (decision.decision != "allowed-once") {
            return RootProbeResult(false, error = "root probe was not approved (${decision.decision})")
        }
        val response = UbuntuRuntime.client.rootProbe()
        val result = response.toRootProbeResult()
        RootCommandRunner.updateProbe(result)
        return result
    }

    private fun unavailable(detail: String, rootOnly: Boolean) = AndroidCommandResult(
        backend = PrivilegedBackend.NONE,
        exitCode = 126,
        stdout = "",
        stderr = "",
        unavailableReason = if (rootOnly) {
            "authorized root with the required capability is unavailable: $detail"
        } else {
            detail
        },
    )

    private fun MinisdResponse.toAndroidCommandResult(rootOnly: Boolean): AndroidCommandResult {
        if (!ok) {
            val code = error?.code.orEmpty()
            val detail = error?.detail?.ifBlank { code } ?: "minisd root.exec failed"
            return AndroidCommandResult(
                backend = PrivilegedBackend.NONE,
                exitCode = if (code == "TOOL_TIMEOUT" || code == "TRANSPORT_TIMEOUT") 124 else 126,
                stdout = "",
                stderr = "",
                timedOut = code == "TOOL_TIMEOUT" || code == "TRANSPORT_TIMEOUT",
                unavailableReason = if (rootOnly) {
                    "authorized root with the required capability is unavailable: $detail"
                } else {
                    detail
                },
            )
        }
        val body = result
            ?: return AndroidCommandResult(
                PrivilegedBackend.NONE,
                126,
                "",
                "",
                unavailableReason = "minisd root.exec returned no result",
            )
        return AndroidCommandResult(
            backend = PrivilegedBackend.ROOT,
            exitCode = body.optInt("exit_code", -1),
            stdout = body.optString("stdout"),
            stderr = body.optString("stderr"),
        )
    }

    private fun MinisdResponse.toRootProbeResult(): RootProbeResult {
        if (!ok) {
            return RootProbeResult(
                authorized = false,
                error = error?.detail?.ifBlank { error?.code.orEmpty() } ?: "minisd root.probe failed",
            )
        }
        val body = result ?: return RootProbeResult(false, error = "minisd root.probe returned no result")
        val uid = body.optInt("uid", -1).takeIf { it >= 0 }
        val gid = body.optInt("gid", -1).takeIf { it >= 0 }
        val groupsJson = body.optJSONArray("groups")
        val groups = if (groupsJson == null) emptyList() else
            (0 until groupsJson.length()).map { groupsJson.optInt(it).toString() }
        return RootProbeResult(
            authorized = uid == 0,
            effectiveUid = uid,
            effectiveGid = gid,
            groups = groups,
            effectiveCapabilitiesHex = body.optString("capEff").ifBlank { null },
            selinuxContext = body.optString("selinux").ifBlank { null },
            selinuxMode = if (body.optBoolean("enforcing", true)) "Enforcing" else "Permissive",
            error = if (uid == 0) null else "minisd root.probe returned effective uid=${uid ?: "unknown"}, not uid 0",
        )
    }
}
