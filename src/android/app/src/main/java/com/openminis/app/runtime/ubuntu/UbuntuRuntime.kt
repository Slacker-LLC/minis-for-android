package com.openminis.app.runtime.ubuntu

import android.content.Context
import android.util.Log
import com.openminis.app.data.MountedFoldersStore
import com.openminis.app.runtime.ExecutionCoordinator
import com.openminis.app.runtime.minisd.MinisdError
import com.openminis.app.runtime.minisd.MinisdProtocol
import com.openminis.app.runtime.minisd.MinisdResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Compatibility facade over [UbuntuKernel].
 *
 * There is no daemon/Unix-socket lifecycle here anymore. `running` means the
 * direct Root backend is authorized and the Ubuntu rootfs is healthy; actual
 * guest processes are per-session [RootPersistentShell] instances owned by
 * [ExecutionCoordinator].
 */
object UbuntuRuntime {
    private const val TAG = "UbuntuRuntime"
    private val WHITESPACE = Regex("\\s+")
    private val SHA256_TOKEN = Regex("^[0-9a-fA-F]{64}$")

    data class Snapshot(
        val running: Boolean = false,
        val available: Boolean = false,
        val pid: Int? = null,
        val version: String? = null,
        val provisioned: Boolean = false,
        val guestUid: Int? = null,
        val guestGid: Int? = null,
        val sessionsRoot: String? = null,
        val layoutKnown: Boolean = false,
        val hostWorkspace: String? = null,
        val hostMemory: String? = null,
        val hostSkills: String? = null,
        val hostShared: String? = null,
        val externalMountDigest: String? = null,
        val externalMountVerified: Boolean = false,
        val lastError: String? = null,
        val mock: Boolean = false,
        val statusFresh: Boolean = false,
    )

    data class ShellResult(
        val output: String,
        val exitCode: Int,
        val durationMs: Long,
    )

    class RuntimeInfrastructureException(val runtimeError: MinisdError) :
        IllegalStateException("${runtimeError.code}: ${runtimeError.detail}")

    @Volatile
    var isInitialized: Boolean = false
        private set

    @Volatile
    var redirectPaths: Boolean = false
        private set

