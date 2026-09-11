package com.openminis.app.runtime.ubuntu

import android.content.Context
import android.net.Uri
import android.util.Log
import com.openminis.app.data.MountedFoldersStore
import com.openminis.app.runtime.ExecutionCoordinator
import com.openminis.app.runtime.guest.GuestCommandBridge
import com.openminis.app.runtime.RuntimePathRegistry
import com.openminis.app.sandbox.RootfsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Direct Root/chroot backend for Minis for Android.
 *
 * It deliberately mirrors upstream PRootKernel's responsibility boundary:
 * prepare the rootfs, global environment and per-session launch command. It is
 * not a daemon and owns no socket/RPC protocol. Each session gets its own
 * mount namespace and persistent shell process owned by the Android app.
 */
internal object UbuntuKernel {
    private const val TAG = "UbuntuKernel"
    private const val ROOTFS_ASSET = "minis-runtime/ubuntu-arm64-rootfs.tar.gz"
    private const val ROOT_TIMEOUT_MS = 15_000L
    private const val ROOTFS_TIMEOUT_MS = 600_000L

    data class Status(
        val ready: Boolean,
        val appUid: Int? = null,
        val version: String? = null,
        val error: String? = null,
    )

    data class Launch(
        val argv: List<String>,
        val pidFile: File,
    )

