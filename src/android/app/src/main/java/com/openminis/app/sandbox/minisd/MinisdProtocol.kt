package com.openminis.app.sandbox.minisd

import org.json.JSONArray
import org.json.JSONObject

/** Wire types for minisd JSON-RPC v1. */
data class MinisdRequest(
    val id: Long,
    val method: String,
    val params: JSONObject = JSONObject(),
    val clientId: String = "app",
    val token: String? = null,
    val confirmId: String? = null,
    val v: Int = MinisdProtocol.PROTOCOL_V,
)

data class MinisdError(
    val code: String,
    val detail: String,
    val confirmId: String? = null,
    val supported: List<Int>? = null,
)

data class MinisdResponse(
    val v: Int,
    val id: Long,
    val ok: Boolean,
    val result: JSONObject?,
    val error: MinisdError?,
) {
    val code: String? get() = error?.code
}

object MinisdProtocol {
    const val PROTOCOL_V = 1
    const val MAX_REQUEST_BYTES = 64 * 1024
    const val MAX_RESPONSE_BYTES = 1024 * 1024
    const val DEFAULT_SOCKET = "/data/adb/minis/run/minisd.sock"
    const val DEFAULT_BIN = "/data/adb/minis/bin/minisd"
    const val DEFAULT_ROOTFS = "/data/adb/minis/rootfs"
    const val HOST_WORKSPACE = "/data/adb/minis/workspace"
    const val GUEST_WORKSPACE = "/workspace"
    const val GUEST_UID = 10000

    const val ERROR_ROOTFS_INVALID = "ROOTFS_INVALID"
    const val ERROR_KEEPER_NAMESPACE_LOST = "KEEPER_NAMESPACE_LOST"
    const val ERROR_CHROOT_UNAVAILABLE = "CHROOT_UNAVAILABLE"
    const val ERROR_RUNTIME_LAYOUT_MISMATCH = "RUNTIME_LAYOUT_MISMATCH"
    const val ERROR_PRIVILEGE_SETUP_FAILED = "PRIVILEGE_SETUP_FAILED"
    const val ERROR_RUNTIME_UNAVAILABLE = "RUNTIME_UNAVAILABLE"

    private const val HELPER_SETNS_FAILED = 4
    private const val HELPER_CHROOT_FAILED = 5
    private const val HELPER_PRIVILEGE_FAILED = 6

    fun encodeRequest(req: MinisdRequest): String {
        val client = JSONObject().put("id", req.clientId)
        req.token?.let { client.put("token", it) }
        client.put("capabilities", JSONArray().put(req.method))
        val body = JSONObject()
            .put("v", req.v)
            .put("id", req.id)
            .put("method", req.method)
            .put("client", client)
            .put("params", req.params)
        req.confirmId?.let { body.put("confirm_id", it) }
        return body.toString()
    }

    fun decodeResponse(raw: String): MinisdResponse {
        val trimmed = raw.trim().lineSequence().firstOrNull { it.isNotBlank() }
            ?: throw IllegalArgumentException("empty minisd response")
        val obj = JSONObject(trimmed)
        val errorObj = obj.optJSONObject("error")
        val error = errorObj?.let {
            val supported = it.optJSONArray("supported")?.let { arr ->
                (0 until arr.length()).map { i -> arr.getInt(i) }
            }
            MinisdError(
                code = it.optString("code"),
                detail = it.optString("detail"),
                confirmId = it.optString("confirm_id").ifEmpty { null },
                supported = supported,
            )
        }
        return MinisdResponse(
            v = obj.optInt("v", PROTOCOL_V),
            id = obj.optLong("id"),
            ok = obj.optBoolean("ok"),
            result = obj.optJSONObject("result"),
            error = error,
        )
    }

