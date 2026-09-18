package com.openminis.app.runtime.ubuntu

import android.content.Context
import android.util.Log
import com.openminis.app.sandbox.RootfsManager

/**
 * Trusted Root-only maintenance for the Ubuntu base image.
 *
 * The packaged rootfs is intentionally small. On first boot we install the
 * tool set required by the guest, but the privileged process exists
 * only for this bounded maintenance operation. Agent/model commands never pass
 * through this object and still execute after [UbuntuKernel] drops to App UID.
 */
internal object UbuntuProvisioner {
    private const val TAG = "UbuntuProvisioner"
    private const val MARKER = "etc/minis/provisioned"
    private const val PROBE_TIMEOUT_MS = 15_000L
    private const val PROVISION_TIMEOUT_MS = 780_000L
    private const val PREFS_NAME = "ubuntu_provision_state"
    private const val LAST_FAILURE_MS_KEY = "last_failure_ms"
    /** Short and recoverable: a transient network/rootfs failure must retry soon. */
    internal const val PROVISION_BACKOFF_MS = 30_000L

    internal val BASE_PACKAGES = listOf(
        "gawk",
        "python3",
        "python3-pip",
        "python3-venv",
        "git",
        "curl",
        "iputils-ping",
        "wget",
        "ca-certificates",
        "zip",
        "unzip",
        "xz-utils",
        "zstd",
    )

    data class Result(
        val ready: Boolean,
        val alreadyProvisioned: Boolean = false,
        val detail: String? = null,
    )

    suspend fun ensureProvisioned(context: Context): Result {
        val rootfs = UbuntuPaths.HOST_ROOTFS
        val before = DirectRootRunner.runScript(buildProbeCommand(rootfs), PROBE_TIMEOUT_MS)
        if (before.success && before.stdout.lineSequence().any { it.trim() == "MINIS_PROVISION:READY" }) {
            clearProvisionFailure(context)
            return Result(true, alreadyProvisioned = true)
        }

        val retryAfterMs = provisionBackoffRemaining(
            nowMs = System.currentTimeMillis(),
            lastFailureMs = provisionPreferences(context).getLong(LAST_FAILURE_MS_KEY, 0L),
        )
        if (retryAfterMs > 0L) {
            return Result(
                false,
                detail = "Ubuntu package provisioning is retrying after a recent failure; " +
                    "try again in ${retryAfterMs}ms",
            )
        }

        val dns = RootfsManager.getInstance(context).getSystemDnsServers()
        val resolv = RootfsManager.formatResolvConf(dns)
        val dnsWrite = DirectRootRunner.runScript(
            UbuntuKernel.buildResolvConfWriteCommand(rootfs, resolv),
            PROBE_TIMEOUT_MS,
        )
        if (!dnsWrite.success) {
            recordProvisionFailure(context)
            return Result(
                false,
                detail = "cannot prepare Ubuntu DNS: " +
                    (dnsWrite.error ?: dnsWrite.stderr.ifBlank { "exit ${dnsWrite.exitCode}" }),
            )
        }

        // An occupied loopback port is not proof that this App owns the
        // helper. Only inject the authenticated URI while the current helper
        // process is alive and READY; otherwise apt uses the guest's direct
        // network path instead of retrying an unmanaged legacy listener.
        val proxy = RootNetworkProxy.proxyEnv()["http_proxy"].orEmpty()
        val provision = DirectRootRunner.runScript(
            buildProvisionCommand(rootfs, proxy),
            PROVISION_TIMEOUT_MS,
        )
        if (!provision.success) {
            val detail = provision.error ?: provision.stderr.ifBlank { "exit ${provision.exitCode}" }
            Log.w(TAG, "Ubuntu package provisioning failed: $detail")
            recordProvisionFailure(context)
            return Result(false, detail = "Ubuntu package provisioning failed: $detail")
        }

        val after = DirectRootRunner.runScript(buildProbeCommand(rootfs), PROBE_TIMEOUT_MS)
        val ready = after.success && after.stdout.lineSequence().any { it.trim() == "MINIS_PROVISION:READY" }
        return if (ready) {
            clearProvisionFailure(context)
            Result(true, alreadyProvisioned = false)
        } else {
            val probeDetail = after.error ?: listOfNotNull(
                after.stderr.takeIf { it.isNotBlank() }?.let { "stderr=${it.take(600)}" },
                after.stdout.takeIf { it.isNotBlank() }?.let { "stdout=${it.take(600)}" },
                "exit=${after.exitCode}",
            ).joinToString(" ")
            Log.w(TAG, "Ubuntu readiness probe failed after provisioning: $probeDetail")
            recordProvisionFailure(context)
            Result(
                false,
                detail = "Ubuntu package provisioning completed but required tools are still missing: " +
                    probeDetail,
            )
        }
    }

    /** Pure timing rule kept separate so the retry contract has JVM tests. */
    internal fun provisionBackoffRemaining(
        nowMs: Long,
        lastFailureMs: Long,
        backoffMs: Long = PROVISION_BACKOFF_MS,
    ): Long {
        if (lastFailureMs <= 0L || backoffMs <= 0L) return 0L
        if (nowMs <= lastFailureMs) return backoffMs
        return (backoffMs - (nowMs - lastFailureMs)).coerceAtLeast(0L)
    }

