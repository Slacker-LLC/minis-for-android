package com.openminis.app.runtime.minisd

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import com.openminis.app.runtime.guest.NativeOffloadRequest
import com.openminis.app.runtime.guest.ConfigOffloadHandler
import com.openminis.app.runtime.guest.NativeOffloadHandler
import com.openminis.app.runtime.guest.NativeOffloadResult
import com.openminis.app.runtime.guest.NativeOffloadServer
import com.openminis.app.runtime.ubuntu.UbuntuPaths
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.concurrent.thread

/**
 * Android-side endpoint for the minisd-owned minis-config control channel.
 *
 * This is intentionally independent from the historical PRoot native-offload
 * transport. minisd connects to this abstract Unix socket, forwards the CLI
 * argv/cwd/session, and this server invokes the existing ConfigBridge-backed
 * command handler inside the app process.
 *
 * Security boundary:
 *  - the socket name is scoped by the current Android app UID;
 *  - only uid 0 peers are accepted (the privileged minisd broker);
 *  - writes still pass through ConfigBridge confirmation/audit policy.
 */
object MinisdConfigBridgeServer {
    private const val TAG = "MinisdConfigBridge"
    private const val MAX_REQUEST_BYTES = 2 * 1024 * 1024
    private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024

    @Volatile
    private var serverSocket: LocalServerSocket? = null

    @Volatile
    private var expectedAppUid: Int = -1

    private val handler by lazy { ConfigOffloadHandler() }

    fun socketNameForUid(uid: Int): String = "minis-config-bridge-$uid"

    @Synchronized
    fun start(context: Context): Boolean {
        if (serverSocket != null) return true
        val uid = context.applicationInfo.uid
        if (uid <= 0) return false
        expectedAppUid = uid
        val name = socketNameForUid(uid)
        val server = bindWithRetry(name) ?: run {
            Log.w(TAG, "failed to bind Android config bridge '$name'")
            return false
        }
        serverSocket = server
        thread(name = "minisd-config-bridge-accept", isDaemon = true) {
            runAcceptLoop(server)
        }
        Log.i(TAG, "listening on abstract socket '$name' for minisd")
        return true
    }

    private fun bindWithRetry(name: String): LocalServerSocket? {
        val delays = longArrayOf(0L, 50L, 100L, 200L, 400L, 800L)
        for (delay in delays) {
            if (delay > 0) Thread.sleep(delay)
            try {
                return LocalServerSocket(name)
            } catch (t: Throwable) {
                Log.w(TAG, "bind '$name' failed: ${t.message}")
            }
        }
        return null
    }

    private fun runAcceptLoop(server: LocalServerSocket) {
        while (true) {
            val client = try {
                server.accept()
            } catch (t: Throwable) {
                Log.i(TAG, "accept loop terminated: ${t.message}")
                return
            }
            thread(name = "minisd-config-bridge-worker", isDaemon = true) {
                try {
                    handleClient(client)
                } catch (t: Throwable) {
                    Log.w(TAG, "bridge request failed: ${t.message}", t)
                    runCatching {
                        writeResponse(
                            client,
                            JSONObject()
                                .put("exit_code", 1)
                                .put("output", "minis-config bridge: ${t.message ?: "internal error"}\n"),
                        )
                    }
                } finally {
                    runCatching { client.close() }
                }
            }
        }
    }

    private fun handleClient(client: LocalSocket) {
        val peer = client.peerCredentials
        if (peer == null || peer.uid != 0) {
            Log.w(
                TAG,
                "rejected non-root bridge peer uid=${peer?.uid ?: -1} appUid=$expectedAppUid",
            )
            return
        }

        val input = DataInputStream(client.inputStream)
        client.soTimeout = 15_000
        val length = input.readInt()
        require(length in 1..MAX_REQUEST_BYTES) { "invalid request length $length" }
        val payload = ByteArray(length)
        input.readFully(payload)
        val request = JSONObject(String(payload, Charsets.UTF_8))

        val result = dispatchRequest(request, peer.pid) { name ->
            if (name == "minis-config") handler else NativeOffloadServer.getHandler(name)
        }
        writeResponse(
            client,
            JSONObject().put("exit_code", result.exitCode).put("output", result.output),
        )
    }

    internal fun dispatchRequest(
        request: JSONObject,
        peerPid: Int,
        resolveHandler: (String) -> NativeOffloadHandler?,
    ): NativeOffloadResult {

        val rawArgv = request.optJSONArray("argv") ?: JSONArray()
        require(rawArgv.length() in 1..128) { "argv missing or too large" }
        val argv = ArrayList<String>(rawArgv.length())
        for (i in 0 until rawArgv.length()) {
            val arg = rawArgv.opt(i) as? String ?: error("argv[$i] is not a string")
            require(arg.toByteArray(Charsets.UTF_8).size <= 64 * 1024) { "argv[$i] too large" }
            require(!arg.contains('\u0000')) { "NUL in argv[$i]" }
            argv += arg
        }
        val cmdName = argv.first().substringAfterLast('/')
        require(cmdName == "minis-config" || cmdName == "minis-model-use") {
            "unsupported bridge command: $cmdName"
        }

        fun stringField(name: String, default: String): String {
            if (!request.has(name)) return default
            return request.opt(name) as? String ?: error("$name is not a string")
        }
        val session = stringField("session", "").takeIf { it.isNotBlank() }
        require(session == null || UbuntuPaths.isSafeSessionId(session)) { "invalid session id" }
        val cwd = stringField("cwd", "/workspace").ifBlank { "/workspace" }
        require(cwd.startsWith('/') && !cwd.contains('\u0000') && cwd.length <= 4096) { "invalid cwd" }
        val stdin = stringField("stdin", "")
        val env = if (session == null) {
            emptyMap()
        } else {
            mapOf("MINIS_CHAT_SESSION_ID" to session)
        }

        val offloadRequest = NativeOffloadRequest(
            pid = peerPid,
            argv = argv,
            env = env,
            cwd = cwd,
            sessionId = session,
            stdin = stdin,
        )
        val resolved = resolveHandler(cmdName)
            ?: return NativeOffloadResult(127, "$cmdName handler not registered\n")
        return resolved.handle(offloadRequest)
    }

    private fun writeResponse(client: LocalSocket, response: JSONObject) {
        val bytes = response.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_RESPONSE_BYTES) { "response too large" }
        DataOutputStream(client.outputStream).use { output ->
            output.writeInt(bytes.size)
            output.write(bytes)
            output.flush()
        }
    }
}
