package com.openminis.app.tools

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.agent.AgentRunner
import com.openminis.app.data.db.BotInboxEventEntity
import com.openminis.app.data.db.BotTaskEntity
import com.openminis.app.data.repository.BotDelegationRepository
import com.openminis.app.data.repository.BotInboxRepository
import com.openminis.app.data.repository.BotRepository
import com.openminis.app.data.repository.BotTaskRepository
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.scheduled.ScheduledTaskManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Durable asynchronous wake-up consumer for completed Bot delegations. */
internal class BotWakeDispatcher(
    private val application: Application,
    private val botRepository: BotRepository,
    private val chatRepository: ChatRepository,
    private val providerRepository: ProviderRepository,
    private val delegationRepository: BotDelegationRepository,
    private val taskRepository: BotTaskRepository,
    private val inboxRepository: BotInboxRepository,
    private val wakingTasks: WakeTaskRegistry,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processLeaseOwner = "wake:${UUID.randomUUID()}"
    private val scheduledGroups = ConcurrentHashMap.newKeySet<String>()
    private val processingGroups = ConcurrentHashMap.newKeySet<String>()
    private val waitingBots = WakeWaitRegistry()
    private val leaseTimerScheduled = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Called only after the receipt message is durable in the owner's history. */
    fun onInboxEventPublished(event: BotInboxEventEntity) {
        val taskId = event.rootTaskId ?: return
        scheduleGroup(event.recipientBotId, taskId)
        scheduleLeaseRecovery()
    }

    /** Called from the app's process-start recovery hook. */
    fun recoverAfterProcessStart() {
        scope.launch {
            inboxRepository.releaseExpired()
            schedulePendingGroups()
            scheduleLeaseRecovery()
        }
    }

    private fun scheduleGroup(botId: String, taskId: String) {
        val key = BotWakePolicy.groupKey(botId, taskId)
        if (!scheduledGroups.add(key)) return
        scope.launch {
            delay(DEBOUNCE_MS)
            scheduledGroups.remove(key)
            if (!processingGroups.add(key)) return@launch
            try {
                processGroup(botId, taskId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                android.util.Log.e(TAG, "wake group failed bot=$botId task=$taskId", error)
            } finally {
                processingGroups.remove(key)
            }
        }
    }

    private suspend fun processGroup(botId: String, taskId: String) {
        var pending = inboxRepository.listPendingForRootTask(botId, taskId)
        if (pending.isEmpty()) return

        val task = taskRepository.get(taskId)
        if (task == null) {
            for (event in pending) inboxRepository.markDead(event.id)
            return
        }

        val now = System.currentTimeMillis()
        val expired = pending.filter { BotWakePolicy.eventExpired(it.createdAt, now) }
        if (expired.isNotEmpty()) {
            for (event in expired) inboxRepository.markDead(event.id)
            val current = taskRepository.get(taskId)
            if (current != null && BotWakePolicy.isWakeable(current.status)) {
                val updated = taskRepository.updateStateIfWakeable(
                    current.id,
                    BotTaskEntity.STATUS_NEEDS_USER,
                    BotTaskEntity.PHASE_REVIEWING,
                    current.currentOwnerSessionId ?: current.originSessionId,
                )
                if (updated) {
                    notifyTask(current, current.currentOwnerSessionId ?: current.originSessionId,
                        application.getString(R.string.bots_task_notification_needs_user))
                }
            }
            pending = pending - expired.toSet()
            if (pending.isEmpty()) return
        }

        if (!BotWakePolicy.isReady(delegationRepository.countNonTerminalForRootTask(taskId))) return

        val currentTask = taskRepository.get(taskId) ?: run {
            for (event in pending) inboxRepository.markDead(event.id)
            return
        }
        if (!BotWakePolicy.isWakeable(currentTask.status)) {
            for (event in pending) inboxRepository.markDead(event.id)
            return
        }

        val owner = botRepository.getBot(botId)
        var session = chatRepository.latestBotConversation(botId)?.takeIf { it.botId == botId }
        for (sessionId in pending.mapNotNull { it.recipientSessionId }.distinct()) {
            val candidate = try {
                chatRepository.getSession(sessionId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
            if (candidate?.botId == botId) {
                session = candidate
                break
            }
        }
        when (BotWakePolicy.recipientFailure(owner != null, owner?.enabled == true, session != null)) {
            BotWakePolicy.RecipientFailure.DELETED -> {
                for (event in pending) inboxRepository.markDead(event.id)
                taskRepository.updateStateIfWakeable(currentTask.id, BotTaskEntity.STATUS_CANCELLED,
                    BotTaskEntity.PHASE_REVIEWING, currentTask.currentOwnerSessionId ?: currentTask.originSessionId)
                return
            }
            BotWakePolicy.RecipientFailure.DISABLED -> {
                for (event in pending) inboxRepository.markDead(event.id)
                val updated = taskRepository.updateStateIfWakeable(currentTask.id, BotTaskEntity.STATUS_NEEDS_USER,
                    BotTaskEntity.PHASE_REVIEWING, currentTask.currentOwnerSessionId ?: currentTask.originSessionId)
                if (updated) notifyTask(currentTask, currentTask.currentOwnerSessionId ?: currentTask.originSessionId,
                    application.getString(R.string.bots_task_notification_needs_user))
                return
            }
            BotWakePolicy.RecipientFailure.NO_SESSION -> {
                for (event in pending) inboxRepository.markDead(event.id)
                val updated = taskRepository.updateStateIfWakeable(currentTask.id, BotTaskEntity.STATUS_NEEDS_USER,
                    BotTaskEntity.PHASE_REVIEWING, currentTask.currentOwnerSessionId ?: currentTask.originSessionId)
                if (updated) notifyTask(currentTask, currentTask.currentOwnerSessionId ?: currentTask.originSessionId,
                    application.getString(R.string.bots_task_notification_needs_user))
                return
            }
            null -> Unit
        }
        val usableSession = requireNotNull(session)

        if (BotTurnLockRegistry.isLocked(botId)) {
            awaitOwnerAvailability(botId)
            return
        }

        when (BotWakePolicy.budgetFailure(currentTask, now)) {
            BotWakePolicy.BudgetFailure.TASK_NOT_CONTINUABLE -> {
                for (event in pending) inboxRepository.markDead(event.id)
                return
            }
            BotWakePolicy.BudgetFailure.DEADLINE_EXPIRED,
            BotWakePolicy.BudgetFailure.AUTO_WAKE_LIMIT -> {
                val exhausted = taskRepository.exhaustBudget(taskId)
                for (event in pending) inboxRepository.markDead(event.id)
                if (exhausted) notifyTask(currentTask, usableSession.id,
                    application.getString(R.string.bots_task_notification_budget))
                return
            }
            null -> Unit
        }

        val stopGeneration = currentTask.stopGeneration
        if (!taskRepository.recordAutoRun(taskId, BotWakePolicy.MAX_AUTO_WAKES)) {
            for (event in pending) inboxRepository.markDead(event.id)
            val latest = taskRepository.get(taskId)
            if (latest != null && (latest.autoRunsUsed >= BotWakePolicy.MAX_AUTO_WAKES ||
                    latest.deadlineAt?.let { it <= now } == true)
            ) {
                if (taskRepository.exhaustBudget(taskId)) {
                    notifyTask(latest, usableSession.id, application.getString(R.string.bots_task_notification_budget))
                }
            }
            return
        }

        val beforeClaim = taskRepository.get(taskId)
        if (beforeClaim == null || beforeClaim.stopGeneration != stopGeneration ||
            !BotWakePolicy.isWakeable(beforeClaim.status)
        ) {
            for (event in pending) inboxRepository.markDead(event.id)
            return
        }

        val claimed = inboxRepository.claimPending(
            recipientBotId = botId,
            leaseOwner = processLeaseOwner,
            leaseMs = WAKE_LEASE_MS,
            limit = MAX_WAKE_BATCH,
            rootTaskId = taskId,
        )
        if (claimed.isEmpty()) return
        scheduleLeaseRecovery()

        val afterClaim = taskRepository.get(taskId)
        if (afterClaim == null || afterClaim.stopGeneration != beforeClaim.stopGeneration ||
            !BotWakePolicy.isWakeable(afterClaim.status)
        ) {
            for (event in claimed) inboxRepository.markDead(event.id)
            return
        }
        val freshOwner = botRepository.getBot(botId)
        val freshSession = chatRepository.getSession(usableSession.id)
        when (BotWakePolicy.recipientFailure(freshOwner != null, freshOwner?.enabled == true,
                freshSession?.botId == botId)
        ) {
            BotWakePolicy.RecipientFailure.DELETED -> {
                for (event in claimed) inboxRepository.markDead(event.id)
                taskRepository.updateStateIfWakeable(taskId, BotTaskEntity.STATUS_CANCELLED,
                    BotTaskEntity.PHASE_REVIEWING, usableSession.id)
                return
            }
            BotWakePolicy.RecipientFailure.DISABLED,
            BotWakePolicy.RecipientFailure.NO_SESSION -> {
                for (event in claimed) inboxRepository.markDead(event.id)
                val updated = taskRepository.updateStateIfWakeable(taskId, BotTaskEntity.STATUS_NEEDS_USER,
                    BotTaskEntity.PHASE_REVIEWING, usableSession.id)
                if (updated) notifyTask(afterClaim, usableSession.id,
                    application.getString(R.string.bots_task_notification_needs_user))
                return
            }
            null -> Unit
        }

        val promptResults = claimed.map { event -> resultFor(event) }
        val prompt = BotWakePolicy.buildPrompt(
            task = afterClaim,
            wakeNumber = afterClaim.autoRunsUsed,
            results = promptResults,
        )
        var turnCompleted = false
        var runFailure: String? = null
        wakingTasks.begin(taskId, usableSession.id)
        try {
            val result = OffloadPermissionManager.withUnattendedSession(usableSession.id) {
                AgentRunner.prompt(
                    context = application,
                    sessionId = usableSession.id,
                    text = prompt,
                    wait = true,
                    timeoutMs = WAKE_TIMEOUT_MS,
                )
            }
            turnCompleted = result.status.equals("Completed", ignoreCase = true)
            if (!turnCompleted) {
                runFailure = result.responseText?.takeIf { it.isNotBlank() }
                    ?: "owner wake ${result.status.lowercase()}"
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                runCatching { AgentRunner.cancel(application, usableSession.id) }
                withTimeoutOrNull(STREAM_EXIT_TIMEOUT_MS) {
                    runCatching { AgentRunner.awaitStreamExit(application, usableSession.id) }
                }
            }
            runFailure = "owner wake was cancelled"
        } catch (error: Exception) {
            withContext(NonCancellable) {
                runCatching { AgentRunner.cancel(application, usableSession.id) }
                withTimeoutOrNull(STREAM_EXIT_TIMEOUT_MS) {
                    runCatching { AgentRunner.awaitStreamExit(application, usableSession.id) }
                }
            }
            runFailure = error.message ?: error.javaClass.simpleName
        } finally {
            wakingTasks.end(taskId, usableSession.id)
            withContext(NonCancellable) {
                for (event in claimed) inboxRepository.consume(event.id, processLeaseOwner)
            }
        }

        withContext(NonCancellable) {
            val afterTurn = taskRepository.get(taskId) ?: return@withContext
            if (afterTurn.stopGeneration != stopGeneration || !BotWakePolicy.isWakeable(afterTurn.status)) {
                markRemainingPendingDead(botId, taskId)
                return@withContext
            }

            val hasNewDelegations = turnCompleted && delegationRepository.countNonTerminalForRootTask(taskId) > 0
            val transition = BotWakePolicy.transition(turnCompleted, hasNewDelegations)
            if (transition.status == BotTaskEntity.STATUS_COMPLETED) {
                if (taskRepository.complete(taskId)) {
                    notifyTask(afterTurn, usableSession.id,
                        application.getString(R.string.bots_task_notification_completed))
                }
            } else {
                val updated = taskRepository.updateStateIfWakeable(taskId, transition.status,
                    transition.phase, usableSession.id)
                if (!turnCompleted) {
                    if (updated) notifyTask(afterTurn, usableSession.id,
                        application.getString(R.string.bots_task_notification_needs_user))
                    android.util.Log.w(TAG, "owner wake failed task=$taskId: ${runFailure.orEmpty()}")
                }
            }

            schedulePendingGroups(botId)
        }
    }

    private suspend fun resultFor(event: BotInboxEventEntity): BotWakePolicy.Result {
        val delegationId = event.producerDelegationId
            ?: runCatching { JSONObject(event.payloadJson).optString("delegation_id") }.getOrDefault("")
        val delegation = delegationId.takeIf { it.isNotBlank() }?.let { delegationRepository.get(it) }
            ?.takeIf { it.rootTaskId == event.rootTaskId }
        val targetName = delegation?.let { botRepository.getBot(it.targetBotId)?.name } ?: event.producerDelegationId.orEmpty()
        val text = when {
            delegation?.resultText?.isNotBlank() == true -> delegation.resultText
            delegation?.errorText?.isNotBlank() == true -> delegation.errorText
            delegation == null -> "委派记录缺失，无法核验完整结果。"
            else -> "目标会话没有文本回执。"
        }
        return BotWakePolicy.Result(
            delegationId = delegationId.ifBlank { event.id },
            targetName = targetName,
            targetSessionId = delegation?.targetSessionId,
            status = delegation?.status ?: "FAILED",
            outcomeUnknown = delegation?.outcomeUnknown != 0,
            text = text.orEmpty(),
        )
    }

    private fun awaitOwnerAvailability(botId: String) {
        if (!waitingBots.begin(botId)) return
        scope.launch {
            try {
                BotTurnLockRegistry.awaitAvailable(botId)
            } finally {
                waitingBots.end(botId)
            }
            schedulePendingGroups(botId)
        }
    }

    private suspend fun schedulePendingGroups(onlyBotId: String? = null) {
        inboxRepository.listPendingAll(MAX_PENDING_SCAN)
            .asSequence()
            .filter { onlyBotId == null || it.recipientBotId == onlyBotId }
            .filter { it.rootTaskId != null }
            .groupBy { it.recipientBotId to it.rootTaskId!! }
            .keys
            .forEach { (botId, taskId) -> scheduleGroup(botId, taskId) }
        val orphaned = inboxRepository.listPendingAll(MAX_PENDING_SCAN).filter { it.rootTaskId == null }
        for (event in orphaned) inboxRepository.markDead(event.id)
    }

    private suspend fun markRemainingPendingDead(botId: String, taskId: String) {
        val pending = inboxRepository.listPendingForRootTask(botId, taskId)
        for (event in pending) inboxRepository.markDead(event.id)
    }

    private fun scheduleLeaseRecovery() {
        if (!leaseTimerScheduled.compareAndSet(false, true)) return
        scope.launch {
            try {
                val expiresAt = inboxRepository.earliestLeaseExpiration() ?: return@launch
                delay((expiresAt - System.currentTimeMillis()).coerceAtLeast(1L))
                inboxRepository.releaseExpired()
                schedulePendingGroups()
            } finally {
                leaseTimerScheduled.set(false)
            }
            if (inboxRepository.earliestLeaseExpiration() != null) scheduleLeaseRecovery()
        }
    }

    private suspend fun notifyTask(task: BotTaskEntity, preferredSessionId: String?, message: String) {
        val app = application as? MinisApp ?: return
        if (app.isAppForeground()) return
        val sessionId = preferredSessionId?.takeIf { runCatching { chatRepository.getSession(it) != null }.getOrDefault(false) }
            ?: task.originSessionId.takeIf { runCatching { chatRepository.getSession(it) != null }.getOrDefault(false) }
            ?: runCatching {
                botRepository.getBot(task.ownerBotId)?.takeIf { it.enabled }
                    ?.let { botRepository.openConversation(it.id, chatRepository, providerRepository, newTopic = true)?.id }
            }.getOrNull()
        val title = application.getString(R.string.bots_task_notification_title, task.goal.take(80))
        val notificationId = task.id.hashCode() and 0x7fffffff
        val launchIntent = sessionId?.let { session ->
            Intent(Intent.ACTION_VIEW, Uri.parse("minis://session/$session")).apply {
                setPackage(application.packageName)
            }
        }
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                application,
                notificationId,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val builder = NotificationCompat.Builder(application, ScheduledTaskManager.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        contentIntent?.let(builder::setContentIntent)
        runCatching {
            (application.getSystemService(Application.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .notify(notificationId, builder.build())
        }.onFailure { android.util.Log.w(TAG, "task notification suppressed: ${it.message}") }
    }

    companion object {
        private const val TAG = "BotWakeDispatcher"
        private const val DEBOUNCE_MS = 2_000L
        private const val WAKE_TIMEOUT_MS = 10 * 60 * 1000L
        private const val WAKE_LEASE_MS = 12 * 60 * 1000L
        private const val STREAM_EXIT_TIMEOUT_MS = 5_000L
        private const val MAX_WAKE_BATCH = 16
        private const val MAX_PENDING_SCAN = 2_048
    }
}
