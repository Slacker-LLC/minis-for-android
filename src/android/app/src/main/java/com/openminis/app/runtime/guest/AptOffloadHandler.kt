package com.openminis.app.runtime.guest

import android.content.Context
import android.util.Log
import com.openminis.app.runtime.ubuntu.AptCommandPolicy
import com.openminis.app.runtime.ubuntu.DirectRootRunner
import com.openminis.app.runtime.ubuntu.RootNetworkProxy
import com.openminis.app.sandbox.RootfsManager
import com.openminis.app.runtime.ubuntu.UbuntuKernel
import com.openminis.app.runtime.ubuntu.UbuntuPaths
import com.openminis.app.runtime.ubuntu.UbuntuProvisioner
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `minis-apt` — install and remove Ubuntu packages from the guest shell.
 *
 * The guest runs as the App's own user and cannot use apt; this command asks the App to do that one
 * job as Root inside the Ubuntu rootfs. What it may do is fixed by [AptCommandPolicy] (update, install,
 * remove; plain package names only; removals that would take out something the runtime needs are
 * refused), the call goes through the same permission gate as the other guest CLIs, and only one
 * runs at a time. The agent never gets a Root shell: it names packages, the App builds the command.
 */
class AptOffloadHandler(private val context: Context) : NativeOffloadHandler {
    private val running = AtomicBoolean(false)

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = request.argv.drop(1)
        if (args.firstOrNull() in setOf("-h", "--help", "help")) {
            return NativeOffloadResult(0, AptCommandPolicy.USAGE + "\n")
        }
        val parsed = AptCommandPolicy.parse(args)
        if (parsed is AptCommandPolicy.Parsed.Refused) {
            return NativeOffloadResult(2, "minis-apt: ${parsed.message}\n")
        }
        val command = (parsed as AptCommandPolicy.Parsed.Ok).request
        if (!OffloadGate.allow("apt_cli", "minis-apt", request)) {
            return NativeOffloadResult(
                126,
                "minis-apt: the agent is not allowed to install packages. Ask the user to allow it under Settings → Permissions.\n",
            )
        }
        if (!running.compareAndSet(false, true)) {
            return NativeOffloadResult(75, "minis-apt: another package operation is already running; try again when it has finished\n")
        }
        return try {
            runBlocking { run(command) }
        } catch (error: Throwable) {
            Log.w(TAG, "minis-apt failed: ${error.message}", error)
            NativeOffloadResult(1, "minis-apt: failed: ${error.message ?: error.javaClass.simpleName}\n")
        } finally {
            running.set(false)
        }
    }

    private suspend fun run(command: AptCommandPolicy.Request): NativeOffloadResult {
        val rootfs = UbuntuPaths.HOST_ROOTFS
        // The same DNS and (when its helper is alive and READY) proxy preparation the provisioner does.
        val dns = RootfsManager.getInstance(context).getSystemDnsServers()
        val resolv = RootfsManager.formatResolvConf(dns)
        val dnsWrite = DirectRootRunner.runScript(UbuntuKernel.buildResolvConfWriteCommand(rootfs, resolv), 30_000L)
        if (!dnsWrite.success) {
            return NativeOffloadResult(1, "minis-apt: cannot prepare DNS for the Ubuntu environment: ${dnsWrite.error ?: dnsWrite.stderr.take(200)}\n")
        }
        val proxy = RootNetworkProxy.proxyEnv()["http_proxy"].orEmpty()
        val result = DirectRootRunner.runScript(
            UbuntuProvisioner.wrapInRootfsNamespace(rootfs, AptCommandPolicy.guestScript(command, proxy)),
            TIMEOUT_MS,
        )
        val output = tail((result.stdout + result.stderr).trim())
        val verdict = when {
            result.timedOut -> "timed out after ${TIMEOUT_MS / 60_000} minutes"
            result.error != null -> "failed: ${result.error}"
            result.exitCode == 0 -> "ok"
            result.exitCode == AptCommandPolicy.EXIT_PROTECTED -> "refused"
            else -> "failed (exit ${result.exitCode})"
        }
        val code = if (result.success) 0 else if (result.exitCode > 0) result.exitCode else 1
        return NativeOffloadResult(code, "minis-apt: $verdict\n$output\n")
    }

    private fun tail(text: String): String =
        if (text.length <= MAX_OUTPUT_CHARS) text else "…\n" + text.takeLast(MAX_OUTPUT_CHARS)

    private companion object {
        const val TAG = "AptOffloadHandler"
        const val TIMEOUT_MS = 20 * 60_000L
        const val MAX_OUTPUT_CHARS = 8_000
    }
}
