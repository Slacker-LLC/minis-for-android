package com.openminis.app.tools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.runtime.RuntimePathRegistry
import com.openminis.app.runtime.files.WorkspaceFileClient
import com.openminis.app.runtime.ubuntu.UbuntuPaths
import com.openminis.app.tools.runtime.ToolExecutor
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Real tool dispatch, SAF grants, Android NIO and PackageManager; no mocked filesystem. */
@RunWith(AndroidJUnit4::class)
class AgentToolsWorkflowE2ETest {
    @Test
    fun copyInspectMoveAndRejectUnsafeWrites(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        UbuntuPaths.initialize(context)
        val session = "tool-e2e-${UUID.randomUUID()}"
        checkNotNull(UbuntuPaths.ensureSessionDirs(session))
        val store = checkNotNull(RuntimePathRegistry.mountedFoldersStore)
        val mount = store.entries.value.single { it.name == "MinisToolE2E" }
        check(mount.effectiveWritable) { "Choose writable /sdcard/MinisToolE2E in Mount External Folders first" }
        val shared = "/var/minis/shared/$session"
        val external = "/var/minis/mounts/${mount.name}/$session"
        val report = JSONObject().put("device", android.os.Build.FINGERPRINT)
            .put("session", session).put("scenarios", JSONArray())
        val reportFile = File(context.filesDir, "agent-tools-e2e.json")
        suspend fun call(name: String, args: JSONObject): ToolExecutionResult =
            ToolExecutor.execute(name, args.toString(), session, context)
        fun passed(name: String) {
            report.getJSONArray("scenarios").put(JSONObject().put("name", name).put("passed", true))
            reportFile.writeText(report.toString(2))
        }
        try {
            // The installed debug APK is a real signed archive larger than the old 50 MiB cap.
            val source = File(context.applicationInfo.sourceDir)
            assertTrue("fixture must exceed the former 50 MiB limit", source.length() > 50L * 1024 * 1024)
            source.inputStream().use { input ->
                WorkspaceFileClient.writeStream(session, "$shared/app.apk", input, source.length())
            }
            val expectedHash = hash(source)
            report.put("artifactBytes", source.length()).put("artifactSha256", expectedHash)
            val copy = call("linux.file.copy", JSONObject().put("source", "$shared/app.apk").put("destination", "$external/app.apk"))
            assertTrue(copy.output, copy.success)
            val externalHost = checkNotNull(UbuntuPaths.resolveForFileAccess(session, "$external/app.apk"))
            assertEquals(expectedHash, hash(externalHost))
            passed("large APK shared -> SAF mount, SHA-256 verified")

            for (path in listOf("$shared/app.apk", "$external/app.apk")) {
                val inspected = call("android.deploy", JSONObject().put("action", "inspect_apk").put("artifactPath", path))
                assertTrue(inspected.output, inspected.success)
                val artifact = JSONObject(inspected.output)
                assertEquals(context.packageName, artifact.getString("package"))
                assertEquals(source.length(), artifact.getLong("sizeBytes"))
                assertEquals(expectedHash, hash(File(artifact.getString("hostPath"))))
                assertTrue(artifact.getJSONArray("signingSha256").length() > 0)
            }
            passed("large guest and SAF APKs parsed by Android PackageManager")

            val moved = call("linux.file.move", JSONObject().put("source", "$external/app.apk").put("destination", "/workspace/returned.apk"))
            assertTrue(moved.output, moved.success)
            assertFalse(WorkspaceFileClient.info(session, "$external/app.apk").optBoolean("exists"))
            assertEquals(expectedHash, hash(checkNotNull(UbuntuPaths.resolveForFileAccess(session, "/workspace/returned.apk"))))
            passed("SAF mount -> session workspace move, source removed after complete copy")

            WorkspaceFileClient.writeBytes(session, "$shared/tree/sub/a.txt", "A".toByteArray())
            val tree = call("linux.file.copy", JSONObject().put("source", "$shared/tree").put("destination", "$external/tree"))
            assertTrue(tree.output, tree.success)
            assertEquals("A", WorkspaceFileClient.readAll(session, "$external/tree/sub/a.txt").decodeToString())
            passed("recursive shared -> SAF mount directory copy")

            assertTrue(store.setUserAllowWrite(mount.id, false))
            val denied = call("linux.file.copy", JSONObject().put("source", "$shared/app.apk").put("destination", "$external/denied.apk"))
            assertFalse(denied.output, denied.success)
            assertFalse(WorkspaceFileClient.info(session, "$external/denied.apk").optBoolean("exists"))
            passed("read-only SAF destination refused before mutation")
            assertTrue(store.setUserAllowWrite(mount.id, true))

            SessionPermissionStore.setPreset(context, session, SessionPermissionStore.WORKSPACE_WRITE)
            val scoped = call("linux.file.copy", JSONObject().put("source", "/workspace/returned.apk").put("destination", "$external/scoped.apk"))
            assertFalse(scoped.output, scoped.success)
            assertFalse(WorkspaceFileClient.info(session, "$external/scoped.apk").optBoolean("exists"))
            passed("workspace-write session refuses external mutation")
            SessionPermissionStore.setPreset(context, session, null)

            val escape = call("linux.file.copy", JSONObject().put("source", "$shared/app.apk").put("destination", "$external/../escaped.apk"))
            assertFalse(escape.output, escape.success)
            passed("parent traversal refused")
            report.put("passed", true)
        } catch (error: Throwable) {
            report.put("passed", false).put("failure", error.toString())
            throw error
        } finally {
            SessionPermissionStore.setPreset(context, session, null)
            store.setUserAllowWrite(mount.id, true)
            runCatching { WorkspaceFileClient.delete(session, external) }
            runCatching { WorkspaceFileClient.delete(session, shared) }
            runCatching { WorkspaceFileClient.deleteSession(session) }
            reportFile.writeText(report.toString(2))
        }
    }

    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
