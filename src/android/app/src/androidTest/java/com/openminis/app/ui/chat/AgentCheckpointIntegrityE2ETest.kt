package com.openminis.app.ui.chat

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.agent.*
import com.openminis.app.data.db.BotTaskEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.repository.awaitConfigLoaded
import com.openminis.app.runtime.files.WorkspaceFileClient
import com.openminis.app.runtime.ubuntu.UbuntuPaths
import com.openminis.app.tools.*
import com.openminis.app.tools.runtime.ToolExecutor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real Room goals, Agent requests, file permissions, persisted intents and owner rebuild. */
@RunWith(AndroidJUnit4::class)
class AgentCheckpointIntegrityE2ETest {
    @Test fun damagedRecordsStopExecutionAndPreserveRecovery(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        app.providerRepository.awaitConfigLoaded(); UbuntuPaths.initialize(app)
        val marker = "m2-integrity-${UUID.randomUUID()}"
        val sessions = mutableListOf<String>()
        val report = JSONObject().put("marker", marker).put("device", android.os.Build.FINGERPRINT)
            .put("scenarios", JSONArray())
        fun passed(name: String) { report.getJSONArray("scenarios").put(JSONObject().put("name", name).put("passed", true)) }
        suspend fun session(): String = app.chatRepository.createSession("scripted-model", title = marker, memoryEnabled = false)
            .id.also(sessions::add)
        suspend fun prepare(sid: String, provider: ScriptedProvider) {
            val vm = withContext(Dispatchers.Main) { chatProjection(app, sid) }
            withTimeout(15_000) { vm.sessionLoaded.first { it } }
            withContext(Dispatchers.Main) {
                vm.currentProvider = provider; vm.currentModel = provider.model; vm._activeEntryId.value = "checkpoint-integrity-scripted"
            }
        }
        try {
            val sid = session()
            val backing = File(app.filesDir, "checkpoints/$sid.jsonl").also { it.parentFile!!.mkdirs() }
            val body = "$marker existing verified bytes\n".toByteArray()
            WorkspaceFileClient.writeBytes(sid, "/workspace/proof.txt", body)
            val fingerprint = WorkspaceFileClient.fingerprint(sid, "/workspace/proof.txt")
            AgentStateStore.goalSet(sid, "$marker verify existing proof", app, 3)
            val active = checkNotNull(app.botTaskRepository.sessionGoal(sid))
            assertTrue(GoalTools.execute("update_goal", JSONObject().put("task_id", active.id)
                .put("revision", active.revision).put("stop_generation", active.stopGeneration)
                .put("status", "waiting").put("progress", "preserve unknown effects").toString(), sid, app).success)
            val task = checkNotNull(app.botTaskRepository.sessionGoal(sid))
            val completeArgs = JSONObject().put("task_id", task.id).put("revision", task.revision)
                .put("stop_generation", task.stopGeneration).put("status", "completed").put("progress", "verified file")
                .put("evidence", JSONArray().put(JSONObject().put("type", "file").put("path", "/workspace/proof.txt")))
            val provider = ScriptedProvider { call, _, _ -> if (call == 1) {
                toolCall("integrity-complete", "update_goal", completeArgs)
            } else text("must not be reached while checkpoint is damaged") }
            prepare(sid, provider)
            backing.writeText("{\"callId\":\"interrupted-write\",\"state\":\"pending\"\n")
            val originalDamage = backing.readBytes()
            val result = AgentRunner.prompt(app, sid, "核验已有成果，不能重放未知动作", wait = true, timeoutMs = 20_000)
            report.put("initialResult", result.toString()).put("initialTaskStatus", app.botTaskRepository.sessionGoal(sid)!!.status)
            assertFalse(result.toString(), result.completed)
            assertEquals(0, provider.calls)
            assertEquals(BotTaskEntity.STATUS_WAITING, app.botTaskRepository.sessionGoal(sid)!!.status)
            assertNull(app.botTaskRepository.sessionGoal(sid)!!.completionEvidence)
            assertArrayEquals(originalDamage, backing.readBytes())
            assertArrayEquals(body, WorkspaceFileClient.readAll(sid, "/workspace/proof.txt"))
            passed("damaged checkpoint blocks the actual model before it can complete a Room task with otherwise valid file evidence")

            backing.delete()
            val execution = checkNotNull(ToolCheckpointStore.recordIntent(app, sid, "integrity-original-write", "linux.file.write",
                JSONObject().put("path", "/workspace/proof.txt").put("content", body.decodeToString()).toString(),
                "integrity-original-run", task.id, task.revision, task.stopGeneration))
            val good = backing.readBytes()
            val row = JSONObject(good.decodeToString())
            val invalid = linkedMapOf(
                "truncated JSON" to "{",
                "empty object" to "{}",
                "missing timestamp" to JSONObject(row.toString()).apply { remove("at") }.toString(),
                "missing call identity" to JSONObject(row.toString()).apply { remove("callId") }.toString(),
                "empty tool" to JSONObject(row.toString()).put("tool", "").toString(),
                "unknown state" to JSONObject(row.toString()).put("state", "lost").toString(),
                "structured args replacing text" to JSONObject(row.toString()).put("args", JSONObject()).toString(),
                "invalid Run type" to JSONObject(row.toString()).put("runId", JSONArray()).toString(),
                "string revision" to JSONObject(row.toString()).put("revision", "2").toString(),
                "negative stop generation" to JSONObject(row.toString()).put("stopGeneration", -1).toString(),
                "duplicate execution identity" to row.toString(),
                "trailing JSON content" to "$row $row",
            )
            for ((name, line) in invalid) {
                val damaged = good + (line + "\n").toByteArray()
                backing.writeBytes(damaged)
                val read = ToolExecutor.execute("get_goal", "{}", sid, app)
                assertFalse("$name: ${read.output}", read.success)
                assertTrue(read.output, read.output.contains("tool_checkpoint_unavailable"))
                assertArrayEquals(damaged, backing.readBytes())
                passed("$name is rejected through the shared goal tool without changing the valid neighboring intent")
            }
            val invalidUtf8 = good + "{\"callId\":\"".toByteArray() + byteArrayOf(0xc3.toByte(), 0x28) + "\"}\n".toByteArray()
            backing.writeBytes(invalidUtf8)
            assertFalse(ToolExecutor.execute("get_goal", "{}", sid, app).success)
            assertArrayEquals(invalidUtf8, backing.readBytes())
            passed("invalid UTF-8 is rejected rather than decoded into replacement characters")

            val canary = "CHECKPOINT_PRIVATE_${UUID.randomUUID()}"
            val privateDamage = good + "{\"callId\":\"private-damaged\",\"args\":\"$canary".toByteArray()
            backing.writeBytes(privateDamage)
            val privateRead = ToolExecutor.execute("get_goal", "{}", sid, app)
            assertFalse(privateRead.success); assertFalse(privateRead.output.contains(canary))
            val privateRun = AgentRunner.prompt(app, sid, "只报告不可读，不能披露原记录", wait = true, timeoutMs = 20_000)
            assertFalse(privateRun.completed); assertFalse(privateRun.responseText.orEmpty().contains(canary))
            val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("logcat -d -t 500 --pid=${android.os.Process.myPid()}")
            val logs = android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
            assertFalse("checkpoint parsing must not copy a private record into logs", logs.contains(canary))
            assertArrayEquals(privateDamage, backing.readBytes())
            passed("shared tool refusal and actual Agent failure do not copy private damaged-record contents into results or Android logs")

            backing.writeBytes(good)
            Os.chmod(backing.path, 0)
            try {
                assertFalse(backing.canRead())
                val read = ToolExecutor.execute("get_goal", "{}", sid, app)
                assertFalse(read.output, read.success)
                assertTrue(read.output, read.output.contains("tool_checkpoint_unavailable"))
            } finally { Os.chmod(backing.path, 384) }
            assertArrayEquals(good, backing.readBytes())
            passed("real App-UID read permission refusal remains an unavailable checkpoint and preserves bytes")
            val parent = backing.parentFile!!
            val parentMode = Os.stat(parent.path).st_mode and 511
            Os.chmod(parent.path, 0)
            try {
                val read = ToolExecutor.execute("get_goal", "{}", sid, app)
                assertFalse(read.output, read.success)
                assertTrue(read.output, read.output.contains("tool_checkpoint_unavailable"))
            } finally { Os.chmod(parent.path, parentMode) }
            assertArrayEquals(good, backing.readBytes())
            passed("an unreadable checkpoint parent is refused rather than classified as a missing file")
            assertTrue(backing.delete()); assertTrue(backing.mkdir())
            assertFalse(ToolExecutor.execute("get_goal", "{}", sid, app).success)
            assertTrue(backing.isDirectory); assertTrue(backing.delete())
            passed("a directory replacing the sidecar is refused and retained")
            Os.symlink("missing-$marker", backing.path)
            try { assertFalse(ToolExecutor.execute("get_goal", "{}", sid, app).success) }
            finally { assertTrue(backing.delete()) }
            passed("a dangling checkpoint link is unavailable rather than a new empty session")

            val damaged = good + "{broken\n".toByteArray()
            backing.writeBytes(damaged)
            ToolCheckpointStore.acknowledge(app, sid, setOf(execution))
            assertArrayEquals(damaged, backing.readBytes())
            ToolCheckpointStore.markDoneBatch(app, sid, mapOf("integrity-original-write" to true))
            assertArrayEquals(damaged, backing.readBytes())
            val blocked = ToolExecutor.execute("file_write", JSONObject().put("path", "/workspace/not-started.txt")
                .put("content", "must not execute").toString(), sid, app,
                modelExecution = ToolExecutor.ModelExecution("integrity-blocked-write", "integrity-gate-run", task.id,
                    task.revision, task.stopGeneration))
            assertFalse(blocked.success); assertEquals(ToolFailureKind.CHECKPOINT_FAILURE, blocked.failureKind)
            assertEquals(false, blocked.executionStarted)
            assertFalse(WorkspaceFileClient.info(sid, "/workspace/not-started.txt").optBoolean("exists"))
            assertArrayEquals(damaged, backing.readBytes())
            passed("exact ACK, legacy settlement and a real file-write execution gate preserve the entire damaged sidecar")

            val owner = checkNotNull(AgentSessionStore.existing(sid))
            withContext(Dispatchers.Main) {
                owner._lastTurnContextTokens.value = 1
                owner.sendMessage("页面入口也必须停止损坏现场的请求")
            }
            assertFalse(owner.isStreaming.value); assertEquals(0, provider.calls)
            val pressureResult = AgentRunner.prompt(app, sid, "程序化请求也必须明确拒绝", wait = true, timeoutMs = 15_000)
            assertFalse(pressureResult.completed); assertFalse(pressureResult.timedOut)
            assertTrue(pressureResult.toString(), pressureResult.responseText.orEmpty().contains("tool_checkpoint_unavailable"))
            assertEquals(0, provider.calls); assertArrayEquals(damaged, backing.readBytes())
            passed("page and programmatic pre-send pressure checks refuse corruption without throwing or leaving a hanging request")

            val other = session()
            val isolated = ScriptedProvider { call, _, _ -> if (call == 1) toolCall("integrity-isolated-write", "file_write",
                JSONObject().put("path", "/workspace/result.txt").put("content", "$marker independent session")) else text("独立会话已完成写入。") }
            prepare(other, isolated)
            assertTrue(AgentRunner.prompt(app, other, "另一会话仍可正常工作", wait = true, timeoutMs = 20_000).completed)
            assertEquals("$marker independent session", WorkspaceFileClient.readAll(other, "/workspace/result.txt").decodeToString())
            assertArrayEquals(damaged, backing.readBytes())
            passed("another real session can execute and persist its receipt while the damaged session remains untouched")

            val autoSid = session()
            AgentStateStore.goalSet(autoSid, "$marker preserve automatic continuation budget", app, 3)
            val autoTask = checkNotNull(app.botTaskRepository.sessionGoal(autoSid))
            val autoBacking = File(app.filesDir, "checkpoints/$autoSid.jsonl")
            val interrupted = ScriptedProvider { _, _, _ ->
                autoBacking.writeText("{broken during actual model response\n")
                text("当前回复结束，尚未验收目标。")
            }
            prepare(autoSid, interrupted)
            val autoResult = AgentRunner.prompt(app, autoSid, "不能在坏记录下自动接续", wait = true, timeoutMs = 20_000)
            assertFalse(autoResult.toString(), autoResult.completed); assertFalse(autoResult.timedOut)
            assertEquals(1, interrupted.calls)
            val afterAuto = checkNotNull(app.botTaskRepository.sessionGoal(autoSid))
            assertEquals(BotTaskEntity.STATUS_ACTIVE, afterAuto.status); assertNull(afterAuto.completionEvidence)
            assertEquals(autoTask.autoRunsUsed, afterAuto.autoRunsUsed)
            assertEquals("{broken during actual model response\n", autoBacking.readText())
            passed("damage introduced during an actual model response stops automatic continuation without consuming goal budget or completing the task")

            // Only restore this fixture's captured valid bytes; production never repairs by discarding damage.
            backing.writeBytes(good)
            AgentSessionStore.closeAndAwait(sid)
            withContext(Dispatchers.Main) { ChatViewModelStore.release(sid) }
            val recovered = ScriptedProvider { _, history, _ ->
                check(history.any { it.content.contains("[tool outcome unknown]") && it.content.contains(execution) })
                text("只观察保留的未知动作，不重放或完成等待任务。")
            }
            prepare(sid, recovered)
            assertTrue(AgentRunner.prompt(app, sid, "重建后只检查实际未知记录", wait = true, timeoutMs = 20_000).completed)
            assertEquals(execution, ToolCheckpointStore.drainPending(app, sid).single().intentId)
            assertArrayEquals(body, WorkspaceFileClient.readAll(sid, "/workspace/proof.txt"))
            val current = checkNotNull(app.botTaskRepository.sessionGoal(sid))
            assertEquals(BotTaskEntity.STATUS_WAITING, current.status); assertNull(current.completionEvidence)
            assertEquals(task.autoRunsUsed, current.autoRunsUsed)
            passed("owner rebuild after fixture restoration preserves the precise unknown execution, waiting task, budget and existing file")

            val legacy = JSONObject().put("callId", "legacy-pending").put("tool", "linux.shell").put("args", "truncated legacy arguments")
                .put("at", 1L).put("state", "pending")
            val reported = JSONObject(legacy.toString()).put("callId", "legacy-reported").put("state", "reported")
            backing.writeText("$legacy\n$reported\n{\"callId\":\"old-done\",\"state\":\"done\"}\n")
            val legacyRead = ToolExecutor.execute("get_goal", "{}", sid, app)
            assertTrue(legacyRead.output, legacyRead.success)
            val unknown = JSONObject(legacyRead.output).getJSONArray("unknown_outcomes")
            assertEquals(2, unknown.length())
            assertEquals(setOf("legacy-pending", "legacy-reported"), (0 until unknown.length())
                .map { unknown.getJSONObject(it).getString("call_id") }.toSet())
            assertEquals(3, backing.readLines().size)
            passed("legacy pending and reported rows remain unknown, while old done rows and missing new identity fields remain readable")
            File(app.filesDir, "agent-checkpoint-integrity-delivery.json").writeText(JSONObject().put("marker", marker)
                .put("execution_id", execution).put("damagedSha256", com.openminis.app.util.Sha256.hex(damaged))
                .put("restoredSha256", com.openminis.app.util.Sha256.hex(good))
                .put("damagedCheckpoint", damaged.decodeToString()).put("originalCheckpoint", good.decodeToString())
                .put("task_status", current.status)
                .put("auto_runs_used", current.autoRunsUsed).put("file", fingerprint).put("blocked_execution_started", blocked.executionStarted)
                .put("corruptProviderCalls", provider.calls).put("legacyUnknownCalls", JSONArray(listOf("legacy-pending", "legacy-reported")))
                .toString(2))
            report.put("passed", true)
        } catch (error: Throwable) { report.put("passed", false).put("failure", error.toString()); throw error }
        finally {
            sessions.forEach { sid ->
                AgentSessionStore.closeAndAwait(sid)
                withContext(Dispatchers.Main) { ChatViewModelStore.release(sid) }
                ToolCheckpointStore.clearSession(app, sid); SessionEventHub.clear(sid); app.chatRepository.deleteSession(sid)
            }
            File(app.filesDir, "agent-checkpoint-integrity-e2e.json").writeText(report.toString(2))
        }
    }
}
