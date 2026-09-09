package com.openminis.app.runtime.ubuntu

import android.content.Context
import android.util.Log
import com.openminis.app.runtime.RuntimePathRegistry
import com.openminis.app.sandbox.RootfsManager

/**
 * Trusted Root-only maintenance for the Ubuntu base image.
 *
 * The packaged rootfs is intentionally small. On first boot we install the
 * same tool set the old broker provisioned, but the privileged process exists
 * only for this bounded maintenance operation. Agent/model commands never pass
 * through this object and still execute after [UbuntuKernel] drops to App UID.
 */
internal object UbuntuProvisioner {
    private const val TAG = "UbuntuProvisioner"
    private const val MARKER = "etc/minis/provisioned"
    private const val PROBE_TIMEOUT_MS = 15_000L
    private const val PROVISION_TIMEOUT_MS = 780_000L

    internal val BASE_PACKAGES = listOf(
        "gawk",
        "python3",
        "python3-pip",
        "python3-venv",
        "git",
        "curl",
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
            return Result(true, alreadyProvisioned = true)
        }

        val dns = RootfsManager.getInstance(context).getSystemDnsServers()
        val resolv = RootfsManager.formatResolvConf(dns)
        val resolvTarget = "$rootfs/etc/resolv.conf"
        val dnsWrite = DirectRootRunner.runScript(
            "mkdir -p ${DirectRootRunner.shellQuote("$rootfs/etc")} && " +
                "printf %s ${DirectRootRunner.shellQuote(resolv)} > ${DirectRootRunner.shellQuote(resolvTarget)} && " +
                "chmod 644 ${DirectRootRunner.shellQuote(resolvTarget)}",
            PROBE_TIMEOUT_MS,
        )
        if (!dnsWrite.success) {
            return Result(
                false,
                detail = "cannot prepare Ubuntu DNS: " +
                    (dnsWrite.error ?: dnsWrite.stderr.ifBlank { "exit ${dnsWrite.exitCode}" }),
            )
        }

        val proxy = RuntimePathRegistry.systemProxyEnv(context)["http_proxy"].orEmpty()
        val provision = DirectRootRunner.runScript(
            buildProvisionCommand(rootfs, proxy),
            PROVISION_TIMEOUT_MS,
        )
        if (!provision.success) {
            val detail = provision.error ?: provision.stderr.ifBlank { "exit ${provision.exitCode}" }
            Log.w(TAG, "Ubuntu package provisioning failed: $detail")
            return Result(false, detail = "Ubuntu package provisioning failed: $detail")
        }

        val after = DirectRootRunner.runScript(buildProbeCommand(rootfs), PROBE_TIMEOUT_MS)
        val ready = after.success && after.stdout.lineSequence().any { it.trim() == "MINIS_PROVISION:READY" }
        return if (ready) {
            Result(true, alreadyProvisioned = false)
        } else {
            Result(
                false,
                detail = "Ubuntu package provisioning completed but required tools are still missing: " +
                    (after.error ?: after.stderr.ifBlank { after.stdout.ifBlank { "probe failed" } }),
            )
        }
    }

    internal fun buildProbeCommand(rootfs: String): String {
        val commands = listOf(
            "test -f ${DirectRootRunner.shellQuote("$rootfs/$MARKER")}",
            "test -x ${DirectRootRunner.shellQuote("$rootfs/usr/bin/python3")}",
            "test -x ${DirectRootRunner.shellQuote("$rootfs/usr/bin/git")}",
            "test -x ${DirectRootRunner.shellQuote("$rootfs/usr/bin/curl")}",
            "test -x ${DirectRootRunner.shellQuote("$rootfs/usr/bin/wget")}",
            "test -x ${DirectRootRunner.shellQuote("$rootfs/usr/bin/gawk")}",
            "test -x ${DirectRootRunner.shellQuote("$rootfs/usr/bin/zip")}",
            "test -x ${DirectRootRunner.shellQuote("$rootfs/usr/bin/unzip")}",
            "test -x ${DirectRootRunner.shellQuote("$rootfs/usr/bin/xz")}",
            "test -x ${DirectRootRunner.shellQuote("$rootfs/usr/bin/zstd")}",
            "${DirectRootRunner.shellQuote("$rootfs/usr/bin/python3")} -m pip --version >/dev/null 2>&1",
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
        val packageArgs = BASE_PACKAGES.joinToString(" ") { DirectRootRunner.shellQuote(it) }
        val guest = buildString {
            appendLine("set -eu")
            appendLine("export DEBIAN_FRONTEND=noninteractive")
            appendLine("/usr/bin/apt-get -o APT::Sandbox::User=root$aptProxy update")
            appendLine("/usr/bin/apt-get -o APT::Sandbox::User=root$aptProxy install -y --no-install-recommends $packageArgs")
            appendLine("mkdir -p /etc/minis")
            appendLine("printf 'ok\\n' > /$MARKER")
            appendLine("chmod 644 /$MARKER")
        }
        val inner = buildString {
            appendLine("set -eu")
            appendLine("ROOTFS=${DirectRootRunner.shellQuote(rootfs)}")
            appendLine("mount --make-rprivate /")
            appendLine("mkdir -p \"\$ROOTFS/dev\" \"\$ROOTFS/proc\" \"\$ROOTFS/sys\"")
            appendLine("mount --rbind /dev \"\$ROOTFS/dev\"")
            appendLine("mount --make-rslave \"\$ROOTFS/dev\" || true")
            appendLine("mount --rbind /proc \"\$ROOTFS/proc\"")
            appendLine("mount --make-rslave \"\$ROOTFS/proc\" || true")
            appendLine("mount --rbind /sys \"\$ROOTFS/sys\"")
            appendLine("mount --make-rslave \"\$ROOTFS/sys\" || true")
            appendLine(
                "exec chroot \"\$ROOTFS\" /usr/bin/env -i " +
                    "HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin " +
                    "LANG=C.UTF-8 LC_ALL=C.UTF-8 /bin/bash -c ${DirectRootRunner.shellQuote(guest)}",
            )
        }
        return "exec unshare -m /system/bin/sh -c ${DirectRootRunner.shellQuote(inner)}"
    }
}
