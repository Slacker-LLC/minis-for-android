package com.openminis.app.integrity

import com.openminis.app.runtime.ubuntu.DirectRootRunner
import com.openminis.app.runtime.ubuntu.UbuntuPaths
import com.openminis.app.util.shellQuote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The single door for privileged commands that come from the agent or a guest CLI (Issue #182).
 * `root.shell`, `android-root-cli` and the Android tools all call this instead of [DirectRootRunner];
 * infrastructure that the app builds itself (rootfs, namespaces, binds, the network helper, accessibility
 * repair) keeps using [DirectRootRunner] directly and is not subject to the policy.
 *
 * With protection on, a command is first checked by [DeviceIntegrityPolicy] (a refusal returns
 * `DEVICE_PROTECTED: <category>: <target>` on stderr with [EXIT_DEVICE_PROTECTED], never a prompt) and
 * then runs inside the [ProtectedView]. With protection off it runs as before.
 */
internal object ProtectedRoot {
    /** Fixed exit code of a refused command (EX_NOPERM). */
    const val EXIT_DEVICE_PROTECTED = 77

    @Volatile
    internal var policy: DeviceIntegrityPolicy = DeviceIntegrityPolicy(RootCorePackageResolver)

    /** Replaceable for tests: runs the final script as Root. */
    @Volatile
    internal var runner: suspend (String, Long) -> DirectRootRunner.Result = { script, timeout ->
        DirectRootRunner.runScript(script, timeout)
    }

    @Volatile
    internal var enabled: () -> Boolean = { DeviceProtectionStore.isEnabled }

    @Volatile
    internal var storageBlocksWrites: () -> Boolean = { StorageGuard.writesBlocked }

    @Volatile
    internal var rootfs: String = UbuntuPaths.HOST_ROOTFS

    suspend fun runArgv(
        entryPoint: String,
        sessionId: String?,
        argv: List<String>,
        timeoutMs: Long = 30_000L,
    ): DirectRootRunner.Result {
        require(argv.isNotEmpty()) { "argv must not be empty" }
        if (enabled()) {
            val denial = withContext(Dispatchers.IO) {
                policy.checkArgv(argv.first(), argv.drop(1))
                    ?: storageDenial(argv.first(), argv.drop(1).joinToString(" "))
            }
            if (denial != null) return refuse(entryPoint, sessionId, denial)
        }
        return execute("exec " + argv.joinToString(" ") { shellQuote(it) }, timeoutMs)
    }

    suspend fun runScript(
        entryPoint: String,
        sessionId: String?,
        script: String,
        timeoutMs: Long = 30_000L,
    ): DirectRootRunner.Result {
        if (enabled()) {
            val denial = withContext(Dispatchers.IO) {
                policy.checkScript(script) ?: storageDenial(script.trimStart().substringBefore(' '), script)
            }
            if (denial != null) return refuse(entryPoint, sessionId, denial)
        }
        return execute(script, timeoutMs)
    }

    /** Check a path an operation will write to, without running anything (file push destination). */
    fun checkWrite(entryPoint: String, sessionId: String?, path: String): DeviceIntegrityPolicy.Denial? =
        if (!enabled()) null else policy.checkWritePath(path)?.also { IntegrityAudit.record(entryPoint, sessionId, it) }

    /** Check a path an operation will delete or rename. */
    fun checkDelete(entryPoint: String, sessionId: String?, path: String): DeviceIntegrityPolicy.Denial? =
        if (!enabled()) null else policy.checkDeletePath(path)?.also { IntegrityAudit.record(entryPoint, sessionId, it) }

    fun refusalResult(denial: DeviceIntegrityPolicy.Denial) =
        DirectRootRunner.Result(EXIT_DEVICE_PROTECTED, "", denial.message)

    private fun refuse(entryPoint: String, sessionId: String?, denial: DeviceIntegrityPolicy.Denial): DirectRootRunner.Result {
        IntegrityAudit.record(entryPoint, sessionId, denial)
        return refusalResult(denial)
    }

    private suspend fun execute(script: String, timeoutMs: Long): DirectRootRunner.Result {
        val finalScript = if (enabled()) ProtectedView.wrap(script, rootfs) else script
        return runner(finalScript, timeoutMs)
    }

    /** While `/data` is nearly full, new commands that write bulk data wait (see [StorageGuard]). */
    private fun storageDenial(command: String, full: String): DeviceIntegrityPolicy.Denial? {
        if (!storageBlocksWrites()) return null
        val name = command.substringAfterLast('/').lowercase()
        return if (StorageGuard.consumesSpace(name, full)) {
            DeviceIntegrityPolicy.Denial(DeviceIntegrityPolicy.Category.STORAGE, "/data is nearly full; free space first")
        } else {
            null
        }
    }
}
