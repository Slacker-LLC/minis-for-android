#!/usr/bin/env python3
from pathlib import Path
import os

ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / "src/android/app/src/main/java"
TEST = ROOT / "src/android/app/src/test/java"


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def replace_required(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"missing expected text in {path}: {old[:120]!r}")
    path.write_text(text.replace(old, new), encoding="utf-8")


# 1. WorkspaceFileClient is already direct App-side I/O. Move it out of the
# obsolete broker package and update every production/test reference.
old_workspace = MAIN / "com/openminis/app/runtime/minisd/WorkspaceFileClient.kt"
new_workspace = MAIN / "com/openminis/app/runtime/files/WorkspaceFileClient.kt"
if old_workspace.exists():
    new_workspace.parent.mkdir(parents=True, exist_ok=True)
    os.replace(old_workspace, new_workspace)
text = new_workspace.read_text(encoding="utf-8")
text = text.replace(
    "package com.openminis.app.runtime.minisd",
    "package com.openminis.app.runtime.files",
)
new_workspace.write_text(text, encoding="utf-8")

for base in (MAIN, TEST):
    for path in base.rglob("*.kt"):
        text = path.read_text(encoding="utf-8")
        next_text = text.replace(
            "com.openminis.app.runtime.minisd.WorkspaceFileClient",
            "com.openminis.app.runtime.files.WorkspaceFileClient",
        )
        if next_text != text:
            path.write_text(next_text, encoding="utf-8")

# 2. UbuntuRuntime is now only the public facade over UbuntuKernel. Drop all
# broker-era response/retry/layout helpers that existed solely for stale tests.
ubuntu_runtime = MAIN / "com/openminis/app/runtime/ubuntu/UbuntuRuntime.kt"
text = ubuntu_runtime.read_text(encoding="utf-8")
for line in (
    "import com.openminis.app.runtime.minisd.MinisdError\n",
    "import com.openminis.app.runtime.minisd.MinisdProtocol\n",
    "import com.openminis.app.runtime.minisd.MinisdResponse\n",
):
    text = text.replace(line, "")
text = text.replace('    private val WHITESPACE = Regex("\\\\s+")\n', "")
text = text.replace('    private val SHA256_TOKEN = Regex("^[0-9a-fA-F]{64}$")\n', "")
start = text.find("    class RuntimeInfrastructureException")
if start >= 0:
    end = text.find("\n\n    @Volatile", start)
    if end < 0:
        raise SystemExit("cannot locate RuntimeInfrastructureException end")
    text = text[:start] + text[end + 2:]
marker = "    // ---------------------------------------------------------------------\n    // Transitional pure helpers retained only so the existing broker-era JVM"
idx = text.find(marker)
if idx < 0:
    raise SystemExit("UbuntuRuntime transitional helper marker missing")
text = text[:idx].rstrip() + "\n}\n"
ubuntu_runtime.write_text(text, encoding="utf-8")

# 3. RuntimeProvision keeps only the rootfs staging contract. Broker install
# snippets are dead now that no executable broker is deployed.
write(
    MAIN / "com/openminis/app/runtime/ubuntu/RuntimeProvision.kt",
    '''package com.openminis.app.runtime.ubuntu

/** Packaged Ubuntu rootfs paths retained for repair/migration code. */
object RuntimeProvision {
    const val ROOTFS_ASSET = "minis-runtime/ubuntu-arm64-rootfs.tar.gz"
    const val STAGED_ROOTFS_ARCHIVE = "/data/adb/minis/runtime/staging/ubuntu-arm64-rootfs.tar.gz"
}
''',
)

# 4. The terminal production path already launches UbuntuKernel directly. Drop
# the old companion helper that fabricated a minisd --helper exec command.
terminal = MAIN / "com/openminis/app/sandbox/TerminalSession.kt"
text = terminal.read_text(encoding="utf-8")
start = text.find("        internal suspend fun prepareLaunch(")
if start >= 0:
    end = text.find("    }\n\n    enum class State", start)
    if end < 0:
        raise SystemExit("cannot locate TerminalSession legacy helper end")
    text = text[:start] + text[end:]