    /**
     * Older minisd helpers report failures that happen before execve through
     * reserved helper exit statuses. Promote those to structured runtime
     * errors so callers never confuse them with the user's command exit code.
     * A future minisd may return the same structured codes directly; those are
     * preserved unchanged.
     */
    fun promoteExecInfrastructureFailure(response: MinisdResponse): MinisdResponse {
        if (!response.ok) return response
        val result = response.result ?: return response
        val explicit = result.optString("pre_exec_error").takeIf { it.isNotBlank() }
        val error = when (explicit) {
            ERROR_KEEPER_NAMESPACE_LOST,
            ERROR_CHROOT_UNAVAILABLE,
            ERROR_ROOTFS_INVALID,
            ERROR_RUNTIME_LAYOUT_MISMATCH,
            ERROR_PRIVILEGE_SETUP_FAILED,
            -> MinisdError(explicit, result.optString("pre_exec_detail").ifBlank { explicit })
            else -> when (result.optInt("exit_code", Int.MIN_VALUE)) {
                HELPER_SETNS_FAILED -> MinisdError(
                    ERROR_KEEPER_NAMESPACE_LOST,
                    result.optString("stderr").ifBlank { "keeper mount namespace is no longer available" },
                )
                HELPER_CHROOT_FAILED -> MinisdError(
                    ERROR_CHROOT_UNAVAILABLE,
                    result.optString("stderr").ifBlank { "Ubuntu chroot is unavailable" },
                )
                HELPER_PRIVILEGE_FAILED -> MinisdError(
                    ERROR_PRIVILEGE_SETUP_FAILED,
                    result.optString("stderr").ifBlank { "runtime privilege setup failed before execve" },
                )
                else -> null
            }
        }
        return if (error == null) {
            response
        } else {
            MinisdResponse(
                v = response.v,
                id = response.id,
                ok = false,
                result = null,
                error = error,
            )
        }
    }

    fun isRetrySafeKeeperFailure(response: MinisdResponse): Boolean =
        promoteExecInfrastructureFailure(response).error?.code == ERROR_KEEPER_NAMESPACE_LOST

    fun runtimeError(code: String, detail: String, id: Long = 0): MinisdResponse =
        MinisdResponse(
            v = PROTOCOL_V,
            id = id,
            ok = false,
            result = null,
            error = MinisdError(code = code, detail = detail),
        )

    fun ping(id: Long = 1): MinisdRequest = MinisdRequest(id = id, method = "system.ping")

    fun ubuntuStatus(id: Long = 1): MinisdRequest =
        MinisdRequest(id = id, method = "ubuntu.status")

    fun ubuntuStart(
        id: Long = 1,
        rootfs: String = DEFAULT_ROOTFS,
        workspace: String = "",
        memory: String = "",
        skills: String = "",
        shared: String = "",
        sessionsRoot: String = "",
    ): MinisdRequest {
        val params = JSONObject().put("rootfs", rootfs)
        if (workspace.isNotEmpty()) params.put("workspace", workspace)
        if (memory.isNotEmpty()) params.put("memory", memory)
        if (skills.isNotEmpty()) params.put("skills", skills)
        if (shared.isNotEmpty()) params.put("shared", shared)
        if (sessionsRoot.isNotEmpty()) params.put("sessions_root", sessionsRoot)
        return MinisdRequest(id = id, method = "ubuntu.start", params = params)
    }

    fun ubuntuStop(id: Long = 1): MinisdRequest =
        MinisdRequest(id = id, method = "ubuntu.stop")

    fun ubuntuExec(
        argv: List<String>,
        timeoutMs: Long = 30_000,
        cwd: String = GUEST_WORKSPACE,
        env: Map<String, String> = emptyMap(),
        id: Long = 1,
        sessionId: String? = null,
    ): MinisdRequest {
        val arr = JSONArray()
        argv.forEach { arr.put(it) }
        val params = JSONObject()
            .put("argv", arr)
            .put("timeout_ms", timeoutMs)
            .put("cwd", cwd)
        if (env.isNotEmpty()) {
            val obj = JSONObject()
            env.forEach { (k, v) -> obj.put(k, v) }
            params.put("env", obj)
        }
        sessionId?.takeIf { it.isNotEmpty() }?.let { params.put("session_id", it) }
        return MinisdRequest(
            id = id,
            method = "ubuntu.exec",
            params = params,
        )
    }

    fun ubuntuProvision(id: Long = 1): MinisdRequest =
        MinisdRequest(id = id, method = "ubuntu.provision")

    fun rootExec(
        tool: String,
        args: List<String> = emptyList(),
        timeoutMs: Long = 30_000,
        id: Long = 1,
    ): MinisdRequest = MinisdRequest(
        id = id,
        method = "root.exec",
        params = JSONObject()
            .put("tool", tool)
            .put("args", JSONArray(args))
            .put("timeout_ms", timeoutMs),
    )

    fun ubuntuAdminExec(
        argv: List<String>,
        timeoutMs: Long = 120_000,
        confirmId: String? = null,
        id: Long = 1,
    ): MinisdRequest {
        val arr = JSONArray()
        argv.forEach { arr.put(it) }
        return MinisdRequest(
            id = id,
            method = "ubuntu.adminExec",
            params = JSONObject().put("argv", arr).put("timeout_ms", timeoutMs),
            confirmId = confirmId,
        )
    }
}
