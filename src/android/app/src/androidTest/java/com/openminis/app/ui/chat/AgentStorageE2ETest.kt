package com.openminis.app.ui.chat

import android.app.usage.StorageStatsManager
import android.os.Process
import android.os.storage.StorageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.agent.AgentRunner
import com.openminis.app.agent.AgentSessionStore
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.repository.awaitConfigLoaded
import com.openminis.app.runtime.files.SafeFileTree
import com.openminis.app.runtime.files.WorkspaceFileClient
import com.openminis.app.runtime.ubuntu.UbuntuPaths
import com.openminis.app.ui.settings.loadStorageSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

/** Actual model file effects + Room session inventory + own-package Android storage accounting. */
@RunWith(AndroidJUnit4::class)
class AgentStorageE2ETest {
    @Test
    fun accountForGlobalAndRetainedDataWithoutRoot(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        app.providerRepository.awaitConfigLoaded(); UbuntuPaths.initialize(app)
        val marker = "storage-e2e-${UUID.randomUUID()}"
        val sid = app.chatRepository.createSession("scripted-model", title = marker, memoryEnabled = false).id
        val orphan = "orphan-${UUID.randomUUID()}"
        val report = JSONObject().put("device", android.os.Build.FINGERPRINT).put("scenarios", JSONArray())
        val globals = linkedMapOf("shared" to "/var/minis/shared", "skills" to "/var/minis/skills", "memory" to "/var/minis/memory", "home" to "/home/minis")
        val cacheFixture = File(app.cacheDir, marker)
        fun passed(name: String) { report.getJSONArray("scenarios").put(JSONObject().put("name", name).put("passed", true)) }
        var calls = 0
        suspend fun write(path: String, content: String) {
            val callId = "storage-call-${++calls}"
            var receipt: AgentContentPart.ToolResult? = null
            val provider = ScriptedProvider { call, history, _ ->
                if (call == 1) toolCall(callId, "file_write", JSONObject().put("path", path).put("content", content)) else {
                    receipt = history.flatMap { it.contentParts }.filterIsInstance<AgentContentPart.ToolResult>().singleOrNull { it.id == callId }
                    text("已保存实际测试文件。")
                }
            }
            val vm = withContext(Dispatchers.Main) { chatProjection(app, sid) }
            withTimeout(15_000) { vm.sessionLoaded.first { it } }
            withContext(Dispatchers.Main) { vm.currentProvider = provider; vm.currentModel = provider.model; vm._activeEntryId.value = "storage-scripted" }
            assertTrue(AgentRunner.prompt(app, sid, "保存本次测试文件", wait = true, timeoutMs = 20_000).completed)
            val actual = receipt ?: error("no file receipt")
            assertFalse(actual.content, actual.isError)
            assertEquals(content.toByteArray().size.toLong(), WorkspaceFileClient.info(sid, path).getLong("size"))
        }
        try {
            val before = loadStorageSnapshot(app, app.database.chatDao())
            assertEquals(0L, before.sessions.single { it.id == sid }.minisSize)
            assertNull(AgentSessionStore.existing(sid))
            assertFalse(SafeFileTree.existsNoFollow(File(UbuntuPaths.hostSessions, sid)))
            assertTrue(before.sessionSizesComplete)
            passed("an actual unused Room draft measures zero bytes without creating a runtime owner or guest directory")
            assertNotNull(before.appSize); assertTrue(before.appSize!! > 0L)
            assertNotNull(before.appDataSize); assertNotNull(before.cacheSize)
            passed("actual own-package StorageStats query works without granting usage-access or Root")
            val payload = "owned storage payload\n".repeat(256)
            for ((key, root) in globals) {
                write("$root/$marker/payload.txt", payload)
                val snapshot = loadStorageSnapshot(app, app.database.chatDao())
                assertEquals(before.globalSizes.getValue(key)!! + payload.toByteArray().size, snapshot.globalSizes.getValue(key))
            }
            passed("real model writes to shared skills memory and home are all visible in their existing file-backed categories")
            write("/workspace/$marker.txt", "session work\n")
            write("/var/minis/attachments/$marker.txt", "session input\n")
            write("/var/minis/offloads/$marker.txt", "session output\n")
            write("/var/minis/browser/$marker.txt", "session browser\n")
            write("/workspace/projects/attachments/payload.txt", "nested work\n")
            val current = loadStorageSnapshot(app, app.database.chatDao())
            val owned = current.sessions.single { it.id == sid }
            assertEquals("session work\nsession input\nsession output\nsession browser\nnested work\n".toByteArray().size.toLong(), owned.minisSize)
            assertEquals(marker, owned.title); assertTrue(current.sessionSizesComplete)
            passed("real Room session and four existing guest roots resolve to the correct session storage breakdown")
            assertTrue(cacheFixture.mkdirs())
            val extraBytes = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
            File(cacheFixture, "cache.bin").writeBytes(extraBytes)
            WorkspaceFileClient.writeBytes(orphan, "/workspace/retained.bin", extraBytes)
            assertNull(app.database.chatDao().getSession(orphan))
            val retained = loadStorageSnapshot(app, app.database.chatDao())
            assertFalse(retained.sessions.any { it.id == orphan })
            assertTrue("actual platform app data must include newly allocated cache and retained guest data", retained.appDataSize!! > before.appDataSize!!)
            assertTrue(retained.cacheSize!! > before.cacheSize!!)
            report.put("appDataGrowthBytes", retained.appDataSize!! - before.appDataSize!!)
            passed("actual platform totals include new cache and orphan session data without inventing Room sessions")
            val target = File(cacheFixture, "cache.bin")
            val link = File(UbuntuPaths.hostShared, "$marker/foreign-link")
            Files.createSymbolicLink(link.toPath(), target.toPath())
            val linked = loadStorageSnapshot(app, app.database.chatDao())
            assertEquals(retained.globalSizes.getValue("shared"), linked.globalSizes.getValue("shared"))
            passed("existing secure global directory measurement does not follow a symlink into another category")
            assertEquals((linked.shellSize ?: 0L) + linked.appSize!! + linked.appDataSize!!, linked.total)
            assertEquals(linked.shellSize != null, linked.totalComplete)
            assertTrue(linked.hasMeasuredTotal)
            report.put("rootMeasureUnavailable", linked.shellSize == null)
            if (linked.shellSize == null) assertFalse(linked.totalComplete)
            // This emulator has no app-authorized Root probe. The unavailable size must stay unknown.
            assertNull("the emulator must exercise the unavailable Root measurement path", linked.shellSize)
            passed("unavailable real Root size remains unknown and the header represents a measured lower bound")
            val stats = app.getSystemService(StorageStatsManager::class.java).queryStatsForPackage(
                app.applicationInfo.storageUuid ?: StorageManager.UUID_DEFAULT, app.packageName, Process.myUserHandle())
            assertEquals(stats.appBytes, linked.appSize)
            assertTrue(linked.appDataSize!! >= linked.cacheSize!!)
            assertNotEquals(linked.total + linked.dbSize + linked.sessionsTotal + linked.cacheSize!!, linked.total)
            passed("package program and data are the top-level totals; database sessions and cache are not counted twice")
            val delivery = JSONObject().put("marker", marker).put("appBytes", linked.appSize).put("appDataBytes", linked.appDataSize)
                .put("cacheBytes", linked.cacheSize).put("rootBytes", JSONObject.NULL).put("totalComplete", linked.totalComplete)
                .put("globalSizes", JSONObject(linked.globalSizes)).put("sessionBytes", owned.totalSize)
            val bytes = delivery.toString(2).toByteArray()
            File(app.filesDir, "agent-storage-delivery.json").writeBytes(bytes)
            report.put("artifactSha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            report.put("passed", true)
        } catch (error: Throwable) { report.put("passed", false).put("failure", error.toString()); throw error }
        finally {
            var cleanupComplete = true
            globals.values.forEach { root ->
                val removed = runCatching {
                    val path = "$root/$marker"
                    if (WorkspaceFileClient.info(sid, path).optBoolean("exists")) WorkspaceFileClient.delete(sid, path)
                    !WorkspaceFileClient.info(sid, path).optBoolean("exists")
                }.getOrDefault(false)
                cleanupComplete = cleanupComplete && removed
            }
            cleanupComplete = SafeFileTree.deleteRecursively(cacheFixture) && cleanupComplete
            cleanupComplete = UbuntuPaths.deleteSession(app, orphan) && cleanupComplete
            AgentRunner.cancel(app, sid); app.chatRepository.deleteSession(sid)
            cleanupComplete = !SafeFileTree.existsNoFollow(File(UbuntuPaths.hostSessions, sid)) && cleanupComplete
            report.put("cleanupComplete", cleanupComplete)
            if (!cleanupComplete) report.put("passed", false).put("cleanupFailure", "one or more dedicated fixtures remain")
            File(app.filesDir, "agent-storage-e2e.json").writeText(report.toString(2))
            assertTrue("all dedicated storage fixtures must be removed", cleanupComplete)
        }
    }
}