terminal.write_text(text, encoding="utf-8")

# Keep the lifecycle/PTY tests; remove only broker-launch assertions.
write(
    TEST / "com/openminis/app/runtime/terminal/TerminalSessionTest.kt",
    '''package com.openminis.app.runtime.terminal

import com.openminis.app.sandbox.TerminalSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalSessionTest {
    private val launch = TerminalSession.Launch("fixture-su", arrayOf("fixture-su"), emptyArray())

    @Test
    fun `duplicate starts during boot and cancellation cannot create a late PTY`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var preparations = 0
        val backend = FakePty()
        val session = TerminalSession(this, { preparations++; gate.await(); launch }, backend)
        session.start()
        runCurrent()
        session.start()
        assertEquals(TerminalSession.State.BOOTING, session.state.value)
        session.stop()
        session.stop()
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, preparations)
        assertEquals(0, backend.opened)
        assertEquals(TerminalSession.State.STOPPED, session.state.value)
    }

    @Test
    fun `EOF and repeated stop close and reap exactly once`() = runTest {
        val backend = FakePty()
        backend.reads.trySend(0)
        val session = TerminalSession(this, { launch }, backend)
        session.start()
        runCurrent()
        session.stop()
        session.stop()
        runCurrent()
        assertEquals(TerminalSession.State.STOPPED, session.state.value)
        assertEquals(listOf(11), backend.closed)
        assertEquals(listOf(101), backend.reaped)
        assertEquals(1, backend.readCount)
    }

    @Test
    fun `old reader cleanup cannot stop a restarted session`() = runTest {
        val oldReader = CompletableDeferred<Unit>()
        val backend = FakePty().apply {
            readHook = { fd ->
                if (fd == 11) withContext(NonCancellable) { oldReader.await(); 0 } else reads.receive()
            }
        }
        val session = TerminalSession(this, { launch }, backend)
        session.start()
        runCurrent()
        session.start()
        assertEquals(1, backend.opened)
        session.stop()
        session.start()
        runCurrent()
        assertEquals(2, backend.opened)
        oldReader.complete(Unit)
        runCurrent()
        assertEquals(TerminalSession.State.RUNNING, session.state.value)
        assertEquals(listOf(11), backend.closed)
        session.stop()
        runCurrent()
        assertEquals(listOf(11, 12), backend.closed)
        assertEquals(listOf(101, 102), backend.reaped)
    }

    @Test
    fun `partial writes retain input order and caller buffer ownership`() = runTest {
        val backend = FakePty()
        val session = TerminalSession(this, { launch }, backend)
        session.start()
        runCurrent()
        val first = "ab".toByteArray()
        session.sendRawBytes(first)
        first[0] = 'z'.code.toByte()
        session.sendText("cd")
        session.sendInterrupt()
        repeat(6) { backend.reads.trySend(-11) }
        runCurrent()
        assertEquals("abcd\\u0003", backend.written.toString())
        session.stop()
        runCurrent()
    }

    private class FakePty : PtyBackend {
        override val available = true
        var opened = 0
        var readCount = 0
        val reads = Channel<Int>(Channel.UNLIMITED)
        var readHook: (suspend (Int) -> Int)? = null
        val closed = mutableListOf<Int>()
        val reaped = mutableListOf<Int>()
        val written = StringBuilder()
        override fun open(launch: TerminalSession.Launch, cols: Int, rows: Int, outPid: IntArray): Int {
            opened++
            outPid[0] = 100 + opened
            return 10 + opened
        }
        override suspend fun read(fd: Int, bytes: ByteArray): Int {
            readCount++
            return readHook?.invoke(fd) ?: reads.receive()
        }
        override fun write(fd: Int, bytes: ByteArray, offset: Int): Int { written.append(bytes[offset].toInt().toChar()); return 1 }
        override fun resize(fd: Int, cols: Int, rows: Int) = Unit
        override fun close(fd: Int) { closed += fd }
        override fun terminateAndWait(pid: Int) { reaped += pid }
    }
}
''',
)

