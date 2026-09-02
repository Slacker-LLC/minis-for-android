package com.openminis.app.runtime.minisd

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
    const val ERROR_EXEC_UNAVAILABLE = "EXEC_UNAVAILABLE"
    const val ERROR_RUNTIME_UNAVAILABLE = "RUNTIME_UNAVAILABLE"
    const val ERROR_RUNTIME_SWITCH_UNKNOWN = "RUNTIME_SWITCH_UNKNOWN"
    const val ERROR_CONFIRM_REQUIRED = "CONFIRM_REQUIRED"
    const val ERROR_POLICY_DENIED = "POLICY_DENIED"
    const val ERROR_BAD_PARAMS = "BAD_PARAMS"

    const val RUNTIME_MAINTENANCE_METHOD = "runtime.maintenance"
    const val RUNTIME_MAINTENANCE_TIMEOUT_MS = 1_500_000L
    const val RUNTIME_OP_PROBE = "probe"
    const val RUNTIME_OP_STAGE = "stage"
    const val RUNTIME_OP_VERIFY = "verify"
    const val RUNTIME_OP_SWITCH = "switch"
    const val RUNTIME_OP_ROLLBACK = "rollback"
    const val RUNTIME_OP_RESET = "reset"
    const val RUNTIME_OP_READ_STATE = "read_state"
    const val RUNTIME_OP_WRITE_STATE = "write_state"
    const val RUNTIME_OP_CLEAR_STATE = "clear_state"

    private const val HELPER_NAMESPACE_FAILED = 4
    private const val HELPER_CHROOT_FAILED = 5
    private const val HELPER_PRIVILEGE_FAILED = 6
    private const val HELPER_EXECVE_FAILED = 7

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

    fun promoteExecInfrastructureFailure(
        response: MinisdResponse,
        userCommandStarted: Boolean? = null,
    ): MinisdResponse {
        if (!response.ok) return response
        val result = response.result ?: return response
        val explicit = result.optString("pre_exec_error").takeIf { it.isNotBlank() }
        val error = when (explicit) {
            ERROR_KEEPER_NAMESPACE_LOST,
            ERROR_CHROOT_UNAVAILABLE,
            ERROR_ROOTFS_INVALID,
            ERROR_RUNTIME_LAYOUT_MISMATCH,
            ERROR_PRIVILEGE_SETUP_FAILED,
            ERROR_EXEC_UNAVAILABLE,
            -> MinisdError(
                explicit,
                result.optString("pre_exec_detail").ifBlank { explicit },
            )
            else -> if (userCommandStarted == false) classifyLegacyPreExec(result) else null
        }
        return if (error == null) response else MinisdResponse(
            v = response.v,
            id = response.id,
            ok = false,
            result = null,
            error = error,
        )
    }

    private fun classifyLegacyPreExec(result: JSONObject): MinisdError? {
        val stderr = result.optString("stderr")
        return when (result.optInt("exit_code", Int.MIN_VALUE)) {
            HELPER_NAMESPACE_FAILED -> {
                val keeperLost =
                    (stderr.contains("open /proc/") && stderr.contains("/ns/mnt")) ||
                        stderr.contains("setns CLONE_NEWNS")
                if (keeperLost) {
                    MinisdError(
                        ERROR_KEEPER_NAMESPACE_LOST,
                        stderr.ifBlank { "keeper mount namespace is no longer available" },
                    )
                } else {
                    MinisdError(
                        ERROR_RUNTIME_LAYOUT_MISMATCH,
                        stderr.ifBlank { "runtime namespace or session mount setup failed" },
                    )
                }
            }
            HELPER_CHROOT_FAILED -> MinisdError(
                ERROR_CHROOT_UNAVAILABLE,
                stderr.ifBlank { "Ubuntu chroot is unavailable" },
            )
            HELPER_PRIVILEGE_FAILED -> MinisdError(
                ERROR_PRIVILEGE_SETUP_FAILED,
                stderr.ifBlank { "runtime privilege setup failed before execve" },
            )
            HELPER_EXECVE_FAILED -> MinisdError(
                ERROR_EXEC_UNAVAILABLE,
                stderr.ifBlank { "guest executable could not be started" },
            )
            else -> null
        }
    }

    fun isRetrySafeKeeperFailure(
        response: MinisdResponse,
        userCommandStarted: Boolean? = null,
    ): Boolean = promoteExecInfrastructureFailure(response, userCommandStarted)
        .error
        ?.code == ERROR_KEEPER_NAMESPACE_LOST

    fun runtimeError(code: String, detail: String, id: Long = 0): MinisdResponse =
        MinisdResponse(
            v = PROTOCOL_V,
            id = id,
            ok = false,
            result = null,
            error = MinisdError(code = code, detail = detail),
        )

    fun ping(id: Long = 1): MinisdRequest = MinisdRequest(id = id, method = "system.ping")

    fun rootProbe(id: Long = 1): MinisdRequest =
        MinisdRequest(id = id, method = "root.probe")

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
        executionId: String? = null,
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
        executionId?.takeIf { it.isNotEmpty() }?.let { params.put("execution_id", it) }
        return MinisdRequest(id = id, method = "ubuntu.exec", params = params)
    }

    fun execCancel(executionId: String, id: Long = 1): MinisdRequest = MinisdRequest(
        id = id,
        method = "exec.cancel",
        params = JSONObject().put("execution_id", executionId),
    )

    fun ubuntuProvision(id: Long = 1): MinisdRequest =
        MinisdRequest(id = id, method = "ubuntu.provision")

    fun runtimeMaintenance(
        operation: String,
        params: JSONObject = JSONObject(),
        id: Long = 1,
    ): MinisdRequest {
        val requestParams = JSONObject(params.toString()).put("operation", operation)
        return MinisdRequest(
            id = id,
            method = RUNTIME_MAINTENANCE_METHOD,
            params = requestParams,
        )
    }

    fun workspaceFile(
        operation: String,
        sessionId: String? = null,
        sourceSessionId: String? = null,
        destinationSessionId: String? = null,
        path: String? = null,
        source: String? = null,
        destination: String? = null,
        target: String? = null,
        dataBase64: String? = null,
        offset: Long? = null,
        length: Int? = null,
        limit: Int? = null,
        createDirs: Boolean? = null,
        id: Long = 1,
    ): MinisdRequest {
        val params = JSONObject().put("operation", operation)
        sessionId?.takeIf { it.isNotEmpty() }?.let { params.put("session_id", it) }
        sourceSessionId?.takeIf { it.isNotEmpty() }?.let { params.put("source_session_id", it) }
        destinationSessionId?.takeIf { it.isNotEmpty() }?.let { params.put("destination_session_id", it) }
        path?.let { params.put("path", it) }
        source?.let { params.put("source", it) }
        destination?.let { params.put("destination", it) }
        target?.let { params.put("target", it) }
        dataBase64?.let { params.put("data_base64", it) }
        offset?.let { params.put("offset", it) }
        length?.let { params.put("length", it) }
        limit?.let { params.put("limit", it) }
        createDirs?.let { params.put("create_dirs", it) }
        return MinisdRequest(id = id, method = "workspace.file", params = params)
    }

    fun rootExec(
        tool: String,
        args: List<String> = emptyList(),
        timeoutMs: Long = 30_000,
        id: Long = 1,
        executionId: String? = null,
    ): MinisdRequest {
        val params = JSONObject()
            .put("tool", tool)
            .put("args", JSONArray(args))
            .put("timeout_ms", timeoutMs)
        executionId?.takeIf { it.isNotEmpty() }?.let { params.put("execution_id", it) }
        return MinisdRequest(id = id, method = "root.exec", params = params)
    }

    fun rootFullExec(
        tool: String,
        args: List<String> = emptyList(),
        timeoutMs: Long = 30_000,
        confirmId: String? = null,
        id: Long = 1,
        executionId: String? = null,
    ): MinisdRequest {
        val params = JSONObject()
            .put("tool", tool)
            .put("args", JSONArray(args))
            .put("timeout_ms", timeoutMs)
        executionId?.takeIf { it.isNotEmpty() }?.let { params.put("execution_id", it) }
        return MinisdRequest(
            id = id,
            method = "root.fullExec",
            params = params,
            confirmId = confirmId,
        )
    }

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
