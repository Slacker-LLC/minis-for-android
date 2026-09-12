package com.openminis.app.runtime.ubuntu

import android.content.Context
import android.net.Uri
import android.system.Os
import android.util.Log
import com.openminis.app.data.MountedFoldersStore
import com.openminis.app.runtime.ExecutionCoordinator
import com.openminis.app.runtime.guest.GuestCommandBridge
import com.openminis.app.runtime.RuntimePathRegistry
import com.openminis.app.sandbox.RootfsManager
import com.openminis.app.sandbox.TerminalSession
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
 * It owns rootfs preparation, the global environment and each per-session
 * launch command. It is not a daemon and owns no socket/RPC protocol. Each session gets its own
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
        val error: String? = null,
    )

    data class Launch(
        val argv: List<String>,
        val pidFile: File,
    )

    @Volatile
    private var appContext: Context? = null
    private var staleRuntimeStateReconciled = false
    private val lock = Mutex()

    @Synchronized
    fun init(context: Context) {
        val ctx = context.applicationContext
        if (appContext !== ctx) staleRuntimeStateReconciled = false
        appContext = ctx
        UbuntuPaths.initialize(ctx)
    }

    fun findSu(): String? = DirectRootRunner.findSu()

    internal data class AppIdentity(val uid: Int, val gid: Int)

    /** Read the IDs of this actual App process; never infer GID from package UID. */
    internal fun currentAppIdentity(context: Context): AppIdentity {
        val uid = Os.getuid()
        val gid = Os.getgid()
        require(uid > 0) { "invalid app uid" }
        require(gid > 0) { "invalid app gid" }
        val packageUid = context.applicationInfo.uid
        require(packageUid <= 0 || packageUid == uid) {
            "process uid $uid does not match package uid $packageUid"
        }
        return AppIdentity(uid = uid, gid = gid)
    }

    internal fun buildGuestSetprivExec(uid: Int, gid: Int, envArgs: String, shellArgs: String): String {
        require(uid > 0) { "invalid app uid" }
        require(gid > 0) { "invalid app gid" }
        return "exec /system/bin/chroot \"\$ROOTFS\" /usr/bin/setpriv " +
            "--reuid=$uid --regid=$gid --clear-groups " +
            "--inh-caps=-all --ambient-caps=-all --bounding-set=-all --no-new-privs " +
            "/usr/bin/env -i $envArgs $shellArgs"
    }

    /**
     * Keep the guest's human-readable identity aligned with Android's actual
     * App UID/GID. The privilege boundary still uses the numeric IDs and
     * clears supplementary groups; this only prevents bash/getent/groups from
     * rendering a valid unprivileged guest as "I have no name!" after an APK
     * reinstall or package-UID reassignment.
     */
    internal fun buildGuestIdentityCommands(rootfs: String, identity: AppIdentity): List<String> {
        require(identity.uid > 0) { "invalid app uid" }
        require(identity.gid > 0) { "invalid app gid" }
        val passwd = "$rootfs/etc/passwd"
        val group = "$rootfs/etc/group"
        val passwdEntry = "minis:x:${identity.uid}:${identity.gid}:Minis:/home/minis:/bin/bash"
        val groupEntry = "minis:x:${identity.gid}:"
        val passwdQuoted = DirectRootRunner.shellQuote(passwd)
        val groupQuoted = DirectRootRunner.shellQuote(group)
        return listOf(
            "[ -f $passwdQuoted ] && [ ! -L $passwdQuoted ] || exit 76",
            "[ -f $groupQuoted ] && [ ! -L $groupQuoted ] || exit 76",
            "if ! /system/bin/grep -q '^minis:x:${identity.uid}:${identity.gid}:' $passwdQuoted; then " +
                "/system/bin/sed -i '/^minis:/d' $passwdQuoted; " +
                "printf '%s\\n' ${DirectRootRunner.shellQuote(passwdEntry)} >> $passwdQuoted; " +
                "fi",
            "if ! /system/bin/grep -q '^minis:x:${identity.gid}:' $groupQuoted; then " +
                "/system/bin/sed -i '/^minis:/d' $groupQuoted; " +
                "printf '%s\\n' ${DirectRootRunner.shellQuote(groupEntry)} >> $groupQuoted; " +
                "fi",
        )
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

        if (!staleRuntimeStateReconciled) {
            if (!reconcileStaleRuntimeState()) {
                return@withLock Status(false, error = "failed to reconcile stale direct Ubuntu process state")
            }
            staleRuntimeStateReconciled = true
        }

        val health = ensureRootfsLocked(ctx)
        if (!health.healthy) {
            return@withLock Status(false, error = health.detail)
        }

        val rootfs = UbuntuPaths.HOST_ROOTFS
        val backendProbe = DirectRootRunner.runScript(
                "test -x /system/bin/unshare && " +
                "test -x /system/bin/mount && " +
                "test -x /system/bin/chroot && " +
                "test -x /system/bin/setsid && " +
                "test -x ${DirectRootRunner.shellQuote(rootfs + "/usr/bin/setpriv")} && " +
                "/system/bin/chroot ${DirectRootRunner.shellQuote(rootfs)} /usr/bin/setpriv --no-new-privs /usr/bin/true",
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
            // The helper is a device-compatibility path, not a Root/chroot
            // prerequisite. Devices whose App UID can open guest sockets use
            // direct networking; only shells that need the helper will report
            // their network failure at the command boundary.
            Log.w(TAG, "Root network proxy unavailable; continuing with guest direct networking: ${network.detail}")
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

        Status(true, appUid = currentAppIdentity(ctx).uid)
    }

    suspend fun inspectRootfs(): RootfsHealth = lock.withLock {
        inspectRootfsLocked()
    }

    private suspend fun inspectRootfsLocked(): RootfsHealth {
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
        val before = inspectRootfsLocked()
        if (before.healthy) return before
        if (before.code == RootfsHealthCode.ROOT_UNAVAILABLE) return before

        // Readiness can discover corruption after a shell was already running
        // (for example after external rootfs damage). Recycle every guest
        // owner before replacing the Root-owned tree. UbuntuRuntime already
        // holds the lifecycle gate for normal readiness; the direct calls from
        // RootfsManager are fenced by withRuntimeStopped as well.
        TerminalSession.stopAllAndJoin()
        ExecutionCoordinator.stopCurrentCommand()

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
        return inspectRootfsLocked()
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

    /** Write a user-selected config file while preserving the Root-owned boundary. */
    internal suspend fun writeManagedRootfsConfig(relativePath: String, content: String): Boolean =
        lock.withLock {
            if (!isManagedRootfsConfig(relativePath) ||
                content.toByteArray(Charsets.UTF_8).size > RootfsManager.MAX_MANAGED_CONFIG_BYTES ||
                content.contains('\u0000')
            ) {
                return@withLock false
            }
            DirectRootRunner.runScript(
                buildManagedRootfsConfigWriteCommand(UbuntuPaths.HOST_ROOTFS, relativePath, content),
                ROOT_TIMEOUT_MS,
            ).success
        }

    /** Restore a user-selected config file from its Root-owned backup. */
    internal suspend fun restoreManagedRootfsConfig(relativePath: String): Boolean =
        lock.withLock {
            if (!isManagedRootfsConfig(relativePath)) return@withLock false
            DirectRootRunner.runScript(
                buildManagedRootfsConfigRestoreCommand(UbuntuPaths.HOST_ROOTFS, relativePath),
                ROOT_TIMEOUT_MS,
            ).success
        }

    suspend fun refreshDns(nameservers: List<String>): Boolean = lock.withLock {
        val safe = nameservers.filter { it.matches(Regex("^[0-9A-Fa-f:.]{2,64}$")) }.distinct()
        if (safe.isEmpty()) return@withLock false
        val lines = safe.joinToString("\n") { "nameserver $it" } + "\n"
        DirectRootRunner.runScript(
            buildResolvConfWriteCommand(UbuntuPaths.HOST_ROOTFS, lines),
            ROOT_TIMEOUT_MS,
        ).success
    }

    /** Build a Root-only, no-follow DNS update for the rootfs. */
    internal fun buildResolvConfWriteCommand(rootfs: String, content: String): String {
        require(!content.contains('\u0000')) { "DNS content contains NUL" }
        require(content.toByteArray(Charsets.UTF_8).size <= RootfsManager.MAX_MANAGED_CONFIG_BYTES) {
            "DNS content is too large"
        }
        val root = rootfs.trimEnd('/')
        val target = "$root/etc/resolv.conf"
        val parent = "$root/etc"
        val parentDirs = listOf(root, parent)
        return buildString {
            appendLine("set -eu")
            appendLine("TARGET=${DirectRootRunner.shellQuote(target)}")
            appendLine("PARENT=${DirectRootRunner.shellQuote(parent)}")
            appendLine("TEMP=\"\$TARGET.minis-dns-tmp.\$\$\"")
            parentDirs.forEach { directory ->
                val quoted = DirectRootRunner.shellQuote(directory)
                appendLine("[ -d $quoted ] && [ ! -L $quoted ] || exit 72")
            }
            // A symlink may be the distro's conventional resolv.conf entry.
            // Remove only the link itself, never its target, before installing
            // the Root-owned regular file via an atomic rename.
            appendLine("if [ -L \"\$TARGET\" ]; then rm -f -- \"\$TARGET\"; fi")
            appendLine("if [ -e \"\$TARGET\" ] && [ ! -f \"\$TARGET\" ]; then exit 73; fi")
            appendLine("rm -f -- \"\$TEMP\"")
            appendLine("umask 077; printf %s ${DirectRootRunner.shellQuote(content)} > \"\$TEMP\"")
            appendLine("chmod 644 \"\$TEMP\"; chown 0:0 \"\$TEMP\"; mv -f -- \"\$TEMP\" \"\$TARGET\"")
        }
    }

    private fun isManagedRootfsConfig(relativePath: String): Boolean =
        RootfsManager.isManagedRootfsConfig(relativePath)

    internal fun buildManagedRootfsConfigWriteCommand(
        rootfs: String,
        relativePath: String,
        content: String,
    ): String {
        require(isManagedRootfsConfig(relativePath)) { "unsupported Root-owned config path" }
        require(!content.contains('\u0000')) { "config content contains NUL" }
        require(content.toByteArray(Charsets.UTF_8).size <= RootfsManager.MAX_MANAGED_CONFIG_BYTES) {
            "config content is too large"
        }
        val target = "$rootfs/$relativePath"
        val parent = target.substringBeforeLast('/')
        val backup = "$target.bak"
        val parentDirs = noSymlinkParentDirectories(rootfs, relativePath)
        return buildString {
            appendLine("set -eu")
            appendLine("ROOTFS=${DirectRootRunner.shellQuote(rootfs)}")
            appendLine("TARGET=${DirectRootRunner.shellQuote(target)}")
            appendLine("PARENT=${DirectRootRunner.shellQuote(parent)}")
            appendLine("BACKUP=${DirectRootRunner.shellQuote(backup)}")
            appendLine("TEMP=\"\$TARGET.minis-tmp.\$\$\"")
            parentDirs.forEach { directory ->
                val quoted = DirectRootRunner.shellQuote(directory)
                appendLine("[ -d $quoted ] && [ ! -L $quoted ] || exit 72")
            }
            appendLine("[ -d \"\$PARENT\" ] || exit 72")
            appendLine("[ ! -L \"\$TARGET\" ] || exit 73")
            appendLine("if [ -e \"\$TARGET\" ] && [ ! -f \"\$TARGET\" ]; then exit 73; fi")
            appendLine("[ ! -L \"\$BACKUP\" ] || exit 74")
            appendLine("if [ -e \"\$BACKUP\" ] && [ ! -f \"\$BACKUP\" ]; then exit 74; fi")
            appendLine("if [ ! -e \"\$BACKUP\" ] && [ -f \"\$TARGET\" ]; then cp -f -- \"\$TARGET\" \"\$BACKUP\"; chmod 644 \"\$BACKUP\"; chown 0:0 \"\$BACKUP\"; fi")
            appendLine("rm -f -- \"\$TEMP\"")
            appendLine("umask 077; printf %s ${DirectRootRunner.shellQuote(content)} > \"\$TEMP\"")
            appendLine("chmod 644 \"\$TEMP\"; chown 0:0 \"\$TEMP\"; mv -f -- \"\$TEMP\" \"\$TARGET\"")
        }
    }

    internal fun buildManagedRootfsConfigRestoreCommand(rootfs: String, relativePath: String): String {
        require(isManagedRootfsConfig(relativePath)) { "unsupported Root-owned config path" }
        val target = "$rootfs/$relativePath"
        val parent = target.substringBeforeLast('/')
        val backup = "$target.bak"
        val parentDirs = noSymlinkParentDirectories(rootfs, relativePath)
        return buildString {
            appendLine("set -eu")
            appendLine("ROOTFS=${DirectRootRunner.shellQuote(rootfs)}")
            appendLine("TARGET=${DirectRootRunner.shellQuote(target)}")
            appendLine("PARENT=${DirectRootRunner.shellQuote(parent)}")
            appendLine("BACKUP=${DirectRootRunner.shellQuote(backup)}")
            parentDirs.forEach { directory ->
                val quoted = DirectRootRunner.shellQuote(directory)
                appendLine("[ -d $quoted ] && [ ! -L $quoted ] || exit 72")
            }
            appendLine("[ -d \"\$PARENT\" ] || exit 72")
            appendLine("[ ! -L \"\$BACKUP\" ] || exit 73")
            appendLine("[ -f \"\$BACKUP\" ] || exit 73")
            appendLine("[ ! -L \"\$TARGET\" ] || exit 74")
            appendLine("if [ -e \"\$TARGET\" ] && [ ! -f \"\$TARGET\" ]; then exit 74; fi")
            appendLine("rm -f -- \"\$TARGET\"; mv -f -- \"\$BACKUP\" \"\$TARGET\"")
            appendLine("chmod 644 \"\$TARGET\"; chown 0:0 \"\$TARGET\"")
        }
    }

    private fun noSymlinkParentDirectories(rootfs: String, relativePath: String): List<String> {
        val root = rootfs.trimEnd('/')
        val components = relativePath.substringBeforeLast('/').split('/').filter { it.isNotEmpty() }
        return buildList {
            var current = root
            add(current)
            for (component in components) {
                current = "$current/$component"
                add(current)
            }
        }
    }

    private fun noSymlinkMountTargetGuards(rootfs: String, guestPath: String): List<String> {
        val root = rootfs.trimEnd('/')
        val components = guestPath.trim('/').split('/').filter { it.isNotEmpty() }
        return buildList {
            add("[ -d ${DirectRootRunner.shellQuote(root)} ] && [ ! -L ${DirectRootRunner.shellQuote(root)} ] || exit 72")
            var current = root
            for (component in components) {
                current = "$current/$component"
                val quoted = DirectRootRunner.shellQuote(current)
                add("[ ! -L $quoted ] || exit 73")
                add("if [ -e $quoted ] && [ ! -d $quoted ]; then exit 74; fi")
            }
        }
    }

    /**
     * A force-killed Android process cannot run its normal stop path. Reconcile
     * only the Root marker directories left by this runtime, and only signal a
     * PID whose current command line still identifies the expected helper.
     */
    private suspend fun reconcileStaleRuntimeState(): Boolean {
        var success = true
        listOf(
            Triple("root", "runner-*.pid", "MINIS_DIRECT_ROOT_RUNNER"),
            Triple("proxy", "proxy-*.pid", "libminisnetproxy.so"),
            Triple("shells", "shell-*.pid", "unshare"),
        ).forEach { (directory, glob, commandNeedle) ->
            val markerDir = if (directory == "root") {
                DirectRootRunner.ROOT_STATE_DIR
            } else {
                "${DirectRootRunner.ROOT_STATE_DIR}/$directory"
            }
            val result = DirectRootRunner.runScript(
                DirectRootRunner.buildStaleProcessCleanupCommand(
                    markerDir = markerDir,
                    markerGlob = glob,
                    commandNeedle = commandNeedle,
                    environmentVariable = if (directory == "shells") "MINIS_DIRECT_ROOT_SHELL" else null,
                ),
                ROOT_TIMEOUT_MS,
            )
            if (!result.success) {
                success = false
                Log.w(
                    TAG,
                    "stale $directory process reconciliation failed: " +
                        (result.error ?: result.stderr.ifBlank { "exit ${result.exitCode}" }),
                )
            }
        }
        return success
    }

    /** Validate a candidate SAF snapshot and recycle live shells so next spawn uses it. */
    suspend fun reconcileExternalMounts(entries: List<MountedFoldersStore.Entry>? = null): Boolean {
        val store = RuntimePathRegistry.mountedFoldersStore ?: return true
        return try {
            store.validateMountEntries(entries ?: store.entries.value)
            TerminalSession.stopAllAndJoin()
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
        val identity = currentAppIdentity(ctx)
        require(sessionId == null || UbuntuPaths.isSafeSessionId(sessionId)) { "invalid session id" }

        val session = sessionId?.let { UbuntuPaths.ensureSessionDirs(it) }
        if (sessionId != null) checkNotNull(session) { "cannot prepare session directories" }

        val workspace = if (session != null) File(session, "workspace") else File(UbuntuPaths.hostWorkspace)
        val attachments = if (session != null) File(session, "attachments") else File(workspace, "attachments")
        val offloads = if (session != null) File(session, "offloads") else File(workspace, "offloads")
        val browser = if (session != null) File(session, "browser") else File(workspace, "browser")
        val shellMarkerToken = UUID.randomUUID().toString().replace("-", "")
        check(UbuntuPaths.ensureBaseDirs()) { "cannot create safe app-owned Minis data directories" }
        listOf(workspace, attachments, offloads, browser).forEach {
            check(UbuntuPaths.ensureHostDirectory(it)) {
                "cannot create safe session bind directory: ${it.absolutePath}"
            }
        }

        data class Bind(val host: String, val guest: String, val readOnly: Boolean = false)
        val binds = mutableListOf(
            Bind(workspace.absolutePath, "/workspace"),
            // Native offload responses are returned as /tmp/<name>. Bind the
            // App-owned, session-specific directory so the guest sees the
            // exact files written by NativeOffloadServer.
            Bind(offloads.absolutePath, "/tmp"),
            Bind(attachments.absolutePath, "/workspace/attachments"),
            Bind(offloads.absolutePath, "/workspace/offloads"),
            Bind(browser.absolutePath, "/workspace/browser"),
            Bind(UbuntuPaths.hostMemory, "/memory"),
            Bind(UbuntuPaths.hostSkills, "/skills"),
            Bind(UbuntuPaths.hostShared, "/shared"),
            Bind(UbuntuPaths.hostMcpServers, "/var/minis/mcp-servers"),
            Bind(UbuntuPaths.hostHome, "/home/minis"),
        )

        val store = RuntimePathRegistry.mountedFoldersStore
        if (store != null) {
            store.validateMountEntries(store.entries.value)
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
        commands += buildGuestIdentityCommands(rootfs, identity)
        // Android's toybox mount has no GNU --make-rprivate subcommand. The
        // two-path bind form carries the same MS_PRIVATE|MS_REC operation and
        // works with the mount implementation shipped on rooted devices.
        commands += "/system/bin/mount -o rprivate,bind / /"
        commands += UbuntuMountPolicy.setupCommands()
        for (bind in binds) {
            val target = rootfs + bind.guest
            commands += noSymlinkMountTargetGuards(rootfs, bind.guest)
            commands += "mkdir -p ${DirectRootRunner.shellQuote(target)}"
            commands += "[ -d ${DirectRootRunner.shellQuote(target)} ] && [ ! -L ${DirectRootRunner.shellQuote(target)} ] || exit 75"
            commands += "/system/bin/mount -o bind ${DirectRootRunner.shellQuote(bind.host)} ${DirectRootRunner.shellQuote(target)}"
            if (bind.readOnly) {
                commands += "/system/bin/mount -o remount,bind,ro ${DirectRootRunner.shellQuote(target)}"
            }
        }

        val env = linkedMapOf(
            "TERM" to if (interactive) "xterm-256color" else "dumb",
            "LANG" to "C.UTF-8",
            "LC_ALL" to "C.UTF-8",
            "HOME" to "/home/minis",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "BROWSER" to "/usr/local/bin/minis-open",
            "TZ" to RuntimePathRegistry.posixTz(),
            "MINIS_CHAT_SESSION_ID" to sessionId.orEmpty(),
            "NO_COLOR" to if (interactive) "" else "1",
            "PYTHONDONTWRITEBYTECODE" to "1",
            "GOMAXPROCS" to "2",
            "MINIS_DIRECT_ROOT_SHELL" to shellMarkerToken,
        )
        env.putAll(RootNetworkProxy.proxyEnv())
        val envArgs = env.entries.joinToString(" ") {
            DirectRootRunner.shellQuote("${it.key}=${it.value}")
        }
        val shellArgs = if (interactive) "/bin/bash -l" else "/bin/bash --noprofile --norc"
        commands += buildGuestSetprivExec(identity.uid, identity.gid, envArgs, shellArgs)
        val inner = commands.joinToString("\n")

        // The Root launcher writes this marker before dropping privileges.
        // Keep it in Root-owned state: an App-writable cache directory would
        // let a guest race the marker path with a symlink and redirect a Root
        // write outside the runtime state boundary.
        val pidDir = File(DirectRootRunner.ROOT_STATE_DIR, "shells")
        val pidFile = File(pidDir, "shell-${UUID.randomUUID()}.pid")
        val child = "umask 077; " +
            "mkdir -p ${DirectRootRunner.shellQuote(pidDir.absolutePath)} || exit 126; " +
            "chmod 711 ${DirectRootRunner.shellQuote(pidDir.absolutePath)} || exit 126; " +
            "printf '%s\\n%s\\n' \"\$\$\" ${DirectRootRunner.shellQuote(shellMarkerToken)} > ${DirectRootRunner.shellQuote(pidFile.absolutePath)} || exit 126; " +
            "exec /system/bin/unshare -m /system/bin/sh -c ${DirectRootRunner.shellQuote(inner)}"
        // forkpty() has already made the interactive child a session leader
        // with the PTY as its controlling terminal. Calling setsid here would
        // deliberately detach that terminal; toybox `setsid -c` can only set
        // the foreground process group after a controlling terminal exists.
        // Keep the interactive path in that PTY session. One-shot Root work
        // remains isolated in its own setsid process group.
        val outer = buildRootShellOuterCommand(child, interactive)
        val su = checkNotNull(findSu()) { "Root launcher is unavailable" }
        return Launch(listOf(su, "-c", outer), pidFile)
    }

    internal fun buildRootShellOuterCommand(child: String, interactive: Boolean): String {
        val launcher = if (interactive) {
            "/system/bin/sh -c"
        } else {
            "/system/bin/setsid /system/bin/sh -c"
        }
        return "exec $launcher ${DirectRootRunner.shellQuote(child)}"
    }

    private suspend fun migrateRootOwnedUserDataLocked(ctx: Context): Boolean {
        val marker = File(ctx.filesDir, "minis/.root-data-migrated-v1")
        if (marker.isFile) return true
        marker.parentFile?.mkdirs()
        val identity = currentAppIdentity(ctx)
        val mappings = listOf(
            UbuntuPaths.LEGACY_WORKSPACE to UbuntuPaths.hostWorkspace,
            UbuntuPaths.LEGACY_MEMORY to UbuntuPaths.hostMemory,
            UbuntuPaths.LEGACY_SKILLS to UbuntuPaths.hostSkills,
            UbuntuPaths.LEGACY_SHARED to UbuntuPaths.hostShared,
            "${UbuntuPaths.HOST_MINIS}/mcp-servers" to UbuntuPaths.hostMcpServers,
            UbuntuPaths.LEGACY_HOME to UbuntuPaths.hostHome,
            UbuntuPaths.LEGACY_SESSIONS to UbuntuPaths.hostSessions,
        )
        val script = buildLegacyMigrationCommand(identity, mappings)
        val result = DirectRootRunner.runScript(script, ROOTFS_TIMEOUT_MS)
        if (!result.success) {
            val detail = result.error ?: listOfNotNull(
                result.stderr.takeIf { it.isNotBlank() }?.let { "stderr=${it.take(600)}" },
                result.stdout.takeIf { it.isNotBlank() }?.let { "stdout=${it.take(600)}" },
                "exit=${result.exitCode}",
            ).joinToString(" ")
            Log.w(TAG, "legacy data migration failed: $detail")
            return false
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                marker.writeText("migrated=${System.currentTimeMillis()}\n")
                true
            }.getOrDefault(false)
        }
    }

    /**
     * Build the one-shot legacy copy command. The source list is fixed by the
     * migration contract; the destination checks are the important part here:
     * Root must not follow an App-created symlink while materializing data or
     * changing ownership under an App-owned directory.
     */
    internal fun buildLegacyMigrationCommand(
        identity: AppIdentity,
        mappings: List<Pair<String, String>>,
    ): String {
        require(identity.uid > 0 && identity.gid > 0) { "invalid App identity" }
        require(mappings.isNotEmpty()) { "legacy migration mappings must not be empty" }
        // The Android framework may expose filesDir through a system alias
        // such as /data/user/0 -> /data/data. Do not reject that trusted
        // framework path as an App-created symlink. Start the no-follow
        // checks at the common App-owned destination root instead.
        val parentDirectories = guardedMigrationParentDirectories(mappings.map { it.second })
        return buildString {
            appendLine("set -eu")
            parentDirectories.forEach { directory ->
                val quoted = DirectRootRunner.shellQuote(directory)
                appendLine(
                    "if [ ! -d $quoted ]; then " +
                        "echo 'legacy migration parent missing: $directory' >&2; exit 72; fi",
                )
                appendLine(
                    "if [ -L $quoted ]; then " +
                        "echo 'legacy migration parent is symlink: $directory -> '" +
                        "\$(readlink $quoted 2>/dev/null || true) >&2; exit 72; fi",
                )
            }
            appendLine(
                "copy_tree() { " +
                    "SRC=\"\$1\"; DST=\"\$2\"; " +
                    "[ -d \"\$SRC\" ] || return 0; " +
                    "[ ! -L \"\$DST\" ] || return 73; " +
                    "if [ -e \"\$DST\" ] && [ ! -d \"\$DST\" ]; then return 74; fi; " +
                    "mkdir -p \"\$DST\"; " +
                    "[ ! -L \"\$DST\" ] || return 73; " +
                    "LINKS=\$(/system/bin/find \"\$DST\" -type l -print -quit 2>/dev/null || true); " +
                    "[ -z \"\$LINKS\" ] || return 75; " +
                    "cp -a \"\$SRC\"/. \"\$DST\"/; " +
                    "chown -R ${identity.uid}:${identity.gid} \"\$DST\"; " +
                    "}"
            )
            mappings.forEach { (source, destination) ->
                appendLine("copy_tree ${DirectRootRunner.shellQuote(source)} ${DirectRootRunner.shellQuote(destination)}")
            }
        }
    }

    private fun guardedMigrationParentDirectories(destinations: List<String>): List<String> {
        require(destinations.isNotEmpty()) { "migration destinations must not be empty" }
        val parents = destinations.map { destination ->
            require(destination.startsWith('/') && !destination.contains("..")) {
                "migration destination must be absolute"
            }
            destination.trimEnd('/').substringBeforeLast('/', missingDelimiterValue = "").ifBlank { "/" }
        }
        val common = commonAbsoluteAncestor(parents)
        val commonComponents = pathComponents(common)
        return parents.flatMap { parent ->
            val components = pathComponents(parent)
            require(components.size >= commonComponents.size && components.take(commonComponents.size) == commonComponents) {
                "migration destinations must share an absolute ancestor"
            }
            buildList {
                var current = common
                add(current)
                components.drop(commonComponents.size).forEach { component ->
                    current = if (current == "/") "/$component" else "$current/$component"
                    add(current)
                }
            }
        }.distinct()
    }

    private fun commonAbsoluteAncestor(paths: List<String>): String {
        val componentLists = paths.map(::pathComponents)
        val commonCount = componentLists
            .first()
            .indices
            .takeWhile { index -> componentLists.all { it.size > index && it[index] == componentLists.first()[index] } }
            .count()
        val common = componentLists.first().take(commonCount)
        return if (common.isEmpty()) "/" else "/${common.joinToString("/")}"
    }

    private fun pathComponents(path: String): List<String> =
        path.trim('/').split('/').filter { it.isNotEmpty() }
}
