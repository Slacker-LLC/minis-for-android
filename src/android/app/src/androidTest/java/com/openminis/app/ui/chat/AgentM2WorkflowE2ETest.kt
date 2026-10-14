package com.openminis.app.ui.chat

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.agent.AgentRunner
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.AgentTaskEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.repository.awaitConfigLoaded
import com.openminis.app.debug.DebugRPCHandler
import com.openminis.app.debug.handle
import com.openminis.app.remote.AgentCommandRegistry
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
import java.security.MessageDigest

/** Complete Android workflows over real Room, tool calls, core requests and files. */
@RunWith(AndroidJUnit4::class)
class AgentM2WorkflowE2ETest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
    private suspend fun rpc(method: String, sid: String, params: JSONObject = JSONObject()): JSONObject {
        val response = JSONObject(DebugRPCHandler(app).handle(JSONObject().put("jsonrpc", "2.0").put("id", 1)
            .put("method", method).put("params", params.put("sessionId", sid)).toString()))
        assertFalse(response.toString(), response.has("error"))
        return response.getJSONObject("result")
    }
    private suspend fun prepare(sid: String, provider: ScriptedProvider) {
        val vm = withContext(Dispatchers.Main) { chatProjection(app, sid) }
        withTimeout(20_000) { vm.sessionLoaded.first { it } }
        withContext(Dispatchers.Main) {
            vm.currentProvider = provider; vm.currentModel = provider.model; vm._activeEntryId.value = "m2-scripted-entry"
        }
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun save(name: String, report: JSONObject) = File(app.filesDir, name).writeText(report.toString(2))

    @Test
    fun sharedGoalWorkflow(): Unit = runBlocking {
        app.providerRepository.awaitConfigLoaded()
        UbuntuPaths.initialize(app)
        val session = app.chatRepository.createSession("scripted-model", title = "M2 ordinary task")
        val ids = mutableListOf(session.id)
        val report = JSONObject().put("scenarios", JSONArray())
        fun passed(name: String) { report.getJSONArray("scenarios").put(JSONObject().put("name", name).put("passed", true)) }
        try {
            val provider = ScriptedProvider { call, messages, _ -> when (call) {
                1 -> toolCall("m2-goal", "create_goal", JSONObject()
                    .put("goal", "交付可核验 M2 文件，保留源数据").put("acceptance_criteria", "读取文件确认 M2 durable task")
                    .put("max_goal_rounds", 9))
                2 -> toolCall("m2-write", "file_write", JSONObject().put("path", "/workspace/m2-result.txt").put("content", "M2 durable task\n"))
                3 -> toolCall("m2-read", "file_read", JSONObject().put("path", "/workspace/m2-result.txt"))
                4 -> {
                    val identity = JSONObject(messages.flatMap { it.contentParts }.filterIsInstance<AgentContentPart.ToolResult>()
                        .single { it.id == "m2-goal" }.content)
                    toolCall("m2-pause", "update_goal", JSONObject().put("status", "paused")
                        .put("task_id", identity.getString("task_id")).put("revision", identity.getInt("revision"))
                        .put("stop_generation", identity.getInt("stop_generation")).put("progress", "文件已读回，保留目标等待后续验收。"))
                }
                else -> text("文件已写入并读回；本回合结束仍需任务验收。")
            } }
            prepare(session.id, provider)
            assertTrue(AgentRunner.prompt(app, session.id, "建立目标并完成文件工作", wait = true, timeoutMs = 25_000).completed)
            val goal = rpc("agent.goal.get", session.id)
            assertTrue("goal must be created through the offered schema: $goal", goal.getString("id").isNotBlank())
            val task = app.agentTaskRepository.get(goal.getString("id"))!!
            assertEquals(session.id, task.goalSessionId)
            assertEquals(9, task.maxAutoRuns)
            assertTrue(provider.systemPrompts.drop(1).all { it!!.contains("交付可核验 M2 文件") && it.contains("读取文件确认 M2 durable task") })
            val bytes = WorkspaceFileClient.readAll(session.id, "/workspace/m2-result.txt")
            assertEquals("M2 durable task\n", bytes.decodeToString())
            val read = provider.requests.last().flatMap { it.contentParts }.filterIsInstance<AgentContentPart.ToolResult>().single { it.id == "m2-read" }
            assertFalse(read.isError)
            assertTrue(read.content.contains("M2 durable task"))
            assertEquals(AgentTaskEntity.STATUS_PAUSED, task.status)
            report.put("artifactSha256", sha(bytes)).put("modelCalls", provider.calls).put("taskId", task.id)
            File(app.filesDir, "m2-result-e2e.txt").writeBytes(bytes)
            passed("model creates a durable ordinary task; actual file write/read succeeds; ending the turn does not finish the goal")

            assertTrue(AgentCommandRegistry.execute(app, session.id, "plan", "保留原文件 → 修改 → 读回核验").ok)
            assertEquals("保留原文件 → 修改 → 读回核验", rpc("agent.plan.get", session.id).getString("plan"))
            rpc("agent.goal.setActive", session.id, JSONObject().put("active", true))
            assertTrue(app.agentTaskRepository.recordAutoRun(task.id, 9))
            assertTrue(app.agentTaskRepository.recordAutoRun(task.id, 9))
            rpc("agent.goal.setActive", session.id, JSONObject().put("active", false))
            assertTrue(AgentCommandRegistry.execute(app, session.id, "goal", "补充目标：仍需保留源数据").ok)
            val paused = rpc("agent.goal.get", session.id)
            assertFalse(paused.getBoolean("active"))
            assertEquals("PAUSED", paused.getString("status"))
            assertEquals(2, paused.getInt("roundsUsed"))
            assertEquals(9, paused.getInt("maxGoalRounds"))
            assertEquals(task.id, paused.getString("id"))
            assertEquals(3, paused.getInt("stopGeneration"))
            assertTrue(paused.getString("acceptanceCriteria").contains("读取文件"))
            assertFalse(app.agentTaskRepository.recordAutoRun(task.id, 9))
            passed("Debug and slash commands share task identity, durable plan, pause generation and spent budget without reactivation")

            withContext(Dispatchers.Main) { AgentRunner.forget(session.id) }
            val reopened = ScriptedProvider { _, _, _ -> text("只回答本次进度问题。") }
            prepare(session.id, reopened)
            assertTrue(AgentRunner.prompt(app, session.id, "当前进度是什么", wait = true, timeoutMs = 20_000).completed)
            assertTrue(reopened.systemPrompts.single()!!.contains("PAUSED"))
            assertTrue(reopened.systemPrompts.single()!!.contains("保留原文件 → 修改 → 读回核验"))
            assertEquals(2, rpc("agent.goal.get", session.id).getInt("roundsUsed"))
            passed("reopening core restores paused constraints and task plan in the actual provider request")

            val clearer = ScriptedProvider { call, _, _ -> if (call == 1)
                toolCall("m2-clear", "update_goal", JSONObject().put("goal", "")) else text("目标已清除。") }
            prepare(session.id, clearer)
            assertTrue(AgentRunner.prompt(app, session.id, "清除当前目标", wait = true, timeoutMs = 20_000).completed)
            assertFalse(clearer.requests.last().flatMap { it.contentParts }.filterIsInstance<AgentContentPart.ToolResult>()
                .single { it.id == "m2-clear" }.isError)
            assertTrue(rpc("agent.goal.get", session.id).getString("id").isBlank())
            assertEquals("CANCELLED", app.agentTaskRepository.get(task.id)!!.status)
            assertNull(app.agentTaskRepository.get(task.id)!!.goalSessionId)
            val results = coroutineScope { (1..2).map { async(Dispatchers.IO) {
                GoalTools.execute("create_goal", "{\"goal\":\"并行创建只能有一个目标\"}", session.id, app).success
            } }.awaitAll() }
            assertEquals(1, results.count { it })
            assertNotEquals(task.id, rpc("agent.goal.get", session.id).getString("id"))
            assertFalse(GoalTools.execute("update_goal", "{}", session.id, app).success)
            assertFalse(GoalTools.execute("create_goal", "{\"goal\":\"不存在会话不可建任务\"}", "m2-deleted-session", app).success)
            val draft = "__new__${java.util.UUID.randomUUID()}"
            val observed = async { withTimeout(15_000) { AgentStateStore.observeGoal(draft, app).first { it.text == "草稿晋升后显示同一持久目标" } } }
            yield()
            assertTrue(AgentCommandRegistry.execute(app, draft, "goal", "草稿晋升后显示同一持久目标").ok)
            val promoted = com.openminis.app.agent.AgentSessionStore.resolvePersistedId(draft)
            ids += promoted
            assertNotEquals(draft, promoted)
            assertEquals(rpc("agent.goal.get", promoted).getString("id"), observed.await().id)
            assertEquals(rpc("agent.goal.get", promoted).getString("id"), rpc("agent.goal.get", draft).getString("id"))
            assertTrue(AgentCommandRegistry.execute(app, draft, "plan", "草稿计划进入真实任务").ok)
            assertEquals("草稿计划进入真实任务", rpc("agent.plan.get", draft).getString("plan"))
            passed("clearing, concurrent create, invalid requests and draft promotion preserve one canonical task and live observation")

            val empty = app.chatRepository.createSession("scripted-model")
            ids += empty.id
            val kept = rpc("agent.goal.set", empty.id, JSONObject().put("text", "空聊天也保留已授权目标"))
            prepare(empty.id, ScriptedProvider { _, _, _ -> text("保留空会话目标") })
            val idleOwner = withContext(Dispatchers.Main) { AgentRunner.sessionForCommand(app, empty.id) }
            assertEquals(0, app.chatRepository.dao.deleteSessionIfUnusedDraft(empty.id))
            assertFalse(app.chatRepository.deleteUnusedDraft(empty.id))
            assertSame(idleOwner, withContext(Dispatchers.Main) { AgentRunner.sessionForCommand(app, empty.id) })
            assertNotNull(app.chatRepository.getSession(empty.id))
            app.chatRepository.dao.clearConversation(empty.id)
            assertEquals(kept.getString("id"), rpc("agent.goal.get", empty.id).getString("id"))
            app.chatRepository.deleteSession(empty.id)
            assertNull(app.chatRepository.getSession(empty.id))
            assertEquals("CANCELLED", app.agentTaskRepository.get(kept.getString("id"))!!.status)
            assertNull(app.agentTaskRepository.get(kept.getString("id"))!!.goalSessionId)
            passed("empty-draft cleanup and clear preserve explicit task ownership; explicit session deletion atomically stops it")
            report.put("passed", true)
        } catch (error: Throwable) {
            report.put("passed", false).put("failure", error.toString()); throw error
        } finally {
            for (id in ids) app.chatRepository.deleteSession(id)
            save("agent-m2-workflow-e2e.json", report)
        }
    }

    @Test
    fun migrateLegacyTaskIntoAgentAndRetainRecoveryCopy(): Unit = runBlocking {
        // Host supplies the committed v23 schema, so this opens an actual v23 DB file.
        val name = "m2-migration-e2e.db"
        app.deleteDatabase(name)
        val schema = JSONObject(File(app.filesDir, "m2-v23-schema.json").readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(app.getDatabasePath(name), null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i); val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\u0024{TABLE_NAME}", table))
                val indices = entity.getJSONArray("indices")
                for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\u0024{TABLE_NAME}", table))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL("INSERT INTO bot_tasks VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf("legacy-m2", "legacy-session", "legacy-run", "legacy-bot", "旧 Bot 目标", "必须保留预算", "PAUSED", "REVISING", 7,
                    "legacy-owner-session", 2, 6, 1, 4, 1890000000000L, 1700000000000L, 1700000000001L, null))
            db.execSQL("INSERT INTO sessions (id,model_id,created_at,updated_at,memory_enabled,edit_count) VALUES ('legacy-session','legacy-model',1700000000000,1700000000001,0,0)")
            db.execSQL("INSERT INTO messages (id,session_id,role,parts_json,created_at,sort_order,stream_interrupt_count) VALUES (?,?,?,?,?,?,?)",
                arrayOf("legacy-input", "legacy-session", "user", "[{\"type\":\"text\",\"value\":\"原始历史正文\"}]", 1700000000000L, 0, 0))
            db.version = 23
        }
        val db = Room.databaseBuilder(app, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_23_24, AppDatabase.MIGRATION_24_25,
                AppDatabase.MIGRATION_25_26, AppDatabase.MIGRATION_26_27).build()
        val report = JSONObject()
        try {
            val old = db.agentTaskDao().get("legacy-m2")!!
            assertEquals("legacy-run", old.originMessageId)
            assertEquals("NEEDS_USER", old.status); assertEquals("REVIEWING", old.phase)
            assertEquals(7, old.revision); assertEquals(2, old.autoRunsUsed); assertEquals(4, old.stopGeneration)
            assertEquals(1890000000000L, old.deadlineAt); assertEquals(1700000000000L, old.createdAt)
            assertEquals(1700000000001L, old.updatedAt); assertNull(old.completedAt)
            assertEquals("必须保留预算", old.acceptanceCriteria); assertEquals("legacy-session", old.currentOwnerSessionId)
            assertNull(old.goalSessionId); assertEquals(3, old.maxAutoRuns); assertEquals("off", old.planMode)
            assertTrue(old.progress.contains("已移除"))
            db.openHelper.readableDatabase.query(
                "SELECT owner_bot_id, current_owner_session_id, delegations_used, revision_rounds_used FROM bot_tasks WHERE id = ?",
                arrayOf("legacy-m2"),
            ).use { archived ->
                assertTrue(archived.moveToFirst())
                assertEquals("legacy-bot", archived.getString(0))
                assertEquals("legacy-owner-session", archived.getString(1))
                assertEquals(6, archived.getInt(2))
                assertEquals(1, archived.getInt(3))
            }
            val oldInput = db.chatDao().getMessageById("legacy-input")!!
            assertEquals("[{\"type\":\"text\",\"value\":\"原始历史正文\"}]", oldInput.partsJson)
            assertNull(oldInput.requestState); assertNull(oldInput.requestDelivery); assertNull(oldInput.requestTaskId)
            assertNull(oldInput.requestTaskGeneration); assertNull(oldInput.requestRunId); assertNull(oldInput.requestUserInput)
            assertTrue(oldInput.isConversationMessage)
            assertEquals(27, db.openHelper.readableDatabase.version)
            report.put("oldTask", JSONObject().put("id", old.id).put("budgetUsed", old.autoRunsUsed).put("stopGeneration", old.stopGeneration))
                .put("schemaFrom", 23).put("schemaTo", db.openHelper.readableDatabase.version).put("passed", true)
        } catch (error: Throwable) { report.put("passed", false).put("failure", error.toString()); throw error }
        finally { db.close(); app.deleteDatabase(name); save("agent-m2-migration-e2e.json", report) }
    }

    @Test
    fun prepareProcessDeath(): Unit = runBlocking {
        app.providerRepository.awaitConfigLoaded(); UbuntuPaths.initialize(app)
        val session = app.chatRepository.createSession("scripted-model", title = "M2 process death fixture")
        val goal = rpc("agent.goal.set", session.id, JSONObject().put("text", "重启后继续核验已有文件，未知写入先观察")
            .put("maxGoalRounds", 11).put("acceptanceCriteria", "保持 M2 restart evidence 文件哈希，不重放未知写入"))
        rpc("agent.plan.set", session.id, JSONObject().put("mode", "plan").put("plan", "已写文件；重启先读回；保留暂停"))
        repeat(2) { assertTrue(app.agentTaskRepository.recordAutoRun(goal.getString("id"), 11)) }
        rpc("agent.goal.setActive", session.id, JSONObject().put("active", false))
        val provider = ScriptedProvider { call, _, _ -> if (call == 1) toolCall("m2-restart-write", "file_write",
            JSONObject().put("path", "/workspace/m2-restart.txt").put("content", "M2 restart evidence\n")) else text("已保存文件。") }
        prepare(session.id, provider)
        assertTrue(AgentRunner.prompt(app, session.id, "保存测试工作现场文件", wait = true, timeoutMs = 20_000).completed)
        ToolCheckpointStore.recordIntent(app, session.id, "m2-outcome-unknown", "linux.file.write", "{\"path\":\"/workspace/m2-restart.txt\",\"content\":\"must not replay\"}")
        val report = JSONObject().put("sessionId", session.id).put("goal", rpc("agent.goal.get", session.id))
            .put("plan", rpc("agent.plan.get", session.id)).put("pid", android.os.Process.myPid())
            .put("artifactSha256", sha(WorkspaceFileClient.readAll(session.id, "/workspace/m2-restart.txt"))).put("passed", true)
        save("agent-m2-process-before.json", report)
    }

    @Test
    fun recoverAfterProcessDeath(): Unit = runBlocking {
        app.providerRepository.awaitConfigLoaded(); UbuntuPaths.initialize(app)
        val before = JSONObject(File(app.filesDir, "agent-m2-process-before.json").readText())
        val sid = before.getString("sessionId"); val report = JSONObject()
        try {
            assertNotEquals(before.getInt("pid"), android.os.Process.myPid())
            assertEquals(before.getJSONObject("goal").toString(), rpc("agent.goal.get", sid).toString())
            assertEquals(before.getJSONObject("plan").toString(), rpc("agent.plan.get", sid).toString())
            assertEquals(listOf("m2-outcome-unknown"), ToolCheckpointStore.drainPending(app, sid).map { it.callId })
            val provider = ScriptedProvider { call, _, _ -> if (call == 1) toolCall("m2-restart-read", "file_read",
                JSONObject().put("path", "/workspace/m2-restart.txt")) else text("已有文件已核验，目标仍暂停。") }
            prepare(sid, provider)
            assertTrue(AgentRunner.prompt(app, sid, "核验已有文件并报告，不恢复暂停目标", wait = true, timeoutMs = 20_000).completed)
            assertTrue(provider.systemPrompts.first()!!.contains("重启后继续核验已有文件"))
            assertTrue(provider.systemPrompts.first()!!.contains("已写文件；重启先读回；保留暂停"))
            val sha = sha(WorkspaceFileClient.readAll(sid, "/workspace/m2-restart.txt"))
            assertEquals(before.getString("artifactSha256"), sha)
            assertEquals(listOf("m2-outcome-unknown"), ToolCheckpointStore.drainPending(app, sid).map { it.callId })
            assertEquals(before.getJSONObject("goal").toString(), rpc("agent.goal.get", sid).toString())
            report.put("passed", true).put("beforePid", before.getInt("pid")).put("afterPid", android.os.Process.myPid())
                .put("artifactSha256", sha).put("goal", rpc("agent.goal.get", sid)).put("unknownWriteRetained", true)
        } catch (error: Throwable) { report.put("passed", false).put("failure", error.toString()); throw error }
        finally { app.chatRepository.deleteSession(sid); save("agent-m2-process-after.json", report) }
    }
}
