package com.openminis.app.runtime.guest

import android.content.Context
import android.os.Process
import android.util.Log
import com.openminis.app.runtime.ubuntu.DirectRootRunner
import com.openminis.app.runtime.ubuntu.UbuntuKernel
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Direct App-owned bridge for guest CLI commands that must call back into
 * Android. This includes the core configuration/model commands and every
 * currently registered Android/Minis offload handler.
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
    private const val MAX_CONCURRENT_REQUESTS = 16
    private const val READ_TIMEOUT_MS = 15_000
    private const val INSTALL_TIMEOUT_MS = 15_000L

    data class Endpoint(val port: Int, val token: String)

    @Volatile
    private var listener: ServerSocket? = null

    @Volatile
    private var endpoint: Endpoint? = null

    @Volatile
    private var cliInstalled: Boolean = false

    @Volatile
    private var installedCommandNames: Set<String> = emptySet()

    private val configHandler by lazy { ConfigOffloadHandler() }
    private val requestLimiter = GuestBridgeConnectionLimiter(MAX_CONCURRENT_REQUESTS)
    private val workers = java.util.concurrent.ConcurrentHashMap<Socket, Thread>()

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

    @Synchronized
    fun stop() {
        runCatching { listener?.close() }
        workers.forEach { (socket, worker) ->
            runCatching { socket.close() }
            worker.interrupt()
        }
        listener = null
        endpoint = null
        cliInstalled = false
        installedCommandNames = emptySet()
    }

    /**
     * Install the authenticated Bash wrappers into the Root-owned rootfs.
     * This is trusted runtime maintenance; guest/model commands themselves are
     * still launched after privilege drop by [com.openminis.app.runtime.ubuntu.UbuntuKernel].
     * The command set follows NativeOffloadServer registration so a new
     * handler cannot silently be registered without a corresponding PATH
     * entry on the next Direct Ubuntu start.
     */
    suspend fun ensureGuestCliInstalled(context: Context): Boolean {
        val commandNames = managedCommandNames(NativeOffloadServer.registeredHandlers)
        if (cliInstalled && installedCommandNames == commandNames) return true
        val ep = start(context.applicationContext)
        val identity = try {
            UbuntuKernel.currentAppIdentity(context)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "cannot read App UID/GID: ${error.message}")
            return false
        }
        val rootfs = UbuntuPaths.HOST_ROOTFS
        val config = "MINIS_CONFIG_PROXY_PORT=${ep.port}\nMINIS_CONFIG_PROXY_TOKEN='${ep.token}'\n"
        val wrapper = wrapperScript()
        val urlWrapper = minisOpenWrapperScript()
        val binDir = "$rootfs/opt/minis/bin"
        val etcDir = "$rootfs/etc/minis"
        val usrLocalBin = "$rootfs/usr/local/bin"
        val configFile = "$etcDir/minis-config-proxy"
        val configWrapper = "$binDir/minis-config"
        val modelWrapper = "$binDir/minis-model-use"
        val configLink = "$usrLocalBin/minis-config"
        val modelLink = "$usrLocalBin/minis-model-use"
        val bridgeCommands = commandNames.filter {
            it !in CORE_COMMAND_NAMES && it !in URL_COMMAND_NAMES
        }
        val bridgePaths = bridgeCommands.map { "$usrLocalBin/$it" }
        val urlPaths = URL_COMMAND_NAMES.map { "$usrLocalBin/$it" }
        // The two core entries are managed symlinks. Validate/replace them
        // below instead of treating an already-installed exact symlink as a
        // hostile generated file.
        val generatedFiles = listOf(configFile, configWrapper, modelWrapper) + bridgePaths + urlPaths
        val script = buildString {
            appendLine("set -eu")
            // Ubuntu ships /etc/os-release as a relative symlink into /usr.
            // Keep the same exact-target exception as rootfs health checks;
            // rejecting the distro's standard link makes the bridge fail
            // after an otherwise healthy rootfs has been provisioned.
            noSymlinkRootfsPathGuards(rootfs, "etc", leafMustBeDirectory = true)
                .forEach(::appendLine)
            appendLine(osReleaseGuard(rootfs))
            listOf("opt/minis/bin", "etc/minis", "usr/local/bin").forEach { relative ->
                noSymlinkRootfsPathGuards(rootfs, relative, leafMustBeDirectory = true)
                    .forEach(::appendLine)
            }
            appendLine("mkdir -p ${DirectRootRunner.shellQuote(binDir)} ${DirectRootRunner.shellQuote(etcDir)} ${DirectRootRunner.shellQuote(usrLocalBin)}")
            generatedFiles.forEach { file ->
                val quoted = DirectRootRunner.shellQuote(file)
                appendLine("[ ! -L $quoted ] || exit 75")
                appendLine("if [ -e $quoted ] && [ ! -f $quoted ]; then exit 75; fi")
            }
            appendLine("printf %s ${DirectRootRunner.shellQuote(config)} > ${DirectRootRunner.shellQuote(configFile)}")
            appendLine("printf %s ${DirectRootRunner.shellQuote(wrapper)} > ${DirectRootRunner.shellQuote(configWrapper)}")
            appendLine("cp ${DirectRootRunner.shellQuote(configWrapper)} ${DirectRootRunner.shellQuote(modelWrapper)}")
            bridgePaths.forEach { path ->
                appendLine("printf %s ${DirectRootRunner.shellQuote(wrapper)} > ${DirectRootRunner.shellQuote(path)}")
            }
            urlPaths.forEach { path ->
                appendLine("printf %s ${DirectRootRunner.shellQuote(urlWrapper)} > ${DirectRootRunner.shellQuote(path)}")
            }
            val executablePaths = listOf(configWrapper, modelWrapper) + bridgePaths + urlPaths
            appendLine(
                "chmod 755 " + listOf(
                    DirectRootRunner.shellQuote("$rootfs/opt"),
                    DirectRootRunner.shellQuote("$rootfs/opt/minis"),
                    DirectRootRunner.shellQuote(binDir),
                ).plus(executablePaths.map(DirectRootRunner::shellQuote)).joinToString(" "),
            )
            appendLine("chown ${identity.uid}:${identity.gid} ${DirectRootRunner.shellQuote(configFile)}")
            appendLine("chmod 600 ${DirectRootRunner.shellQuote(configFile)}")
            listOf(configLink, modelLink).forEach { link ->
                val quoted = DirectRootRunner.shellQuote(link)
                val expectedTarget = if (link == configLink) "/opt/minis/bin/minis-config" else "/opt/minis/bin/minis-model-use"
                // Older rootfs revisions used ordinary handler stubs at these
                // two paths. They are application-managed command slots, so
                // replace a regular old file while still rejecting a special
                // file or an unexpected symlink.
                appendLine("if [ -L $quoted ]; then")
                appendLine("  existing_target=\"\$(readlink $quoted 2>/dev/null || true)\"")
                appendLine(
                    "  if [ \"\$existing_target\" != '${expectedTarget}' ]; then echo \"guest-cli: unexpected symlink $link -> \$existing_target\" >&2; exit 76; fi",
                )
                appendLine("  rm -f -- $quoted")
                appendLine("fi")
                appendLine("if [ -e $quoted ] && [ ! -f $quoted ]; then echo \"guest-cli: unsupported core path $link\" >&2; exit 76; fi")
                appendLine("if [ -f $quoted ]; then rm -f -- $quoted; fi")
            }
            appendLine("ln -s /opt/minis/bin/minis-config ${DirectRootRunner.shellQuote(configLink)}")
            appendLine("ln -s /opt/minis/bin/minis-model-use ${DirectRootRunner.shellQuote(modelLink)}")
        }
        val result = DirectRootRunner.runScript(script, INSTALL_TIMEOUT_MS)
        if (!result.success) {
            val detail = result.error ?: listOfNotNull(
                result.stderr.takeIf { it.isNotBlank() }?.let { "stderr=${it.take(600)}" },
                result.stdout.takeIf { it.isNotBlank() }?.let { "stdout=${it.take(600)}" },
                "exit=${result.exitCode}",
            ).joinToString(" ")
            Log.w(TAG, "guest CLI install failed: $detail")
            return false
        }
        cliInstalled = true
        installedCommandNames = commandNames
        return true
    }

    fun invalidateGuestCli() {
        cliInstalled = false
        installedCommandNames = emptySet()
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
        require(isSupportedBridgeCommand(cmd)) { "unsupported command: $cmd" }
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
            } catch (error: Exception) {
                if (!server.isClosed) Log.w(TAG, "accept failed: ${error.message}")
                return
            }
            if (!requestLimiter.tryAcquire()) {
                try {
                    socket.close()
                } catch (_: Exception) {
                    // Best effort while rejecting excess work.
                }
                Log.w(TAG, "rejecting bridge connection: too many concurrent requests")
                continue
            }
            try {
                thread(name = "guest-command-bridge-request", isDaemon = true) {
                    workers[socket] = Thread.currentThread()
                    try {
                        if (server.isClosed) {
                            socket.close()
                            return@thread
                        }
                        socket.use { client ->
                            try {
                                handleClient(client, expectedToken)
                            } catch (error: Exception) {
                                Log.w(TAG, "request failed: ${error.message}")
                            }
                        }
                    } finally {
                        workers.remove(socket)
                        requestLimiter.release()
                    }
                }
            } catch (error: Exception) {
                requestLimiter.release()
                try {
                    socket.close()
                } catch (_: Exception) {
                    // Best effort after worker creation failed.
                }
                Log.w(TAG, "cannot start bridge request worker: ${error.message}")
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
            if (!isSupportedBridgeCommand(cmd)) {
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
            // One request per connection. The wrapper keeps its write side open
            // while awaiting the result; EOF means its owning command exited.
            socket.soTimeout = 0
            val worker = Thread.currentThread()
            val finished = java.util.concurrent.atomic.AtomicBoolean(false)
            val disconnectWatcher = thread(name = "guest-command-disconnect", isDaemon = true) {
                try { input.read() } catch (_: Exception) { }
                if (!finished.get()) worker.interrupt()
            }
            val result = try {
                dispatch(cmd, rewritten, session, cwd, stdin)
            } catch (error: IllegalArgumentException) {
                NativeOffloadResult(1, "minis-bridge: ${error.message}\n")
            } catch (error: Exception) {
                Log.w(TAG, "handler failed for $cmd: ${error.message}", error)
                NativeOffloadResult(1, "minis-bridge: handler failed\n")
            } finally {
                finished.set(true)
            }
            Log.d(TAG, "handled command=$cmd exit=${result.exitCode} outputChars=${result.output.length}")
            writeResponse(output, result.exitCode.coerceIn(0, 255), result.output)
            socket.close()
            disconnectWatcher.join(1000)
        } catch (error: Exception) {
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

    private fun noSymlinkRootfsPathGuards(
        rootfs: String,
        relativePath: String,
        leafMustBeDirectory: Boolean,
    ): List<String> {
        val root = rootfs.trimEnd('/')
        var current = root
        return buildList {
            add("[ -d ${DirectRootRunner.shellQuote(current)} ] && [ ! -L ${DirectRootRunner.shellQuote(current)} ] || exit 72")
            val components = relativePath.split('/').filter { it.isNotEmpty() }
            components.forEachIndexed { index, component ->
                current = "$current/$component"
                val quoted = DirectRootRunner.shellQuote(current)
                add("[ ! -L $quoted ] || exit 73")
                if (index < components.lastIndex || leafMustBeDirectory) {
                    add("if [ -e $quoted ] && [ ! -d $quoted ]; then exit 74; fi")
                }
            }
        }
    }

    private fun osReleaseGuard(rootfs: String): String {
        val root = rootfs.trimEnd('/')
        val link = DirectRootRunner.shellQuote("$root/etc/os-release")
        val target = DirectRootRunner.shellQuote("$root/usr/lib/os-release")
        return "if [ -L $link ]; then " +
            "[ \"\$(readlink $link 2>/dev/null || true)\" = '../usr/lib/os-release' ] || exit 73; " +
            "[ -f $target ] && [ ! -L $target ] || exit 74; " +
            "else [ -f $link ] && [ ! -L $link ] || exit 75; fi"
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
  stdin_hex="${'$'}(head -c 2097153 | od -An -v -tx1 | tr -d ' \n')"
  if [ "${'$'}{#stdin_hex}" -gt 4194304 ]; then
    echo "${'$'}cmd: stdin exceeds 2 MiB" >&2
    exit 1
  fi
fi
exec 3<>"/dev/tcp/127.0.0.1/${'$'}{MINIS_CONFIG_PROXY_PORT}" || {
  echo "${'$'}cmd: cannot connect to Minis bridge" >&2
  exit 127
}
printf 'MINISCFG3\n%s\n%s\n%s\n' "${'$'}MINIS_CONFIG_PROXY_TOKEN" "${'$'}cmd" "${'$'}#" >&3
for arg in "${'$'}@"; do
  printf '%s' "${'$'}arg" | od -An -v -tx1 | tr -d ' \n' >&3
  printf '\n' >&3
done
printf '%s' "${'$'}{MINIS_CHAT_SESSION_ID:-}" | od -An -v -tx1 | tr -d ' \n' >&3
printf '\n' >&3
printf '%s' "${'$'}PWD" | od -An -v -tx1 | tr -d ' \n' >&3
printf '\n' >&3
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
  printf '1\n' >&3
  od -An -v -tx1 -- "${'$'}file_path" | tr -d ' \n' >&3
  printf '\n' >&3
else
  printf '0\n' >&3
fi
printf '%s\n' "${'$'}stdin_hex" >&3
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

    /** Exposes only the generated script shape to same-module regression tests. */
    internal fun wrapperScriptForTest(): String = wrapperScript()

    /** In-app preview opener used by BROWSER/xdg-open inside the guest. */
    private fun minisOpenWrapperScript(): String = """#!/bin/sh
# minis-open — MinisApp URL interceptor for the Direct Ubuntu guest.
# The terminal host consumes the OSC 1337 marker and routes the URL to the
# built-in browser/preview surface. Non-previewable paths are best effort.

set -e

ESC=${'$'}(printf '\033')
BEL=${'$'}(printf '\007')

emit_marker() {
    printf '%s]1337;MinisOpenURL=%s%s\n' "${'$'}ESC" "${'$'}1" "${'$'}BEL"
}

emit() {
    case "${'$'}1" in
        http://*|https://*|about:*)
            emit_marker "${'$'}1"
            printf 'Opened in Minis browser: %s\n' "${'$'}1"
            ;;
        minis://*)
            emit_marker "${'$'}1"
            printf 'Opened in Minis preview: %s\n' "${'$'}1"
            ;;
        /var/minis/*)
            rel="${'$'}{1#/var/minis/}"
            case "${'$'}rel" in
                */*)
                    host="${'$'}{rel%%/*}"
                    path="${'$'}{rel#*/}"
                    url="minis://${'$'}host/${'$'}path"
                    emit_marker "${'$'}url"
                    printf 'Opened in Minis preview: %s\n' "${'$'}url"
                    ;;
                *)
                    printf 'minis-open: not a previewable resource: %s\n' "${'$'}1" >&2
                    return 0
                    ;;
            esac
            ;;
        *)
            printf 'minis-open: not a URL or chat resource, ignoring: %s\n' "${'$'}1" >&2
            return 0
            ;;
    esac
}

if [ ${'$'}# -eq 0 ]; then
    echo "Usage: minis-open <url-or-path>" >&2
    echo "  url-or-path: http(s):// URL, minis:// URL, or /var/minis/<host>/<path>" >&2
    exit 1
fi

for arg in "${'$'}@"; do
    emit "${'$'}arg"
done

exit 0
"""

    internal fun managedCommandNames(registeredHandlers: Set<String>): Set<String> =
        (registeredHandlers + CORE_COMMAND_NAMES + URL_COMMAND_NAMES)
            .filter(::isSafeCommandName)
            .toSortedSet()

    private fun isSupportedBridgeCommand(command: String): Boolean =
        command in CORE_COMMAND_NAMES ||
            command in KNOWN_BRIDGE_COMMAND_NAMES ||
            command in NativeOffloadServer.registeredHandlers

    private fun isSafeCommandName(command: String): Boolean =
        command.length in 1..64 && command.matches(SAFE_COMMAND_NAME)

    private val SAFE_COMMAND_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")
    private val CORE_COMMAND_NAMES = setOf("minis-config", "minis-model-use")
    private val URL_COMMAND_NAMES = setOf(
        "minis-open",
        "xdg-open",
        "sensible-browser",
        "www-browser",
        "x-www-browser",
        "gnome-open",
        "kde-open",
    )
    private val KNOWN_BRIDGE_COMMAND_NAMES = setOf(
        "android-alarm",
        "android-calendar",
        "android-clipboard",
        "android-contacts",
        "android-device",
        "android-location",
        "android-notification",
        "android-open",
        "android-photos",
        "android-player",
        "android-speak",
        "android-speech",
        "android-weather",
        "android-a11y-cli",
        "android-shizuku-cli",
        "minis-browser-use",
        "minis-scheduled",
        "minis-sessions-cli",
        "minis-debug",
    )
}

/** Fixed-capacity gate applied before a bridge worker thread is created. */
internal class GuestBridgeConnectionLimiter(private val maxConcurrent: Int) {
    init {
        require(maxConcurrent > 0) { "maxConcurrent must be positive" }
    }

    private val active = AtomicInteger(0)

    fun tryAcquire(): Boolean {
        while (true) {
            val current = active.get()
            if (current >= maxConcurrent) return false
            if (active.compareAndSet(current, current + 1)) return true
        }
    }

    fun release() {
        val remaining = active.decrementAndGet()
        check(remaining >= 0) { "bridge connection limiter released without acquisition" }
    }

    internal fun activeCount(): Int = active.get()
}
