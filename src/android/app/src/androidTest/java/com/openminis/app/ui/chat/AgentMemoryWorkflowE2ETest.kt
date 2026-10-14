package com.openminis.app.ui.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.agent.*
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.awaitConfigLoaded
import com.openminis.app.runtime.files.WorkspaceFileClient
import com.openminis.app.runtime.ubuntu.UbuntuPaths
import com.openminis.app.tools.runtime.ToolExecutor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CyclicBarrier

/** Actual model sessions, shared repository, registered tools, Room and App-owned files. */
@RunWith(AndroidJUnit4::class)
class AgentMemoryWorkflowE2ETest {
    @Test fun memorySourcesAndConcurrentChangesStayExact(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        app.providerRepository.awaitConfigLoaded(); UbuntuPaths.initialize(app)
        val repo = app.memoryRepository
        val marker = "m4-memory-${UUID.randomUUID()}"
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".md"
        val sessions = mutableListOf<String>()
        val cleanup = mutableListOf<Pair<String, String?>>()
        val report = JSONObject().put("device", android.os.Build.FINGERPRINT).put("marker", marker).put("scenarios", JSONArray())
        fun passed(name: String) { report.getJSONArray("scenarios").put(JSONObject().put("name", name).put("passed", true)) }
        suspend fun session(enabled: Boolean = true) = app.chatRepository.createSession("scripted-model",
            title = "M4 memory E2E", memoryEnabled = enabled).id.also(sessions::add)
        suspend fun raw() = WorkspaceFileClient.readAll("", "/var/minis/memory/$date").toString(Charsets.UTF_8)
        fun metadata(content: String) = content.lineSequence().filter { it.startsWith("<!-- minis-memory ") }
            .map { JSONObject(it.removePrefix("<!-- minis-memory ").removeSuffix(" -->")) }.toList()
        suspend fun modelWrite(sid: String, content: String, kind: String): MemoryToolRecord {
            val provider = ScriptedProvider { call, history, _ ->
                if (call == 1) toolCall("memory-$sid", "memory_write", JSONObject().put("tool_title", "保存验收记录")
                    .put("content", content).put("kind", kind))
                else {
                    val result = history.flatMap { it.contentParts }.filterIsInstance<AgentContentPart.ToolResult>()
                        .single { it.id == "memory-$sid" }
                    assertFalse(result.content, result.isError); text("记录已保存。")
                }
            }
            val vm = withContext(Dispatchers.Main) { chatProjection(app, sid) }
            withTimeout(15_000) { vm.sessionLoaded.first { it } }
            withContext(Dispatchers.Main) {
                vm.currentProvider = provider; vm.currentModel = provider.model; vm._activeEntryId.value = "memory-scripted"
            }
            assertTrue(AgentRunner.prompt(app, sid, "保存本次验收记录", wait = true, timeoutMs = 25_000).completed)
            return vm.memoryToolRecords.value.single { it.isWrite }.also { cleanup.add(content to it.entryId) }
        }
        try {
            val globalBefore = repo.readFileForEdit("GLOBAL.md")
            val same = "## $marker\n两个真实会话保存相同正文。"
            val a = session(); val b = session()
            val ra = modelWrite(a, same, "task_fact")
            val rb = modelWrite(b, same, "user_preference")
            assertNotNull(ra.entryId); assertNotEquals(ra.entryId, rb.entryId)
            val initial = metadata(raw())
            assertEquals(a, initial.single { it.getString("entry_id") == ra.entryId }.getString("session_id"))
            assertEquals("task_fact", initial.single { it.getString("entry_id") == ra.entryId }.getString("kind"))
            assertEquals(b, initial.single { it.getString("entry_id") == rb.entryId }.getString("session_id"))
            assertEquals("user_preference", initial.single { it.getString("entry_id") == rb.entryId }.getString("kind"))
            passed("model memory writes record distinct IDs, actual source sessions and background types")

            val ownerA = checkNotNull(AgentSessionStore.existing(a))
            assertTrue(ownerA.revokeMemoryRecord(ra) is MemoryRepository.EntryMutationResult.Success)
            assertFalse(metadata(raw()).any { it.optString("entry_id") == ra.entryId })
            assertTrue(metadata(raw()).any { it.optString("entry_id") == rb.entryId })
            passed("revoking identical text in one session preserves the other session record")
            val ownerB = checkNotNull(AgentSessionStore.existing(b))
            val edited = "## $marker\n编辑后保留身份与来源。"
            assertTrue(ownerB.replaceMemoryRecord(rb, edited) is MemoryRepository.EntryMutationResult.Success)
            cleanup.add(edited to rb.entryId)
            val afterEdit = metadata(raw()).single { it.getString("entry_id") == rb.entryId }
            assertEquals(b, afterEdit.getString("session_id")); assertEquals("user_preference", afterEdit.getString("kind"))
            assertTrue(ownerB.revokeMemoryRecord(rb) is MemoryRepository.EntryMutationResult.NotFound)
            assertTrue(ownerB.replaceMemoryRecord(rb, "stale overwrite") is MemoryRepository.EntryMutationResult.NotFound)
            assertTrue(raw().contains(edited))
            passed("editing preserves provenance and stale edit or revoke cannot overwrite newer content")

            val c = session()
            val beforeRejected = raw()
            val base = JSONObject().put("content", "$marker rejected").put("tool_title", "拒绝伪造来源")
            cleanup.add(base.getString("content") to null)
            assertTrue(ToolExecutor.execute("memory_write", JSONObject(base.toString()).put("session_id", b).toString(), c, app).success)
            val forged = metadata(raw()).single { it.optString("session_id") == c }
            assertEquals(c, forged.getString("session_id")); assertNotEquals(b, forged.getString("session_id"))
            val beforeInvalidKind = raw()
            assertFalse(ToolExecutor.execute("memory_write", JSONObject(base.toString()).put("kind", "standing_instruction").toString(), c, app).success)
            assertEquals(beforeInvalidKind, raw()); assertNull(AgentSessionStore.existing(c))
            assertNotEquals(beforeRejected, beforeInvalidKind)
            passed("extra source argument cannot forge provenance and invalid kind has no file or owner side effect")

            val opened = repo.readFileForEdit(date)
            val concurrent = (0 until 12).map { index -> session() to "## $marker concurrent-$index\n同时写入 $index" }
            concurrent.forEach { (_, body) -> cleanup.add(body to null) }
            val barrier = CyclicBarrier(concurrent.size)
            val results = concurrent.map { (sid, body) -> async(Dispatchers.IO) {
                barrier.await(10, java.util.concurrent.TimeUnit.SECONDS)
                ToolExecutor.execute("memory_write", JSONObject().put("content", body).put("tool_title", "并发写记忆").toString(), sid, app)
            } }.awaitAll()
            val persisted = raw()
            results.forEach { assertTrue(it.output, it.success) }
            concurrent.forEach { (sid, body) ->
                assertTrue(persisted.contains(body)); assertNull(AgentSessionStore.existing(sid))
                val id = metadata(persisted).single { it.optString("session_id") == sid }.getString("entry_id")
                cleanup.add(body to id)
            }
            assertEquals(12, metadata(persisted).count { it.optString("session_id") in concurrent.map { pair -> pair.first } })
            passed("twelve concurrent real headless session tools preserve every successful write without creating owners")
            assertFalse(repo.saveFileIfUnchanged(date, "stale editor overwrite", opened))
            assertEquals(persisted, raw())
            passed("editor compare-and-save rejects a snapshot invalidated by concurrent successful writes")

            val longBody = "## $marker deep\n" + (0 until 80).joinToString("\n") {
                if (it == 60) "$marker needledeep" else "background line $it"
            }
            val deepSid = session(); val deep = modelWrite(deepSid, longBody, "task_fact")
            val found = repo.getMemory("needledeep", "daily")
            assertTrue(found.contains(deep.entryId!!)); assertTrue(found.contains(deepSid))
            assertTrue(found.contains("needledeep"))
            assertTrue(repo.listAllFiles().single { it.name == date }.preview.contains(marker))
            passed("deep keyword matches retain source metadata and daily file preview stays readable")

            val disabled = session(false)
            val beforeDisabled = raw()
            assertFalse(ToolExecutor.execute("memory_write", base.toString(), disabled, app).success)
            assertEquals(beforeDisabled, raw()); assertEquals(globalBefore, repo.readFileForEdit("GLOBAL.md"))
            passed("disabled session refuses shared memory writes and user-maintained GLOBAL remains unchanged")
            AgentSessionStore.closeAndAwait(deepSid); AgentSessionStore.release(deepSid)
            withContext(Dispatchers.Main) { ChatViewModelStore.release(deepSid) }
            assertTrue(repo.getMemory("needledeep", "daily").contains(deep.entryId!!))
            val unknown = "$marker unknown-source"
            val unknownId = UUID.randomUUID().toString()
            assertTrue(repo.writeMemory(unknown, entryId = unknownId).startsWith("Memory saved"))
            cleanup.add(unknown to unknownId)
            assertTrue(metadata(raw()).single { it.getString("entry_id") == unknownId }.isNull("session_id"))
            assertTrue(repo.revokeEntry(unknown, unknownId, a) is MemoryRepository.EntryMutationResult.NotFound)
            passed("file provenance survives owner release and unavailable legacy source is never guessed")
            val large = "## $marker oversized\n" + "长行背景资料".repeat(10_000)
            val largeSid = session(); val largeRecord = modelWrite(largeSid, large, "task_fact")
            val promptProvider = ScriptedProvider { _, _, _ -> text("只处理当前请求。") }
            val largeOwner = checkNotNull(AgentSessionStore.existing(largeSid))
            withContext(Dispatchers.Main) {
                largeOwner.currentProvider = promptProvider; largeOwner.currentModel = promptProvider.model
            }
            assertTrue(AgentRunner.prompt(app, largeSid, "检查当前上下文", wait = true, timeoutMs = 25_000).completed)
            val prompt = promptProvider.systemPrompts.single().orEmpty()
            val daily = prompt.substringAfter("Recent memories (auto-injected from daily logs):").substringBefore("Runtime context:")
            assertTrue(prompt.contains("not standing instructions")); assertTrue(daily.contains("truncated"))
            assertTrue("daily prompt must use live 32768-token window", daily.length < 10_000)
            assertTrue(raw().contains(large)); assertTrue(daily.contains(largeRecord.entryId!!))
            assertEquals(repo.loadRecentDailyMemoryFragment(32768), repo.loadRecentDailyMemoryFragmentAsync(32768))
            passed("actual model prompt bounds long daily lines by live context budget without changing stored memory")
            val offProvider = ScriptedProvider { _, _, _ -> text("不注入记忆。") }
            val offVm = withContext(Dispatchers.Main) { chatProjection(app, disabled) }
            withTimeout(15_000) { offVm.sessionLoaded.first { it } }
            withContext(Dispatchers.Main) {
                offVm.currentProvider = offProvider; offVm.currentModel = offProvider.model; offVm._activeEntryId.value = "memory-off-scripted"
            }
            assertTrue(AgentRunner.prompt(app, disabled, "检查关闭记忆的上下文", wait = true, timeoutMs = 25_000).completed)
            assertFalse(offProvider.systemPrompts.single().orEmpty().contains("Recent memories (auto-injected"))
            assertFalse(offProvider.offeredTools.any { it in setOf("memory_write", "memory_get") })
            passed("disabled memory session excludes daily injection and memory tools from the actual provider request")
            File(app.filesDir, "agent-memory-delivery.json").writeText(JSONObject().put("marker", marker)
                .put("concurrentSources", JSONArray(concurrent.map { it.first }))
                .put("initialModelEntries", JSONArray(initial.filter { it.optString("entry_id") in listOf(ra.entryId, rb.entryId) }))
                .put("deepSearch", found).put("globalUnchanged", true).toString(2))
            report.put("passed", true)
        } catch (error: Throwable) {
            report.put("passed", false).put("failure", error.toString()); throw error
        } finally {
            cleanup.asReversed().forEach { (body, id) -> repo.revokeEntry(body, id) }
            sessions.forEach { sid ->
                AgentSessionStore.closeAndAwait(sid); AgentSessionStore.release(sid)
                withContext(Dispatchers.Main) { ChatViewModelStore.release(sid) }
                SessionEventHub.clear(sid); app.chatRepository.deleteSession(sid)
            }
            File(app.filesDir, "agent-memory-e2e.json").writeText(report.toString(2))
        }
    }
}