# 5. Replace the old wire/broker boundary test with the direct backend boundary.
write(
    TEST / "com/openminis/app/runtime/RuntimePackageBoundaryTest.kt",
    '''package com.openminis.app.runtime

import com.openminis.app.runtime.terminal.TerminalSanitizer
import com.openminis.app.runtime.ubuntu.UbuntuPaths
import com.openminis.app.runtime.ubuntu.UbuntuRuntime
import com.openminis.app.sandbox.RootfsManager
import com.openminis.app.sandbox.TerminalSession
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimePackageBoundaryTest {
    @Test
    fun activeComponentsLiveUnderCurrentRuntimeBoundary() {
        assertEquals("com.openminis.app.runtime", RuntimePathRegistry::class.java.packageName)
        assertEquals("com.openminis.app.runtime", ExternalMountCoordinator::class.java.packageName)
        assertEquals("com.openminis.app.runtime", ExecutionCoordinator::class.java.packageName)
        assertEquals("com.openminis.app.runtime.ubuntu", UbuntuRuntime::class.java.packageName)
        assertEquals("com.openminis.app.runtime.terminal", TerminalSanitizer::class.java.packageName)
    }

    @Test
    fun compatibilityShellsRemainOutsideActiveRuntimeBoundary() {
        assertEquals("com.openminis.app.sandbox", RootfsManager::class.java.packageName)
        assertEquals("com.openminis.app.sandbox", TerminalSession::class.java.packageName)
    }

    @Test
    fun directRuntimeKeepsOnlyRootfsUnderPrivilegedDataRoot() {
        assertEquals("/data/adb/minis", UbuntuPaths.HOST_MINIS)
        assertEquals("/data/adb/minis/rootfs", UbuntuPaths.HOST_ROOTFS)
    }
}
''',
)

# 6. UI must no longer depend on a protocol constant just to find the rootfs.
mirror = MAIN / "com/openminis/app/ui/sandbox/MirrorSettingsScreen.kt"
text = mirror.read_text(encoding="utf-8")
text = text.replace(
    "File(com.openminis.app.runtime.minisd.MinisdProtocol.DEFAULT_ROOTFS)",
    "File(com.openminis.app.runtime.ubuntu.UbuntuPaths.HOST_ROOTFS)",
)
mirror.write_text(text, encoding="utf-8")

# 7. Delete production-dead broker response/distribution layers and their stale
# tests. RuntimeDistributionManifest/PayloadVerifier stay until rootfs-only
# manifest packaging is migrated in phase 2c.
for rel in [
    "src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/RuntimeDiagnostics.kt",
    "src/android/app/src/main/java/com/openminis/app/runtime/distribution/RuntimeDistributionManager.kt",
    "src/android/app/src/main/java/com/openminis/app/runtime/minisd/MinisdClient.kt",
    "src/android/app/src/main/java/com/openminis/app/runtime/minisd/MinisdProtocol.kt",
    "src/android/app/src/main/java/com/openminis/app/runtime/minisd/MinisdBootstrap.kt",
    "src/android/app/src/main/java/com/openminis/app/runtime/minisd/ExecutionCancellationRegistry.kt",
    "src/android/app/src/test/java/com/openminis/app/runtime/ubuntu/RuntimeDiagnosticsTest.kt",
    "src/android/app/src/test/java/com/openminis/app/runtime/ubuntu/UbuntuRuntimeRecoveryTest.kt",
    "src/android/app/src/test/java/com/openminis/app/runtime/ubuntu/RuntimeProvisionTest.kt",
    "src/android/app/src/test/java/com/openminis/app/runtime/distribution/RuntimeDistributionManagerTest.kt",
    "src/android/app/src/test/java/com/openminis/app/runtime/minisd/ExecutionCancellationRegistryTest.kt",
    "src/android/app/src/test/java/com/openminis/app/runtime/minisd/MinisdBootstrapTest.kt",
    "src/android/app/src/test/java/com/openminis/app/runtime/minisd/MinisdProtocolTest.kt",
    "src/android/app/src/test/java/com/openminis/app/runtime/minisd/RootfsDnsRefreshTest.kt",
]:
    path = ROOT / rel
    if path.exists():
        path.unlink()

print("phase2a direct runtime cleanup applied")
