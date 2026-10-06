package com.openminis.app.auth

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI

class OAuthCallbackServer(
    private val port: Int,
    private val fallbackPorts: List<Int> = emptyList(),
    private val expectedPath: String? = null,
    private val expectedState: String? = null,
    private val onCode: (code: String, state: String?) -> Unit,
) {
    companion object {
        /**
         * The request line without its query string: `GET /callback?code=…&state=…` becomes
         * `GET /callback (query: yes)`. The authorization code and state must not reach the log.
         */
        internal fun loggableRequestLine(requestLine: String): String {
            val parts = requestLine.split(' ')
            val method = parts.firstOrNull().orEmpty().take(16)
            val target = parts.getOrNull(1).orEmpty()
            val path = target.substringBefore('?').take(120)
            return "${"$method $path".trim()} (query: ${if ('?' in target) "yes" else "no"})"
        }

        private const val TAG = "OAuthCallbackServer"
        private const val MAX_LINE_CHARS = 8 * 1024
        private const val MAX_HEADER_LINES = 64

        /**
         * Key/value pairs of a raw (still percent-encoded) query. Each is form-decoded exactly once,
         * after splitting: decoding first turns `code=a%26b` into `code=a&b`, and decoding twice turns
         * `a%2Bb` into `a b`.
         */
        internal fun parseQuery(rawQuery: String?): Map<String, String> {
            if (rawQuery.isNullOrEmpty()) return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (pair in rawQuery.split('&')) {
                if (pair.isEmpty()) continue
                val kv = pair.split('=', limit = 2)
                val key = runCatching { java.net.URLDecoder.decode(kv[0], "UTF-8") }.getOrNull() ?: continue
                val value = if (kv.size > 1) {
                    runCatching { java.net.URLDecoder.decode(kv[1], "UTF-8") }.getOrNull() ?: continue
                } else ""
                out.putIfAbsent(key, value)
            }
            return out
        }

        /** A line of at most [MAX_LINE_CHARS] characters, or null at end of stream; throws past the limit. */
        internal fun readBoundedLine(reader: java.io.Reader): String? {
            val sb = StringBuilder()
            while (true) {
                val c = reader.read()
                if (c < 0) return if (sb.isEmpty()) null else sb.toString()
                if (c == '\n'.code) return sb.toString().removeSuffix("\r")
                if (sb.length >= MAX_LINE_CHARS) throw java.io.IOException("request line too long")
                sb.append(c.toChar())
            }
        }
    }

    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    /** The port actually bound (may differ from [port] if fallback was used). */
    var boundPort: Int = port
        private set

    /**
     * Invoked by [stop] when the server is shut down by an external
     * caller (e.g. the user dismisses Chrome Custom Tab and the auth
     * manager wants to abort the in-flight wait without waiting for the
     * 5-minute timeout). The callback is _only_ fired when stop() is
     * called by something other than the success path —
     * [onCode] callers set this to null before calling stop() so they
     * don't fire a cancel after a successful redirect.
     *
     * [T-xai-oauth-stop-resume, port iOS d1dbdd5d]
     */
    @Volatile var onExternalCancel: (() -> Unit)? = null

    fun start() {
        running = true
        Thread {
            try {
                val portsToTry = listOf(port) + fallbackPorts
                var bound = false
                for (p in portsToTry) {
                    try {
                        // OAuth redirect URIs use localhost. Binding the
                        // wildcard address makes an in-progress login
                        // reachable (and abortable) by every LAN peer.
                        serverSocket = ServerSocket().apply {
                            reuseAddress = true
                            soTimeout = 10_000
                            bind(InetSocketAddress(InetAddress.getLoopbackAddress(), p))
                        }
                        boundPort = p
                        bound = true
                        break
                    } catch (e: java.net.BindException) {
                        Log.w(TAG, "Port $p in use, trying next...")
                    }
                }
                if (!bound) {
                    Log.e(TAG, "All ports unavailable: $portsToTry")
                    // Tell whoever is waiting for a code that none can arrive.
                    stop()
                    return@Thread
                }
                Log.d(TAG, "Listening on port $boundPort")
                while (running) {
                    val socket = try {
                        serverSocket?.accept() ?: break
                    } catch (_: java.net.SocketTimeoutException) {
                        continue
                    }
                    try {
                        socket.soTimeout = 10_000
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                        val requestLine = readBoundedLine(reader) ?: continue
                        Log.d(TAG, "Request: ${loggableRequestLine(requestLine)}")

                        // CORS preflight for providers (e.g. xAI) that
                        // OPTIONS /callback from their authorization page
                        // before redirecting the browser. Without this we
                        // return a 404 to the preflight and the browser
                        // never follows the redirect — OAuth stalls.
                        // Read the rest of the headers to find Origin and
                        // only echo back permissive CORS for known xAI hosts.
                        if (requestLine.startsWith("OPTIONS")) {
                            var origin: String? = null
                            var headerLines = 0
                            while (true) {
                                val h = readBoundedLine(reader) ?: break
                                if (h.isEmpty()) break
                                if (++headerLines > MAX_HEADER_LINES) throw java.io.IOException("too many header lines")
                                val lower = h.lowercase()
                                if (lower.startsWith("origin:")) {
                                    origin = h.substringAfter(":").trim()
                                }
                            }
                            val trustedHosts = listOf("auth.x.ai", "accounts.x.ai")
                            val allowOrigin = if (origin != null && trustedHosts.any { origin!!.contains(it) }) {
                                origin
                            } else {
                                "null"
                            }
                            val pre = "HTTP/1.1 204 No Content\r\n" +
                                "Access-Control-Allow-Origin: $allowOrigin\r\n" +
                                "Access-Control-Allow-Methods: GET, OPTIONS\r\n" +
                                "Access-Control-Allow-Headers: *\r\n" +
                                "Access-Control-Max-Age: 600\r\n" +
                                "Connection: close\r\n\r\n"
                            socket.getOutputStream().write(pre.toByteArray())
                            socket.close()
                            continue
                        }

                        // Parse GET /callback?code=xxx&state=yyy HTTP/1.1
                        val parts = requestLine.split(" ")
                        if (parts.size >= 2) {
                            val uri = URI("http://localhost${ parts[1] }")
                            val params = parseQuery(uri.rawQuery)

                            val code = params["code"]
                            val state = params["state"]

                            if (expectedPath != null && uri.path != expectedPath) {
                                socket.close()
                                continue
                            }
                            if (expectedState != null && state != expectedState) {
                                val response = "HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n"
                                socket.getOutputStream().write(response.toByteArray())
                                socket.close()
                                continue
                            }

                            // Send response
                            val html = "<html><body><h1>Authorization complete</h1><p>You can close this tab.</p><script>window.close()</script></body></html>"
                            val response = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.length}\r\nConnection: close\r\n\r\n$html"
                            socket.getOutputStream().write(response.toByteArray())
                            socket.close()

                            if (code != null) {
                                onCode(code, state)
                                stop()
                                return@Thread
                            }
                            // The provider reported a failure (the user pressed Deny): no code will
                            // follow, so end the wait instead of listening until it times out.
                            if (params["error"] != null) {
                                Log.w(TAG, "Authorization ended with error=${params["error"]}")
                                stop()
                                return@Thread
                            }
                        }
                        socket.close()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error handling connection", e)
                        try { socket.close() } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "Server error", e)
            }
        }.start()
    }

    fun stop() {
        val wasRunning = running
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        // Notify external callers (e.g. XAIOAuthManager) so they can
        // cancel an in-flight suspendCancellableCoroutine instead of
        // hanging until the next inbound connection / the 5-min
        // OAuth wait timeout fires. Consume the callback (set to
        // null before invoking) so re-entrant stop() calls don't
        // double-fire (T-xai-oauth-stop-resume).
        if (wasRunning) {
            val cancel = onExternalCancel
            onExternalCancel = null
            try { cancel?.invoke() } catch (e: Exception) {
                Log.w(TAG, "onExternalCancel threw: ${e.message}")
            }
        }
    }
}
