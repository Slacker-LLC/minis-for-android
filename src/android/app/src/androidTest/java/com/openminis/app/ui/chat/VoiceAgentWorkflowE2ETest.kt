package com.openminis.app.ui.chat

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.model.*
import com.openminis.app.data.repository.awaitConfigLoaded
import com.openminis.app.deeplink.DeepLinkAction
import com.openminis.app.deeplink.DeepLinkHandler
import com.openminis.app.provider.LLMProvider
import com.openminis.app.voicecall.controller.CallController
import com.openminis.app.voicecall.model.BloubState
import com.openminis.app.voicecall.model.CallMode
import com.openminis.app.voicecall.ports.*
import com.openminis.app.voicecall.runtime.AgentCallGateway
import com.openminis.app.voicecall.runtime.MinisCallRuntimeAdapter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Scripted acoustic boundaries; real production gateway, Agent Loop, Room and Android tool dispatch. */
@RunWith(AndroidJUnit4::class)
class VoiceAgentWorkflowE2ETest {
    @Test
    fun voiceTurnsUseToolsAndHistoryAndRespectAudioLifecycle(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val report = JSONObject().put("device", android.os.Build.FINGERPRINT)
            .put("audioFixture", "scripted ASR/TTS; physical audio and OEM wake not verified")
            .put("modelFixture", "scripted provider; production Agent, Room and Android tool dispatch")
            .put("scenarios", JSONArray())
        val artifact = File(context.filesDir, "voice-agent-e2e.json")
        fun passed(name: String) {
            report.getJSONArray("scenarios").put(JSONObject().put("name", name).put("passed", true))
            artifact.writeText(report.toString(2))
        }
        try {
            for (link in listOf("minis://action/voice", "minis://action/voice_chat"))
                assertEquals(DeepLinkAction.NewVoiceChat, DeepLinkHandler.parse(Uri.parse(link)))
            passed("old and new assistant URIs enter voice conversation")
            val scripted = ScriptedProvider { call, _, _ -> when (call) {
                1 -> toolCall("voice-time", "android.time")
                2 -> text("第一轮任务已完成")
                else -> text("第 $call 次回答")
            } }
            withVoiceSession(scripted) { vm, repository, provider ->
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
                val input = ScriptedInput()
                val output = ScriptedOutput()
                val controller = withContext(Dispatchers.Main) {
                    CallController(MinisCallRuntimeAdapter(input, AgentCallGateway(vm.agentSession), output, scope), scope)
                }
                suspend fun ui(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
                suspend fun until(condition: () -> Boolean) = withTimeout(30_000L) { while (!condition()) delay(30) }
                try {
                    ui { controller.startCall(CallMode.VOICE) }
                    until { input.rounds.size == 1 }
                    val first = input.rounds.last()
                    ui { first.final("现在几点？"); first.final("重复回调不可再提交") }
                    until { output.texts.size == 1 }
                    assertEquals(2, provider.calls)
                    assertTrue("android.time" in provider.offeredTools)
                    assertTrue(provider.systemPrompts.first().orEmpty().isNotBlank())
                    assertNull(repository.getSession(vm.currentSessionId)?.legacyProfileId)
                    val result = provider.requests[1].flatMap { it.contentParts }
                        .filterIsInstance<AgentContentPart.ToolResult>().single { it.id == "voice-time" }
                    assertFalse(result.isError)
                    assertTrue(result.content.isNotBlank())
                    assertEquals("第一轮任务已完成", output.texts.single())
                    passed("speech -> real Agent -> Android tool -> persisted answer -> TTS; duplicate final rejected")
                    ui { output.finish() }
                    until { input.rounds.size == 2 }
                    ui { first.final("上一轮晚到回调") }
                    delay(200)
                    assertEquals(2, provider.calls)
                    ui { input.rounds.last().final("继续，记住上一轮") }
                    until { output.texts.size == 2 }
                    val history = provider.requests.last().joinToString("\n") { it.content }
                    assertTrue(history.contains("现在几点？") && history.contains("第一轮任务已完成"))
                    assertTrue(repository.dao.loadMessages(vm.currentSessionId).any { it.role == "user" && it.partsJson.contains("继续，记住上一轮") })
                    passed("next utterance shares chat history and Room; stale recognition rejected")
                    ui { controller.setMuted(true) }
                    delay(150)
                    assertEquals(2, input.rounds.size)
                    ui { controller.setMuted(false) }
                    until { input.rounds.size == 3 }
                    passed("muting playback suppresses mic restart; explicit unmute resumes")
                    var gate = CompletableDeferred<Unit>()
                    var waiting = false
                    var foreignStepProduced = false
                    val blocked = object : LLMProvider {
                        override val name = provider.name
                        override var model = provider.model
                        override suspend fun sendMessageClamped(
                            messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int, temperature: Double?,
                            imageParts: List<LLMMessage.ImagePart>, tools: List<AgentToolDefinition>, thinkingLevel: ThinkingLevel,
                        ): LLMResponse = provider.sendMessageClamped(messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel)
                        override fun streamMessageClamped(
                            messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int, temperature: Double?,
                            imageParts: List<LLMMessage.ImagePart>, tools: List<AgentToolDefinition>, thinkingLevel: ThinkingLevel,
                        ): Flow<LLMStreamChunk> = flow {
                            waiting = true
                            gate.await()
                            if (!foreignStepProduced && messages.any { it.content.contains("其它入口的任务") }) {
                                foreignStepProduced = true
                                toolCall("voice-foreign-time", "android.time").forEach { emit(it) }
                            } else emitAll(provider.streamMessageClamped(messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel))
                        }
                    }
                    ui {
                        vm.currentProvider = blocked
                        input.rounds.last().final("执行任务，静音不能取消它")
                    }
                    until { waiting && vm.isStreaming.value }
                    ui { controller.setMuted(true) }
                    assertTrue(vm.isStreaming.value)
                    ui { controller.setMuted(false) }
                    assertEquals(3, input.rounds.size)
                    ui { controller.listenForFollowUp() }
                    assertTrue("follow-up listening must retain the agent task", vm.isStreaming.value)
                    assertEquals(4, input.rounds.size)
                    ui { controller.endCall() }
                    assertTrue("hangup leaves accepted task running", vm.isStreaming.value)
                    gate.complete(Unit)
                    until { !vm.isStreaming.value }
                    assertTrue(repository.dao.loadMessages(vm.currentSessionId).any { it.partsJson.contains("第 4 次回答") })
                    delay(150)
                    assertEquals(4, input.rounds.size)
                    assertEquals(2, output.texts.size)
                    passed("mute, follow-up listening and hangup retain task; late completion cannot restart audio")
                    gate = CompletableDeferred()
                    waiting = false
                    ui { controller.startCall(CallMode.VOICE); input.rounds.last().final("这次任务要打断") }
                    until { waiting && vm.isStreaming.value }
                    val stopped = input.rounds.last()
                    ui { controller.interrupt() }
                    until { !vm.isStreaming.value }
                    assertEquals(BloubState.LISTENING, controller.state.ai.bloubState)
                    ui { stopped.final("取消任务的晚到回调") }
                    delay(150)
                    assertFalse(vm.isStreaming.value)
                    passed("explicit interrupt cancels owned turn and invalidates its late callbacks")
                    waiting = false
                    ui { vm.submitPrompt("其它入口的任务") }
                    until { waiting && vm.isStreaming.value }
                    val draft = InputAttachment(fileName = "unsent.txt", uri = Uri.parse("content://voice-test/unsent"), mimeType = "text/plain", kind = InputAttachment.Kind.DOCUMENT)
                    ui { vm.addAttachment(draft); input.rounds.last().final("会话忙时补充要求，不要抢占") }
                    until { output.texts.size == 3 }
                    assertTrue(output.texts.last().contains("补充指令"))
                    assertEquals(listOf(draft), vm.attachments.value)
                    val queued = vm.messages.value.single { it.isQueued }
                    assertTrue(queued.attachmentNames.isEmpty())
                    ui { controller.interrupt() }
                    assertTrue("interrupt cannot cancel foreign task", vm.isStreaming.value)
                    ui { controller.endCall() }
                    gate.complete(Unit)
                    until { !vm.isStreaming.value }
                    assertTrue(provider.requests.last().any { it.content.contains("会话忙时补充要求") })
                    passed("busy voice follow-up steers the same task; unsent attachments retained; foreign task cannot be cancelled")
                    ui { controller.startCall(CallMode.VOICE) }
                    val count = input.rounds.size
                    ui { input.rounds.last().error(RecognitionError.PERMISSION_DENIED, "microphone denied") }
                    until { controller.state.ai.bloubState == BloubState.ERROR }
                    delay(600)
                    assertEquals(count, input.rounds.size)
                    ui { controller.interrupt() }
                    assertEquals(count + 1, input.rounds.size)
                    ui { controller.endCall() }
                    passed("recognition failure waits for explicit retry; hangup releases audio")
                } finally {
                    ui { controller.endCall() }
                    scope.cancel()
                }
            }
            report.put("passed", true)
        } catch (error: Throwable) {
            report.put("passed", false).put("failure", error.toString())
            throw error
        } finally { artifact.writeText(report.toString(2)) }
    }
    private suspend fun withVoiceSession(provider: ScriptedProvider,
        block: suspend (ChatViewModel, com.openminis.app.data.repository.ChatRepository, ScriptedProvider) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as com.openminis.app.MinisApp
        app.providerRepository.awaitConfigLoaded()
        val session = app.chatRepository.createSession("scripted-model", memoryEnabled = false)
        try {
            val vm = withContext(Dispatchers.Main) { chatProjection(app, session.id) }
            withTimeout(20_000) { vm.sessionLoaded.first { it } }
            withContext(Dispatchers.Main) { vm.currentProvider = provider; vm.currentModel = provider.model; vm._activeEntryId.value = "voice-scripted" }
            block(vm, app.chatRepository, provider)
        } finally {
            withContext(Dispatchers.Main) { com.openminis.app.agent.AgentSessionStore.closeAndAwait(session.id); com.openminis.app.agent.AgentSessionStore.release(session.id) }
            app.chatRepository.deleteSession(session.id)
        }
    }
    private class ScriptedInput : CallSpeechInput {
        data class Round(val final: (String) -> Unit, val error: (RecognitionError, String?) -> Unit)
        val rounds = mutableListOf<Round>()
        override fun start(onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (RecognitionError, String?) -> Unit) { rounds.add(Round(onFinal, onError)) }
        override fun stop() {}
        override fun cancel() {}
    }
    private class ScriptedOutput : CallSpeechOutput {
        val texts = mutableListOf<String>()
        private var completion: CompletableDeferred<Unit>? = null
        override suspend fun speak(text: String) {
            texts.add(text)
            val done = CompletableDeferred<Unit>()
            completion = done
            done.await()
        }
        fun finish() { completion?.complete(Unit) }
        override fun stop() { finish() }
    }
}
