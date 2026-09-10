package com.openminis.app.runtime.guest

import android.content.Context
import android.os.Process
import android.util.Log
import com.openminis.app.runtime.ubuntu.DirectRootRunner
import com.openminis.app.runtime.ubuntu.UbuntuPaths
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Direct App-owned bridge for guest CLI commands that must call back into
 * Android (`minis-config` and `minis-model-use`).
 *
 * The Ubuntu chroot shares Android's network namespace, so the guest wrappers
 * can connect to an App-owned loopback socket. A per-process 256-bit token
 * authenticates requests. No Root daemon or Unix-socket forwarding layer is
 * involved.
 */
internal object GuestCommandBridge {
    private const val TAG = "GuestCommandBridge"
    private const val MAGIC = "MINISCFG3"
    private const val MAX_ARGC = 128
    private const val MAX_ARG_BYTES = 64 * 1024
    private const val MAX_FILE_BYTES = 2 * 1024 * 1024
    private const val READ_TIMEOUT_MS = 15_000
    private const val INSTALL_TIMEOUT_MS = 15_000L

    data class Endpoint(val port: Int, val token: String)

    @Volatile
    private var listener: ServerSocket? = null

    @Volatile
    private var endpoint: Endpoint? = null

    @Volatile
    private var cliInstalled: Boolean = false

    private val configHandler by lazy { ConfigOffloadHandler() }

