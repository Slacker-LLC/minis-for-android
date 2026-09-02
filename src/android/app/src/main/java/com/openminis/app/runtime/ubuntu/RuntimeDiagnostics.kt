package com.openminis.app.runtime.ubuntu

import com.openminis.app.runtime.minisd.MinisdResponse
import org.json.JSONObject

/** States are intentionally independent: a usable broker does not imply a healthy rootfs. */
enum class RootDiagnosticState {
    UNKNOWN,
    UNAVAILABLE,
    AVAILABLE,
}

enum class BrokerDiagnosticState {
    UNKNOWN,
    UNREACHABLE,
    REACHABLE,
}

enum class KeeperDiagnosticState {
    UNKNOWN,
    STOPPED,
    STARTING,
    RUNNING,
    NAMESPACE_LOST,
    LAYOUT_MISMATCH,
}

enum class ProvisionDiagnosticState {
    UNKNOWN,
    NOT_PROVISIONED,
    IN_PROGRESS,
    FAILED,
    SUCCEEDED,
}

data class RootDiagnostic(
    val state: RootDiagnosticState = RootDiagnosticState.UNKNOWN,
    val uid: Int? = null,
    val gid: Int? = null,
    val groups: List<Int> = emptyList(),
    val capEff: String? = null,
    val selinux: String? = null,
    val selinuxEnforcing: Boolean? = null,
    val detail: String? = null,
)

data class BrokerDiagnostic(
    val state: BrokerDiagnosticState = BrokerDiagnosticState.UNKNOWN,
    val socketReachable: Boolean? = null,
    /** UID reported by root.probe for the running broker process. */
    val processUid: Int? = null,
    val detail: String? = null,
)

data class RootfsDiagnostic(
    val state: RootfsHealthCode = RootfsHealthCode.UNKNOWN,
    val available: Boolean? = null,
    val detail: String? = null,
)

data class KeeperDiagnostic(
    val state: KeeperDiagnosticState = KeeperDiagnosticState.UNKNOWN,
    val running: Boolean? = null,
    val pid: Int? = null,
    val layoutKnown: Boolean? = null,
    val detail: String? = null,
)

data class ProvisionDiagnostic(
    val state: ProvisionDiagnosticState = ProvisionDiagnosticState.UNKNOWN,
    val revision: Int? = null,
    val detail: String? = null,
)

data class RuntimeDiagnostics(
    val root: RootDiagnostic = RootDiagnostic(),
    val broker: BrokerDiagnostic = BrokerDiagnostic(),
    val rootfs: RootfsDiagnostic = RootfsDiagnostic(),
    val keeper: KeeperDiagnostic = KeeperDiagnostic(),
    val provision: ProvisionDiagnostic = ProvisionDiagnostic(),
)

