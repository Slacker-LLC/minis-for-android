package com.openminis.app.ui.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.agent.*
import com.openminis.app.data.model.*
import com.openminis.app.data.repository.TaskArtifacts
import com.openminis.app.data.repository.awaitConfigLoaded
import com.openminis.app.data.storage.MediaStore
import com.openminis.app.data.storage.SessionStorage
import com.openminis.app.data.db.BotTaskEntity
import com.openminis.app.provider.LLMProvider
import com.openminis.app.runtime.files.SafeFileTree
import com.openminis.app.runtime.files.WorkspaceFileClient
import com.openminis.app.runtime.ubuntu.UbuntuPaths
import com.openminis.app.tools.AgentStateStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

/** Actual model files/Goal evidence, dated media, owner cancellation fence and scoped deletion. */
@RunWith(AndroidJUnit4::class)
class AgentCleanupE2ETest {
    @Test
    fun previewAndClearSelectedFilesAfterOldWritesExit(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        app.providerRepository.awaitConfigLoaded(); UbuntuPaths.initialize(app)
        val marker = "cleanup-e2e-${UUID.randomUUID()}"
        val sid = app.chatRepository.createSession("scripted-model", title = marker, memoryEnabled = false).id
        val other = app.chatRepository.createSession("scripted-model", title = "$marker-other", memoryEnabled = false).id
        val taskIds = mutableListOf<String>()
        val nativeProcesses = mutableListOf<NativeInputProcess>()
        val jobIds = mutableListOf<String>()
        val foreign = File(app.cacheDir, marker).apply { mkdirs() }
        val global = "/var/minis/shared/$marker.txt"
        val media = MediaStore(app)
        val report = JSONObject().put("device", android.os.Build.FINGERPRINT).put("scenarios", JSONArray())
        val held = HoldingProvider(sid)
        fun passed(name: String) { report.getJSONArray("scenarios").put(JSONObject().put("name", name).put("passed", true)) }
        suspend fun prepare(provider: LLMProvider): AgentSession {
            val vm = withContext(Dispatchers.Main) { chatProjection(app, sid) }
            withTimeout(15_000) { vm.sessionLoaded.first { it } }
            withContext(Dispatchers.Main) { vm.currentProvider = provider; vm.currentModel = provider.model; vm._activeEntryId.value = "cleanup-scripted" }
            return vm.agentSession
        }
        suspend fun until(check: suspend () -> Boolean) = withTimeout(15_000) { while (!check()) delay(20) }
        fun snapshot(value: SessionStorage.Preview): JSONObject = JSONObject().apply {
            value.bytes.forEach { (k,v) -> put(k.name,v ?: JSONObject.NULL) }
        }
        try {
            val prefix = UUID.randomUUID().toString()
            val writes = listOf("/workspace/results/report.txt" to "completed work\n",
                "/var/minis/attachments/input.txt" to "input bytes\n",
                "/var/minis/offloads/output.txt" to "tool bytes\n",
                "/var/minis/browser/browser.txt" to "browser bytes\n",
                "/workspace/projects/attachments/nested.txt" to "nested work\n")
            var identity: JSONObject? = null
            var finished: AgentContentPart.ToolResult? = null
            prepare(ScriptedProvider { call, history, _ ->
                fun receipt(id: String) = history.flatMap { it.contentParts }.filterIsInstance<AgentContentPart.ToolResult>().lastOrNull { it.id == id }
                when {
                    call == 1 -> toolCall("$prefix-goal", "create_goal", JSONObject().put("goal", marker).put("max_goal_rounds", 2))
                    call in 2..6 -> {
                        if (call == 2) { identity = JSONObject(receipt("$prefix-goal")!!.content); taskIds += identity!!.getString("task_id") }
                        val (path,content) = writes[call - 2]
                        toolCall("$prefix-$call", "file_write", JSONObject().put("path",path).put("content",content))
                    }
                    call == 7 -> toolCall("$prefix-finish", "update_goal", JSONObject().put("status","completed")
                        .put("task_id",identity!!.getString("task_id")).put("revision",identity!!.getInt("revision"))
                        .put("stop_generation",identity!!.getInt("stop_generation")).put("progress","已核验实际工作文件。")
                        .put("evidence",JSONArray().put(JSONObject().put("type","file").put("path",writes.first().first))))
                    else -> { finished = receipt("$prefix-finish"); text("已交付。") }
                }
            })
            assertTrue(AgentRunner.prompt(app,sid,"生成分类工作现场并核验成果",wait=true,timeoutMs=25_000).completed)
            assertNotNull(finished); assertFalse(finished!!.content,finished!!.isError)
            val completedId = identity!!.getString("task_id")
            val historical = app.database.botTaskDao().get(completedId)!!
            val artifact = TaskArtifacts.page(app,sid).references.single { it.taskId == completedId }
            assertEquals(TaskArtifacts.State.VERIFIED,TaskArtifacts.verify(app,artifact).state)
            passed("actual model creates all four file categories and a verified persistent work artifact")

            val mediaRef = media.saveMedia("media bytes\n".toByteArray(),"text/plain",sid,"input.txt")
            val otherRef = media.saveMedia("other media\n".toByteArray(),"text/plain",other,"other.txt")
            WorkspaceFileClient.writeBytes(other,"/workspace/results/report.txt","other work\n".toByteArray())
            WorkspaceFileClient.writeBytes(null,global,"shared retained\n".toByteArray())
            val foreignTarget = File(foreign,"$sid/keep.txt").apply { parentFile!!.mkdirs(); writeText("foreign bytes\n") }
            Files.createSymbolicLink(File(media.mediaBaseDir,"$marker-link").toPath(),foreign.toPath())
            Files.createSymbolicLink(File(media.mediaBaseDir,mediaRef.relativePath).parentFile!!.resolve("foreign-link").toPath(),foreign.toPath())
            val initial = SessionStorage.preview(app,sid)
            assertEquals("input bytes\nmedia bytes\n".toByteArray().size.toLong(),initial.bytes[SessionStorage.Category.INPUTS])
            assertEquals("completed work\nnested work\n".toByteArray().size.toLong(),initial.bytes[SessionStorage.Category.WORK])
            assertEquals("tool bytes\n".toByteArray().size.toLong(),initial.bytes[SessionStorage.Category.OUTPUTS])
            assertEquals("browser bytes\n".toByteArray().size.toLong(),initial.bytes[SessionStorage.Category.BROWSER])
            report.put("initial",snapshot(initial))
            passed("actual category preview skips workspace aliases and media links while counting nested work and dated input media")

            val owner = AgentSessionStore.existing(sid)!!
            val empty = SessionStorage.clear(app,sid,emptySet())
            assertEquals(initial,empty.after); assertEquals(0L,empty.removedBytes)
            assertSame(owner,AgentSessionStore.existing(sid))
            assertEquals(historical,app.database.botTaskDao().get(completedId))
            passed("empty selection leaves the actual owner, task history and every file unchanged")

            val browserResult = SessionStorage.clear(app,sid,setOf(SessionStorage.Category.BROWSER))
            assertTrue(browserResult.complete); assertEquals(initial.bytes[SessionStorage.Category.BROWSER],browserResult.removedBytes)
            assertEquals(initial.bytes[SessionStorage.Category.WORK],browserResult.after.bytes[SessionStorage.Category.WORK])
            assertEquals(initial.bytes[SessionStorage.Category.INPUTS],browserResult.after.bytes[SessionStorage.Category.INPUTS])
            assertEquals(initial.bytes[SessionStorage.Category.OUTPUTS],browserResult.after.bytes[SessionStorage.Category.OUTPUTS])
            assertEquals(TaskArtifacts.State.VERIFIED,TaskArtifacts.verify(app,artifact).state)
            report.put("afterBrowser",snapshot(browserResult.after))
            passed("clearing only browser data preserves actual input, tool output, work bytes and the verified artifact")

            val outputResult = SessionStorage.clear(app,sid,setOf(SessionStorage.Category.OUTPUTS))
            assertTrue(outputResult.complete); assertEquals(initial.bytes[SessionStorage.Category.OUTPUTS],outputResult.removedBytes)
            assertEquals(TaskArtifacts.State.VERIFIED,TaskArtifacts.verify(app,artifact).state)
            passed("tool-output cleanup uses its independent root and does not delete work products through an alias")

            assertNull(AgentSessionStore.existing(sid))
            val targetJob = NativeInputProcess(File(UbuntuPaths.hostSessions,"$sid/offloads/background.txt"))
            val otherJob = NativeInputProcess(File(UbuntuPaths.hostSessions,"$other/workspace/background.txt"))
            nativeProcesses += targetJob; nativeProcesses += otherJob
            val targetJobId = com.openminis.app.runtime.ShellJobs.start(sid,"cleanup-owned-process",newProcess={targetJob})
            val otherJobId = com.openminis.app.runtime.ShellJobs.start(other,"cleanup-other-process",newProcess={otherJob})
            jobIds += targetJobId; jobIds += otherJobId
            withTimeout(15_000) { targetJob.started.await();otherJob.started.await() }
            assertTrue(targetJob.process!!.isAlive); assertTrue(otherJob.process!!.isAlive)
            SessionStorage.clear(app,sid,setOf(SessionStorage.Category.OUTPUTS))
            until { targetJob.process?.isAlive == false }
            assertEquals(com.openminis.app.tools.JobRegistry.JobStatus.KILLED,com.openminis.app.tools.JobRegistry.get(targetJobId)!!.status)
            assertFalse(WorkspaceFileClient.info(sid,"/var/minis/offloads/background.txt").getBoolean("exists"))
            assertTrue(otherJob.process!!.isAlive);assertEquals(0,otherJob.stopCalls)
            otherJob.process!!.outputStream.apply { write("continue\n".toByteArray());flush() }
            until { com.openminis.app.tools.JobRegistry.get(otherJobId)?.status == com.openminis.app.tools.JobRegistry.JobStatus.COMPLETED }
            assertEquals("background bytes\n",WorkspaceFileClient.readAll(other,"/workspace/background.txt").decodeToString())
            passed("actual App UID shell background process stops even without an Agent owner while another session process continues and writes normally")

            val sessionRoot = File(UbuntuPaths.hostSessions,sid).toPath()
            val permissions = Files.getPosixFilePermissions(sessionRoot)
            try {
                Files.setPosixFilePermissions(sessionRoot,emptySet())
                val unavailable = SessionStorage.preview(app,sid)
                assertTrue("unreadable session must not be presented as empty",unavailable.bytes.values.all { it == null })
                val overview = com.openminis.app.ui.settings.loadStorageSnapshot(app,app.database.chatDao())
                assertFalse(overview.sessionSizesComplete)
                assertFalse(overview.sessions.single { it.id == sid }.sizesComplete)
                assertTrue("denied deletion must not report successful zero-byte cleanup",runCatching {
                    SessionStorage.clear(app,sid,setOf(SessionStorage.Category.WORK))
                }.isFailure)
            } finally { Files.setPosixFilePermissions(sessionRoot,permissions) }
            assertEquals(TaskArtifacts.State.VERIFIED,TaskArtifacts.verify(app,artifact).state)
            passed("actual Android permission denial stays unknown and failed cleanup preserves work bytes instead of reporting empty success")

            app.botTaskRepository.clearSessionGoal(sid)
            val active = AgentStateStore.goalSet(sid,"继续当前工作",app,maxGoalRounds=2)
            taskIds += active.id
            val live = prepare(held)
            val turn = withContext(Dispatchers.Main) { AgentRunner.submit(live,"等待清理请求") }
            withTimeout(15_000) { held.started.await() }
            val clearing = async(Dispatchers.Default) { SessionStorage.clear(app,sid,setOf(SessionStorage.Category.WORK)) }
            withTimeout(15_000) { held.cleanupStarted.await() }
            assertFalse(clearing.isCompleted)
            assertTrue(WorkspaceFileClient.info(sid,writes.first().first).getBoolean("exists"))
            val during = withContext(Dispatchers.Main) { AgentRunner.sessionForCommand(app,sid) }
            until { live.execution.isClosed }
            assertSame(live,during); assertTrue(during.execution.isClosed)
            val rejected = withContext(Dispatchers.Main) { AgentRunner.submit(during,"不能跨越清理边界") }
            assertEquals(AgentTurnOutcome.Rejected("session_closed"),rejected.result.await())
            assertEquals(BotTaskEntity.STATUS_PAUSED,app.database.botTaskDao().get(active.id)!!.status)
            held.release.complete(Unit)
            val workResult = withTimeout(15_000) { clearing.await() }
            assertEquals(AgentTurnOutcome.Cancelled,withTimeout(15_000) { turn.result.await() })
            assertTrue(held.lateWriteCompleted); assertTrue(workResult.complete)
            assertFalse(WorkspaceFileClient.info(sid,"/workspace/late-cleanup.txt").getBoolean("exists"))
            assertEquals(0L,workResult.after.bytes[SessionStorage.Category.WORK])
            assertEquals(initial.bytes[SessionStorage.Category.INPUTS],workResult.after.bytes[SessionStorage.Category.INPUTS])
            assertNull(AgentSessionStore.existing(sid))
            report.put("afterWork",snapshot(workResult.after)).put("waitedForActualCleanup",true)
            passed("actual held turn cleanup writes before the deletion fence; Goal pauses, crossing submit is rejected, and the late file is then removed")

            assertEquals(TaskArtifacts.State.UNAVAILABLE,TaskArtifacts.verify(app,artifact).state)
            assertEquals(historical.completionEvidence,app.database.botTaskDao().get(completedId)!!.completionEvidence)
            assertEquals(BotTaskEntity.STATUS_COMPLETED,app.database.botTaskDao().get(completedId)!!.status)
            assertNotNull(app.database.chatDao().getSession(sid))
            assertTrue(app.database.chatDao().loadMessages(sid).isNotEmpty())
            passed("selected work deletion makes its current artifact unavailable without rewriting historical completion or deleting the conversation")

            val inputResult = SessionStorage.clear(app,sid,setOf(SessionStorage.Category.INPUTS))
            assertTrue(inputResult.complete); assertEquals(initial.bytes[SessionStorage.Category.INPUTS],inputResult.removedBytes)
            assertFalse(File(media.mediaBaseDir,mediaRef.relativePath).exists())
            assertEquals("other media\n",File(media.mediaBaseDir,otherRef.relativePath).readText())
            assertEquals("foreign bytes\n",foreignTarget.readText())
            assertEquals("other work\n",WorkspaceFileClient.readAll(other,writes.first().first).decodeToString())
            assertEquals("shared retained\n",WorkspaceFileClient.readAll(null,global).decodeToString())
            passed("actual input and media deletion does not follow links or change another session or global shared data")

            val fresh = prepare(ScriptedProvider { _,_,_ -> text("清理后可继续。") })
            assertNotSame(live,fresh)
            val resumed = withContext(Dispatchers.Main) { AgentRunner.submit(fresh,"清理后正常对话") }
            assertEquals(AgentTurnOutcome.Completed,withTimeout(15_000) { resumed.result.await() })
            assertEquals(BotTaskEntity.STATUS_PAUSED,app.database.botTaskDao().get(active.id)!!.status)
            passed("a real fresh projection can chat after cleanup while the persistent Goal remains paused")

            val bad = runCatching { SessionStorage.clear(app,"../$sid",setOf(SessionStorage.Category.WORK)) }
            assertTrue(bad.isFailure)
            val deleted = app.chatRepository.createSession("scripted-model",title=marker,memoryEnabled=false).id
            app.chatRepository.deleteSession(deleted)
            assertTrue(runCatching { SessionStorage.clear(app,deleted,setOf(SessionStorage.Category.WORK)) }.isFailure)
            assertFalse(File(UbuntuPaths.hostSessions,deleted).exists())
            passed("invalid and deleted session cleanup fails without creating a new owner or guest directory")
            val delivery = JSONObject().put("initial",snapshot(initial)).put("afterBrowser",snapshot(browserResult.after))
                .put("afterWork",snapshot(workResult.after)).put("retainedSharedSha256",WorkspaceFileClient.fingerprint(sid,global).getString("sha256"))
                .put("historyRetained",true).put("waitedForActualCleanup",true).toString(2).toByteArray()
            File(app.filesDir,"agent-cleanup-delivery.json").writeBytes(delivery)
            report.put("artifactSha256",MessageDigest.getInstance("SHA-256").digest(delivery).joinToString("") { "%02x".format(it) })
            report.put("passed",true)
        } catch (error: Throwable) { report.put("passed",false).put("failure",error.stackTraceToString()); throw error }
        finally {
            held.release.complete(Unit)
            jobIds.forEach { com.openminis.app.tools.JobRegistry.kill(it,"test cleanup") }
            nativeProcesses.forEach { it.stop() }
            for (id in listOf(sid,other)) { AgentSessionStore.release(id); runCatching { app.chatRepository.deleteSession(id) }; media.deleteSessionMedia(id) }
            taskIds.forEach { app.database.openHelper.writableDatabase.execSQL("DELETE FROM bot_tasks WHERE id = ?",arrayOf(it)) }
            runCatching { WorkspaceFileClient.delete(null,global) }
            SafeFileTree.deleteRecursively(File(media.mediaBaseDir,"$marker-link")); SafeFileTree.deleteRecursively(foreign)
            report.put("cleanupComplete",!File(UbuntuPaths.hostSessions,sid).exists() && !File(UbuntuPaths.hostSessions,other).exists() && !foreign.exists())
            File(app.filesDir,"agent-cleanup-e2e.json").writeText(report.toString(2))
        }
    }

