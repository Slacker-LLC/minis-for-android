package com.openminis.app.ui.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.agent.AgentRunner
import com.openminis.app.data.db.AgentTaskEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.repository.awaitConfigLoaded
import com.openminis.app.runtime.files.WorkspaceFileClient
import com.openminis.app.runtime.ubuntu.UbuntuPaths
import com.openminis.app.tools.AgentStateStore
import com.openminis.app.tools.GoalTools
import com.openminis.app.tools.ToolCheckpointStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Actual core → offered tools → Room/workspace → evidence → terminal task workflows. */
@RunWith(AndroidJUnit4::class)
class AgentTaskLoopWorkflowE2ETest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
    private fun identity(messages: List<com.openminis.app.data.model.LLMMessage>, call: String): JSONObject =
        JSONObject(messages.flatMap { it.contentParts }.filterIsInstance<AgentContentPart.ToolResult>().single { it.id == call }.content)
    private fun settle(identity: JSONObject, status: String, refs: JSONArray? = null) = JSONObject()
        .put("status", status).put("task_id", identity.getString("task_id")).put("revision", identity.getInt("revision"))
        .put("stop_generation", identity.getInt("stop_generation")).put("progress", "已核验实际结果；需要保留源数据。")
        .also { if (refs != null) it.put("evidence", refs) }
    private fun verification(identity: JSONObject, execution: String, run: String) = JSONObject()
        .put("task_id", identity.getString("task_id")).put("revision", identity.getInt("revision"))
        .put("stop_generation", identity.getInt("stop_generation"))
        .put("outcome", JSONObject().put("execution_id", execution).put("run_id", run))
    private suspend fun prepare(sid: String, provider: com.openminis.app.provider.LLMProvider) {
        val vm = withContext(Dispatchers.Main) { chatProjection(app, sid) }
        withTimeout(20_000) { vm.sessionLoaded.first { it } }
        withContext(Dispatchers.Main) {
            vm.currentProvider = provider; vm.currentModel = provider.model; vm._activeEntryId.value = "task-loop-scripted-entry"
        }
    }

    @Test
    fun executeVerifyStopAndRecover(): Unit = runBlocking {
        app.providerRepository.awaitConfigLoaded(); UbuntuPaths.initialize(app)
        val ids = mutableListOf<String>(); val report = JSONObject().put("scenarios", JSONArray())
        suspend fun session(title: String): String = app.chatRepository.createSession("scripted-model", title = title).id.also { ids += it }
        fun passed(name: String) { report.getJSONArray("scenarios").put(JSONObject().put("name", name).put("passed", true)) }
        try {
            val sid = session("M2 bounded goal delivery")
            val provider = ScriptedProvider { call, messages, _ -> when (call) {
                1 -> toolCall("loop-create", "create_goal", JSONObject().put("goal", "交付任务文件").put("acceptance_criteria", "文件内容为 M2 verified delivery，保留源数据").put("max_goal_rounds", 3))
                2 -> toolCall("loop-write", "file_write", JSONObject().put("path", "/workspace/delivery.txt").put("content", "M2 verified delivery\n"))
                3 -> text("已保存文件，还需提交实际证据验收。")
                4 -> toolCall("loop-get", "get_goal")
                5 -> toolCall("loop-complete", "update_goal", settle(identity(messages, "loop-get"), "completed", JSONArray()
                    .put(JSONObject().put("type", "file").put("path", "/workspace/delivery.txt"))
                    .put(JSONObject().put("type", "tool_result").put("call_id", "loop-write"))))
                else -> text("目标已通过实际文件及工具回执核验。")
            } }
            prepare(sid, provider)
            assertTrue(AgentRunner.prompt(app, sid, "交付并验收文件", wait = true, timeoutMs = 30_000).completed)
            val task = app.agentTaskRepository.sessionGoal(sid)!!
            assertEquals("COMPLETED", task.status); assertEquals(1, task.autoRunsUsed); assertNotNull(task.completedAt)
            val proof = JSONObject(task.completionEvidence!!)
            assertEquals(task.id, proof.getString("task_id")); assertEquals(2, proof.getJSONArray("references").length())
            assertTrue(proof.getString("run_id").startsWith("run-"))
            val checkpoint = com.openminis.app.agent.RunCheckpointStore.list(app).single { it.runId == proof.getString("run_id") }
            assertTrue(checkpoint.terminal)
            val requestMessage = JSONObject(checkpoint.entryPayload!!).getString("request_message_id")
            val requested = com.openminis.app.data.db.AppDatabase.getInstance(app).chatDao().getMessageById(requestMessage)!!
            assertEquals(sid, requested.sessionId); assertEquals("user", requested.role)
            assertTrue(checkpoint.events.any { event -> JSONObject(event).optString("type") == "task_bound" && JSONObject(event).optString("task_id") == task.id })
            val actual = WorkspaceFileClient.fingerprint(sid, "/workspace/delivery.txt")
            assertEquals(actual.getString("sha256"), proof.getJSONArray("references").getJSONObject(0).getString("sha256"))
            assertTrue(provider.requests[3].any { it.content.contains("继续当前目标") })
            assertEquals(6, provider.calls)
            assertFalse(GoalTools.execute("update_goal", "{\"goal\":\"不得改写已验收任务\"}", sid, app).success)
            File(app.filesDir, "agent-task-delivery-e2e.txt").writeBytes(WorkspaceFileClient.readAll(sid, "/workspace/delivery.txt"))
            report.put("delivery", proof)
            withContext(Dispatchers.Main) { AgentRunner.forget(sid) }
            assertEquals(task.completionEvidence, app.agentTaskRepository.sessionGoal(sid)!!.completionEvidence)
            assertTrue(GoalTools.execute("update_goal", "{\"goal\":\"\"}", sid, app).success)
            assertNull(app.agentTaskRepository.sessionGoal(sid))
            assertEquals("COMPLETED", app.agentTaskRepository.get(task.id)!!.status)
            assertEquals(task.completionEvidence, app.agentTaskRepository.get(task.id)!!.completionEvidence)
            passed("same core continues a real task, consumes durable budget, verifies file/receipt and preserves delivery proof on reopen")

            val rejectSid = session("M2 refusal workflow")
            val reject = ScriptedProvider { call, messages, _ -> when (call) {
                1 -> toolCall("reject-create", "create_goal", JSONObject().put("goal", "必须核验真实成果"))
                2 -> toolCall("reject-write", "file_write", JSONObject().put("path", "/workspace/result.txt").put("content", "actual"))
                in 3..10 -> {
                    val args = settle(identity(messages, "reject-create"), "completed", JSONArray().put(JSONObject().put("type", "file").put("path", "/workspace/result.txt")))
                    when (call) {
                        3 -> args.put("evidence", JSONArray())
                        4 -> args.getJSONArray("evidence").getJSONObject(0).put("sha256", "0".repeat(64))
                        5 -> args.getJSONArray("evidence").getJSONObject(0).put("path", "/workspace/missing.txt")
                        6 -> args.put("evidence", JSONArray().put(JSONObject().put("type", "tool_result").put("call_id", "never-executed")))
                        7 -> args.put("revision", args.getInt("revision") + 1)
                        8 -> args.put("stop_generation", args.getInt("stop_generation") + 1)
                        9 -> args.put("task_id", task.id)
                        10 -> args.put("evidence", JSONArray().put(JSONObject().put("type", "tool_result").put("call_id", "reject-create")))
                    }
                    toolCall("reject-$call", "update_goal", args)
                }
                11 -> toolCall("reject-wait", "update_goal", settle(identity(messages, "reject-create"), "needs_user"))
                else -> text("验收提交均被正确拒绝，等待用户决定。")
            } }
            prepare(rejectSid, reject)
            assertTrue(AgentRunner.prompt(app, rejectSid, "验证真实证据和身份要求", wait = true, timeoutMs = 35_000).completed)
            val results = reject.requests.last().flatMap { it.contentParts }.filterIsInstance<AgentContentPart.ToolResult>()
            for (call in 3..10) assertTrue("reject-$call must fail", results.single { it.id == "reject-$call" }.isError)
            val waiting = app.agentTaskRepository.sessionGoal(rejectSid)!!
            assertEquals("NEEDS_USER", waiting.status); assertNull(waiting.completionEvidence); assertEquals(0, waiting.autoRunsUsed)
            passed("eight false evidence, hash, missing file, forged receipt, stale revision/generation and cross-session completion submissions are rejected")

            val budgetSid = session("M2 durable bounded continuation")
            AgentStateStore.goalSet(budgetSid, "尚未完成不能无限执行", app, 2)
            val bounded = ScriptedProvider { _, _, _ -> text("仍有工作尚未验收。") }
            prepare(budgetSid, bounded)
            assertFalse(AgentRunner.prompt(app, budgetSid, "继续执行", wait = true, timeoutMs = 25_000).completed)
            val exhausted = app.agentTaskRepository.sessionGoal(budgetSid)!!
            assertEquals(3, bounded.calls); assertEquals(2, exhausted.autoRunsUsed); assertEquals("BUDGET_EXHAUSTED", exhausted.status)
            AgentStateStore.goalSet(budgetSid, exhausted.goal, app, 4)
            AgentStateStore.goalSetActive(budgetSid, true, app)
            val resumed = app.agentTaskRepository.sessionGoal(budgetSid)!!
            assertEquals(2, resumed.autoRunsUsed); assertEquals("ACTIVE", resumed.status)
            assertEquals(exhausted.stopGeneration + 1, resumed.stopGeneration)
            val chatOnly = ScriptedProvider { _, _, _ -> text("本次只回答进度，不启动工作。") }
            prepare(budgetSid, chatOnly)
            assertTrue(AgentRunner.prompt(app, budgetSid, "只聊聊进度", chatOnly = true, wait = true, timeoutMs = 20_000).completed)
            assertEquals(1, chatOnly.calls)
            assertEquals("ACTIVE", app.agentTaskRepository.sessionGoal(budgetSid)!!.status)
            assertEquals(2, app.agentTaskRepository.sessionGoal(budgetSid)!!.autoRunsUsed)
            assertTrue(chatOnly.offeredTools.isEmpty())
            assertTrue(chatOnly.systemPrompts.single()!!.contains("本次仅对话"))
            passed("chat-only requests preserve an active task without consuming continuation budget or enabling tools")
            AgentStateStore.goalSetActive(budgetSid, false, app)
            val answer = ScriptedProvider { _, _, _ -> text("只报告当前进度，目标继续暂停。") }
            prepare(budgetSid, answer)
            assertTrue(AgentRunner.prompt(app, budgetSid, "进度如何", wait = true, timeoutMs = 20_000).completed)
            assertEquals(1, answer.calls); assertEquals(2, app.agentTaskRepository.sessionGoal(budgetSid)!!.autoRunsUsed)
            passed("continuations stop at persisted budget; explicit extension preserves spending; paused questions never resume the task")

            AgentStateStore.goalSetActive(budgetSid, true, app)
            val renewed = app.agentTaskRepository.sessionGoal(budgetSid)!!
            assertEquals("ACTIVE", renewed.status)
            assertFalse(app.agentTaskRepository.exhaustBudget(renewed.id, renewed.revision, renewed.stopGeneration - 1))
            assertFalse(app.agentTaskRepository.exhaustBudget(renewed.id, renewed.revision - 1, renewed.stopGeneration))
            assertFalse(app.agentTaskRepository.updateStateIfWakeable(renewed.id, "NEEDS_USER", "REVIEWING", budgetSid,
                renewed.revision, renewed.stopGeneration - 1))
            assertEquals(renewed, app.agentTaskRepository.get(renewed.id))
            passed("late budget/recipient decisions cannot overwrite a renewed task revision or stop generation")

            val foreignSid = session("M2 foreign task override refusal")
            val foreign = ScriptedProvider { _, _, _ -> text("must not run") }
            prepare(foreignSid, foreign)
            val protected = app.agentTaskRepository.sessionGoal(budgetSid)!!
            assertFalse(AgentRunner.prompt(app, foreignSid, "不得领取他人会话的普通任务", wait = true, timeoutMs = 20_000,
                taskId = protected.id).completed)
            assertEquals(0, foreign.calls)
            assertEquals(protected, app.agentTaskRepository.get(protected.id))
            assertTrue(AgentRunner.awaitStreamExit(app, foreignSid))
            passed("request task identity is bound to its actual job/run/message and rejects a foreign session before any provider/tool execution")

            val reuseSid = session("M2 reused call identity")
            val original = ScriptedProvider { call, _, _ -> if (call == 1)
                toolCall("reusable-call", "file_write", JSONObject().put("path", "/workspace/reused.txt").put("content", "original"))
                else text("写入已返回实际回执。") }
            prepare(reuseSid, original)
            assertTrue(AgentRunner.prompt(app, reuseSid, "执行原始写入", wait = true, timeoutMs = 20_000).completed)
            val originalReceipt = withContext(Dispatchers.Main) { chatProjection(app, reuseSid) }.agentHistory
                .flatMap { it.contentParts }.filterIsInstance<AgentContentPart.ToolResult>().single { it.id == "reusable-call" }
            assertNotNull(originalReceipt.executionIntentId); assertNotNull(originalReceipt.executionRunId)
            assertTrue(ToolCheckpointStore.drainPending(app, reuseSid).isEmpty())
            val newId = checkNotNull(ToolCheckpointStore.recordIntent(app, reuseSid, "reusable-call", "linux.file.write",
                JSONObject().put("path", "/workspace/reused.txt").put("content", "different").toString(), "new-interrupted-run"))
            assertNotEquals(originalReceipt.executionIntentId, newId)
            val reuseVm = withContext(Dispatchers.Main) { chatProjection(app, reuseSid).also {
                it.sessionLoaded.value = false; it.reloadSessionFromDb()
            } }
            withTimeout(20_000) { reuseVm.sessionLoaded.first { it } }
            val inspectReuse = ScriptedProvider { _, _, _ -> text("新操作结果未知，必须核验。") }
            prepare(reuseSid, inspectReuse)
            assertTrue(AgentRunner.prompt(app, reuseSid, "只核对现场，不重复写入", wait = true, timeoutMs = 20_000).completed)
            assertTrue(inspectReuse.requests.last().any { it.content.contains("[tool outcome unknown]") && it.content.contains(newId) })
            assertEquals(newId, ToolCheckpointStore.drainPending(app, reuseSid).single().intentId)
            assertEquals("original", WorkspaceFileClient.readAll(reuseSid, "/workspace/reused.txt").decodeToString())
            passed("reloaded old receipt cannot acknowledge a new interrupted execution that reuses its call ID, and recovery does not replay it")

            AgentStateStore.goalSet(reuseSid, "重复调用 ID 必须逐次核验", app, 3)
            ToolCheckpointStore.recordIntent(app, reuseSid, "reusable-call", "linux.file.write",
                JSONObject().put("path", "/workspace/reused.txt").put("content", "original").toString(), "another-interrupted-run")
            val ambiguous = ScriptedProvider { call, messages, _ -> when (call) {
                1 -> toolCall("ambiguous-goal", "get_goal")
                2 -> toolCall("ambiguous-complete", "update_goal", settle(identity(messages, "ambiguous-goal"), "completed", JSONArray()
                    .put(JSONObject().put("type", "file").put("path", "/workspace/reused.txt")))
                    .put("resolved_call_ids", JSONArray().put("reusable-call")))
                3 -> toolCall("ambiguous-stop", "update_goal", settle(identity(messages, "ambiguous-goal"), "needs_user"))
                else -> text("无法区分两个未知执行，不能批量确认。")
            } }
            prepare(reuseSid, ambiguous)
            assertTrue(AgentRunner.prompt(app, reuseSid, "尝试核验但不得合并未知执行", wait = true, timeoutMs = 20_000).completed)
            val refusal = ambiguous.requests.last().flatMap { it.contentParts }.filterIsInstance<AgentContentPart.ToolResult>()
                .single { it.id == "ambiguous-complete" }
            assertTrue(refusal.isError); assertTrue(refusal.content.contains("unsettled_tool_outcome_requires_verification"))
            assertEquals(2, ToolCheckpointStore.drainPending(app, reuseSid).size)
            assertNull(app.agentTaskRepository.sessionGoal(reuseSid)!!.completionEvidence)
            passed("two unknown executions sharing a call ID cannot be merged into a single acknowledgement or false completion")

            val unknownSid = session("M2 uncertain side effect")
            AgentStateStore.goalSet(unknownSid, "先核验未知写入，不能重放", app, 3)
            WorkspaceFileClient.writeBytes(unknownSid, "/workspace/unknown.txt", "original".toByteArray())
            val unknownTask = checkNotNull(app.agentTaskRepository.sessionGoal(unknownSid))
            val unknownExecution = checkNotNull(ToolCheckpointStore.recordIntent(app, unknownSid, "unknown-write", "linux.file.write",
                JSONObject().put("path", "/workspace/unknown.txt").put("content", "wrong").toString(),
                "unknown-run", unknownTask.id, unknownTask.revision, unknownTask.stopGeneration))
            val uncertain = ScriptedProvider { _, _, _ -> text("需要核验未知写入。") }
            prepare(unknownSid, uncertain)
            assertFalse(AgentRunner.prompt(app, unknownSid, "报告现场", wait = true, timeoutMs = 20_000).completed)
            assertEquals("WAITING", app.agentTaskRepository.sessionGoal(unknownSid)!!.status)
            assertEquals(1, uncertain.calls); assertEquals("original", WorkspaceFileClient.readAll(unknownSid, "/workspace/unknown.txt").decodeToString())
            assertEquals(listOf("unknown-write"), ToolCheckpointStore.drainPending(app, unknownSid).map { it.callId })
            passed("unknown side effects stop automatic continuation without replay or false success")
            val rejectUnknown = ScriptedProvider { call, messages, _ -> when (call) {
                1 -> toolCall("unknown-get", "get_goal")
                2 -> toolCall("unknown-resolve", "verify_outcome", verification(identity(messages, "unknown-get"), unknownExecution, "unknown-run"))
                3 -> toolCall("unknown-wait", "update_goal", settle(identity(messages, "unknown-get"), "needs_user"))
                else -> text("实际文件内容不符合未知写入意图，不能声称成功。")
            } }
            prepare(unknownSid, rejectUnknown)
            assertTrue(AgentRunner.prompt(app, unknownSid, "核验未知写入并明确结算", wait = true, timeoutMs = 20_000).completed)
            val mismatch = rejectUnknown.requests.last().flatMap { it.contentParts }.filterIsInstance<AgentContentPart.ToolResult>()
                .single { it.id == "unknown-resolve" }
            assertTrue(mismatch.isError); assertTrue(mismatch.content.contains("unknown_write_does_not_match_verified_file"))
            assertNull(app.agentTaskRepository.sessionGoal(unknownSid)!!.completionEvidence)
            assertEquals(listOf("unknown-write"), ToolCheckpointStore.drainPending(app, unknownSid).map { it.callId })
            passed("reconciliation refuses a real file whose hash differs from the complete unknown write intent, retaining the checkpoint")

            val pauseSid = session("M2 pause actual active goal run")
            AgentStateStore.goalSet(pauseSid, "执行中的目标必须立即服从暂停", app, 3)
            val pauseTask = app.agentTaskRepository.sessionGoal(pauseSid)!!
            val entered = CompletableDeferred<Unit>()
            val delegate = ScriptedProvider { _, _, _ -> text("unused") }
            val held = object : com.openminis.app.provider.LLMProvider {
                override val name = delegate.name
                override var model = delegate.model
                override suspend fun sendMessageClamped(
                    messages: List<com.openminis.app.data.model.LLMMessage>, systemPrompt: String?, maxTokens: Int,
                    temperature: Double?, imageParts: List<com.openminis.app.data.model.LLMMessage.ImagePart>,
                    tools: List<com.openminis.app.data.model.AgentToolDefinition>, thinkingLevel: com.openminis.app.data.model.ThinkingLevel,
                ) = delegate.sendMessageClamped(messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel)
                override fun streamMessageClamped(
                    messages: List<com.openminis.app.data.model.LLMMessage>, systemPrompt: String?, maxTokens: Int,
                    temperature: Double?, imageParts: List<com.openminis.app.data.model.LLMMessage.ImagePart>,
                    tools: List<com.openminis.app.data.model.AgentToolDefinition>, thinkingLevel: com.openminis.app.data.model.ThinkingLevel,
                ) = kotlinx.coroutines.flow.flow<com.openminis.app.data.model.LLMStreamChunk> {
                    entered.complete(Unit)
                    emit(com.openminis.app.data.model.LLMStreamChunk.Text("执行尚未结束"))
                    awaitCancellation()
                }
            }
            prepare(pauseSid, held)
            val core = withContext(Dispatchers.Main) { chatProjection(app, pauseSid).agentSession }
            val request = withContext(Dispatchers.Main) { AgentRunner.submit(core, "执行直到被暂停") }
            withTimeout(15_000) { entered.await() }
            AgentStateStore.goalSetActive(pauseSid, false, app)
            assertEquals(com.openminis.app.agent.AgentTurnOutcome.Cancelled, withTimeout(15_000) { request.result.await() })
            assertTrue(AgentRunner.awaitStreamExit(app, pauseSid))
            val pausedTask = app.agentTaskRepository.sessionGoal(pauseSid)!!
            assertEquals("PAUSED", pausedTask.status); assertEquals(0, pausedTask.autoRunsUsed)
            assertEquals(pauseTask.stopGeneration + 1, pausedTask.stopGeneration)
            assertFalse(GoalTools.execute("update_goal", settle(JSONObject().put("task_id", pauseTask.id)
                .put("revision", pauseTask.revision).put("stop_generation", pauseTask.stopGeneration), "completed",
                JSONArray().put(JSONObject().put("type", "file").put("path", "/workspace/not-delivered.txt"))).toString(), pauseSid, app).success)
            assertNull(app.agentTaskRepository.sessionGoal(pauseSid)!!.completionEvidence)
            passed("pausing the actual in-flight owner request cancels its job, waits for cleanup and rejects a late completion")

            val largeSid = session("M2 streamed large artifact verification")
            val fixture = UbuntuPaths.resolveSessionHostPath(largeSid, "/workspace/large.bin", app)!!
            fixture.parentFile!!.mkdirs()
            fixture.outputStream().use { stream -> val chunk = ByteArray(1024 * 1024) { 0x5a }; repeat(60) { stream.write(chunk) } }
            AgentStateStore.goalSet(largeSid, "核验已存在的大文件", app, 1)
            val largeTask = app.agentTaskRepository.sessionGoal(largeSid)!!
            val large = ScriptedProvider { call, _, _ -> if (call == 1) toolCall("large-complete", "update_goal", settle(JSONObject()
                .put("task_id", largeTask.id).put("revision", largeTask.revision).put("stop_generation", largeTask.stopGeneration), "completed",
                JSONArray().put(JSONObject().put("type", "file").put("path", "/workspace/large.bin")))) else text("大文件核验已完成。") }
            prepare(largeSid, large)
            assertTrue(AgentRunner.prompt(app, largeSid, "核验大文件并交付", wait = true, timeoutMs = 25_000).completed)
            val largeProof = JSONObject(app.agentTaskRepository.sessionGoal(largeSid)!!.completionEvidence!!)
            assertEquals(60L * 1024 * 1024, largeProof.getJSONArray("references").getJSONObject(0).getLong("size"))
            passed("60 MiB artifact evidence is streamed through secure directory handles without the whole-file read limit")

            report.put("passed", true)
        } catch (error: Throwable) { report.put("passed", false).put("failure", error.toString()); throw error }
        finally { ids.forEach { app.chatRepository.deleteSession(it) }; File(app.filesDir, "agent-task-loop-e2e.json").writeText(report.toString(2)) }
    }

    @Test
    fun prepareActiveProcessDeath(): Unit = runBlocking {
        app.providerRepository.awaitConfigLoaded(); UbuntuPaths.initialize(app)
        val sid = app.chatRepository.createSession("scripted-model", title = "M2 recover active task with verified file").id
        AgentStateStore.goalSet(sid, "进程恢复后先核验已有文件，禁止重写，再交付", app, 7,
            "delivery-after-death.txt 为 M2 recovered delivery，保持哈希，未知写入必须核验")
        AgentStateStore.planSet(sid, "plan", "先读回 → 对照意图 → 核验结算 → 交付", app)
        val task = app.agentTaskRepository.sessionGoal(sid)!!
        repeat(2) { assertTrue(app.agentTaskRepository.recordAutoRun(task.id, 7)) }
        val provider = ScriptedProvider { call, _, _ -> when (call) {
            1 -> toolCall("before-death-write", "file_write", JSONObject().put("path", "/workspace/delivery-after-death.txt")
                .put("content", "M2 recovered delivery\n"))
            2 -> toolCall("before-death-pause", "update_goal", settle(JSONObject().put("task_id", task.id)
                .put("revision", task.revision).put("stop_generation", task.stopGeneration), "paused"))
            else -> text("工作现场已保存。")
        } }
        prepare(sid, provider)
        assertTrue(AgentRunner.prompt(app, sid, "保存真实工作文件", wait = true, timeoutMs = 20_000).completed)
        AgentStateStore.goalSetActive(sid, true, app)
        val resumed = checkNotNull(app.agentTaskRepository.sessionGoal(sid))
        ToolCheckpointStore.recordIntent(app, sid, "death-unknown-write", "linux.file.write", JSONObject()
            .put("path", "/workspace/delivery-after-death.txt").put("content", "M2 recovered delivery\n").toString(),
            "death-original-run", resumed.id, resumed.revision, resumed.stopGeneration)
        val report = JSONObject().put("sessionId", sid).put("pid", android.os.Process.myPid())
            .put("lastSortOrder", com.openminis.app.data.db.AppDatabase.getInstance(app).chatDao().loadMessages(sid).last().sortOrder)
            .put("task", JSONObject(GoalTools.execute("get_goal", "{}", sid, app).output))
            .put("artifact", WorkspaceFileClient.fingerprint(sid, "/workspace/delivery-after-death.txt")).put("passed", true)
        File(app.filesDir, "agent-task-loop-before-death.json").writeText(report.toString(2))
    }

    @Test
    fun reconcileAndDeliverAfterProcessDeath(): Unit = runBlocking {
        app.providerRepository.awaitConfigLoaded(); UbuntuPaths.initialize(app)
        val before = JSONObject(File(app.filesDir, "agent-task-loop-before-death.json").readText())
        val sid = before.getString("sessionId"); val report = JSONObject()
        try {
            assertNotEquals(before.getInt("pid"), android.os.Process.myPid())
            val restored = app.agentTaskRepository.sessionGoal(sid)!!
            assertEquals("ACTIVE", restored.status); assertEquals(2, restored.autoRunsUsed)
            assertEquals(before.getJSONObject("task").getString("task_id"), restored.id)
            assertEquals(before.getJSONObject("task").getInt("revision"), restored.revision)
            assertEquals(before.getJSONObject("task").getInt("stop_generation"), restored.stopGeneration)
            assertEquals(listOf("death-unknown-write"), ToolCheckpointStore.drainPending(app, sid).map { it.callId })
            val provider = ScriptedProvider { call, messages, _ -> when (call) {
                1 -> toolCall("after-death-read", "file_read", JSONObject().put("path", "/workspace/delivery-after-death.txt"))
                2 -> toolCall("after-death-get", "get_goal")
                3 -> toolCall("after-death-verify", "verify_outcome", verification(identity(messages, "after-death-get"),
                    identity(messages, "after-death-get").getJSONArray("unknown_outcomes").getJSONObject(0).getString("execution_id"),
                    "death-original-run"))
                4 -> {
                    assertFalse(identity(messages, "after-death-verify").has("completion_evidence"))
                    assertEquals("ACTIVE", identity(messages, "after-death-verify").getString("status"))
                    toolCall("after-death-complete", "update_goal", settle(identity(messages, "after-death-get"), "completed", JSONArray()
                        .put(JSONObject().put("type", "file").put("path", "/workspace/delivery-after-death.txt")
                            .put("sha256", before.getJSONObject("artifact").getString("sha256")))))
                }
                else -> text("重启后的已有成果已核验并交付，没有重放写入。")
            } }
            prepare(sid, provider)
            assertTrue(AgentRunner.prompt(app, sid, "恢复工作，先检查现场再继续验收", wait = true, timeoutMs = 25_000).completed)
            assertTrue(provider.systemPrompts.first()!!.contains("先读回 → 对照意图 → 核验结算 → 交付"))
            assertTrue(provider.requests.first().any { it.content.contains("UNKNOWN") })
            assertTrue(provider.requests.first().any { it.content.contains("death-unknown-write") && it.content.contains("intended_arguments") })
            val task = app.agentTaskRepository.sessionGoal(sid)!!
            assertEquals("COMPLETED", task.status); assertEquals(2, task.autoRunsUsed)
            assertEquals(restored.stopGeneration, task.stopGeneration)
            val proof = JSONObject(task.completionEvidence!!)
            val independentProof = identity(provider.requests.last(), "after-death-verify").getJSONObject("verified_outcome")
            assertEquals("death-unknown-write", independentProof.getString("call_id"))
            assertEquals("death-original-run", independentProof.getString("run_id"))
            assertTrue(ToolCheckpointStore.drainPending(app, sid).isEmpty())
            val after = WorkspaceFileClient.fingerprint(sid, "/workspace/delivery-after-death.txt")
            assertEquals(before.getJSONObject("artifact").getString("sha256"), after.getString("sha256"))
            val rows = com.openminis.app.data.db.AppDatabase.getInstance(app).chatDao().loadMessages(sid)
            val savedVerifications = rows.flatMap { row ->
                val parts = JSONArray(row.partsJson)
                (0 until parts.length()).mapNotNull { index -> parts.optJSONObject(index)?.optJSONObject("value")?.optJSONObject("outcomeVerification") }
            }.filter { it.optString("execution_id") == independentProof.getString("execution_id") }
            assertEquals(1, savedVerifications.size)
            assertEquals(independentProof.toString(), savedVerifications.single().toString())
            val newUses = rows.filter { it.sortOrder > before.getInt("lastSortOrder") }.flatMap { row ->
                val parts = JSONArray(row.partsJson)
                (0 until parts.length()).mapNotNull { index -> parts.optJSONObject(index)
                    ?.takeIf { it.optString("type") == "toolUse" }?.optJSONObject("value") }
            }
            assertFalse(newUses.any { it.optString("name") in setOf("file_write", "linux.file.write") })
            File(app.filesDir, "agent-task-recovered-delivery-e2e.txt").writeBytes(WorkspaceFileClient.readAll(sid, "/workspace/delivery-after-death.txt"))
            report.put("passed", true).put("beforePid", before.getInt("pid")).put("afterPid", android.os.Process.myPid())
                .put("artifact", after).put("delivery", proof).put("independentVerification", independentProof).put("writeReplayed", false)
        } catch (error: Throwable) { report.put("passed", false).put("failure", error.toString()); throw error }
        finally {
            val delivered = app.agentTaskRepository.sessionGoal(sid)
            app.chatRepository.deleteSession(sid)
            if (delivered?.status == "COMPLETED") {
                assertEquals("COMPLETED", app.agentTaskRepository.get(delivered.id)!!.status)
                assertEquals(delivered.completionEvidence, app.agentTaskRepository.get(delivered.id)!!.completionEvidence)
            }
            File(app.filesDir, "agent-task-loop-after-death.json").writeText(report.toString(2))
        }
    }
}
