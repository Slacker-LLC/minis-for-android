#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / "src/android/app/src/main/java"
TEST = ROOT / "src/android/app/src/test/java"


def replace_required(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"missing expected text in {path}: {old[:160]!r}")
    path.write_text(text.replace(old, new), encoding="utf-8")


kernel = MAIN / "com/openminis/app/runtime/ubuntu/UbuntuKernel.kt"
replace_required(
    kernel,
    "import com.openminis.app.runtime.ExecutionCoordinator\n",
    "import com.openminis.app.runtime.ExecutionCoordinator\nimport com.openminis.app.runtime.guest.GuestCommandBridge\n",
)
replace_required(
    kernel,
    '''        val migrated = migrateRootOwnedUserDataLocked(ctx)\n        if (!migrated) {\n            return@withLock Status(false, error = "failed to migrate legacy /data/adb/minis user data into app storage")\n        }\n\n        val version = health.metadata?.optString("version")?.takeIf { it.isNotBlank() }\n''',
    '''        val migrated = migrateRootOwnedUserDataLocked(ctx)\n        if (!migrated) {\n            return@withLock Status(false, error = "failed to migrate legacy /data/adb/minis user data into app storage")\n        }\n\n        if (!GuestCommandBridge.ensureGuestCliInstalled(ctx)) {\n            return@withLock Status(false, error = "failed to install direct guest command bridge")\n        }\n\n        val version = health.metadata?.optString("version")?.takeIf { it.isNotBlank() }\n''',
)
replace_required(
    kernel,
    '''        if (!repaired.success) {\n            return RootfsHealth(\n                RootfsHealthCode.CORRUPT,\n                repaired.error ?: repaired.stderr.ifBlank { "rootfs repair exited ${repaired.exitCode}" },\n            )\n        }\n        return inspectRootfs()\n''',
    '''        if (!repaired.success) {\n            return RootfsHealth(\n                RootfsHealthCode.CORRUPT,\n                repaired.error ?: repaired.stderr.ifBlank { "rootfs repair exited ${repaired.exitCode}" },\n            )\n        }\n        GuestCommandBridge.invalidateGuestCli()\n        return inspectRootfs()\n''',
)
replace_required(
    kernel,
    '''        val result = DirectRootRunner.runScript(\n            "rm -rf -- ${DirectRootRunner.shellQuote(rootfs)}",\n            ROOTFS_TIMEOUT_MS,\n        )\n        result.success\n''',
    '''        val result = DirectRootRunner.runScript(\n            "rm -rf -- ${DirectRootRunner.shellQuote(rootfs)}",\n            ROOTFS_TIMEOUT_MS,\n        )\n        if (result.success) GuestCommandBridge.invalidateGuestCli()\n        result.success\n''',
)

# Old Android abstract-socket endpoint is no longer part of the active runtime.
old_server = MAIN / "com/openminis/app/runtime/minisd/MinisdConfigBridgeServer.kt"
if old_server.exists():
    old_server.unlink()

old_test = TEST / "com/openminis/app/runtime/minisd/MinisdBridgeCommandTest.kt"
if old_test.exists():
    old_test.unlink()

new_test = TEST / "com/openminis/app/runtime/guest/GuestCommandBridgeTest.kt"
new_test.parent.mkdir(parents=True, exist_ok=True)
new_test.write_text(r'''package com.openminis.app.runtime.guest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GuestCommandBridgeTest {
    @Test
    fun `dispatch preserves stdin session cwd and shell-sensitive arguments`() {
        val prompt = "quote=\"x\"\n中文 $ ` \\ end\n"
        var received: NativeOffloadRequest? = null
        val result = GuestCommandBridge.dispatch(
            cmd = "minis-model-use",
            args = listOf("run", "--model", "image-model"),
            session = "session-42",
            cwd = "/workspace",
            stdin = prompt,
            pid = 321,
        ) { name ->
            assertEquals("minis-model-use", name)
            NativeOffloadHandler {
                received = it
                NativeOffloadResult(0, "ok")
            }
        }
        assertEquals(0, result.exitCode)
        val request = received!!
        assertEquals(321, request.pid)
        assertEquals("session-42", request.sessionId)
        assertEquals("session-42", request.env["MINIS_CHAT_SESSION_ID"])
        assertEquals("/workspace", request.cwd)
        assertEquals(prompt, request.stdin)
        assertEquals(listOf("minis-model-use", "run", "--model", "image-model"), request.argv)
    }

    @Test
    fun `file payload replaces file flag and path with exact content`() {
        val args = listOf("set", "soul.body", "--file", "/tmp/value.json", "--caption", "x")
        val rewritten = GuestCommandBridge.rewriteFileArgument(
            args,
            "\"line1\\nline2 $ ` \\\\\"\"".toByteArray(),
        )
        assertEquals(
            listOf("set", "soul.body", "\"line1\\nline2 $ ` \\\\\"\"", "--caption", "x"),
            rewritten,
        )
        assertEquals(args, GuestCommandBridge.rewriteFileArgument(args, null))
    }

    @Test
    fun `invalid command and session are rejected before handler lookup`() {
        assertThrows(IllegalArgumentException::class.java) {
            GuestCommandBridge.dispatch("su", listOf("-c", "id"), "", "/workspace", "", 1) { error("lookup") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            GuestCommandBridge.dispatch("minis-model-use", emptyList(), "../other", "/workspace", "", 1) { error("lookup") }
        }
    }

    @Test
    fun `unregistered model handler fails explicitly`() {
        val result = GuestCommandBridge.dispatch(
            "minis-model-use",
            listOf("list"),
            "",
            "/workspace",
            "",
            1,
        ) { null }
        assertEquals(127, result.exitCode)
        assertEquals("minis-model-use handler not registered\n", result.output)
    }
}
''', encoding="utf-8")

# These comments were the last Java/Kotlin mentions of the removed API/package.
for path, old, new in [
    (
        MAIN / "com/openminis/app/tools/runtime/ToolProvider.kt",
        "(UbuntuRuntime / MinisdClient)",
        "(UbuntuRuntime / App-owned direct backend)",
    ),
    (
        MAIN / "com/openminis/app/tools/runtime/LinuxProvider.kt",
        "singleton (UbuntuRuntime / MinisdClient)",
        "singleton (UbuntuRuntime / App-owned direct backend)",
    ),
    (
        MAIN / "com/openminis/app/runtime/files/WorkspaceFileClient.kt",
        "move out of runtime.minisd once all callers are migrated.",
        "retain the guest-path API while using direct App-owned file I/O.",
    ),
]:
    replace_required(path, old, new)

print("phase2b direct guest bridge cleanup applied")
