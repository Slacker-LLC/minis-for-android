package com.openminis.app.ui.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.agent.AgentRunner
import com.openminis.app.agent.AgentTurnHandle
import com.openminis.app.agent.AgentTurnOutcome
import com.openminis.app.data.db.BotDelegationEntity
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.model.*
import com.openminis.app.data.repository.awaitConfigLoaded
import com.openminis.app.provider.LLMProvider
import com.openminis.app.runtime.files.WorkspaceFileClient
import com.openminis.app.runtime.ubuntu.UbuntuPaths
import com.openminis.app.tools.AgentStateStore
import com.openminis.app.tools.BotDelegationCoordinator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real request cancellation/Room/receipt workflows, with only model transport controlled. */
@RunWith(AndroidJUnit4::class)
class AgentTaskCoordinationE2ETest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
    private suspend fun prepare(sid: String, provider: LLMProvider) {
        val vm = withContext(Dispatchers.Main) { chatProjection(app, sid) }
        withTimeout(20_000) { vm.sessionLoaded.first { it } }
        withContext(Dispatchers.Main) {
            vm.currentProvider = provider; vm.currentModel = provider.model; vm._activeEntryId.value = "coordination-scripted-entry"
        }
    }

    private fun heldProvider(entered: CompletableDeferred<Unit>): LLMProvider {
        val delegate = ScriptedProvider { _, _, _ -> text("unused") }
        return object : LLMProvider {
            override val name = delegate.name
            override var model = delegate.model
            override suspend fun sendMessageClamped(messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int,
                temperature: Double?, imageParts: List<LLMMessage.ImagePart>, tools: List<AgentToolDefinition>, thinkingLevel: ThinkingLevel) =
                delegate.sendMessageClamped(messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel)
            override fun streamMessageClamped(messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int,
                temperature: Double?, imageParts: List<LLMMessage.ImagePart>, tools: List<AgentToolDefinition>, thinkingLevel: ThinkingLevel) = flow<LLMStreamChunk> {
                entered.complete(Unit); emit(LLMStreamChunk.Text("子工作尚在执行")); awaitCancellation()
            }
        }
    }

    @Test
    fun rootCreationReviewAndCascadingStop(): Unit = runBlocking {
        app.providerRepository.awaitConfigLoaded(); UbuntuPaths.initialize(app)
        val coordinator = checkNotNull(BotDelegationCoordinator.current())
        val db = com.openminis.app.data.db.AppDatabase.getInstance(app)
        val sessions = mutableListOf<String>()
        val bots = mutableListOf<String>()
        val report = JSONObject().put("device", android.os.Build.FINGERPRINT)
        val scenarios = JSONArray(); report.put("scenarios", scenarios)
        fun passed(name: String) { scenarios.put(JSONObject().put("name", name).put("passed", true)) }
        suspend fun session(title: String, botId: String, source: String? = null): String =
            app.chatRepository.createSession("scripted-model", title = title, botId = botId, source = source).id.also(sessions::add)
        try {
            val owner = app.botRepository.createBot("Task closure owner").also { bots.add(it.id) }
            val target = app.botRepository.createBot("Task closure worker").also { bots.add(it.id) }
            val legacySid = session("Root creation concurrency", owner.id)
            val roots = coroutineScope { (1..24).map { async(Dispatchers.Default) {
                app.botTaskRepository.ensureRootTask(legacySid, "same-source-run", owner.id, "同一个来源只创建一个根任务")
            } }.awaitAll() }
            assertEquals(1, roots.map { it.id }.distinct().size)
            assertEquals(1, app.botTaskRepository.observeRecent().first().count { it.originSessionId == legacySid })
            passed("24 concurrent source requests create exactly one durable root task")
            assertTrue(runCatching { app.botTaskRepository.ensureRootTask(legacySid, "same-source-run", target.id, "错误负责人") }.isFailure)
            assertEquals(roots.first(), app.botTaskRepository.get(roots.first().id))
            passed("another owner cannot acquire the same source root")

            val sid = session("Root goal stop", owner.id)
            AgentStateStore.goalSet(sid, "已有子工作必须结算后才能交付", app, 5)
            val root = app.botTaskRepository.sessionGoal(sid)!!
            val explicit = coroutineScope { (1..24).map { async(Dispatchers.Default) {
                app.botTaskRepository.ensureRootTask(sid, "explicit-run-$it", owner.id, "派工不新建根目标")
            } }.awaitAll() }
            assertTrue(explicit.all { it.id == root.id }); assertEquals(root, app.botTaskRepository.get(root.id))
            passed("parallel delegation requests all reuse the explicit goal without changing its budget or identity")

            suspend fun worker(sourceSid: String, rootId: String): Triple<String, AgentTurnHandle, BotDelegationEntity> {
                val workerSid = session("Active worker stop evidence", target.id, ChatSessionEntity.SOURCE_BOT_DELEGATION)
                val entered = CompletableDeferred<Unit>()
                val held = heldProvider(entered)
                prepare(workerSid, held)
                val request = withContext(Dispatchers.Main) { AgentRunner.submit(chatProjection(app, workerSid).agentSession, "执行直到被取消") }
                withTimeout(15_000) { entered.await() }
                // Restore an actual RUNNING journal association; the request itself is live, not a fabricated success.
                val now = System.currentTimeMillis()
                val journal = BotDelegationEntity(UUID.randomUUID().toString(), owner.id, sourceSid, "journal-run",
                    sourceToolId = UUID.randomUUID().toString(), rootTaskId = rootId, targetBotId = target.id,
                    targetSessionId = workerSid, prompt = "停止关联子工作", status = BotDelegationEntity.STATUS_RUNNING,
                    createdAt = now, updatedAt = now, startedAt = now)
                db.botDelegationDao().insert(journal)
                return Triple(workerSid, request, journal)
            }
            val live = worker(sid, root.id)
            val queued = app.botDelegationRepository.enqueue(owner.id, sid, "queued-run", "queued-tool", root.id, target.id, "不能再启动的工作")
            AgentStateStore.goalSetActive(sid, false, app)
            assertFalse(live.second.result.isCompleted)
            assertEquals("RUNNING", app.botDelegationRepository.get(live.third.id)!!.status)
            passed("pausing the owner preserves already running member work")
            AgentStateStore.goalSetActive(sid, true, app)
            WorkspaceFileClient.writeBytes(sid, "/workspace/review.txt", "real file but unfinished member work\n".toByteArray())
            val reviewer = ScriptedProvider { call, messages, _ ->
                val identity = messages.flatMap { it.contentParts }.filterIsInstance<AgentContentPart.ToolResult>()
                    .firstOrNull { it.id == "root-get" }?.content?.let(::JSONObject)
                fun status(value: String) = JSONObject().put("status", value).put("task_id", identity!!.getString("task_id"))
                    .put("revision", identity.getInt("revision")).put("stop_generation", identity.getInt("stop_generation"))
                    .put("progress", "子工作尚未结束，保留等待状态。")
                when (call) {
                    1 -> toolCall("root-get", "get_goal")
                    2 -> toolCall("root-complete", "update_goal", status("completed").put("evidence", JSONArray()
                        .put(JSONObject().put("type", "file").put("path", "/workspace/review.txt"))))
                    3 -> toolCall("root-wait", "update_goal", status("waiting"))
                    else -> text("文件真实存在，但子工作没有结束，不能完成根目标。")
                }
            }
            prepare(sid, reviewer)
            assertTrue(AgentRunner.prompt(app, sid, "核验根目标", wait = true, timeoutMs = 20_000).completed)
            val rejected = reviewer.requests.last().flatMap { it.contentParts }.filterIsInstance<AgentContentPart.ToolResult>()
                .single { it.id == "root-complete" }
            assertTrue(rejected.isError); assertTrue(rejected.content.contains("unsettled_delegations_require_review"))
            assertNull(app.botTaskRepository.sessionGoal(sid)!!.completionEvidence)
            passed("a real file cannot complete the root while queued/running member work remains")

            val ownerEntered = CompletableDeferred<Unit>()
            prepare(sid, heldProvider(ownerEntered))
            val ownerRequest = withContext(Dispatchers.Main) { AgentRunner.submit(chatProjection(app, sid).agentSession, "等待取消负责人") }
            withTimeout(15_000) { ownerEntered.await() }
            AgentStateStore.goalSet(sid, "", app)
            assertEquals(AgentTurnOutcome.Cancelled, withTimeout(15_000) { ownerRequest.result.await() })
            assertTrue(AgentRunner.awaitStreamExit(app, sid))
            assertNull(app.botTaskRepository.sessionGoal(sid)); assertEquals("CANCELLED", app.botTaskRepository.get(root.id)!!.status)
            assertEquals(AgentTurnOutcome.Cancelled, withTimeout(15_000) { live.second.result.await() })
            assertTrue(AgentRunner.awaitStreamExit(app, live.first))
            assertEquals("CANCELLED", app.botDelegationRepository.get(live.third.id)!!.status)
            assertEquals(1, app.botDelegationRepository.get(live.third.id)!!.outcomeUnknown)
            assertEquals("CANCELLED", app.botDelegationRepository.get(queued.id)!!.status)
            assertEquals(0, app.botDelegationRepository.get(queued.id)!!.outcomeUnknown)
            assertNotNull(app.botDelegationRepository.get(live.third.id)!!.deliveredAt)
            assertNotNull(app.botDelegationRepository.get(queued.id)!!.deliveredAt)
            passed("clearing a goal stops the actual owner request, live member and queued member, durably records unknown in-flight effects and delivers receipts")
            val messages = app.chatRepository.dao.loadMessages(sid).map { it.id }
            val stopped = app.botTaskRepository.get(root.id)!!
            repeat(3) { assertTrue(coordinator.cancelRootTask(root.id)) }
            assertEquals(stopped, app.botTaskRepository.get(root.id))
            assertEquals(messages, app.chatRepository.dao.loadMessages(sid).map { it.id })
            assertEquals(4, reviewer.calls)
            assertEquals(0, app.botDelegationRepository.countNonTerminalForRootTask(root.id))
            passed("repeated cancellation preserves task generation and receipts exactly once without waking a stopped owner")

            val deletedSid = session("Deleted source cancels work", owner.id)
            AgentStateStore.goalSet(deletedSid, "删除来源时一并停止子工作", app, 3)
            val deletedRoot = app.botTaskRepository.sessionGoal(deletedSid)!!
            val deletedLive = worker(deletedSid, deletedRoot.id)
            app.chatRepository.deleteSession(deletedSid)
            assertNull(app.chatRepository.getSession(deletedSid))
            assertEquals(AgentTurnOutcome.Cancelled, withTimeout(15_000) { deletedLive.second.result.await() })
            assertTrue(AgentRunner.awaitStreamExit(app, deletedLive.first))
            assertEquals("CANCELLED", app.botDelegationRepository.get(deletedLive.third.id)!!.status)
            assertNotNull(app.botDelegationRepository.get(deletedLive.third.id)!!.deliveredAt)
            assertEquals("CANCELLED", app.botTaskRepository.get(deletedRoot.id)!!.status)
            passed("deleting the source stops its live member and consumes its orphaned receipt")
            val ownerSid = session("Actual owner in another session", owner.id)
            val actualOwnerEntered = CompletableDeferred<Unit>()
            prepare(ownerSid, heldProvider(actualOwnerEntered))
            val actualOwnerRequest = withContext(Dispatchers.Main) { AgentRunner.submit(chatProjection(app, ownerSid).agentSession,
                "接续旧来源的同一任务", taskId = roots.first().id) }
            withTimeout(15_000) { actualOwnerEntered.await() }
            assertEquals(ownerSid, app.botTaskRepository.get(roots.first().id)!!.currentOwnerSessionId)
            assertTrue(coordinator.cancelRootTask(roots.first().id))
            assertEquals(AgentTurnOutcome.Cancelled, withTimeout(15_000) { actualOwnerRequest.result.await() })
            assertTrue(AgentRunner.awaitStreamExit(app, ownerSid))
            val stoppedProvider = ScriptedProvider { _, _, _ -> text("must never run") }
            prepare(ownerSid, stoppedProvider)
            assertFalse(AgentRunner.prompt(app, ownerSid, "不得自动接续已停止的任务", wait = true, timeoutMs = 20_000,
                taskId = roots.first().id).completed)
            assertEquals(0, stoppedProvider.calls)
            passed("root cancellation stops its actual owner in another session and rejects stopped-task continuation before any model execution")

            val orphan = app.botTaskRepository.ensureRootTask(legacySid, "no-member-run", owner.id, "没有委派记录的来源任务")
            app.chatRepository.deleteSession(legacySid)
            assertEquals("CANCELLED", app.botTaskRepository.get(orphan.id)!!.status)
            passed("source deletion also stops a root with no member journal")
            val modelSid = session("Model clear preserves its control receipt", owner.id)
            AgentStateStore.goalSet(modelSid, "在本回合中清除目标但保存真实回执", app, 3)
            val modelTask = app.botTaskRepository.sessionGoal(modelSid)!!
            val modelClear = ScriptedProvider { call, _, _ -> if (call == 1)
                toolCall("model-clear", "update_goal", JSONObject().put("goal", ""))
                else text("控制操作已完成并保存回执。") }
            prepare(modelSid, modelClear)
            assertTrue(AgentRunner.prompt(app, modelSid, "清除本回合目标并确认结果", wait = true, timeoutMs = 20_000).completed)
            assertEquals(2, modelClear.calls)
            assertNull(app.botTaskRepository.sessionGoal(modelSid))
            assertEquals("CANCELLED", app.botTaskRepository.get(modelTask.id)!!.status)
            val clearReceipt = modelClear.requests.last().flatMap { it.contentParts }.filterIsInstance<AgentContentPart.ToolResult>()
                .single { it.id == "model-clear" }
            assertFalse(clearReceipt.isError); assertNotNull(clearReceipt.executionIntentId)
            assertTrue(com.openminis.app.tools.ToolCheckpointStore.drainPending(app, modelSid).isEmpty())
            passed("a model clearing its own goal persists the real control receipt before settling, leaving no fake unknown outcome")
            val addedSid = session("Goal created during a live request", owner.id)
            WorkspaceFileClient.writeBytes(addedSid, "/workspace/live-goal.txt", "live goal delivery\n".toByteArray())
            val entered = CompletableDeferred<Unit>()
            val goalSaved = CompletableDeferred<Unit>()
            val liveGoal = ScriptedProvider { call, history, _ ->
                val identity = history.flatMap { it.contentParts }.filterIsInstance<AgentContentPart.ToolResult>()
                    .firstOrNull { it.id == "live-get" }?.content?.let(::JSONObject)
                when (call) {
                    1 -> toolCall("live-get", "get_goal")
                    2 -> toolCall("live-complete", "update_goal", JSONObject().put("status", "completed")
                        .put("task_id", identity!!.getString("task_id")).put("revision", identity.getInt("revision"))
                        .put("stop_generation", identity.getInt("stop_generation")).put("progress", "已核验真实文件。")
                        .put("evidence", JSONArray().put(JSONObject().put("type", "file").put("path", "/workspace/live-goal.txt"))))
                    else -> text("本回合接收并完成了新目标。")
                }
            }
            val gated = object : LLMProvider {
                override val name = liveGoal.name
                override var model = liveGoal.model
                override suspend fun sendMessageClamped(messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int,
                    temperature: Double?, imageParts: List<LLMMessage.ImagePart>, tools: List<AgentToolDefinition>, thinkingLevel: ThinkingLevel) =
                    liveGoal.sendMessageClamped(messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel)
                override fun streamMessageClamped(messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int,
                    temperature: Double?, imageParts: List<LLMMessage.ImagePart>, tools: List<AgentToolDefinition>, thinkingLevel: ThinkingLevel) = flow {
                    if (liveGoal.calls == 0) { entered.complete(Unit); goalSaved.await() }
                    liveGoal.streamMessageClamped(messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel)
                        .collect { emit(it) }
                }
            }
            prepare(addedSid, gated)
            val addedRequest = withContext(Dispatchers.Main) { AgentRunner.submit(chatProjection(app, addedSid).agentSession, "先读取现场") }
            withTimeout(15_000) { entered.await() }
            AgentStateStore.goalSet(addedSid, "执行中加入目标并核验文件交付", app, 3)
            goalSaved.complete(Unit)
            assertEquals(AgentTurnOutcome.Completed, withTimeout(20_000) { addedRequest.result.await() })
            assertEquals("COMPLETED", app.botTaskRepository.sessionGoal(addedSid)!!.status)
            assertNotNull(app.botTaskRepository.sessionGoal(addedSid)!!.completionEvidence)
            passed("a goal saved during a live unscoped request becomes that request's verified task without a second execution owner")
            report.put("passed", true)
        } catch (error: Throwable) { report.put("passed", false).put("failure", error.toString()); throw error }
        finally {
            sessions.forEach { AgentRunner.cancel(app, it); app.chatRepository.deleteSession(it) }
            bots.forEach { app.botRepository.deleteBot(it) }
            File(app.filesDir, "agent-task-coordination-e2e.json").writeText(report.toString(2))
        }
    }
}
