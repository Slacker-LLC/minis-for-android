from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def p(rel: str) -> Path:
    return ROOT / rel


def read(rel: str) -> str:
    return p(rel).read_text(encoding="utf-8")


def write(rel: str, text: str) -> None:
    p(rel).write_text(text, encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 anchor, found {count}")
    return text.replace(old, new, 1)


# Fix typo in the freshly introduced direct backend.
rel = "src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuKernel.kt"
s = read(rel)
s = s.replace('"$${UbuntuPaths.HOST_MINIS}/mcp-servers"', '"${UbuntuPaths.HOST_MINIS}/mcp-servers"')
write(rel, s)

# Direct Root command execution: keep the existing risk classifier/parser,
# replace only the broker-backed dispatcher object.
rel = "src/android/app/src/main/java/com/openminis/app/tools/android/PrivilegedCommandRunner.kt"
s = read(rel)
for imp in [
    "import com.openminis.app.runtime.minisd.MinisdClient\n",
    "import com.openminis.app.runtime.minisd.MinisdProtocol\n",
    "import com.openminis.app.runtime.minisd.MinisdResponse\n",
    "import com.openminis.app.runtime.ubuntu.UbuntuRuntime\n",
    "import kotlinx.coroutines.NonCancellable\n",
]:
    s = s.replace(imp, "")
if "import com.openminis.app.runtime.ubuntu.DirectRootRunner\n" not in s:
    s = s.replace(
        "import android.util.Log\n",
        "import android.util.Log\nimport com.openminis.app.runtime.ubuntu.DirectRootRunner\n",
        1,
    )
start = s.index("/** Unified seam for operations that genuinely need shell privilege. */")
new_tail = r'''/** Unified seam for operations that genuinely need shell privilege. */
object PrivilegedCommandRunner {
    private const val TAG = "PrivilegedCommand"

    suspend fun run(
        context: Context,
        sessionId: String,
        argv: List<String>,
        operation: String,
        risk: CommandRisk = CommandRisk.READ_ONLY,
        timeoutMs: Long = 30_000L,
        rootOnly: Boolean = false,
    ): AndroidCommandResult {
        require(argv.isNotEmpty()) { "privileged command argv must not be empty" }
        val tool = argv.first()
        val commandArgs = argv.drop(1)
        val effectiveRisk = CommandRisk.max(risk, PrivilegedCommandRisk.classify(tool, commandArgs))
        Log.i(
            TAG,
            "direct-root risk=${effectiveRisk.name.lowercase()} operation=$operation " +
                "tool=$tool session=$sessionId",
        )
        val result = try {
            DirectRootRunner.runArgv(argv, timeoutMs)
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
        return AndroidCommandResult(
            backend = if (result.error == null) PrivilegedBackend.ROOT else PrivilegedBackend.NONE,
            exitCode = result.exitCode,
            stdout = result.stdout,
            stderr = result.stderr,
            timedOut = result.timedOut,
            unavailableReason = result.error?.let {
                if (rootOnly) "required Root capability is unavailable: $it" else it
            },
        )
    }

    /** Explicit Root authorization probe; passive capability reads never invoke su. */
    suspend fun requestActiveRootProbe(context: Context, sessionId: String): RootProbeResult {
        if (RootCommandRunner.passiveSuPath() == null) {
            return RootProbeResult(false, error = "su executable not found")
        }
        Log.i(TAG, "active root probe requested session=$sessionId")
        val script = """
            id
            cat /proc/self/status 2>/dev/null || true
            echo __CONTEXT__
            id -Z 2>/dev/null || echo unknown
            echo __MODE__
            getenforce 2>/dev/null || echo unknown
        """.trimIndent()
        val result = DirectRootRunner.runScript(script, 15_000L)
        val probe = if (result.error != null || result.timedOut) {
            RootProbeResult(false, error = result.error ?: "Root probe timed out")
        } else {
            RootProbeParser.parse(result.stdout, result.exitCode, result.stderr)
        }
        RootCommandRunner.updateProbe(probe)
        return probe
    }
}
'''
s = s[:start] + new_tail
write(rel, s)

# Terminal production constructor: keep old pure helper overloads temporarily
# for JVM tests, but stop invoking minisd --helper in the real app.
rel = "src/android/app/src/main/java/com/openminis/app/sandbox/TerminalSession.kt"
s = read(rel)
if "import com.openminis.app.runtime.ubuntu.UbuntuKernel\n" not in s:
    s = s.replace(
        "import com.openminis.app.runtime.ubuntu.UbuntuPaths\n",
        "import com.openminis.app.runtime.ubuntu.UbuntuPaths\nimport com.openminis.app.runtime.ubuntu.UbuntuKernel\n",
        1,
    )
old = '''        { sessionId ->
            if (!UbuntuRuntime.isInitialized) UbuntuRuntime.init(context.applicationContext)
            prepareLaunch(sessionId, context.applicationInfo.uid,
                { UbuntuRuntime.ensureReady() },
                { WorkspaceFileClient.info(it, "/workspace"); Unit },
                { UbuntuRuntime.findSu() })
        },
'''
new = '''        { sessionId ->
            if (!UbuntuRuntime.isInitialized) UbuntuRuntime.init(context.applicationContext)
            val ready = UbuntuRuntime.ensureReady()
            check(ready.statusFresh && ready.running && ready.lastError == null) {
                ready.lastError ?: "Ubuntu runtime is not ready"
            }
            WorkspaceFileClient.info(sessionId, "/workspace")
            val direct = UbuntuKernel.prepareLaunch(sessionId, interactive = true)
            Launch(
                cmd = direct.argv.first(),
                argv = direct.argv.toTypedArray(),
                env = arrayOf(
                    "TERM=xterm-256color",
                    "LANG=C.UTF-8",
                    "LC_ALL=C.UTF-8",
                    "HOME=/home/minis",
                    "MINIS_CHAT_SESSION_ID=${sessionId.orEmpty()}",
                ),
            )
        },
'''
s = replace_once(s, old, new, "Terminal direct launch")
write(rel, s)

# RootfsManager: direct Root probing/repair/reset; RuntimeDistributionManager
# remains compiled but is no longer on the active runtime path.
rel = "src/android/app/src/main/java/com/openminis/app/sandbox/RootfsManager.kt"
s = read(rel)
s = s.replace("import com.openminis.app.runtime.minisd.MinisdProtocol\n", "")
if "import com.openminis.app.runtime.ExecutionCoordinator\n" not in s:
    s = s.replace(
        "import android.util.Log\n",
        "import android.util.Log\nimport com.openminis.app.runtime.ExecutionCoordinator\nimport com.openminis.app.runtime.ubuntu.UbuntuKernel\nimport com.openminis.app.runtime.ubuntu.UbuntuPaths\n",
        1,
    )
s = replace_once(
    s,
    "    val rootfsDir: File = File(MinisdProtocol.DEFAULT_ROOTFS)\n",
    "    val rootfsDir: File = File(UbuntuPaths.HOST_ROOTFS)\n",
    "RootfsManager rootfs path",
)
old = '''    suspend fun checkHealth(): RootfsHealth = withContext(Dispatchers.IO) {
        if (!com.openminis.app.runtime.ubuntu.UbuntuRuntime.isInitialized) {
            com.openminis.app.runtime.ubuntu.UbuntuRuntime.init(context)
        }
        com.openminis.app.runtime.ubuntu.UbuntuRuntime.inspectRootfs()
    }
'''
new = '''    suspend fun checkHealth(): RootfsHealth = withContext(Dispatchers.IO) {
        if (!UbuntuRuntime.isInitialized) UbuntuRuntime.init(context)
        UbuntuKernel.inspectRootfs()
    }
'''
s = replace_once(s, old, new, "RootfsManager checkHealth")
start = s.index("    suspend fun installIfNeeded() = withContext(Dispatchers.IO) {")
end = s.index("    suspend fun installProotIfNeeded()", start)
s = s[:start] + '''    suspend fun installIfNeeded() = withContext(Dispatchers.IO) {
        if (!UbuntuRuntime.isInitialized) UbuntuRuntime.init(context)
        _installState.value = RootfsInstallState.Preparing
        val before = UbuntuKernel.inspectRootfs()
        if (before.healthy) {
            _installState.value = RootfsInstallState.Installed
            return@withContext
        }
        if (before.code == RootfsHealthCode.ROOT_UNAVAILABLE) {
            _installState.value = RootfsInstallState.Failed(before.detail)
            return@withContext
        }
        _installState.value = RootfsInstallState.Extracting(0f)
        val after = UbuntuKernel.ensureRootfs()
        _installState.value = if (after.healthy) {
            RootfsInstallState.Installed
        } else {
            RootfsInstallState.Failed(after.detail)
        }
    }

''' + s[end:]
start = s.index("    suspend fun reset(keepUserData: Boolean = false): File? = withContext(Dispatchers.IO) {")
end = s.index("    suspend fun getRootfsSize()", start)
s = s[:start] + '''    suspend fun reset(keepUserData: Boolean = false): File? = withContext(Dispatchers.IO) {
        if (keepUserData) Log.i(TAG, "reset: app-owned persistent user data will be preserved")
        if (!UbuntuRuntime.isInitialized) UbuntuRuntime.init(context)
        ExecutionCoordinator.stopCurrentCommand()
        if (!UbuntuKernel.resetRootfs()) {
            val detail = "failed to reset Ubuntu rootfs"
            _installState.value = RootfsInstallState.Failed(detail)
            throw IllegalStateException(detail)
        }
        _installState.value = RootfsInstallState.Idle
        null
    }

''' + s[end:]
old = '''            if (UbuntuRuntime.isInitialized) {
                UbuntuRuntime.refreshDns(nameservers)
            } else {
                true
            }
'''
new = '''            if (!UbuntuRuntime.isInitialized) UbuntuRuntime.init(context)
            UbuntuKernel.refreshDns(nameservers)
'''
s = replace_once(s, old, new, "RootfsManager DNS")
write(rel, s)

print("phase1 direct runtime patch applied")