    @Synchronized
    fun start(context: Context): Endpoint {
        endpoint?.let { return it }
        val socket = ServerSocket()
        socket.reuseAddress = false
        socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 16)
        val token = randomToken()
        val value = Endpoint(socket.localPort, token)
        listener = socket
        endpoint = value
        thread(name = "guest-command-bridge-accept", isDaemon = true) {
            acceptLoop(socket, token)
        }
        Log.i(TAG, "listening on loopback port=${value.port}")
        return value
    }

    /**
     * Install the authenticated Bash wrappers into the Root-owned rootfs.
     * This is trusted runtime maintenance; guest/model commands themselves are
     * still launched after privilege drop by [com.openminis.app.runtime.ubuntu.UbuntuKernel].
     */
    suspend fun ensureGuestCliInstalled(context: Context): Boolean {
        if (cliInstalled) return true
        val ep = start(context.applicationContext)
        val uid = context.applicationInfo.uid
        if (uid <= 0) return false
        val rootfs = UbuntuPaths.HOST_ROOTFS
        val config = "MINIS_CONFIG_PROXY_PORT=${ep.port}\nMINIS_CONFIG_PROXY_TOKEN='${ep.token}'\n"
        val wrapper = wrapperScript()
        val binDir = "$rootfs/opt/minis/bin"
        val etcDir = "$rootfs/etc/minis"
        val usrLocalBin = "$rootfs/usr/local/bin"
        val script = buildString {
            appendLine("set -eu")
            appendLine("test -f ${DirectRootRunner.shellQuote("$rootfs/etc/os-release")}")
            appendLine("mkdir -p ${DirectRootRunner.shellQuote(binDir)} ${DirectRootRunner.shellQuote(etcDir)} ${DirectRootRunner.shellQuote(usrLocalBin)}")
            appendLine("printf %s ${DirectRootRunner.shellQuote(config)} > ${DirectRootRunner.shellQuote("$etcDir/minis-config-proxy")}")
            appendLine("printf %s ${DirectRootRunner.shellQuote(wrapper)} > ${DirectRootRunner.shellQuote("$binDir/minis-config")}")
            appendLine("cp ${DirectRootRunner.shellQuote("$binDir/minis-config")} ${DirectRootRunner.shellQuote("$binDir/minis-model-use")}")
            appendLine("chmod 755 ${DirectRootRunner.shellQuote("$rootfs/opt")} ${DirectRootRunner.shellQuote("$rootfs/opt/minis")} ${DirectRootRunner.shellQuote(binDir)} ${DirectRootRunner.shellQuote("$binDir/minis-config")} ${DirectRootRunner.shellQuote("$binDir/minis-model-use")}")
            appendLine("chown $uid:$uid ${DirectRootRunner.shellQuote("$etcDir/minis-config-proxy")}")
            appendLine("chmod 600 ${DirectRootRunner.shellQuote("$etcDir/minis-config-proxy")}")
            appendLine("ln -sfn /opt/minis/bin/minis-config ${DirectRootRunner.shellQuote("$usrLocalBin/minis-config")}")
            appendLine("ln -sfn /opt/minis/bin/minis-model-use ${DirectRootRunner.shellQuote("$usrLocalBin/minis-model-use")}")
        }
        val result = DirectRootRunner.runScript(script, INSTALL_TIMEOUT_MS)
        if (!result.success) {
            Log.w(TAG, "guest CLI install failed: ${result.error ?: result.stderr}")
            return false
        }
        cliInstalled = true
        return true
    }

    fun invalidateGuestCli() {
        cliInstalled = false
    }

    internal fun dispatch(
        cmd: String,
        args: List<String>,
        session: String,
        cwd: String,
        stdin: String,
        pid: Int = Process.myPid(),
        resolveHandler: (String) -> NativeOffloadHandler? = { NativeOffloadServer.getHandler(it) },
    ): NativeOffloadResult {
        require(cmd == "minis-config" || cmd == "minis-model-use") { "unsupported command: $cmd" }
        require(args.size <= MAX_ARGC) { "too many arguments" }
        require(args.all { it.toByteArray(Charsets.UTF_8).size <= MAX_ARG_BYTES && !it.contains('\u0000') }) {
            "invalid argument"
        }
        val sessionId = session.takeIf { it.isNotBlank() }
        require(sessionId == null || UbuntuPaths.isSafeSessionId(sessionId)) { "invalid session id" }
        val safeCwd = cwd.ifBlank { "/workspace" }
        require(safeCwd.startsWith('/') && safeCwd.length <= 4096 && !safeCwd.contains('\u0000')) { "invalid cwd" }
        require(stdin.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) { "stdin too large" }

        val handler = resolveHandler(cmd)
            ?: if (cmd == "minis-config") configHandler else null
            ?: return NativeOffloadResult(127, "$cmd handler not registered\n")
        val argv = ArrayList<String>(args.size + 1).apply {
            add(cmd)
            addAll(args)
        }
        return handler.handle(
            NativeOffloadRequest(
                pid = pid,
                argv = argv,
                env = sessionId?.let { mapOf("MINIS_CHAT_SESSION_ID" to it) }.orEmpty(),
                cwd = safeCwd,
                sessionId = sessionId,
                stdin = stdin,
            ),
        )
    }

    private fun acceptLoop(server: ServerSocket, expectedToken: String) {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (error: Throwable) {
                if (!server.isClosed) Log.w(TAG, "accept failed: ${error.message}")
                return
            }
            thread(name = "guest-command-bridge-request", isDaemon = true) {
                socket.use { client ->
                    runCatching { handleClient(client, expectedToken) }
                        .onFailure { Log.w(TAG, "request failed: ${it.message}") }
                }
            }
        }
    }

    private fun handleClient(socket: Socket, expectedToken: String) {
        socket.soTimeout = READ_TIMEOUT_MS
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        try {
            val magic = readLineLimited(input, 32)
            if (magic != MAGIC) return writeResponse(output, 1, "minis-bridge: invalid protocol\n")
            val token = readLineLimited(input, 256)
            if (!MessageDigest.isEqual(expectedToken.toByteArray(), token.toByteArray())) {
                return writeResponse(output, 126, "minis-bridge: permission denied\n")
            }
            val cmd = readLineLimited(input, 64)
            if (cmd != "minis-config" && cmd != "minis-model-use") {
                return writeResponse(output, 126, "minis-bridge: unsupported command\n")
            }
            val argc = readLineLimited(input, 16).toIntOrNull()
                ?: return writeResponse(output, 1, "minis-bridge: invalid argc\n")
            if (argc !in 0..MAX_ARGC) return writeResponse(output, 1, "minis-bridge: too many arguments\n")

            val args = ArrayList<String>(argc)
            repeat(argc) {
                val bytes = hexDecode(readLineLimited(input, MAX_ARG_BYTES * 2 + 2), MAX_ARG_BYTES)
                val arg = bytes.toString(Charsets.UTF_8)
                if (arg.contains('\u0000')) return writeResponse(output, 1, "minis-bridge: NUL in argument\n")
                args += arg
            }
            val session = hexDecode(
                readLineLimited(input, MAX_ARG_BYTES * 2 + 2),
                MAX_ARG_BYTES,
            ).toString(Charsets.UTF_8)
            val cwd = hexDecode(
                readLineLimited(input, MAX_ARG_BYTES * 2 + 2),
                MAX_ARG_BYTES,
            ).toString(Charsets.UTF_8)
            val filePayload = when (readLineLimited(input, 4)) {
                "0" -> null
                "1" -> hexDecode(readLineLimited(input, MAX_FILE_BYTES * 2 + 2), MAX_FILE_BYTES)
                else -> return writeResponse(output, 1, "minis-bridge: invalid file marker\n")
            }
            val stdin = hexDecode(
                readLineLimited(input, MAX_FILE_BYTES * 2 + 2),
                MAX_FILE_BYTES,
            ).toString(Charsets.UTF_8)
            val rewritten = try {
                rewriteFileArgument(args, filePayload)
            } catch (error: IllegalArgumentException) {
                return writeResponse(output, 1, "minis-bridge: ${error.message}\n")
            }
            val result = try {
                dispatch(cmd, rewritten, session, cwd, stdin)
            } catch (error: IllegalArgumentException) {
                NativeOffloadResult(1, "minis-bridge: ${error.message}\n")
            } catch (error: Throwable) {
                Log.w(TAG, "handler failed for $cmd: ${error.message}", error)
                NativeOffloadResult(1, "minis-bridge: handler failed\n")
            }
            writeResponse(output, result.exitCode.coerceIn(0, 255), result.output)
        } catch (error: Throwable) {
            writeResponse(output, 1, "minis-bridge: ${error.message ?: "invalid request"}\n")
        }
    }

    internal fun rewriteFileArgument(args: List<String>, payload: ByteArray?): List<String> {
        if (payload == null) return args
        val index = args.indexOf("--file")
        require(index >= 0) { "file payload supplied without --file" }
        require(index + 1 < args.size) { "--file requires a path" }
        val text = payload.toString(Charsets.UTF_8)
        return args.toMutableList().apply {
            removeAt(index + 1)
            this[index] = text
        }
    }

    private fun readLineLimited(input: BufferedInputStream, maxBytes: Int): String {
        val out = ByteArrayOutputStream(minOf(maxBytes, 1024))
        while (true) {
            val value = input.read()
            if (value < 0) throw IllegalArgumentException("unexpected EOF")
            if (value == '\n'.code) break
            if (out.size() >= maxBytes) throw IllegalArgumentException("bridge line too large")
            if (value != '\r'.code) out.write(value)
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }

    private fun hexDecode(value: String, maxBytes: Int): ByteArray {
        require(value.length % 2 == 0) { "invalid hex payload" }
        require(value.length / 2 <= maxBytes) { "payload too large" }
        val out = ByteArray(value.length / 2)
        var i = 0
        while (i < value.length) {
            val hi = Character.digit(value[i], 16)
            val lo = Character.digit(value[i + 1], 16)
            require(hi >= 0 && lo >= 0) { "invalid hex payload" }
            out[i / 2] = ((hi shl 4) or lo).toByte()
            i += 2
        }
        return out
    }

    private fun writeResponse(output: BufferedOutputStream, exitCode: Int, body: String) {
        output.write("${exitCode.coerceIn(0, 255)}\n".toByteArray(Charsets.UTF_8))
        output.write(body.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun randomToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun wrapperScript(): String = """#!/bin/bash
set -u
cmd="${'$'}(basename "${'$'}0")"
CFG=/etc/minis/minis-config-proxy
if [ ! -r "${'$'}CFG" ]; then
  echo "${'$'}cmd: Minis bridge is not initialized" >&2
  exit 127
fi
. "${'$'}CFG"
if [ -z "${'$'}{MINIS_CONFIG_PROXY_PORT:-}" ] || [ -z "${'$'}{MINIS_CONFIG_PROXY_TOKEN:-}" ]; then
  echo "${'$'}cmd: invalid Minis bridge configuration" >&2
  exit 127
fi
stdin_hex=''
has_input=false
for arg in "${'$'}@"; do
  case "${'$'}arg" in --input|--input=*) has_input=true ;; esac
done
if [ "${'$'}cmd" = 'minis-model-use' ] && [ "${'$'}{1:-}" = 'run' ] && [ "${'$'}has_input" = false ] && [ ! -t 0 ]; then
  stdin_hex="${'$'}(head -c 2097153 | od -An -v -tx1 | tr -d ' \\n')"
  if [ "${'$'}{#stdin_hex}" -gt 4194304 ]; then
    echo "${'$'}cmd: stdin exceeds 2 MiB" >&2
    exit 1
  fi
fi
exec 3<>"/dev/tcp/127.0.0.1/${'$'}{MINIS_CONFIG_PROXY_PORT}" || {
  echo "${'$'}cmd: cannot connect to Minis bridge" >&2
  exit 127
}
printf 'MINISCFG3\\n%s\\n%s\\n%s\\n' "${'$'}MINIS_CONFIG_PROXY_TOKEN" "${'$'}cmd" "${'$'}#" >&3
for arg in "${'$'}@"; do
  printf '%s' "${'$'}arg" | od -An -v -tx1 | tr -d ' \\n' >&3
  printf '\\n' >&3
done
printf '%s' "${'$'}{MINIS_CHAT_SESSION_ID:-}" | od -An -v -tx1 | tr -d ' \\n' >&3
printf '\\n' >&3
printf '%s' "${'$'}PWD" | od -An -v -tx1 | tr -d ' \\n' >&3
printf '\\n' >&3
file_path=''
prev=''
for arg in "${'$'}@"; do
  if [ "${'$'}cmd" = 'minis-config' ] && [ "${'$'}prev" = '--file' ]; then
    file_path="${'$'}arg"
    break
  fi
  prev="${'$'}arg"
done
if [ -n "${'$'}file_path" ]; then
  if [ ! -r "${'$'}file_path" ] || [ ! -f "${'$'}file_path" ]; then
    echo "${'$'}cmd: --file cannot read '${'$'}file_path'" >&2
    exit 1
  fi
  if [ "${'$'}(wc -c < "${'$'}file_path")" -gt 2097152 ]; then
    echo "${'$'}cmd: --file exceeds 2 MiB" >&2
    exit 1
  fi
  printf '1\\n' >&3
  od -An -v -tx1 -- "${'$'}file_path" | tr -d ' \\n' >&3
  printf '\\n' >&3
else
  printf '0\\n' >&3
fi
printf '%s\\n' "${'$'}stdin_hex" >&3
if ! IFS= read -r exit_code <&3; then
  echo "${'$'}cmd: Minis bridge closed without a response" >&2
  exit 1
fi
cat <&3
case "${'$'}exit_code" in
  ''|*[!0-9]*) exit 1 ;;
  *) exit "${'$'}exit_code" ;;
esac
"""
}
