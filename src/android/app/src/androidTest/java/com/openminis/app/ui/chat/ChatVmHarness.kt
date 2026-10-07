package com.openminis.app.ui.chat

import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.provider.LLMProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import com.openminis.app.data.repository.awaitConfigLoaded

/**
 * A real [ChatViewModel] over an in-memory Room database with a scripted model. The agent-loop
 * characterization tests use it to pin what the loop does today, so the loop can be taken apart
 * without changing that behaviour.
 */
internal suspend fun withChatVm(
    chatOnly: Boolean,
    provider: ScriptedProvider,
    sessionTitle: String? = "Characterization",
    block: suspend (ChatViewModel, ChatRepository, ScriptedProvider) -> Unit,
) {
    val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
    app.providerRepository.awaitConfigLoaded()
    val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
    val repository = ChatRepository(db.chatDao())
    val session = repository.createSession("scripted-model", title = sessionTitle)
    val store = ViewModelStore()
    val vm = withContext(Dispatchers.Main) {
        ChatViewModel(session.id, repository, app.providerRepository, app).also { store.put("test", it) }
    }
    try {
        @Suppress("UNCHECKED_CAST")
        val loaded = ChatViewModel::class.java.getDeclaredField("sessionLoaded").apply { isAccessible = true }
            .get(vm) as StateFlow<Boolean>
        withTimeout(10_000L) { loaded.first { it } }
        withContext(Dispatchers.Main) {
            ChatViewModel::class.java.getDeclaredField("currentProvider").apply { isAccessible = true }.set(vm, provider)
            ChatViewModel::class.java.getDeclaredField("currentModel").apply { isAccessible = true }.set(vm, provider.model)
            vm.chatOnlyForNextTurn = chatOnly
        }
        block(vm, repository, provider)
    } finally {
        withContext(Dispatchers.Main) { store.clear() }
        vm.awaitStreamExit(5_000L)
        SessionEventHub.clear(session.id)
        db.close()
    }
}

/** A model whose every request is decided by [script], which sees the request number and the offered tools. */
internal class ScriptedProvider(
    private val script: (call: Int, messages: List<LLMMessage>, tools: List<AgentToolDefinition>) -> List<LLMStreamChunk>,
) : LLMProvider {
    override val name = "Scripted provider"
    override var model = LLMModel("scripted-model", "Scripted model", "scripted", contextWindow = 32768)
    @Volatile var calls = 0
    @Volatile var requests = listOf<List<LLMMessage>>()
    @Volatile var offeredTools = listOf<String>()
    @Volatile var titleRequests = 0

    override suspend fun sendMessageClamped(
        messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int, temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>, tools: List<AgentToolDefinition>, thinkingLevel: ThinkingLevel,
    ): LLMResponse {
        titleRequests++
        return LLMResponse("Scripted title", "stop", null)
    }

    override fun streamMessageClamped(
        messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int, temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>, tools: List<AgentToolDefinition>, thinkingLevel: ThinkingLevel,
    ): Flow<LLMStreamChunk> = flow {
        val n = ++calls
        requests = requests + listOf(messages.toList())
        offeredTools = tools.map { it.name }
        script(n, messages, tools).forEach { emit(it) }
    }
}

internal fun text(s: String) = listOf<LLMStreamChunk>(LLMStreamChunk.Text(s), LLMStreamChunk.Finished("stop"))

internal fun toolCall(id: String, name: String, args: JSONObject = JSONObject()) = listOf<LLMStreamChunk>(
    LLMStreamChunk.ToolUseStart(id, name),
    LLMStreamChunk.ToolCallComplete(id, name, args),
    LLMStreamChunk.Finished("tool_use"),
)