    @Volatile
    private var appContext: Context? = null

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        UbuntuKernel.init(ctx)
        isInitialized = true
        redirectPaths = true
        Log.i(TAG, "initialized direct Ubuntu backend uid=${ctx.applicationInfo.uid}")
    }

    internal fun contextOrNull(): Context? = appContext

    suspend fun ensureReady(): Snapshot {
        if (!isInitialized) {
            val error = "UbuntuRuntime.init(context) has not been called"
            return fail(error)
        }
        val status = UbuntuKernel.ensureReady()
        val uid = status.appUid ?: appContext?.applicationInfo?.uid
        val next = if (status.ready && uid != null) {
            Snapshot(
                running = true,
                available = true,
                pid = null,
                version = status.version,
                provisioned = true,
                guestUid = uid,
                guestGid = uid,
                sessionsRoot = UbuntuPaths.hostSessions,
                layoutKnown = true,
                hostWorkspace = UbuntuPaths.hostWorkspace,
                hostMemory = UbuntuPaths.hostMemory,
                hostSkills = UbuntuPaths.hostSkills,
                hostShared = UbuntuPaths.hostShared,
                externalMountVerified = true,
                lastError = null,
                statusFresh = true,
            )
        } else {
            _snapshot.value.copy(
                running = false,
                available = false,
                lastError = status.error ?: "direct Ubuntu backend unavailable",
                statusFresh = true,
            )
        }
        _snapshot.value = next
        redirectPaths = next.running
        return next
    }

    suspend fun refresh(): Snapshot = ensureReady()

    suspend fun start(): Snapshot = ensureReady()

    suspend fun stop(): Snapshot {
        ExecutionCoordinator.stopCurrentCommand()
        val next = _snapshot.value.copy(running = false, available = false, statusFresh = true)
        _snapshot.value = next
        redirectPaths = false
        return next
    }

    suspend fun inspectRootfs(): RootfsHealth = UbuntuKernel.inspectRootfs()

    suspend fun refreshDns(nameservers: List<String> = emptyList()): Boolean {
        if (!isInitialized) return false
        return UbuntuKernel.refreshDns(nameservers)
    }

    suspend fun reconcileExternalMounts(entries: List<MountedFoldersStore.Entry>? = null): Boolean =
        UbuntuKernel.reconcileExternalMounts(entries)

    fun findSu(): String? = UbuntuKernel.findSu()

    fun paths(): JSONObject = JSONObject()
        .put("hostWorkspace", UbuntuPaths.hostWorkspace)
        .put("hostSessions", UbuntuPaths.hostSessions)
        .put("guestWorkspace", "/workspace")
        .put("rootfs", UbuntuPaths.HOST_ROOTFS)
        .put("backend", "direct-chroot")
        .put("guestUid", appContext?.applicationInfo?.uid)

    private fun fail(detail: String): Snapshot {
        val next = _snapshot.value.copy(
            running = false,
            available = false,
            lastError = detail,
            statusFresh = false,
        )
        _snapshot.value = next
        redirectPaths = false
        return next
    }

    // ---------------------------------------------------------------------
    // Transitional pure helpers retained only so the existing broker-era JVM
    // regression suite keeps compiling while phase 2 deletes that dead code.
    // None of these helpers are on the production execution path.
    // ---------------------------------------------------------------------

    internal fun shouldRetryAfterPreExecFailure(error: MinisdError?, attempt: Int): Boolean =
        attempt == 0 && error?.code == MinisdProtocol.ERROR_KEEPER_NAMESPACE_LOST

    internal fun shellStartMarker(seed: Long): String =
        "__MINIS_EXEC_STARTED_${seed.toString(16)}__"

    internal fun wrapShellCommand(command: String, marker: String): String {
        require(marker.matches(Regex("^[A-Za-z0-9_]+$"))) { "invalid shell start marker" }
        return "printf '%s\\n' '$marker' >&2\n$command"
    }

    internal fun didUserCommandStart(response: MinisdResponse, marker: String): Boolean {
        val stderr = response.result?.optString("stderr").orEmpty()
        return stderr.split('\n').any { it.trimEnd('\r') == marker }
    }

    internal fun stripShellStartMarker(stderr: String, marker: String): String =
        stderr.split('\n')
            .filterNot { it.trimEnd('\r') == marker }
            .joinToString("\n")

    internal fun brokerIdentityMatches(snapshot: Snapshot, expectedUid: Int): Boolean =
        snapshot.guestUid == expectedUid

    internal fun parseSha256sum(output: String): String? = output
        .lineSequence()
        .map { it.trim().split(WHITESPACE, limit = 2).firstOrNull().orEmpty() }
        .firstOrNull { it.matches(SHA256_TOKEN) }
        ?.lowercase()

    internal fun brokerBinaryMatches(expectedSha256: String, sha256sumOutput: String): Boolean =
        parseSha256sum(sha256sumOutput) == expectedSha256.lowercase()

    internal fun runtimeLayoutMatches(
        snapshot: Snapshot,
        expectedWorkspace: String,
        expectedMemory: String,
        expectedSkills: String,
        expectedShared: String,
    ): Boolean = snapshot.layoutKnown &&
        snapshot.hostWorkspace == expectedWorkspace &&
        snapshot.hostMemory == expectedMemory &&
        snapshot.hostSkills == expectedSkills &&
        snapshot.hostShared == expectedShared

    internal fun layoutMismatchDetail(
        snapshot: Snapshot,
        expectedWorkspace: String,
        expectedMemory: String,
        expectedSkills: String,
        expectedShared: String,
    ): String = "runtime layout mismatch: " +
        "workspace=${snapshot.hostWorkspace ?: "unknown"} expected=$expectedWorkspace, " +
        "memory=${snapshot.hostMemory ?: "unknown"} expected=$expectedMemory, " +
        "skills=${snapshot.hostSkills ?: "unknown"} expected=$expectedSkills, " +
        "shared=${snapshot.hostShared ?: "unknown"} expected=$expectedShared, " +
        "layoutKnown=${snapshot.layoutKnown}"

    internal fun mergeSnapshot(previous: Snapshot, resp: MinisdResponse): Snapshot {
        val result = resp.result
        return if (resp.ok && result != null) {
            Snapshot(
                running = result.optBoolean("running") ||
                    result.optBoolean("provisioned") && previous.running,
                available = result.optBoolean("available", result.optBoolean("running")),
                pid = if (result.has("pid") && !result.isNull("pid")) result.optInt("pid") else previous.pid,
                version = result.optString("version").ifEmpty { previous.version },
                provisioned = result.optBoolean("provisioned") || previous.provisioned,
                guestUid = if (result.has("uid") && !result.isNull("uid")) result.optInt("uid") else previous.guestUid,
                guestGid = if (result.has("gid") && !result.isNull("gid")) result.optInt("gid") else previous.guestGid,
                sessionsRoot = result.optString("sessions_root")
                    .ifEmpty { previous.sessionsRoot.orEmpty() }.takeIf { it.isNotEmpty() },
                layoutKnown = result.optBoolean("layout_known", false),
                hostWorkspace = result.optNullableString("workspace"),
                hostMemory = result.optNullableString("memory"),
                hostSkills = result.optNullableString("skills"),
                hostShared = result.optNullableString("shared"),
                externalMountDigest = result.optNullableString("external_mount_digest"),
                externalMountVerified = result.optBoolean("external_mount_verified", false),
                lastError = result.optString("last_error").ifEmpty { null },
                mock = result.optBoolean("mock"),
                statusFresh = true,
            )
        } else {
            previous.copy(
                lastError = resp.error?.let { "${it.code}: ${it.detail}" } ?: "legacy runtime response failed",
                statusFresh = false,
            )
        }
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null
}