/** Pure wire-to-diagnosis mapping; it never starts a broker, keeper, or rootfs operation. */
object RuntimeDiagnosticsMapper {
    fun fromResponses(
        ping: MinisdResponse,
        rootProbe: MinisdResponse,
        ubuntuStatus: MinisdResponse,
        rootfsHealth: RootfsHealth? = null,
    ): RuntimeDiagnostics {
        val root = mapRoot(rootProbe)
        val status = ubuntuStatus.result
        val statusDetail = responseDetail(ubuntuStatus)
        val statusOk = ubuntuStatus.ok && status != null
        val lastError = status?.optString("last_error").orEmpty().ifBlank { null }
        val running = status?.optNullableBoolean("running")
        val layoutKnown = status?.optNullableBoolean("layout_known")

        val keeperState = when {
            !statusOk -> KeeperDiagnosticState.UNKNOWN
            lastError?.contains("${com.openminis.app.runtime.minisd.MinisdProtocol.ERROR_KEEPER_NAMESPACE_LOST}:") == true ->
                KeeperDiagnosticState.NAMESPACE_LOST
            lastError?.contains("${com.openminis.app.runtime.minisd.MinisdProtocol.ERROR_RUNTIME_LAYOUT_MISMATCH}:") == true ->
                KeeperDiagnosticState.LAYOUT_MISMATCH
            running == true && layoutKnown == false -> KeeperDiagnosticState.LAYOUT_MISMATCH
            running == true -> KeeperDiagnosticState.RUNNING
            running == false -> KeeperDiagnosticState.STOPPED
            else -> KeeperDiagnosticState.UNKNOWN
        }

        val provisionState = when {
            !statusOk -> ProvisionDiagnosticState.UNKNOWN
            lastError != null -> ProvisionDiagnosticState.FAILED
            status.optBoolean("provisioned") -> ProvisionDiagnosticState.SUCCEEDED
            else -> ProvisionDiagnosticState.NOT_PROVISIONED
        }

        return RuntimeDiagnostics(
            root = root,
            broker = BrokerDiagnostic(
                state = when {
                    !ping.ok -> BrokerDiagnosticState.UNREACHABLE
                    root.state == RootDiagnosticState.AVAILABLE -> BrokerDiagnosticState.REACHABLE
                    else -> BrokerDiagnosticState.UNKNOWN
                },
                socketReachable = ping.ok,
                processUid = root.uid,
                detail = responseDetail(ping),
            ),
            rootfs = rootfsHealth?.let {
                RootfsDiagnostic(
                    state = it.code,
                    available = it.healthy,
                    detail = it.detail,
                )
            } ?: mapRootfs(status, statusOk, statusDetail),
            keeper = KeeperDiagnostic(
                state = keeperState,
                running = running,
                pid = status?.optNullableInt("pid"),
                layoutKnown = layoutKnown,
                detail = lastError ?: statusDetail,
            ),
            provision = ProvisionDiagnostic(
                state = provisionState,
                revision = status?.optNullableInt("provision_revision")
                    ?: rootfsHealth?.metadata?.optNullableInt("revision"),
                detail = lastError ?: statusDetail,
            ),
        )
    }

    private fun mapRoot(response: MinisdResponse): RootDiagnostic {
        if (!response.ok) {
            return RootDiagnostic(
                state = RootDiagnosticState.UNAVAILABLE,
                detail = responseDetail(response),
            )
        }
        val payload = response.result ?: return RootDiagnostic(
            state = RootDiagnosticState.UNKNOWN,
            detail = "root.probe response omitted result",
        )
        val uid = payload.optNullableInt("uid")
        return RootDiagnostic(
            state = if (uid == 0) RootDiagnosticState.AVAILABLE else RootDiagnosticState.UNAVAILABLE,
            uid = uid,
            gid = payload.optNullableInt("gid"),
            groups = payload.optJSONArray("groups")?.let { groups ->
                (0 until groups.length()).mapNotNull { index ->
                    groups.optInt(index, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
                }
            }.orEmpty(),
            capEff = payload.optString("capEff").takeIf { it.isNotBlank() },
            selinux = payload.optString("selinux").takeIf { it.isNotBlank() },
            selinuxEnforcing = payload.optNullableBoolean("enforcing"),
            detail = if (uid == 0) null else "broker root probe did not report uid 0",
        )
    }

    private fun mapRootfs(
        status: JSONObject?,
        statusOk: Boolean,
        statusDetail: String?,
    ): RootfsDiagnostic {
        if (!statusOk || status == null) {
            return RootfsDiagnostic(detail = statusDetail)
        }
        val available = status.optNullableBoolean("available")
        val explicitCode = status.optString("rootfs_health_code")
            .takeIf { it.isNotBlank() }
            ?.let { runCatching { RootfsHealthCode.valueOf(it) }.getOrNull() }
        return RootfsDiagnostic(
            state = explicitCode ?: if (available == true) {
                RootfsHealthCode.HEALTHY
            } else {
                // ubuntu.status currently exposes only a boolean. Do not guess
                // whether an unavailable rootfs is missing, corrupt, or incompatible.
                RootfsHealthCode.UNKNOWN
            },
            available = available,
            detail = if (explicitCode == null && available == false) {
                "ubuntu.status reported an unavailable rootfs; missing/corrupt/incompatible is not distinguished"
            } else {
                statusDetail
            },
        )
    }

    private fun responseDetail(response: MinisdResponse): String? =
        response.error?.let { "${it.code}: ${it.detail}" }

    private fun JSONObject.optNullableInt(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    private fun JSONObject.optNullableBoolean(key: String): Boolean? =
        if (has(key) && !isNull(key)) optBoolean(key) else null
}