    /** Real Android App-UID process. The product ShellJobs registry and cancellation path are unchanged. */
    private class NativeInputProcess(private val destination: File) : com.openminis.app.runtime.ShellJobs.JobProcess {
        val started = CompletableDeferred<Unit>()
        @Volatile var process: Process? = null
        @Volatile var stopCalls = 0
        override suspend fun run(command: String,timeoutMs: Long,onLine: (String)->Unit): com.openminis.app.runtime.ShellJobs.JobProcess.Result = withContext(Dispatchers.IO) {
            val child = ProcessBuilder("/system/bin/sh","-c","echo ready; read line; printf 'background bytes\\n' > \"\$1\"",
                "cleanup-owned-process",destination.absolutePath).start()
            process = child
            try {
                assertEquals("ready",child.inputStream.bufferedReader().readLine())
                started.complete(Unit)
                com.openminis.app.runtime.ShellJobs.JobProcess.Result(child.waitFor())
            } finally { child.destroyForcibly() }
        }
        override fun stop() { stopCalls++; process?.destroyForcibly() }
    }

    private class HoldingProvider(private val sid: String) : LLMProvider {
        override val name = "Cleanup controlled transport"
        override var model = LLMModel("cleanup-held","Cleanup model","cleanup",contextWindow=32768)
        val started = CompletableDeferred<Unit>(); val cleanupStarted = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        @Volatile var lateWriteCompleted = false
        override suspend fun sendMessageClamped(messages: List<LLMMessage>,systemPrompt: String?,maxTokens: Int,temperature: Double?,
            imageParts: List<LLMMessage.ImagePart>,tools: List<AgentToolDefinition>,thinkingLevel: ThinkingLevel) = LLMResponse("Cleanup test","stop",null)
        override fun streamMessageClamped(messages: List<LLMMessage>,systemPrompt: String?,maxTokens: Int,temperature: Double?,
            imageParts: List<LLMMessage.ImagePart>,tools: List<AgentToolDefinition>,thinkingLevel: ThinkingLevel): Flow<LLMStreamChunk> = flow {
            emit(LLMStreamChunk.Text("等待中")); started.complete(Unit)
            try { awaitCancellation() }
            finally { withContext(NonCancellable) {
                cleanupStarted.complete(Unit); release.await()
                WorkspaceFileClient.writeBytes(sid,"/workspace/late-cleanup.txt","late write\n".toByteArray())
                lateWriteCompleted = true
            } }
        }
    }
}