    private fun provisionPreferences(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun recordProvisionFailure(context: Context) {
        provisionPreferences(context).edit()
            .putLong(LAST_FAILURE_MS_KEY, System.currentTimeMillis())
            .apply()
    }

    private fun clearProvisionFailure(context: Context) {
        provisionPreferences(context).edit()
            .remove(LAST_FAILURE_MS_KEY)
            .apply()
    }

    internal fun buildProbeCommand(rootfs: String): String {
        val root = rootfs.trimEnd('/')
        val commands = listOf(
            "test -d ${DirectRootRunner.shellQuote(root)}",
            "test ! -L ${DirectRootRunner.shellQuote(root)}",
            "test -d ${DirectRootRunner.shellQuote("$root/etc")}",
            "test ! -L ${DirectRootRunner.shellQuote("$root/etc")}",
            "test -d ${DirectRootRunner.shellQuote("$root/etc/minis")}",
            "test ! -L ${DirectRootRunner.shellQuote("$root/etc/minis")}",
            "test -f ${DirectRootRunner.shellQuote("$rootfs/$MARKER")}",
            "test ! -L ${DirectRootRunner.shellQuote("$rootfs/$MARKER")}",
            "test -x ${DirectRootRunner.shellQuote("$rootfs/usr/bin/python3")}",
            "test -x ${DirectRootRunner.shellQuote("$rootfs/usr/bin/git")}",
            "test -x ${DirectRootRunner.shellQuote("$rootfs/usr/bin/curl")}",
            "test -x ${DirectRootRunner.shellQuote("$rootfs/usr/bin/ping")}",
            "test -x ${DirectRootRunner.shellQuote("$rootfs/usr/bin/wget")}",
            "test -x ${DirectRootRunner.shellQuote("$rootfs/usr/bin/gawk")}",
            "test -x ${DirectRootRunner.shellQuote("$rootfs/usr/bin/zip")}",
            "test -x ${DirectRootRunner.shellQuote("$rootfs/usr/bin/unzip")}",
            "test -x ${DirectRootRunner.shellQuote("$rootfs/usr/bin/xz")}",
            "test -x ${DirectRootRunner.shellQuote("$rootfs/usr/bin/zstd")}",
            // Ubuntu binaries use the guest glibc loader. Running the ELF
            // directly from Android's host shell returns 126; chroot first so
            // the guest interpreter and libraries resolve inside rootfs.
            "/system/bin/chroot ${DirectRootRunner.shellQuote(root)} /usr/bin/python3 -m pip --version >/dev/null 2>&1",
            "echo MINIS_PROVISION:READY",
        )
        return commands.joinToString(" && ")
    }

    /**
     * Build one isolated Root mount namespace for apt. All mounts disappear
     * with this short-lived command. No keeper, socket, PID file or daemon is
     * created.
     */
    internal fun buildProvisionCommand(rootfs: String, proxy: String): String {
        val aptProxy = if (proxy.isBlank()) {
            ""
        } else {
            " -o ${DirectRootRunner.shellQuote("Acquire::http::Proxy=$proxy")}" +
                " -o ${DirectRootRunner.shellQuote("Acquire::https::Proxy=$proxy")}" 
        }
        // Do not leave the Root launcher blocked for apt's default retry
        // budget when a device has no direct egress or DNS. A later readiness
        // attempt can retry provisioning; one failed network path must not
        // pin the whole Runtime lifecycle indefinitely.
        val aptNetworkTimeouts =
            " -o Acquire::Retries=1 -o Acquire::http::Timeout=30 -o Acquire::https::Timeout=30"
        val packageArgs = BASE_PACKAGES.joinToString(" ") { DirectRootRunner.shellQuote(it) }
        val guest = buildString {
            appendLine("set -eu")
            appendLine("export DEBIAN_FRONTEND=noninteractive")
            appendLine("/usr/bin/apt-get -o APT::Sandbox::User=root$aptProxy$aptNetworkTimeouts update")
            appendLine("/usr/bin/apt-get -o APT::Sandbox::User=root$aptProxy$aptNetworkTimeouts install -y --no-install-recommends $packageArgs")
            appendLine("test -d /etc && test ! -L /etc")
            appendLine("if [ -L /etc/minis ]; then exit 72; fi")
            appendLine("if [ -e /etc/minis ] && [ ! -d /etc/minis ]; then exit 72; fi")
            appendLine("mkdir -p /etc/minis")
            appendLine("test -d /etc/minis && test ! -L /etc/minis")
            appendLine("if [ -L /$MARKER ]; then exit 73; fi")
            appendLine("if [ -e /$MARKER ] && [ ! -f /$MARKER ]; then exit 73; fi")
            appendLine("printf 'ok\\n' > /$MARKER")
            appendLine("chmod 644 /$MARKER")
        }
        val inner = buildString {
            appendLine("set -eu")
            appendLine("ROOTFS=${DirectRootRunner.shellQuote(rootfs)}")
            // Android toybox exposes propagation flags through -o; its GNU
            // --make-rprivate spelling is parsed as an fstab lookup.
            appendLine("/system/bin/mount -o rprivate,bind / /")
            UbuntuMountPolicy.setupCommands().forEach { appendLine(it) }
            appendLine(
                "exec /system/bin/chroot \"\$ROOTFS\" /usr/bin/env -i " +
                    "HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin " +
                    "LANG=C.UTF-8 LC_ALL=C.UTF-8 /bin/bash -c ${DirectRootRunner.shellQuote(guest)}",
            )
        }
        return "exec /system/bin/unshare -m /system/bin/sh -c ${DirectRootRunner.shellQuote(inner)}"
    }
}
