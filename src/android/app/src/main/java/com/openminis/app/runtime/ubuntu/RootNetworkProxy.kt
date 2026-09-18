package com.openminis.app.runtime.ubuntu

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit

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
    internal val PROXY_ENV_KEYS: Set<String> = setOf(
        "http_proxy",
        "https_proxy",
        "HTTP_PROXY",
        "HTTPS_PROXY",
        "all_proxy",
        "ALL_PROXY",
        "no_proxy",
        "NO_PROXY",
    )
    private const val PROXY_USER = "minis"
    private const val TAG = "RootNetworkProxy"
    private const val BINARY = "libminisnetproxy.so"
    private const val CLEANUP_TIMEOUT_MS = 900L
    private val lock = Mutex()
    private val authToken = randomToken()

    val PROXY_URI: String
        get() = buildProxyUri(authToken)

    data class Status(val ready: Boolean, val detail: String? = null)

    @Volatile
    private var process: Process? = null

    @Volatile
    private var processPidFile: File? = null

    /** True only after the currently owned child itself announced a successful bind. */
    @Volatile
    private var processReady: Boolean = false

    suspend fun ensureReady(context: Context): Status = lock.withLock {
        process?.let { child ->
            if (processReady && child.isAlive && listenerReady()) {
                return@withLock Status(true)
            }
            val stalePidFile = processPidFile
            process = null
            processPidFile = null
            processReady = false
            terminateManagedProxy(child, stalePidFile)
            repeat(20) {
                if (!listenerReady()) return@repeat
                delay(25)
            }
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
        val uid = context.applicationInfo.uid
        if (uid <= 0) {
            return@withLock Status(false, "invalid Android app uid for outbound network proxy")
        }
        // The Root launcher writes this marker before starting the
        // Root-owned helper. An App-writable cache directory would allow a
        // guest to race the path with a symlink and redirect a Root write.
        val pidDir = File(DirectRootRunner.ROOT_STATE_DIR, "proxy")
        val pidFile = File(pidDir, "proxy-${UUID.randomUUID()}.pid")
        val command = buildLaunchCommand(binary.absolutePath, pidFile.absolutePath, uid)
        val child = try {
            ProcessBuilder(su, "-c", command)
                .redirectErrorStream(true)
                .start()
        } catch (error: Exception) {
            return@withLock Status(false, "cannot start Root network proxy: ${error.message}")
        }
        process = child
        processPidFile = pidFile
        processReady = false
        try {
            child.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(authToken)
                writer.newLine()
                writer.flush()
            }
        } catch (error: Exception) {
            process = null
            processPidFile = null
            processReady = false
            terminateManagedProxy(child, pidFile)
            return@withLock Status(false, "cannot authenticate Root network proxy startup: ${error.message}")
        }
        try {
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
                    clearOwnedProcess(child, pidFile)
                    return@withLock Status(false, "Root network proxy exited before becoming ready")
                }
                // The TCP probe alone is insufficient: another local process could
                // win the bind race after our preflight check. Trust the listener
                // only after this exact child emitted READY after its successful bind.
                if (processReady && listenerReady() && child.isAlive) return@withLock Status(true)
                delay(25)
            }
            clearOwnedProcess(child, pidFile)
            Status(false, "Root network proxy did not bind $PROXY_LISTEN")
        } catch (cancelled: CancellationException) {
            clearOwnedProcess(child, pidFile)
            throw cancelled
        } catch (error: Exception) {
            clearOwnedProcess(child, pidFile)
            Status(false, "Root network proxy startup failed: ${error.message}")
        }
    }

    suspend fun stop() = lock.withLock {
        val child = process
        val pidFile = processPidFile
        process = null
        processPidFile = null
        processReady = false
        terminateManagedProxy(child, pidFile)
        repeat(40) {
            if (!listenerReady()) return@withLock
            delay(25)
        }
        Log.w(TAG, "Root network proxy listener is still bound after stop: $PROXY_LISTEN")
    }

    /**
     * Return proxy variables only while this process owns a live helper.
     * Direct App-UID guest networking is a valid deployment, so an absent or
     * failed compatibility helper must not poison the guest with a dead URI.
     */
    fun proxyEnv(): Map<String, String> {
        val child = process
        if (!processReady || child == null || !child.isAlive) return emptyMap()
        return buildProxyEnv(PROXY_URI)
    }

    internal fun buildProxyEnv(proxyUri: String): Map<String, String> = linkedMapOf(
        "http_proxy" to proxyUri,
        "https_proxy" to proxyUri,
        "HTTP_PROXY" to proxyUri,
        "HTTPS_PROXY" to proxyUri,
        "all_proxy" to proxyUri,
        "ALL_PROXY" to proxyUri,
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

    internal fun buildLaunchCommand(binaryPath: String, pidFilePath: String, uid: Int): String {
        require(uid > 0) { "invalid app uid" }
        val pidParent = File(pidFilePath).parent ?: pidFilePath
        val child = "umask 077; " +
            "mkdir -p ${DirectRootRunner.shellQuote(pidParent)} || exit 126; " +
            "chmod 711 ${DirectRootRunner.shellQuote(pidParent)} || exit 126; " +
            "echo \$\$ > ${DirectRootRunner.shellQuote(pidFilePath)} || exit 126; " +
            "exec ${DirectRootRunner.shellQuote(binaryPath)} " +
            "--listen ${DirectRootRunner.shellQuote(PROXY_LISTEN)} --auth-stdin"
        return "if [ -x /system/bin/setsid ]; then " +
            "exec /system/bin/setsid /system/bin/sh -c ${DirectRootRunner.shellQuote(child)}; " +
            "else echo 'setsid is required for isolated Root proxy' >&2; exit 125; fi"
    }

    internal fun buildCleanupCommand(pidFilePath: String): String =
        "PID=\$(cat ${DirectRootRunner.shellQuote(pidFilePath)} 2>/dev/null || true); " +
            "case \"\$PID\" in ''|*[!0-9]*) ;; *) " +
            "if [ \"\$PID\" -gt 1 ]; then " +
            "kill -TERM -\$PID 2>/dev/null || kill -TERM \$PID 2>/dev/null || true; " +
            "sleep 0.05; " +
            "kill -KILL -\$PID 2>/dev/null || kill -KILL \$PID 2>/dev/null || true; " +
            "fi ;; esac; rm -f -- ${DirectRootRunner.shellQuote(pidFilePath)}"

    private fun clearOwnedProcess(child: Process, pidFile: File) {
        if (process === child) {
            process = null
            processPidFile = null
            processReady = false
        }
        terminateManagedProxy(child, pidFile)
    }

    private fun terminateManagedProxy(child: Process?, pidFile: File?) {
        val su = DirectRootRunner.findSu()
        if (su != null && pidFile != null) {
            try {
                val killer = ProcessBuilder(
                    su,
                    "-c",
                    buildCleanupCommand(pidFile.absolutePath),
                ).redirectErrorStream(true).start()
                if (!killer.waitFor(CLEANUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    killer.destroyForcibly()
                } else {
                    killer.destroy()
                }
            } catch (_: Exception) {
                // Fall through to the directly owned launcher Process.
            }
        }
        if (child != null) {
            try {
                child.destroy()
            } catch (_: Exception) {
                // Continue with the forcible fallback below.
            }
            if (child.isAlive) {
                try {
                    child.destroyForcibly()
                } catch (_: Exception) {
                    // Listener verification in stop/ensureReady catches leftovers.
                }
            }
        }
        try {
            pidFile?.delete()
        } catch (_: Exception) {
            // Best-effort cleanup of the App-owned lifecycle marker.
        }
    }

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
