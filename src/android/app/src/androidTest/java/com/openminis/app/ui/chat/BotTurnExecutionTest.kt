package com.openminis.app.ui.chat

import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.agent.AgentTurnHandle
import com.openminis.app.agent.AgentTurnOutcome
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.model.*
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.provider.LLMProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import com.openminis.app.data.repository.awaitConfigLoaded

/** Uses the real VM/Room loop with an in-process provider; no network or root tools. */
@RunWith(AndroidJUnit4::class)
class BotTurnExecutionTest {
    private suspend fun withVm(block: suspend (ChatViewModel, ChatRepository, ControlledProvider) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        app.providerRepository.awaitConfigLoaded()
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        val repository = ChatRepository(db.chatDao())
        val session = repository.createSession("controlled-model", title = "Controlled turn test")
        val store = ViewModelStore()
        val provider = ControlledProvider()
        val vm = withContext(Dispatchers.Main) {
            ChatViewModel(session.id, repository, app.providerRepository, app).also { store.put("test", it) }
        }
        try {
            @Suppress("UNCHECKED_CAST")
            val loaded = ChatViewModel::class.java.getDeclaredField("sessionLoaded").apply { isAccessible = true }
                .get(vm) as StateFlow<Boolean>
            withTimeout(10_000L) { loaded.first { it } }
            withContext(Dispatchers.Main) {
                // Keep production construction unchanged; replace only the test's transport.
                ChatViewModel::class.java.getDeclaredField("currentProvider").apply { isAccessible = true }.set(vm, provider)
                ChatViewModel::class.java.getDeclaredField("currentModel").apply { isAccessible = true }.set(vm, provider.model)
                vm.chatOnlyForNextTurn = true
            }
            block(vm, repository, provider)
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            vm.awaitStreamExit(5_000L)
            SessionEventHub.clear(session.id)
            db.close()
        }
    }

    @Test
    fun failedTurnDoesNotReusePreviousSuccessAndCancellationWaitsForExit() = runBlocking {
        withVm { vm, _, provider ->
            val first = withContext(Dispatchers.Main) { vm.submitPrompt("First request") }
            assertEquals(AgentTurnOutcome.Completed, withTimeout(15_000L) { first.result.await() })
            provider.mode = "error"
            val failed = withContext(Dispatchers.Main) { vm.submitPrompt("Fail this request") }
            val failure = withTimeout(15_000L) { failed.result.await() }
            assertTrue(failure.toString(), failure is AgentTurnOutcome.Failed)
            assertTrue((failure as AgentTurnOutcome.Failed).reason.contains("controlled_failure"))
            assertNotEquals(first.userMessageId, failed.userMessageId)
            provider.mode = "wait"
            val cancelled = withContext(Dispatchers.Main) { vm.submitPrompt("Wait until stopped") }
            withTimeout(10_000L) { provider.waiting.await() }
            cancelled.cancel()
            assertEquals(AgentTurnOutcome.Cancelled, withTimeout(10_000L) { cancelled.result.await() })
            assertTrue(vm.awaitStreamExit(1L))
        }
    }

    @Test
    fun liveReceiptIsVisibleOnceAndIncludedOnceInTheNextModelRequest() = runBlocking {
        withVm { vm, repository, provider ->
            val body = "[Bot 委托结果]\n委托 ID：controlled-receipt\n状态：执行结束\nReview found one failing test."
            val receipt = repository.appendMessage(vm.currentSessionId, "assistant",
                JSONArray().put(JSONObject().put("type", "text").put("value", body)).toString())
            withContext(Dispatchers.Main) {
                vm.acceptExternalMessage(receipt)
                vm.acceptExternalMessage(receipt)
            }
            withTimeout(10_000L) { vm.messages.first { messages -> messages.any { it.id == receipt.id } } }
            assertEquals(1, vm.messages.value.count { it.id == receipt.id })
            val followUp = withContext(Dispatchers.Main) { vm.submitPrompt("Summarize the review") }
            assertEquals(AgentTurnOutcome.Completed, withTimeout(15_000L) { followUp.result.await() })
            assertEquals(1, provider.lastMessages.count { it.dbMessageId == receipt.id })
            assertTrue(provider.lastMessages.any { it.content.contains("one failing test") })
            assertEquals(1, repository.dao.loadMessages(vm.currentSessionId).count { it.id == receipt.id })
        }
    }

    @Test
    fun partialStreamAndOutputLimitAreNotReportedAsCompleted() = runBlocking {
        withVm { vm, _, provider ->
            provider.mode = "partial"
            val partial = withContext(Dispatchers.Main) { vm.submitPrompt("Partial request") }
            assertTrue(withTimeout(15_000L) { partial.result.await() } is AgentTurnOutcome.Failed)
            provider.mode = "length"
            val limited = withContext(Dispatchers.Main) { vm.submitPrompt("Limited request") }
            assertEquals(AgentTurnOutcome.NeedsAttention("model_finish_reason:length"),
                withTimeout(15_000L) { limited.result.await() })
        }
    }

