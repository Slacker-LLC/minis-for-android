package io.github.slackerllc.minis.runtime.minisd

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import io.github.slackerllc.minis.runtime.guest.NativeOffloadRequest
import io.github.slackerllc.minis.runtime.guest.ConfigOffloadHandler
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
        val length = input.readInt()
        require(length in 1..MAX_REQUEST_BYTES) { "invalid request length $length" }
        val payload = ByteArray(length)
        input.readFully(payload)
        val request = JSONObject(String(payload, Charsets.UTF_8))

        val rawArgv = request.optJSONArray("argv") ?: JSONArray()
        require(rawArgv.length() in 1..128) { "argv missing or too large" }
        val argv = ArrayList<String>(rawArgv.length())
        for (i in 0 until rawArgv.length()) {
            val arg = rawArgv.optString(i, null) ?: error("argv[$i] is not a string")
            require(!arg.contains('\u0000')) { "NUL in argv[$i]" }
            argv += arg
        }
        require(argv.first().substringAfterLast('/') == "minis-config") {
            "unsupported bridge command"
        }

        val session = request.optString("session", "").takeIf { it.isNotBlank() }
        val cwd = request.optString("cwd", "/workspace").ifBlank { "/workspace" }
        val env = if (session == null) {
            emptyMap()
        } else {
            mapOf("MINIS_CHAT_SESSION_ID" to session)
        }

        val result = handler.handle(
            NativeOffloadRequest(
                pid = peer.pid,
                argv = argv,
                env = env,
                cwd = cwd,
                sessionId = session,
            ),
        )
        writeResponse(
            client,
            JSONObject()
                .put("exit_code", result.exitCode)
                .put("output", result.output),
        )
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
