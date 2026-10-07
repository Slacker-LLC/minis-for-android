package com.openminis.app.agent.subagents

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.agent.AgentRunner
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.ModelsDevApi
import com.openminis.app.data.model.ModelSlot
import com.openminis.app.data.model.SubAgentDefinition
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.openminis.app.ui.chat.enqueuePrompt

/**
 * The app side of [SubAgentPort]: child sessions are ordinary sessions driven through [AgentRunner]
 * (the same loop the chat and the scheduled tasks use), so a sub agent gets the full tool set, its
 * own session workspace and the app's permission gates without a second agent implementation.
 */
class AppSubAgentPort(private val context: Context) : SubAgentPort {
    private val app: MinisApp get() = context.applicationContext as MinisApp

    override fun roster(): List<SubAgentDefinition> = SubAgentStore.currentRoster()

    override fun callableModels(): List<CallableModel> = SubAgents.callableModels(app.providerRepository)

    override suspend fun resolveModel(
        def: SubAgentDefinition,
        choice: SubAgentModelChoice,
        parentSessionId: String,
    ): SubAgentModel? {
        val repo = app.providerRepository
        val usable = repo.allVisibleEntries().filter { it.model.isTextOutput && isUsable(it) }

        // A pinned model must still exist; there is no silent fallback, because the user chose it.
        def.pinnedEntryId?.let { pinned ->
            return usable.firstOrNull { it.id == pinned }?.let { SubAgentModel(it.id, it.model.displayName, ORIGIN_AGENT) }
        }

        fun slot(slot: ModelSlot): ModelEntry? =
            repo.availableEntries(slot).firstOrNull { it.model.isTextOutput && isUsable(it) }

        val entry: ModelEntry? = when (choice) {
            SubAgentModelChoice.SAME_AS_ME -> {
                val parentEntryId = withContext(Dispatchers.Main) {
                    AgentRunner.viewModelForCommand(context, parentSessionId).activeEntryId.value
                }
                usable.firstOrNull { it.id == parentEntryId } ?: slot(ModelSlot.main)
            }
            SubAgentModelChoice.DEFAULT_MODEL -> slot(ModelSlot.main)
            SubAgentModelChoice.SUB_MODEL -> slot(ModelSlot.light) ?: slot(ModelSlot.main)
        }
        return entry?.let { SubAgentModel(it.id, it.model.displayName, choice.wire) }
    }

    private fun isUsable(entry: ModelEntry): Boolean {
        val repo = app.providerRepository
        val instance = repo.instance(entry.providerInstanceId) ?: return false
        return instance.isEnabled && repo.hasAnyCredential(instance)
    }

    override suspend fun createChild(parentSessionId: String, title: String, model: SubAgentModel): String {
        val childId = AgentRunner.ensureSession(context, null)
        app.chatRepository.updateSessionTitle(childId, title)
        app.chatRepository.dao.updateSource(childId, ChatSessionEntity.SOURCE_SUB_AGENT)
        model.entryId?.let { AgentRunner.applyModelOverride(context, childId, it) }
        AppLogger.info(TAG, "child ${childId.take(8)} for parent ${parentSessionId.take(8)} model=${model.label}")
        return childId
    }

    override suspend fun runChild(
        childSessionId: String,
        brief: String,
        thinking: ThinkingLevel?,
        timeoutMs: Long,
    ): ChildOutcome {
        val result = AgentRunner.prompt(
            context = context,
            sessionId = childSessionId,
            text = brief,
            thinkingLevel = thinking,
            wait = true,
            timeoutMs = timeoutMs,
        )
        return ChildOutcome(
            completed = result.status.equals("Completed", ignoreCase = true) && !result.timedOut,
            text = result.responseText,
            timedOut = result.timedOut,
        )
    }

    override suspend fun describeChild(childSessionId: String): ChildProgress? = withContext(Dispatchers.IO) {
        val rows = app.chatRepository.dao.loadMessages(childSessionId)
        if (rows.isEmpty()) return@withContext null
        val assistants = rows.filter { it.role == "assistant" }
        val lastText = assistants.asReversed().firstNotNullOfOrNull { textOf(it.partsJson) }
        // A tool the child started and has not got a result for yet is the one it is in right now.
        val lastAssistant = assistants.lastOrNull()
        val resultIds = rows.filter { it.role == "user" }.flatMap { toolResultIds(it.partsJson) }.toSet()
        val currentTool = lastAssistant?.let { toolUses(it.partsJson) }
            ?.lastOrNull { it.first !in resultIds }?.second
        ChildProgress(currentTool, lastText)
    }

    override suspend fun wrapUpChild(childSessionId: String, graceMs: Long): String? {
        AgentRunner.cancel(context, childSessionId)
        val result = AgentRunner.prompt(
            context = context,
            sessionId = childSessionId,
            text = SubAgentTask.WRAP_UP_PROMPT,
            chatOnly = true,
            wait = true,
            timeoutMs = graceMs,
        )
        return result.responseText?.takeIf { result.status.equals("Completed", ignoreCase = true) }
    }

