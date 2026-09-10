package com.openminis.app.runtime.ubuntu

import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

/**
 * Single-purpose Root outbound proxy for direct Ubuntu guests.
 *
 * Android/VPN BPF policy can block App-UID guest sockets while Root has
 * working egress. This loopback-only helper preserves that compatibility
 * without restoring a generic Root daemon, RPC protocol, or command API.
 * A per-App-process token authenticates every proxy request so another local
 * app cannot borrow the Root-owned listener merely by reaching loopback.
 */
internal object RootNetworkProxy {
    const val PROXY_LISTEN = "127.0.0.1:18787"
    private const val PROXY_USER = "minis"
    private const val TAG = "RootNetworkProxy"
    private const val BINARY = "libminisnetproxy.so"
    private val lock = Mutex()
    private val authToken = randomToken()

    val PROXY_URI: String
        get() = buildProxyUri(authToken)

    data class Status(val ready: Boolean, val detail: String? = null)

    @Volatile
    private var process: Process? = null

    /** True only after the currently owned child itself announced a successful bind. */
    @Volatile
    private var processReady: Boolean = false

    suspend fun ensureReady(context: Context): Status = lock.withLock {
        process?.let { child ->
            if (processReady && child.isAlive && listenerReady()) {
                return@withLock Status(true)
            }
            runCatching { child.destroyForcibly() }
            process = null
            processReady = false
        }

        // A loopback listener without our live, READY-confirmed Process handle
        // is not evidence that the trusted Root proxy is running. Treat an
        // occupied port as a conflict instead of trusting another local process.
        if (listenerReady()) {
            return@withLock Status(false, "$PROXY_LISTEN is already occupied by an unmanaged listener")
        }

        val su = DirectRootRunner.findSu()
            ?: return@withLock Status(false, "Root launcher is unavailable for outbound network proxy")
        val binary = File(context.applicationInfo.nativeLibraryDir, BINARY)
        if (!binary.isFile) {
            return@withLock Status(false, "packaged Root network proxy is missing: ${binary.absolutePath}")
        }

        val command = "exec ${DirectRootRunner.shellQuote(binary.absolutePath)} " +
            "--listen ${DirectRootRunner.shellQuote(PROXY_LISTEN)} --auth-stdin"
        val child = try {
            ProcessBuilder(su, "-c", command)
                .redirectErrorStream(true)
                .start()
        } catch (error: Exception) {
            return@withLock Status(false, "cannot start Root network proxy: ${error.message}")
        }
        process = child
        processReady = false
        try {
            child.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(authToken)
                writer.newLine()
                writer.flush()
            }
        } catch (error: Exception) {
            runCatching { child.destroyForcibly() }
            process = null
            processReady = false
            return@withLock Status(false, "cannot authenticate Root network proxy startup: ${error.message}")
        }
        Thread({
            try {
                child.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (isReadyAnnouncement(line) && process === child) {
                            processReady = true
                        }
                        Log.d(TAG, line)
                    }
                }
            } catch (_: Exception) {
                // Child exit or pipe teardown is observed by the lifecycle poll/next readiness check.
            }
        }, "minis-root-network-proxy-log").apply {
            isDaemon = true
            start()
        }

        repeat(80) {
            if (!child.isAlive) {
                if (process === child) {
                    process = null
                    processReady = false
                }
                return@withLock Status(false, "Root network proxy exited before becoming ready")
            }
            // The TCP probe alone is insufficient: another local process could
            // win the bind race after our preflight check. Trust the listener
            // only after this exact child emitted READY after its successful bind.
            if (processReady && listenerReady() && child.isAlive) return@withLock Status(true)
            delay(25)
        }
        runCatching { child.destroyForcibly() }
        if (process === child) {
            process = null
            processReady = false
        }
        Status(false, "Root network proxy did not bind $PROXY_LISTEN")
    }

    suspend fun stop() = lock.withLock {
        val child = process ?: return@withLock
        process = null
        processReady = false
        runCatching { child.destroy() }
        repeat(20) {
            if (!child.isAlive && !listenerReady()) return@withLock
            delay(25)
        }
        runCatching { child.destroyForcibly() }
        repeat(40) {
            if (!listenerReady()) return@withLock
            delay(25)
        }
        Log.w(TAG, "Root network proxy listener is still bound after stop: $PROXY_LISTEN")
    }

    fun proxyEnv(): Map<String, String> = linkedMapOf(
        "http_proxy" to PROXY_URI,
        "https_proxy" to PROXY_URI,
        "HTTP_PROXY" to PROXY_URI,
        "HTTPS_PROXY" to PROXY_URI,
        "all_proxy" to PROXY_URI,
        "ALL_PROXY" to PROXY_URI,
        "no_proxy" to "localhost,127.0.0.1,::1",
        "NO_PROXY" to "localhost,127.0.0.1,::1",
    )

    internal fun buildProxyUri(token: String): String {
        require(token.length == 64 && token.all { it in '0'..'9' || it in 'a'..'f' }) {
            "proxy token must be 256-bit lowercase hex"
        }
        return "http://$PROXY_USER:$token@$PROXY_LISTEN"
    }

    internal fun isReadyAnnouncement(line: String): Boolean = line == "READY $PROXY_LISTEN"

    private fun randomToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun listenerReady(): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", 18787), 150)
        }
        true
    } catch (_: Exception) {
        false
    }
}
