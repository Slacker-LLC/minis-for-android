package com.openminis.app.ui.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.agent.AgentRunner
import com.openminis.app.agent.AgentSessionStore
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.repository.awaitConfigLoaded
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.runtime.files.WorkspaceFileClient
import com.openminis.app.runtime.ubuntu.UbuntuPaths
import com.openminis.app.scheduled.ScheduledTaskPermissionTier
import com.openminis.app.tools.*
import com.openminis.app.tools.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Real model/Room/file/checkpoint workflows; transport and held tool completion are controlled. */
@RunWith(AndroidJUnit4::class)
class AgentToolIntentE2ETest {
    @Test
    fun recordOnlyAnAcceptedExecutionAndSettleAfterRoom(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        app.providerRepository.awaitConfigLoaded(); UbuntuPaths.initialize(app)
        val sessions = mutableListOf<String>()
        val original = checkNotNull(ToolRegistry.handler("agent.todo"))
        val report = JSONObject().put("device", android.os.Build.FINGERPRINT).put("scenarios", JSONArray())
        fun passed(name: String) { report.getJSONArray("scenarios").put(JSONObject().put("name", name).put("passed", true)) }
        suspend fun session(): String = app.chatRepository.createSession("scripted-model", title = "M3 intent E2E", memoryEnabled = false).id.also(sessions::add)
        suspend fun prepare(sid: String, provider: ScriptedProvider) {
            val vm = withContext(Dispatchers.Main) { chatProjection(app, sid) }
            withTimeout(15_000) { vm.sessionLoaded.first { it } }
            withContext(Dispatchers.Main) { vm.currentProvider = provider; vm.currentModel = provider.model; vm._activeEntryId.value = "intent-scripted-entry" }
        }
        try {
            val deniedSid = session()
            val denial = ScriptedProvider { call, history, _ -> if (call == 1) {
                toolCall("intent-denied-write", "file_write", JSONObject().put("path", "/workspace/denied.txt").put("content", "must not write"))
            } else {
                val receipt = history.flatMap { it.contentParts }.filterIsInstance<AgentContentPart.ToolResult>().single { it.id == "intent-denied-write" }
                assertTrue(receipt.isError); assertNull(receipt.executionIntentId)
                assertTrue(ToolCheckpointStore.drainPending(app, deniedSid).isEmpty())
                text("写入被拒绝，未启动执行体。")
            } }
            prepare(deniedSid, denial)
            val deniedRun = OffloadPermissionManager.withUnattendedSession(deniedSid, ScheduledTaskPermissionTier.READ_ONLY) {
                AgentRunner.prompt(app, deniedSid, "检查只读任务拒绝写入", wait = true, timeoutMs = 20_000)
            }
            assertTrue(deniedRun.completed)
            assertFalse(WorkspaceFileClient.info(deniedSid, "/workspace/denied.txt").optBoolean("exists"))
            passed("a real READ_ONLY model refusal has no execution intent and writes no bytes")

            val heldSid = session()
            AgentStateStore.goalSet(heldSid, "中断后保留已发生副作用的真实执行身份", app, 3)
            val goal = checkNotNull(app.botTaskRepository.sessionGoal(heldSid))
            val entered = CompletableDeferred<ToolCheckpointStore.IntentRecord>()
            val cleaned = CompletableDeferred<Unit>()
            ToolRegistry.register(object : ToolHandler {
                override val definition get() = original.definition
                override suspend fun execute(argsJson: String, sessionId: String, context: android.content.Context, toolId: String): ToolExecutionResult {
                    val intent = ToolCheckpointStore.drainPending(context, sessionId).single()
                    assertEquals("intent-held-call", intent.callId); assertEquals("todo_write", intent.toolName)
                    assertEquals(goal.id, intent.taskId); assertNotNull(intent.intentId)
                    assertEquals(AgentSessionStore.existing(sessionId)!!.activeBotRunId, intent.runId)
                    WorkspaceFileClient.writeBytes(sessionId, "/workspace/held.txt", "one actual effect\n".toByteArray())
                    entered.complete(intent)
                    try { awaitCancellation() } finally { cleaned.complete(Unit) }
                }
            })
            prepare(heldSid, ScriptedProvider { _, _, _ -> toolCall("intent-held-call", "todo_write", JSONObject().put("todos", JSONArray())) })
            val running = async { AgentRunner.prompt(app, heldSid, "写入后等待验证", wait = true, timeoutMs = 30_000) }
            val identity = withTimeout(15_000) { entered.await() }
            assertTrue(AgentRunner.cancel(app, heldSid)); withTimeout(5_000) { cleaned.await() }; running.await()
            assertTrue(AgentRunner.waitForSettle(app, heldSid, 5_000))
            val pending = ToolCheckpointStore.drainPending(app, heldSid).single()
            assertEquals(identity, pending)
            assertEquals("one actual effect\n", WorkspaceFileClient.readAll(heldSid, "/workspace/held.txt").decodeToString())
            passed("the shared executor durably records the actual call/run/task before the body and cancellation preserves its unknown result")

            ToolRegistry.register(original)
            val failedSid = session()
            val blocked = File(app.filesDir, "checkpoints/$failedSid.jsonl")
            blocked.parentFile!!.mkdirs()
            var directoryCreated = false
            val storageFailureProvider = ScriptedProvider { call, _, _ -> if (call == 1) {
                // The outgoing request was readable; fail storage at the actual tool gate.
                directoryCreated = blocked.mkdir()
                toolCall("intent-storage-failure", "file_write", JSONObject().put("path", "/workspace/not-started.txt").put("content", "must not execute"))
            } else text("must not send another request while storage is unreadable") }
            prepare(failedSid, storageFailureProvider)
            try {
                val failure = AgentRunner.prompt(app, failedSid, "确认执行意图写入失败会停止动作", wait = true, timeoutMs = 20_000)
                assertTrue(directoryCreated); assertFalse(failure.completed); assertFalse(failure.timedOut)
                assertTrue(failure.toString(), failure.responseText.orEmpty().contains("tool_checkpoint_unavailable"))
                assertEquals(1, storageFailureProvider.calls)
                val failureReceipt = app.chatRepository.loadMessages(failedSid)
                    .map { AgentSessionStore.existing(failedSid)!!.messageMapper.toLLMMessage(it) }
                    .flatMap { it.contentParts }.filterIsInstance<AgentContentPart.ToolResult>()
                    .single { it.id == "intent-storage-failure" }
                assertTrue(failureReceipt.isError); assertNull(failureReceipt.executionIntentId)
                assertTrue(failureReceipt.content.contains("checkpoint could not be persisted"))
                assertFalse(WorkspaceFileClient.info(failedSid, "/workspace/not-started.txt").optBoolean("exists"))
            } finally { assertTrue(blocked.delete()) }
            passed("a real checkpoint storage failure persists a paired Room error, executes no file body and blocks the next model request")

            val completedSid = session()
            var persistedReceipt: AgentContentPart.ToolResult? = null
            prepare(completedSid, ScriptedProvider { call, history, _ -> if (call == 1) {
                toolCall("intent-success", "file_write", JSONObject().put("path", "/workspace/completed.txt").put("content", "shared execution verified\n"))
            } else {
                persistedReceipt = history.flatMap { it.contentParts }.filterIsInstance<AgentContentPart.ToolResult>().single { it.id == "intent-success" }
                assertFalse(persistedReceipt!!.isError); assertNotNull(persistedReceipt!!.executionIntentId)
                assertTrue(ToolCheckpointStore.drainPending(app, completedSid).isEmpty())
                text("文件及精确回执已保存。")
            } })
            assertTrue(AgentRunner.prompt(app, completedSid, "写入并保存执行回执", wait = true, timeoutMs = 20_000).completed)
            assertNotNull(persistedReceipt)
            val loaded = app.chatRepository.loadMessages(completedSid).map { AgentSessionStore.existing(completedSid)!!.messageMapper.toLLMMessage(it) }
            val reloaded = loaded.flatMap { it.contentParts }.filterIsInstance<AgentContentPart.ToolResult>().single { it.id == "intent-success" }
            assertEquals(persistedReceipt!!.executionIntentId, reloaded.executionIntentId)
            assertEquals(persistedReceipt!!.executionRunId, reloaded.executionRunId)
            assertTrue(ToolCheckpointStore.drainPending(app, completedSid).isEmpty())
            val bytes = WorkspaceFileClient.readAll(completedSid, "/workspace/completed.txt")
            File(app.filesDir, "agent-tool-intent-delivery.txt").writeBytes(bytes)
            report.put("artifactSha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            passed("an exact execution nonce survives Room roundtrip and is acknowledged only after the durable tool receipt")
            val invalidRemote = ToolExecutor.execute("file_write", "{\"path\":\"/workspace/remote.txt\",\"content\":\"denied\"}", completedSid, app,
                caller = "mcp:intent-e2e", modelExecution = ToolExecutor.ModelExecution("foreign", "foreign-run", "foreign-task"))
            assertFalse(invalidRemote.success); assertTrue(invalidRemote.output.contains("permission_denied"))
            assertTrue(ToolCheckpointStore.drainPending(app, completedSid).isEmpty())
            assertFalse(WorkspaceFileClient.info(completedSid, "/workspace/remote.txt").optBoolean("exists"))
            passed("a remote caller cannot supply a local model execution identity or cause its checkpoint or write")
            report.put("passed", true)
        } catch (error: Throwable) { report.put("passed", false).put("failure", error.toString()); throw error }
        finally {
            ToolRegistry.register(original)
            sessions.forEach { AgentRunner.cancel(app, it); app.chatRepository.deleteSession(it) }
            File(app.filesDir, "agent-tool-intent-e2e.json").writeText(report.toString(2))
        }
    }
}