    override suspend fun cancelChild(childSessionId: String): Boolean = AgentRunner.cancel(context, childSessionId)

    /**
     * Queues the correction into the child's running loop (read at its next turn). The child has no
     * composer, so unlike a user's own queued message it carries no attachments.
     */
    override suspend fun steerChild(childSessionId: String, message: String): Boolean =
        withContext(Dispatchers.Main) {
            val vm = AgentRunner.viewModelForCommand(context, childSessionId)
            if (!vm.isStreaming.value) return@withContext false
            vm.enqueuePrompt("[Correction from the delegating agent] $message")
            true
        }

    /**
     * Posts the result into the delegating conversation as a new user turn. A headless prompt is
     * refused while the conversation is mid-turn, so this waits for it to settle first instead of
     * interrupting it — and instead of queuing through the composer, which would also swallow
     * whatever the user has attached there.
     */
    override suspend fun deliverToParent(parentSessionId: String, text: String) {
        repeat(DELIVERY_ATTEMPTS) { attempt ->
            if (app.chatRepository.getSession(parentSessionId) == null) {
                AppLogger.warning(TAG, "parent ${parentSessionId.take(8)} is gone; dropping the callback")
                return
            }
            AgentRunner.waitForSettle(context, parentSessionId, SETTLE_WAIT_MS)
            val result = AgentRunner.prompt(
                context = context,
                sessionId = parentSessionId,
                text = text,
                wait = false,
                timeoutMs = SETTLE_WAIT_MS,
            )
            val busy = result.status.equals("Busy", ignoreCase = true) ||
                result.responseText == "session_busy" || result.responseText == "session_compacting"
            if (!busy && result.status.let { it.equals("Running", true) || it.equals("Completed", true) }) return
            AppLogger.info(TAG, "callback to ${parentSessionId.take(8)} not accepted (${result.status}/${result.responseText}); retry ${attempt + 1}")
            delay(RETRY_DELAY_MS)
        }
        AppLogger.warning(TAG, "callback to ${parentSessionId.take(8)} could not be delivered")
    }

    private fun textOf(partsJson: String): String? = runCatching {
        val arr = org.json.JSONArray(partsJson)
        buildString {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("type") == "text") append(o.optString("value", ""))
            }
        }.trim().ifEmpty { null }
    }.getOrNull()

    /** (tool use id, tool name) for each tool call in a message. */
    private fun toolUses(partsJson: String): List<Pair<String, String>> = runCatching {
        val arr = org.json.JSONArray(partsJson)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            if (o.optString("type") != "toolUse") return@mapNotNull null
            val v = o.optJSONObject("value") ?: return@mapNotNull null
            v.optString("toolUseId") to v.optString("name")
        }
    }.getOrDefault(emptyList())

    private fun toolResultIds(partsJson: String): List<String> = runCatching {
        val arr = org.json.JSONArray(partsJson)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            if (o.optString("type") != "toolResult") return@mapNotNull null
            o.optJSONObject("value")?.optString("toolUseId")
        }
    }.getOrDefault(emptyList())

    companion object {
        private const val TAG = "SubAgents"
        private const val ORIGIN_AGENT = "agent"
        private const val DELIVERY_ATTEMPTS = 20
        private const val SETTLE_WAIT_MS = 10 * 60 * 1000L
        private const val RETRY_DELAY_MS = 3_000L
    }
}

/** Process-wide wiring: one registry and runtime shared by every conversation. */
object SubAgents {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var runtime: SubAgentRuntime? = null

    fun runtime(context: Context): SubAgentRuntime =
        runtime ?: synchronized(this) {
            runtime ?: SubAgentRuntime(
                AppSubAgentPort(context.applicationContext),
                SubAgentJobRegistry(store = PrefsSubAgentJobStore(context.applicationContext)),
                scope,
            )
                .also { runtime = it }
        }

    /** Whether the delegation tool is offered at all (Settings master switch). */
    fun isEnabled(): Boolean = SubAgentStore.enabled.value

    /**
     * The user's "models the agent can use" list (the same one `minis-model-use` sees) as names a model can
     * emit in `subagent.model`: enabled providers that have a credential, text-output models only, in the
     * user's order. The tool schema, the system prompt and the delegation check all read this.
     */
    fun callableModels(repo: ProviderRepository): List<CallableModel> = CallableModels.from(
        repo.resolvedAgentLoopEntries().mapNotNull { entry ->
            val instance = repo.instance(entry.providerInstanceId) ?: return@mapNotNull null
            if (!instance.isEnabled || !repo.hasAnyCredential(instance)) return@mapNotNull null
            val model = ModelsDevApi.enrichModel(entry.model)
            if (!model.isTextOutput) return@mapNotNull null
            CallableModels.Source(
                entryId = entry.id,
                modelId = model.id,
                displayName = model.displayName,
                providerLabel = instance.label,
                contextWindow = model.contextWindow,
                imageInput = model.hasImageInput,
                reasoning = model.supportsReasoning == true,
            )
        },
    )
}
