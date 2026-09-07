package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.db.BotDelegationEntity
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.repository.BotDelegationRepository
import com.openminis.app.data.repository.BotInboxRepository
import com.openminis.app.data.repository.BotRepository
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.repository.BotTaskRepository
import com.openminis.app.agent.AgentRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class BotDelegationCoordinator private constructor(
    private val context: Context,
    private val botRepository: BotRepository,
    private val chatRepository: ChatRepository,
    private val providerRepository: ProviderRepository,
    private val delegationRepository: BotDelegationRepository,
    private val taskRepository: BotTaskRepository,
    private val inboxRepository: BotInboxRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val receiptMutex = Mutex()
    private val targetDispatchLocks = ConcurrentHashMap<String, Mutex>()
    private val waitingTasks = ConcurrentHashMap.newKeySet<String>()

    fun recoverAfterProcessStart() {
        scope.launch {
            delegationRepository.cancelUnsettledAfterProcessStart()
            delegationRepository.listRunning().forEach { delegation ->
                delegationRepository.fail(
                    delegation.id,
                    errorText = "worker process stopped before completion",
                    targetSessionId = delegation.targetSessionId,
                    outcomeUnknown = true,
                )
                deliverReceipt(delegationRepository.get(delegation.id))
            }
            // A process can die after the terminal DB write but before the
            // source-session result card is appended (or after append and before
            // delivered_at is written). Retry these rows at the explicit app
            // recovery entry; deliverReceipt is idempotent by task marker.
            delegationRepository.listUndeliveredTerminal(MAX_DISPATCH_BATCH).forEach { delegation ->
                deliverReceipt(delegation)
            }
            dispatchAll()
        }
    }

    suspend fun onSourceTurnSettled(sourceSessionId: String, succeeded: Boolean, sourceRunId: String? = null) = withContext(NonCancellable) {
        if (!succeeded) {
            val pending = delegationRepository.listForSourceSession(sourceSessionId)
                .filter { delegation ->
                    (sourceRunId.isNullOrBlank() || delegation.sourceRunId == sourceRunId) &&
                        (delegation.status == BotDelegationEntity.STATUS_QUEUED ||
                            delegation.status == BotDelegationEntity.STATUS_WAITING_TARGET)
                }
            // The source stream awaits this write before it exits. That closes
            // the cancellation window where a process could die after the UI
            // reported failure but before queued tasks were durably cancelled.
            if (!sourceRunId.isNullOrBlank()) {
                delegationRepository.cancelQueuedForSourceRun(
                    sourceSessionId,
                    sourceRunId,
                    "source turn did not settle successfully",
                )
            } else {
                delegationRepository.cancelQueuedForSourceSession(
                    sourceSessionId,
                    "source turn did not settle successfully",
                )
            }
            pending.forEach { delegation ->
                val cancelled = delegationRepository.get(delegation.id)
                if (cancelled != null && cancelled.status in BotDelegationEntity.TERMINAL_STATUSES) {
                    deliverReceipt(cancelled)
                }
            }
        } else {
            // Persist the source-turn barrier before launching any worker. A
            // process death after this write but before dispatch is safe: the
            // next startup can dispatch only these explicitly settled rows.
            if (!sourceRunId.isNullOrBlank()) {
                delegationRepository.markSourceRunSettled(sourceSessionId, sourceRunId)
            }
            dispatchForSource(sourceSessionId, sourceRunId)
        }
    }

    suspend fun enqueueFromTool(
        argsJson: String,
        sourceSessionId: String,
        toolId: String,
    ): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrElse {
            return ToolExecutionResult("Error: invalid_delegate_bot_arguments", false)
        }
        val sourceSession = chatRepository.getSession(sourceSessionId)
            ?: return ToolExecutionResult("Error: source_session_not_found", false)
        val sourceBotId = sourceSession.botId
            ?: return ToolExecutionResult("Error: delegate_bot_requires_bot_session", false)
        val sourceBot = botRepository.getBot(sourceBotId)
            ?: return ToolExecutionResult("Error: source_bot_not_found", false)
        if (!sourceBot.enabled) {
            return ToolExecutionResult("Error: source_bot_disabled", false)
        }
        if (sourceSession.source == ChatSessionEntity.SOURCE_BOT_DELEGATION ||
            sourceSession.source == ChatSessionEntity.LEGACY_SOURCE_BOT_DELEGATION) {
            return ToolExecutionResult("Error: delegation_depth_limit_reached", false)
        }
        val targetBotId = args.optString("target_bot_id").trim()
        val prompt = args.optString("prompt").trim()
        if (targetBotId.isBlank() || prompt.isBlank()) {
            return ToolExecutionResult("Error: target_bot_id_and_prompt_are_required", false)
        }
        if (targetBotId == sourceBotId) {
            return ToolExecutionResult("Error: cannot_delegate_to_same_bot", false)
        }
        val target = botRepository.getBot(targetBotId)
            ?: return ToolExecutionResult("Error: target_bot_not_found", false)
        if (!target.enabled) {
            return ToolExecutionResult("Error: target_bot_disabled", false)
        }
        if (toolId.isBlank()) {
            return ToolExecutionResult("Error: source_tool_id_required", false)
        }
        val encodedToolId = toolId
        val runId = encodedToolId.substringBefore("::").takeIf { it.startsWith("run-") }
            ?: encodedToolId
        val sourceToolId = encodedToolId.substringAfter("::", encodedToolId)
        if (sourceToolId.isBlank()) {
            return ToolExecutionResult("Error: source_tool_id_required", false)
        }
        if (delegationRepository.countForSourceRun(sourceSessionId, runId) >= MAX_FANOUT_PER_RUN &&
            delegationRepository.findBySourceTool(sourceSessionId, sourceToolId) == null
        ) {
            return ToolExecutionResult("Error: delegation_fanout_limit_reached", false)
        }
        val existingDelegation = delegationRepository.findBySourceTool(sourceSessionId, sourceToolId)
        // A source run is the stable anchor for a user's goal in this first
        // step. Replaying the same tool call reuses the same root task, while
        // later work can move the anchor to the actual user message ID.
        val rootTask = taskRepository.ensureRootTask(
            originSessionId = sourceSessionId,
            originMessageId = runId,
            ownerBotId = sourceBotId,
            goal = prompt,
        )
        val delegation = existingDelegation ?: delegationRepository.enqueue(
            sourceBotId = sourceBotId,
            sourceSessionId = sourceSessionId,
            sourceRunId = runId,
            sourceToolId = sourceToolId,
            rootTaskId = rootTask.id,
            targetBotId = target.id,
            prompt = prompt,
            depth = 1,
        )
        if (existingDelegation == null) {
            taskRepository.recordDelegation(rootTask.id)
            taskRepository.updateState(
                id = rootTask.id,
                status = com.openminis.app.data.db.BotTaskEntity.STATUS_ACTIVE,
                phase = com.openminis.app.data.db.BotTaskEntity.PHASE_EXECUTING,
                ownerSessionId = sourceSessionId,
            )
        }
        return ToolExecutionResult(
            output = JSONObject().apply {
                put("delegation_id", delegation.id)
                put("root_task_id", rootTask.id)
                put("status", delegation.status)
                put("target_bot_id", target.id)
                put("target_bot_name", target.name)
                put("message", "任务已排队，当前回合结束后执行并回写结果。")
            }.toString(),
            success = true,
            toolTitle = args.optString("tool_title"),
        )
    }

    suspend fun listBotsForSource(sourceSessionId: String): ToolExecutionResult {
        val source = chatRepository.getSession(sourceSessionId)
            ?: return ToolExecutionResult("Error: source_session_not_found", false)
        val sourceBot = source.botId?.let { botRepository.getBot(it) }
        if (source.botId == null || sourceBot == null || !sourceBot.enabled ||
            source.source == ChatSessionEntity.SOURCE_BOT_DELEGATION ||
            source.source == ChatSessionEntity.LEGACY_SOURCE_BOT_DELEGATION) {
            return ToolExecutionResult("Error: bot_roster_requires_bot_session", false)
        }
        val bots = botRepository.listBots()
            .filter { it.enabled && it.id != sourceBot.id }
            .map { bot ->
            JSONObject().apply {
                put("bot_id", bot.id)
                put("name", sanitizeRosterText(bot.name, ROSTER_NAME_MAX_CHARS))
                put("model_binding", bot.modelBinding?.let { sanitizeRosterText(it, ROSTER_BINDING_MAX_CHARS) } ?: JSONObject.NULL)
            }
        }
        return ToolExecutionResult(
            output = JSONArray(bots).toString(),
            success = true,
        )
    }

    suspend fun checkDelegationFromTool(
        argsJson: String,
        sourceSessionId: String,
    ): ToolExecutionResult {
        val source = chatRepository.getSession(sourceSessionId)
            ?: return ToolExecutionResult("Error: source_session_not_found", false)
        val sourceBot = source.botId?.let { botRepository.getBot(it) }
        if (source.botId == null || sourceBot == null || !sourceBot.enabled ||
            source.source == ChatSessionEntity.SOURCE_BOT_DELEGATION ||
            source.source == ChatSessionEntity.LEGACY_SOURCE_BOT_DELEGATION) {
            return ToolExecutionResult("Error: bot_session_required", false)
        }
        val taskId = runCatching { JSONObject(argsJson).optString("task_id").trim() }
            .getOrDefault("")
        if (taskId.isBlank()) return ToolExecutionResult("Error: task_id_is_required", false)
        val delegation = delegationRepository.get(taskId)
            ?: return ToolExecutionResult("Error: delegation_not_found", false)
        if (delegation.sourceSessionId != sourceSessionId) {
            return ToolExecutionResult("Error: delegation_not_found", false)
        }
        return ToolExecutionResult(
            output = JSONObject().apply {
                put("delegation_id", delegation.id)
                put("target_bot_id", delegation.targetBotId)
                put("target_session_id", delegation.targetSessionId ?: JSONObject.NULL)
                put("status", delegation.status)
                put("source_turn_settled", delegation.sourceTurnSettled != 0)
                put("outcome_unknown", delegation.outcomeUnknown != 0)
                put("result", delegation.resultText ?: JSONObject.NULL)
                put("error", delegation.errorText ?: JSONObject.NULL)
                put("delivered", delegation.deliveredAt != null)
            }.toString(),
            success = true,
        )
    }

    private suspend fun dispatchAll() {
        delegationRepository.listDispatchable(MAX_DISPATCH_BATCH).forEach { delegation ->
            scope.launch { dispatchOne(delegation) }
        }
    }

    private suspend fun dispatchForSource(sourceSessionId: String, sourceRunId: String?) {
        delegationRepository.listForSourceSession(sourceSessionId)
            .filter {
                (sourceRunId.isNullOrBlank() || it.sourceRunId == sourceRunId) &&
                    (it.status == BotDelegationEntity.STATUS_QUEUED || it.status == BotDelegationEntity.STATUS_WAITING_TARGET)
            }
            .take(MAX_FANOUT_PER_RUN)
            .forEach { delegation -> scope.launch { dispatchOne(delegation) } }
    }

    private suspend fun dispatchOne(delegation: BotDelegationEntity) {
        val targetLock = targetDispatchLocks.getOrPut(delegation.targetBotId) { Mutex() }
        targetLock.withLock {
            val current = delegationRepository.get(delegation.id) ?: return@withLock
            if (current.status in BotDelegationEntity.TERMINAL_STATUSES ||
                current.status == BotDelegationEntity.STATUS_RUNNING || current.id in waitingTasks) return@withLock
            dispatchOneLocked(current)
        }
    }

    private suspend fun dispatchOneLocked(delegation: BotDelegationEntity) {
        if (delegation.sourceTurnSettled == 0) return
        val sourceSession = chatRepository.getSession(delegation.sourceSessionId)
        if (sourceSession == null || sourceSession.botId != delegation.sourceBotId) {
            if (delegationRepository.cancel(delegation.id, "source session is no longer available")) {
                deliverReceipt(delegationRepository.get(delegation.id))
            }
            return
        }
        // Deleting a source Bot clears its sessions, but queued rows remain in
        // Room for auditability. Refuse those rows before taking a target lock;
        // they must never become orphaned work on the next app start.
        val sourceBot = botRepository.getBot(delegation.sourceBotId)
        if (sourceBot == null || !sourceBot.enabled) {
            if (delegationRepository.cancel(
                    delegation.id,
                    if (sourceBot == null) "source bot was deleted" else "source bot is disabled",
                )) {
                deliverReceipt(delegationRepository.get(delegation.id))
            }
            return
        }
        val target = botRepository.getBot(delegation.targetBotId)
        if (target == null || !target.enabled) {
            delegationRepository.deny(delegation.id, if (target == null) "target bot was deleted" else "target bot is disabled")
            deliverReceipt(delegationRepository.get(delegation.id))
            return
        }
        val entry = com.openminis.app.agent.BotModelResolver.resolve(providerRepository, target.modelBinding)
        if (entry == null) {
            delegationRepository.fail(delegation.id, "no usable provider model is configured")
            deliverReceipt(delegationRepository.get(delegation.id))
            return
        }
        val targetSession = chatRepository.createSession(
            modelId = entry.baseModel.id,
            title = "委托：${target.name}",
            botId = target.id,
            source = ChatSessionEntity.SOURCE_BOT_DELEGATION,
            modelBinding = JSONObject().put("type", "entry").put("entryId", entry.id).toString(),
        )
        if (!BotTurnLockRegistry.tryAcquireExternal(target.id, targetSession.id)) {
            chatRepository.deleteSession(targetSession.id)
            val current = delegationRepository.get(delegation.id) ?: return
            if (current.attempts >= MAX_BUSY_RETRIES) {
                if (delegationRepository.giveUpBusy(delegation.id, "target bot remained busy")) {
                    deliverReceipt(delegationRepository.get(delegation.id))
                }
            } else if (delegationRepository.markWaitingTarget(delegation.id)) {
                waitingTasks.add(delegation.id)
                scope.launch {
                    try {
                        BotTurnLockRegistry.awaitAvailable(target.id)
                    } finally {
                        waitingTasks.remove(delegation.id)
                    }
                    delegationRepository.get(delegation.id)?.let { dispatchOne(it) }
                }
            }
            return
        }
        if (!delegationRepository.claim(delegation.id, targetSession.id)) {
            BotTurnLockRegistry.releaseExternal(target.id, targetSession.id)
            chatRepository.deleteSession(targetSession.id)
            return
        }
        var releaseLockImmediately = true
        try {
            val result = AgentRunner.prompt(
                    context = context,
                    sessionId = targetSession.id,
                    text = delegation.prompt,
                    wait = true,
                    timeoutMs = TARGET_TIMEOUT_MS,
                )
            releaseLockImmediately = result.streamExited
            if (result.status.equals("Cancelled", ignoreCase = true)) {
                delegationRepository.cancel(delegation.id, "target turn cancelled")
            } else if (result.status.equals("Completed", ignoreCase = true)) {
                delegationRepository.complete(
                    delegation.id,
                    targetSession.id,
                    result.responseText?.takeIf { it.isNotBlank() }
                        ?: "目标 Bot 本次执行已结束，没有文本回执。请查看目标会话核验产物。",
                )
            } else {
                delegationRepository.fail(
                    delegation.id,
                    result.responseText ?: "target turn ${result.status.lowercase()}",
                    targetSession.id,
                    outcomeUnknown = result.timedOut,
                )
            }
            deliverReceipt(delegationRepository.get(delegation.id))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            withContext(NonCancellable) {
                AgentRunner.cancel(context, targetSession.id)
                releaseLockImmediately = withTimeoutOrNull(5_000L) {
                    AgentRunner.awaitStreamExit(context, targetSession.id)
                } == true
                delegationRepository.cancel(delegation.id, "delegation worker cancelled")
                deliverReceipt(delegationRepository.get(delegation.id))
            }
            throw cancelled
        } catch (error: Throwable) {
            AgentRunner.cancel(context, targetSession.id)
            releaseLockImmediately = withTimeoutOrNull(5_000L) {
                AgentRunner.awaitStreamExit(context, targetSession.id)
            } == true
            delegationRepository.fail(
                delegation.id,
                error.message ?: error.javaClass.simpleName,
                targetSession.id,
                outcomeUnknown = true,
            )
            deliverReceipt(delegationRepository.get(delegation.id))
        } finally {
            if (releaseLockImmediately) {
                BotTurnLockRegistry.releaseExternal(target.id, targetSession.id)
            } else {
                scope.launch {
                    AgentRunner.awaitStreamExit(context, targetSession.id)
                    BotTurnLockRegistry.releaseExternal(target.id, targetSession.id)
                }
            }
        }
    }
    private suspend fun deliverReceipt(delegation: BotDelegationEntity?) {
        if (delegation == null || delegation.deliveredAt != null) return
        receiptMutex.withLock {
            val current = delegationRepository.get(delegation.id) ?: return@withLock
            if (current.deliveredAt != null) return@withLock
            if (chatRepository.getSession(current.sourceSessionId) == null) {
                // The source conversation was explicitly deleted, so there
                // is no valid place to append a receipt. Consume it rather
                // than retrying an impossible delivery forever.
                delegationRepository.markDelivered(current.id)
                return@withLock
            }
            val marker = "委托 ID：${current.id}"
            val target = botRepository.getBot(current.targetBotId)
            val statusText = when (current.status) {
                BotDelegationEntity.STATUS_COMPLETED -> "执行结束"
                BotDelegationEntity.STATUS_CANCELLED -> "已取消"
                BotDelegationEntity.STATUS_DENIED -> "已拒绝"
                else -> "失败"
            }
            val body = buildString {
                append("[Bot 委托结果]\n")
                append("$marker\n")
                append("目标：${target?.name ?: current.targetBotId}\n")
                append("目标会话：${current.targetSessionId ?: "未创建"}\n")
                current.targetSessionId?.let { append("产物入口：目标会话 $it 的完整输出\n") }
                append("状态：$statusText\n")
                if (current.outcomeUnknown != 0) append("结果：执行进程中断，最终结果未知\n")
                current.resultText?.takeIf { it.isNotBlank() }?.let { append("\n$it") }
                current.errorText?.takeIf { it.isNotBlank() }?.let { append("\n错误：$it") }
            }
            publishInboxEvent(current)
            val parts = JSONArray().put(JSONObject().put("type", "text").put("value", body)).toString()
            runCatching {
                val message = chatRepository.appendMessage(current.sourceSessionId, "assistant", parts,
                    idempotencyKey = "bot-receipt:${current.id}")
                AgentRunner.notifyExternalMessage(context, message)
                delegationRepository.markDelivered(current.id)
            }
        }
    }

    private suspend fun publishInboxEvent(delegation: BotDelegationEntity) {
        val type = when (delegation.status) {
            BotDelegationEntity.STATUS_COMPLETED -> com.openminis.app.data.db.BotInboxEventEntity.TYPE_DELEGATION_SUBMITTED
            BotDelegationEntity.STATUS_FAILED,
            BotDelegationEntity.STATUS_DENIED,
            BotDelegationEntity.STATUS_CANCELLED,
            BotDelegationEntity.STATUS_BUSY_GAVE_UP -> com.openminis.app.data.db.BotInboxEventEntity.TYPE_TASK_FAILED
            else -> return
        }
        runCatching {
            inboxRepository.enqueue(
                dedupeKey = "delegation-result:${delegation.id}",
                rootTaskId = delegation.rootTaskId,
                recipientBotId = delegation.sourceBotId,
                recipientSessionId = delegation.sourceSessionId,
                type = type,
                producerDelegationId = delegation.id,
                payloadJson = JSONObject().apply {
                    put("delegation_id", delegation.id)
                    put("root_task_id", delegation.rootTaskId ?: JSONObject.NULL)
                    put("status", delegation.status)
                    put("result", delegation.resultText ?: JSONObject.NULL)
                    put("error", delegation.errorText ?: JSONObject.NULL)
                    put("outcome_unknown", delegation.outcomeUnknown != 0)
                }.toString(),
            )
            delegation.rootTaskId?.let { taskId ->
                taskRepository.updateState(
                    id = taskId,
                    status = if (delegation.status == BotDelegationEntity.STATUS_COMPLETED) {
                        com.openminis.app.data.db.BotTaskEntity.STATUS_ACTIVE
                    } else {
                        com.openminis.app.data.db.BotTaskEntity.STATUS_NEEDS_USER
                    },
                    phase = com.openminis.app.data.db.BotTaskEntity.PHASE_REVIEWING,
                    ownerSessionId = delegation.sourceSessionId,
                )
            }
        }
    }

    private fun sanitizeRosterText(value: String, maxChars: Int): String =
        value.replace(Regex("[\\p{Cntrl}\\s]+"), " ").trim().take(maxChars)

    companion object {
        private const val MAX_FANOUT_PER_RUN = 4
        private const val MAX_DISPATCH_BATCH = 32
        private const val TARGET_TIMEOUT_MS = 15 * 60 * 1000L
        private const val MAX_BUSY_RETRIES = 3
        private const val ROSTER_NAME_MAX_CHARS = 80
        private const val ROSTER_BINDING_MAX_CHARS = 160
        @Volatile private var instance: BotDelegationCoordinator? = null

        fun install(
            context: Context,
            botRepository: BotRepository,
            chatRepository: ChatRepository,
            providerRepository: ProviderRepository,
            delegationRepository: BotDelegationRepository,
            taskRepository: BotTaskRepository,
            inboxRepository: BotInboxRepository,
        ): BotDelegationCoordinator = synchronized(this) {
            instance ?: BotDelegationCoordinator(
                context.applicationContext,
                botRepository,
                chatRepository,
                providerRepository,
                delegationRepository,
                taskRepository,
                inboxRepository,
            ).also { instance = it }
        }

        fun current(): BotDelegationCoordinator? = instance

        fun definition(): com.openminis.app.data.model.AgentToolDefinition =
            com.openminis.app.data.model.AgentToolDefinition(
                name = "delegate_bot",
                description = "把一个完整、独立的任务交给另一个已存在的 Bot。任务会在当前回合结束后异步执行，结果会回写到当前会话。不能委托给自己；目标 Bot 只能用已配置的 Bot ID。",
                parameters = mapOf(
                    "tool_title" to com.openminis.app.data.model.AgentToolParam("string", "简短描述这次委托。"),
                    "target_bot_id" to com.openminis.app.data.model.AgentToolParam("string", "目标 Bot 的持久化 ID。"),
                    "prompt" to com.openminis.app.data.model.AgentToolParam("string", "给目标 Bot 的完整、自包含任务说明。"),
                ),
                required = listOf("tool_title", "target_bot_id", "prompt"),
                propertyOrdering = listOf("tool_title", "target_bot_id", "prompt"),
                timeoutMs = 30_000L,
            )

        fun listBotsDefinition(): com.openminis.app.data.model.AgentToolDefinition =
            com.openminis.app.data.model.AgentToolDefinition(
                name = "list_bots",
                description = "列出当前可委托的持久 Bot，只返回 Bot ID、名称和默认模型绑定。",
                parameters = mapOf(
                    "tool_title" to com.openminis.app.data.model.AgentToolParam("string", "简短描述这次查询。"),
                ),
                required = listOf("tool_title"),
                propertyOrdering = listOf("tool_title"),
                timeoutMs = 30_000L,
            )

        fun checkDelegationDefinition(): com.openminis.app.data.model.AgentToolDefinition =
            com.openminis.app.data.model.AgentToolDefinition(
                name = "check_delegation",
                description = "查询当前 Bot 在当前来源会话中创建的委托任务状态和结果。",
                parameters = mapOf(
                    "tool_title" to com.openminis.app.data.model.AgentToolParam("string", "简短描述这次查询。"),
                    "task_id" to com.openminis.app.data.model.AgentToolParam("string", "delegate_bot 返回的 delegation_id。"),
                ),
                required = listOf("tool_title", "task_id"),
                propertyOrdering = listOf("tool_title", "task_id"),
                timeoutMs = 30_000L,
            )
    }
}