    @Test
    fun retryReportsItsOwnOutcomeNotTheSessionBusyFlag() = runBlocking {
        withVm { vm, _, provider ->
            val first = withContext(Dispatchers.Main) { vm.submitPrompt("Retry me") }
            assertEquals(AgentTurnOutcome.Completed, withTimeout(15_000L) { first.result.await() })
            val userId = first.userMessageId!!

            // A retry that fails must not read as completed.
            provider.mode = "error"
            val failed = AgentTurnHandle()
            assertTrue(withContext(Dispatchers.Main) { vm.retryFromMessage(userId, failed) })
            val failure = withTimeout(15_000L) { failed.result.await() }
            assertTrue(failure.toString(), failure is AgentTurnOutcome.Failed)

            // While another turn runs, a retry is refused and says so at once.
            provider.mode = "wait"
            val running = withContext(Dispatchers.Main) { vm.submitPrompt("Keep the session busy") }
            withTimeout(10_000L) { provider.waiting.await() }
            val refused = AgentTurnHandle()
            assertFalse(withContext(Dispatchers.Main) { vm.retryFromMessage(userId, refused) })
            assertEquals(AgentTurnOutcome.Rejected("session_busy"), withTimeout(1_000L) { refused.result.await() })
            running.cancel()
            assertEquals(AgentTurnOutcome.Cancelled, withTimeout(10_000L) { running.result.await() })

            // Cancelling the retry's own handle stops that retry and the stream exits.
            val cancelled = AgentTurnHandle()
            assertTrue(withContext(Dispatchers.Main) { vm.retryFromMessage(userId, cancelled) })
            delay(500)
            cancelled.cancel()
            assertEquals(AgentTurnOutcome.Cancelled, withTimeout(10_000L) { cancelled.result.await() })
            assertTrue(vm.awaitStreamExit(5_000L))
        }
    }

    @Test
    fun aProgrammaticPromptLeavesTheUsersDraftAndEditAlone() = runBlocking {
        withVm { vm, _, _ ->
            val first = withContext(Dispatchers.Main) { vm.submitPrompt("Earlier message") }
            assertEquals(AgentTurnOutcome.Completed, withTimeout(15_000L) { first.result.await() })
            val draft = InputAttachment(
                fileName = "draft.txt",
                uri = android.net.Uri.parse("content://test/draft.txt"),
                mimeType = "text/plain",
                kind = InputAttachment.Kind.DOCUMENT,
            )
            withContext(Dispatchers.Main) {
                assertNotNull("the user starts editing the earlier message", vm.editMessage(first.userMessageId!!))
                // editMessage loads the edited message's own attachments; this one is added while editing.
                vm.addAttachment(draft)
            }

            // A background callback arrives while the user is composing.
            val callback = withContext(Dispatchers.Main) { vm.submitPrompt("[Background task finished] result") }
            assertEquals(AgentTurnOutcome.Completed, withTimeout(15_000L) { callback.result.await() })

            assertEquals("the draft attachment stays in the composer", listOf(draft), vm.attachments.value)
            assertEquals("the edit is still the user's", first.userMessageId, vm.editingMessageId.value)
            assertTrue(
                "the earlier message was not truncated away",
                vm.messages.value.any { it.id == first.userMessageId },
            )
        }
    }

    private class ControlledProvider : LLMProvider {
        override val name = "Controlled provider"
        override var model = LLMModel("controlled-model", "Controlled model", "controlled", contextWindow = 32768)
        @Volatile var mode = "complete"
        @Volatile var lastMessages = emptyList<LLMMessage>()
        val waiting = CompletableDeferred<Unit>()

        override suspend fun sendMessageClamped(messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int,
            temperature: Double?, imageParts: List<LLMMessage.ImagePart>, tools: List<AgentToolDefinition>,
            thinkingLevel: ThinkingLevel) = LLMResponse("Controlled title", "stop", null)

        override fun streamMessageClamped(messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int,
            temperature: Double?, imageParts: List<LLMMessage.ImagePart>, tools: List<AgentToolDefinition>,
            thinkingLevel: ThinkingLevel): Flow<LLMStreamChunk> = flow {
            lastMessages = messages.toList()
            if (mode == "error") throw IllegalStateException("controlled_failure")
            if (mode == "wait") {
                waiting.complete(Unit)
                awaitCancellation()
            }
            emit(LLMStreamChunk.Text("Controlled reply"))
            if (mode != "partial") emit(LLMStreamChunk.Finished(if (mode == "length") "length" else "stop"))
        }
    }
}
