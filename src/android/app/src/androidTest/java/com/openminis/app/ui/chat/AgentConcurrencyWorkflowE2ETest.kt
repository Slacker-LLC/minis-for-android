package com.openminis.app.ui.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.agent.AgentRunner
import com.openminis.app.agent.AgentTurnHandle
import com.openminis.app.agent.AgentTurnOutcome
import com.openminis.app.data.model.*
import com.openminis.app.data.repository.awaitConfigLoaded
import com.openminis.app.provider.LLMProvider
import com.openminis.app.runtime.ubuntu.UbuntuPaths
import com.openminis.app.service.SessionConcurrencyManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Saturate real AgentRunner sessions and cancel exactly when the queued session is granted a slot. */
@RunWith(AndroidJUnit4::class)
class AgentConcurrencyWorkflowE2ETest {
    @Test
    fun queuedAndGrantedCancellationReleaseTheirSlots(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        app.providerRepository.awaitConfigLoaded()
        UbuntuPaths.initialize(app)
        val sessions = (0..SessionConcurrencyManager.MAX_CONCURRENT).map {
            app.chatRepository.createSession("slot-scripted", title = "Agent slot E2E $it")
        }
        val providers = sessions.map { SlotProvider() }
        val requests = mutableListOf<AgentTurnHandle>()
        val report = JSONObject().put("device", android.os.Build.FINGERPRINT).put("scenarios", JSONArray())
        val artifact = File(app.filesDir, "agent-concurrency-e2e.json")
        fun passed(name: String) {
            report.getJSONArray("scenarios").put(JSONObject().put("name", name).put("passed", true))
        }
        suspend fun ui(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
        suspend fun until(predicate: () -> Boolean) = withTimeout(20_000L) { while (!predicate()) delay(10) }
        try {
            assertTrue("previous workflows must return every slot", SessionConcurrencyManager.runningSessions.value.isEmpty())
            val models = sessions.mapIndexed { index, session ->
                val vm = withContext(Dispatchers.Main) { chatProjection(app, session.id) }
                withTimeout(20_000L) { vm.sessionLoaded.first { it } }
                ui {
                    vm.currentProvider = providers[index]
                    vm.currentModel = providers[index].model
                    vm._activeEntryId.value = "slot-scripted-entry"
                }
                vm
            }
            for (index in 0 until SessionConcurrencyManager.MAX_CONCURRENT) {
                requests += withContext(Dispatchers.Main) { AgentRunner.submit(models[index].agentSession, "Hold slot $index") }
            }
            withTimeout(20_000L) { providers.dropLast(1).forEach { it.started.await() } }
            val target = sessions.last().id
            val queued = withContext(Dispatchers.Main) { AgentRunner.submit(models.last().agentSession, "Cancel while queued") }
            requests += queued
            until { SessionConcurrencyManager.isSuspended(target) }
            queued.cancel()
            assertEquals(AgentTurnOutcome.Cancelled, withTimeout(20_000L) { queued.result.await() })
            assertFalse(SessionConcurrencyManager.isSuspended(target))
            assertFalse(target in SessionConcurrencyManager.runningSessions.value)
            assertEquals(SessionConcurrencyManager.MAX_CONCURRENT, SessionConcurrencyManager.runningSessions.value.size)
            passed("queued request cancellation leaves active holders intact and removes its waiter")

            val granted = withContext(Dispatchers.Main) { AgentRunner.submit(models.last().agentSession, "Cancel when granted before acquire returns") }
            requests += granted
            until { SessionConcurrencyManager.isSuspended(target) }
            // This observer runs inline at the real grant announcement, before continuation
            // resumption. It deterministically exercises prompt cancellation, without a fake slot manager.
            val cancelAtGrant = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                SessionConcurrencyManager.runningSessions.first { target in it }
                granted.cancel()
            }
            providers.first().gate.complete(Unit)
            withTimeout(20_000L) { cancelAtGrant.join() }
            assertEquals(AgentTurnOutcome.Cancelled, withTimeout(20_000L) { granted.result.await() })
            assertFalse("a grant cancelled before acquire returns must not leak", target in SessionConcurrencyManager.runningSessions.value)
            assertFalse(SessionConcurrencyManager.isSuspended(target))
            assertEquals(SessionConcurrencyManager.MAX_CONCURRENT - 1, SessionConcurrencyManager.runningSessions.value.size)
            assertFalse(providers.last().started.isCompleted)
            passed("cancellation at actual slot grant returns the permit before any model call")

            providers.last().gate.complete(Unit)
            val next = withContext(Dispatchers.Main) { AgentRunner.submit(models.last().agentSession, "Run again after granted cancellation") }
            requests += next
            assertEquals(AgentTurnOutcome.Completed, withTimeout(20_000L) { next.result.await() })
            assertTrue(providers.last().started.isCompleted)
            assertFalse(target in SessionConcurrencyManager.runningSessions.value)
            passed("same session can complete a subsequent turn while other sessions retain their slots")
            report.put("passed", true)
        } catch (error: Throwable) {
            report.put("passed", false).put("failure", error.toString())
            throw error
        } finally {
            providers.forEach { it.gate.complete(Unit) }
            requests.forEach { withTimeout(20_000L) { it.result.await() } }
            ui { sessions.forEach { AgentRunner.forget(it.id) } }
            sessions.forEach {
                SessionEventHub.clear(it.id)
                app.chatRepository.deleteSession(it.id)
            }
            artifact.writeText(report.toString(2))
        }
    }

    private class SlotProvider : LLMProvider {
        override val name = "Slot controlled provider"
        override var model = LLMModel("slot-scripted", "Slot scripted", "scripted", contextWindow = 128000)
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        override suspend fun sendMessageClamped(messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int,
            temperature: Double?, imageParts: List<LLMMessage.ImagePart>, tools: List<AgentToolDefinition>, thinkingLevel: ThinkingLevel) =
            LLMResponse("Slot title", "stop", null)
        override fun streamMessageClamped(messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int,
            temperature: Double?, imageParts: List<LLMMessage.ImagePart>, tools: List<AgentToolDefinition>, thinkingLevel: ThinkingLevel): Flow<LLMStreamChunk> = flow {
            started.complete(Unit)
            gate.await()
            emit(LLMStreamChunk.Text("Slot turn completed"))
            emit(LLMStreamChunk.Finished("stop"))
        }
    }
}