    @Volatile
    private var appContext: Context? = null
    private val lock = Mutex()

    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        UbuntuPaths.initialize(ctx)
    }

    fun contextOrNull(): Context? = appContext

    fun findSu(): String? = DirectRootRunner.findSu()

    internal fun buildGuestSetprivExec(uid: Int, envArgs: String, shellArgs: String): String {
        require(uid > 0) { "invalid app uid" }
        return "exec chroot \"\$ROOTFS\" /usr/bin/setpriv " +
            "--reuid=$uid --regid=$uid --clear-groups " +
            "--inh-caps=-all --ambient-caps=-all --bounding-set=-all --no-new-privs " +
            "/usr/bin/env -i $envArgs $shellArgs"
    }

    suspend fun ensureReady(): Status = lock.withLock {
        val ctx = appContext ?: return@withLock Status(false, error = "UbuntuKernel.init(context) has not been called")
        UbuntuPaths.initialize(ctx)
        if (!UbuntuPaths.ensureBaseDirs()) {
            return@withLock Status(false, error = "cannot create app-owned Minis data directories")
        }

        val rootProbe = DirectRootRunner.runScript("id -u", ROOT_TIMEOUT_MS)
        val rootUid = rootProbe.stdout.lineSequence().firstOrNull()?.trim()?.toIntOrNull()
        if (!rootProbe.success || rootUid != 0) {
            return@withLock Status(
                false,
                error = rootProbe.error
                    ?: rootProbe.stderr.ifBlank { "Root authorization unavailable (uid=${rootUid ?: "unknown"})" },
            )
        }

        val health = ensureRootfsLocked(ctx)
        if (!health.healthy) {
            return@withLock Status(false, error = health.detail)
        }

        val rootfs = UbuntuPaths.HOST_ROOTFS
        val backendProbe = DirectRootRunner.runScript(
            "command -v unshare >/dev/null 2>&1 && " +
                "command -v mount >/dev/null 2>&1 && " +
                "command -v chroot >/dev/null 2>&1 && " +
                "command -v setsid >/dev/null 2>&1 && " +
                "test -x ${DirectRootRunner.shellQuote(rootfs + "/usr/bin/setpriv")} && " +
                "chroot ${DirectRootRunner.shellQuote(rootfs)} /usr/bin/setpriv --no-new-privs /usr/bin/true",
            ROOT_TIMEOUT_MS,
        )
        if (!backendProbe.success) {
            return@withLock Status(
                false,
                error = "direct chroot backend prerequisites are unavailable: " +
                    backendProbe.stderr.ifBlank {
                        backendProbe.error ?: "unshare/mount/chroot/setsid/setpriv --no-new-privs probe failed"
                    },
            )
        }

        val network = RootNetworkProxy.ensureReady(ctx)
        if (!network.ready) {
            return@withLock Status(false, error = network.detail ?: "Root outbound network proxy unavailable")
        }

        val provisioned = UbuntuProvisioner.ensureProvisioned(ctx)
        if (!provisioned.ready) {
            return@withLock Status(
                false,
                error = provisioned.detail ?: "Ubuntu package provisioning failed",
            )
        }

        val migrated = migrateRootOwnedUserDataLocked(ctx)
        if (!migrated) {
            return@withLock Status(false, error = "failed to migrate legacy /data/adb/minis user data into app storage")
        }

        if (!GuestCommandBridge.ensureGuestCliInstalled(ctx)) {
            return@withLock Status(false, error = "failed to install direct guest command bridge")
        }

        val version = health.metadata?.optString("version")?.takeIf { it.isNotBlank() }
        Status(true, appUid = ctx.applicationInfo.uid, version = version)
    }

    suspend fun inspectRootfs(): RootfsHealth {
        if (appContext == null) {
            return RootfsHealth(RootfsHealthCode.ROOT_UNAVAILABLE, "UbuntuKernel.init(context) has not been called")
        }
        val result = DirectRootRunner.runScript(
            RootfsManager.buildProbeCommand(UbuntuPaths.HOST_ROOTFS),
            ROOT_TIMEOUT_MS,
        )
        if (result.error != null || result.timedOut) {
            return RootfsHealth(
                RootfsHealthCode.ROOT_UNAVAILABLE,
                result.error ?: result.stderr.ifBlank { "Root probe failed" },
            )
        }
        if (result.exitCode != 0) {
            return RootfsHealth(
                RootfsHealthCode.CORRUPT,
                result.stderr.ifBlank { "rootfs probe exited ${result.exitCode}" },
            )
        }
        return RootfsManager.evaluateProbeOutput(result.stdout)
    }

    suspend fun ensureRootfs(): RootfsHealth = lock.withLock {
        val ctx = appContext ?: return@withLock RootfsHealth(
            RootfsHealthCode.ROOT_UNAVAILABLE,
            "UbuntuKernel.init(context) has not been called",
        )
        ensureRootfsLocked(ctx)
    }

    private suspend fun ensureRootfsLocked(ctx: Context): RootfsHealth {
        val before = inspectRootfs()
        if (before.healthy) return before
        if (before.code == RootfsHealthCode.ROOT_UNAVAILABLE) return before

        val staged = withContext(Dispatchers.IO) {
            val dir = File(ctx.cacheDir, "minis-runtime").apply { mkdirs() }
            val target = File(dir, "ubuntu-arm64-rootfs.tar.gz")
            try {
                ctx.assets.open(ROOTFS_ASSET).use { input ->
                    FileOutputStream(target).use { output ->
                        input.copyTo(output, 128 * 1024)
                        output.fd.sync()
                    }
                }
                target
            } catch (error: Throwable) {
                Log.w(TAG, "cannot stage packaged rootfs: ${error.message}")
                null
            }
        } ?: return RootfsHealth(
            RootfsHealthCode.CORRUPT,
            "Ubuntu rootfs needs repair but packaged $ROOTFS_ASSET is unavailable",
        )

        val repaired = try {
            DirectRootRunner.runScript(
                RootfsManager.buildRepairCommand(UbuntuPaths.HOST_ROOTFS, staged.absolutePath),
                ROOTFS_TIMEOUT_MS,
            )
        } finally {
            staged.delete()
        }
        if (!repaired.success) {
            return RootfsHealth(
                RootfsHealthCode.CORRUPT,
                repaired.error ?: repaired.stderr.ifBlank { "rootfs repair exited ${repaired.exitCode}" },
            )
        }
        GuestCommandBridge.invalidateGuestCli()
        return inspectRootfs()
    }

    suspend fun resetRootfs(): Boolean = lock.withLock {
        val rootfs = UbuntuPaths.HOST_ROOTFS
        if (rootfs != "/data/adb/minis/rootfs") return@withLock false
        val result = DirectRootRunner.runScript(
            "rm -rf -- ${DirectRootRunner.shellQuote(rootfs)}",
            ROOTFS_TIMEOUT_MS,
        )
        if (result.success) GuestCommandBridge.invalidateGuestCli()
        result.success
    }

    suspend fun refreshDns(nameservers: List<String>): Boolean {
        val safe = nameservers.filter { it.matches(Regex("^[0-9A-Fa-f:.]{2,64}$")) }.distinct()
        if (safe.isEmpty()) return false
        val lines = safe.joinToString("\n") { "nameserver $it" } + "\n"
        val target = UbuntuPaths.HOST_ROOTFS + "/etc/resolv.conf"
        val script = "printf %s ${DirectRootRunner.shellQuote(lines)} > ${DirectRootRunner.shellQuote(target)} && chmod 644 ${DirectRootRunner.shellQuote(target)}"
        return DirectRootRunner.runScript(script, ROOT_TIMEOUT_MS).success
    }

    /** Validate a candidate SAF snapshot and recycle live shells so next spawn uses it. */
    suspend fun reconcileExternalMounts(entries: List<MountedFoldersStore.Entry>? = null): Boolean {
        val store = RuntimePathRegistry.mountedFoldersStore ?: return true
        return try {
            store.validateMountEntries(entries ?: store.entries.value)
            ExecutionCoordinator.stopCurrentCommand()
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "external mount validation failed: ${error.message}")
            false
        }
    }

    /**
     * Build a direct `su -> setsid -> unshare -m -> chroot -> setpriv -> bash`
     * launch. The returned process is a long-lived session shell; namespace
     * mounts vanish automatically when that shell/process tree exits.
     */
    suspend fun prepareLaunch(
        sessionId: String?,
        interactive: Boolean,
    ): Launch {
        val ctx = checkNotNull(appContext) { "UbuntuKernel is not initialized" }
        val uid = ctx.applicationInfo.uid
        require(uid > 0) { "invalid app uid" }
        require(sessionId == null || UbuntuPaths.isSafeSessionId(sessionId)) { "invalid session id" }

        val session = sessionId?.let { UbuntuPaths.ensureSessionDirs(it) }
        if (sessionId != null) checkNotNull(session) { "cannot prepare session directories" }

        val workspace = if (session != null) File(session, "workspace") else File(UbuntuPaths.hostWorkspace)
        val attachments = if (session != null) File(session, "attachments") else File(workspace, "attachments")
        val offloads = if (session != null) File(session, "offloads") else File(workspace, "offloads")
        val browser = if (session != null) File(session, "browser") else File(workspace, "browser")
        listOf(workspace, attachments, offloads, browser).forEach { it.mkdirs() }
        listOf("attachments", "offloads", "browser").forEach { File(workspace, it).mkdirs() }

        data class Bind(val host: String, val guest: String, val readOnly: Boolean = false)
        val binds = mutableListOf(
            Bind(workspace.absolutePath, "/workspace"),
            Bind(workspace.absolutePath, "/var/minis/workspace"),
            Bind(attachments.absolutePath, "/workspace/attachments"),
            Bind(attachments.absolutePath, "/var/minis/workspace/attachments"),
            Bind(attachments.absolutePath, "/var/minis/attachments"),
            Bind(offloads.absolutePath, "/workspace/offloads"),
            Bind(offloads.absolutePath, "/var/minis/workspace/offloads"),
            Bind(offloads.absolutePath, "/var/minis/offloads"),
            Bind(browser.absolutePath, "/workspace/browser"),
            Bind(browser.absolutePath, "/var/minis/workspace/browser"),
            Bind(browser.absolutePath, "/var/minis/browser"),
            Bind(UbuntuPaths.hostMemory, "/memory"),
            Bind(UbuntuPaths.hostMemory, "/var/minis/memory"),
            Bind(UbuntuPaths.hostSkills, "/skills"),
            Bind(UbuntuPaths.hostSkills, "/var/minis/skills"),
            Bind(UbuntuPaths.hostShared, "/shared"),
            Bind(UbuntuPaths.hostShared, "/var/minis/shared"),
            Bind(UbuntuPaths.hostMcpServers, "/var/minis/mcp-servers"),
            Bind(UbuntuPaths.hostHome, "/home/minis"),
        )

        val store = RuntimePathRegistry.mountedFoldersStore
        if (store != null) {
            for (entry in store.entries.value) {
                if (!entry.isActive) continue
                val treeUri = Uri.parse(entry.treeUri)
                val hasPersistedRead = ctx.contentResolver.persistedUriPermissions.any {
                    it.uri == treeUri && it.isReadPermission
                }
                check(hasPersistedRead) { "active external mount ${entry.name} has no persisted read grant" }
                val host = store.resolvePosixPath(treeUri, ctx)
                    ?: error("active external mount ${entry.name} is not accessible")
                val hasPersistedWrite = ctx.contentResolver.persistedUriPermissions.any {
                    it.uri == treeUri && it.isWritePermission
                }
                binds += Bind(
                    host,
                    "/var/minis/mounts/${entry.name}",
                    readOnly = !entry.effectiveWritable || !hasPersistedWrite,
                )
            }
        }

        val rootfs = UbuntuPaths.HOST_ROOTFS
        val commands = mutableListOf<String>()
        commands += "set -eu"
        commands += "ROOTFS=${DirectRootRunner.shellQuote(rootfs)}"
        commands += "mount --make-rprivate /"
        commands += "mkdir -p \"\$ROOTFS/dev\" \"\$ROOTFS/proc\" \"\$ROOTFS/sys\""
        commands += "mount --rbind /dev \"\$ROOTFS/dev\""
        commands += "mount --rbind /proc \"\$ROOTFS/proc\""
        commands += "mount --rbind /sys \"\$ROOTFS/sys\""
        for (bind in binds) {
            val target = rootfs + bind.guest
            commands += "mkdir -p ${DirectRootRunner.shellQuote(target)}"
            commands += "mount --bind ${DirectRootRunner.shellQuote(bind.host)} ${DirectRootRunner.shellQuote(target)}"
            if (bind.readOnly) {
                commands += "mount -o remount,bind,ro ${DirectRootRunner.shellQuote(target)}"
            }
        }

        val env = linkedMapOf(
            "TERM" to if (interactive) "xterm-256color" else "dumb",
            "LANG" to "C.UTF-8",
            "LC_ALL" to "C.UTF-8",
            "HOME" to "/home/minis",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TZ" to RuntimePathRegistry.posixTz(),
            "MINIS_CHAT_SESSION_ID" to sessionId.orEmpty(),
            "NO_COLOR" to if (interactive) "" else "1",
            "PYTHONDONTWRITEBYTECODE" to "1",
            "GOMAXPROCS" to "2",
        )
        env.putAll(RootNetworkProxy.proxyEnv())
        val envArgs = env.entries.joinToString(" ") {
            DirectRootRunner.shellQuote("${it.key}=${it.value}")
        }
        val shellArgs = if (interactive) "/bin/bash -l" else "/bin/bash --noprofile --norc"
        commands += buildGuestSetprivExec(uid, envArgs, shellArgs)
        val inner = commands.joinToString("\n")

        val pidDir = File(ctx.cacheDir, "minis-shells").apply { mkdirs() }
        val pidFile = File(pidDir, "shell-${UUID.randomUUID()}.pid")
        val child = "umask 022; echo \$\$ > ${DirectRootRunner.shellQuote(pidFile.absolutePath)}; " +
            "chown $uid:$uid ${DirectRootRunner.shellQuote(pidFile.absolutePath)} 2>/dev/null || true; " +
            "exec unshare -m /system/bin/sh -c ${DirectRootRunner.shellQuote(inner)}"
        val outer = "exec setsid /system/bin/sh -c ${DirectRootRunner.shellQuote(child)}"
        val su = checkNotNull(findSu()) { "Root launcher is unavailable" }
        return Launch(listOf(su, "-c", outer), pidFile)
    }

    private suspend fun migrateRootOwnedUserDataLocked(ctx: Context): Boolean {
        val marker = File(ctx.filesDir, "minis/.root-data-migrated-v1")
        if (marker.isFile) return true
        marker.parentFile?.mkdirs()
        val uid = ctx.applicationInfo.uid
        val mappings = listOf(
            UbuntuPaths.LEGACY_WORKSPACE to UbuntuPaths.hostWorkspace,
            UbuntuPaths.LEGACY_MEMORY to UbuntuPaths.hostMemory,
            UbuntuPaths.LEGACY_SKILLS to UbuntuPaths.hostSkills,
            UbuntuPaths.LEGACY_SHARED to UbuntuPaths.hostShared,
            "${UbuntuPaths.HOST_MINIS}/mcp-servers" to UbuntuPaths.hostMcpServers,
            UbuntuPaths.LEGACY_HOME to UbuntuPaths.hostHome,
            UbuntuPaths.LEGACY_SESSIONS to UbuntuPaths.hostSessions,
        )
        val script = buildString {
            appendLine("set -eu")
            appendLine("copy_tree() { SRC=\"\$1\"; DST=\"\$2\"; [ -d \"\$SRC\" ] || return 0; mkdir -p \"\$DST\"; cp -a \"\$SRC\"/. \"\$DST\"/; chown -R $uid:$uid \"\$DST\"; }")
            for ((source, destination) in mappings) {
                appendLine("copy_tree ${DirectRootRunner.shellQuote(source)} ${DirectRootRunner.shellQuote(destination)}")
            }
        }
        val result = DirectRootRunner.runScript(script, ROOTFS_TIMEOUT_MS)
        if (!result.success) {
            Log.w(TAG, "legacy data migration failed: ${result.error ?: result.stderr}")
            return false
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                marker.writeText("migrated=${System.currentTimeMillis()}\n")
                true
            }.getOrDefault(false)
        }
    }
}
