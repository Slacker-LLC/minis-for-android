package com.openminis.app.scheduled

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.openminis.app.MinisApp
import com.openminis.app.agent.AgentRunner
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.OffloadPermissionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.openminis.app.data.repository.allVisibleEntries

/**
 * [T-android-scheduled-tasks-design] Headless agent launch for scheduled
 * tasks. Mirrors the shape of iOS SendPromptIntent.perform():
 *
 *  - resolve a session id (NEW_SESSION → create row; APPEND_TO → reuse)
 *  - run the prompt through the existing agent loop (via [AgentRunner])
 *  - post a "completed" notification with a deep-link back to the session
 *  - hand the result preview back to [ScheduledTaskManager] for the row
 *
 * Concurrency: serialised per-session by [AgentRunner]'s VM cache —
 * two scheduled prompts hitting the same session id are queued via
 * ChatViewModel.enqueuePrompt.
 */
object ScheduledAgentRunner {

    private const val TAG = "ScheduledAgentRunner"
    private const val RUN_TIMEOUT_MS = 10 * 60 * 1000L  // 10 min ceiling

    /**
     * [T-android-scheduled-tasks-full] The result of asking a scheduled task to
     * run: the session that received the prompt, or why none could.
     *
     * `null` used to stand for every failure at once. The CLI answered
     * `{"ran": false}` and the editor's "Run now" dialog said only that the
     * task could not start, so neither the agent nor the user could tell a
     * missing provider (fixable in Settings) from a target chat that no longer
     * exists. Measured on the Xiaomi 24129PN74C: `minis-scheduled run --id …`
     * answered `{"ran": false}` on a device with no provider configured.
     */
    data class RunOutcome(
        val sessionId: String? = null,
        val errorCode: String? = null,
        val message: String? = null,
    ) {
        val started: Boolean get() = sessionId != null
    }

    const val ERROR_APP_NOT_READY = "app_not_ready"
    const val ERROR_SUBSYSTEMS_NOT_READY = "subsystems_not_ready"
    const val ERROR_NO_PROVIDER = "no_provider"
    const val ERROR_TARGET_SESSION_GONE = "target_session_gone"
    const val ERROR_BOT_NOT_FOUND = "bot_not_found"
    const val ERROR_BOT_DISABLED = "bot_disabled"

    /**
     * App-scoped scope for fire-and-forget completion work when a caller asks
     * NOT to wait (the "Run now" button). Outlives the editor screen so the
     * agent loop + completion notification finish even after the user leaves.
     */
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Fire a scheduled task.
     *
     * @param waitForCompletion when true, suspend until the agent loop
     *   finishes, then mark-fired + post the completion notification before
     *   returning. When false, return as soon as the session is resolved and
     *   the prompt is dispatched — the agent keeps running in the background
     *   and the completion (mark-fired + notification) is finished off an
     *   app-scoped coroutine. Mirrors iOS SendPromptIntent's waitForResult
     *   flag.
     *
     *   [GH#197] NEVER pass true from a BroadcastReceiver. Waiting here can
     *   take up to [RUN_TIMEOUT_MS] (10 min) and the wait lands on the main
     *   thread (AgentRunner.prompt/retry are
     *   `withContext(Dispatchers.Main)`), so a receiver that waits blows its
     *   ~10s broadcast budget and gets the whole process ANR-killed. Waiting
     *   is only safe off a broadcast — e.g. the minis-scheduled CLI, which
     *   runs in its own offload thread.
     * @return the session id once the action has been DISPATCHED (resolved +
     *   prompt sent), or null when the runner couldn't even start (no provider,
     *   target chat gone, MinisApp not initialized).
     */
    suspend fun run(
        context: Context,
        task: ScheduledTask,
        waitForCompletion: Boolean = true,
    ): RunOutcome {
        val app = context.applicationContext as? MinisApp ?: run {
            AppLogger.error(TAG, "Application is not MinisApp — skipping task ${task.id}")
            return RunOutcome(
                errorCode = ERROR_APP_NOT_READY,
                message = "Minis is not initialized in this process.",
            )
        }
        if (!app.subsystemsReady()) {
            AppLogger.error(
                TAG,
                "MinisApp subsystems not initialized (safe-mode or failed init) — skipping task ${task.id}",
            )
            return RunOutcome(
                errorCode = ERROR_SUBSYSTEMS_NOT_READY,
                message = "Minis is still starting, or it is running in safe mode.",
            )
        }

        val ownerBot = task.botId?.let { app.botRepository.getBot(it) }
        when (ScheduledTaskPolicy.botOwnerState(task.botId, ownerBot)) {
            BotOwnerState.MISSING -> {
                val message = "Bot 已删除"
                val manager = ScheduledTaskManager(app)
                manager.markFired(task.id, null, message, ok = false)
                manager.setEnabled(task.id, false)
                postCompletionNotification(app, task, null, message)
                return RunOutcome(errorCode = ERROR_BOT_NOT_FOUND, message = message)
            }
            BotOwnerState.DISABLED -> {
                val message = "Bot 已停用"
                ScheduledTaskManager(app).markFired(task.id, null, message, ok = false)
                postCompletionNotification(app, task, null, message)
                return RunOutcome(errorCode = ERROR_BOT_DISABLED, message = message)
            }
            BotOwnerState.UNOWNED, BotOwnerState.ENABLED -> Unit
        }

        // Do not pre-start AgentForegroundService here. A scheduled task may
        // fail before it resolves a target session/provider, and Android FGS
        // background-start rules depend on the actual launch context. The
        // existing ChatViewModel execution path marks the session active only
        // when a real agent turn begins; that transition is the single owner of
        // the Agent FGS lifecycle.
        val resolved = withContext(Dispatchers.IO) { resolveSessionId(app, task, ownerBot) }
        val sessionId = resolved.sessionId ?: return resolved

        AppLogger.info(
            TAG,
            "running task=${task.id} label=\"${task.label}\" " +
                "mode=${task.targetMode.encode()} session=$sessionId wait=$waitForCompletion",
        )

        if (waitForCompletion) {
            var tierDenials = emptyList<OffloadPermissionManager.ScheduledTierDenial>()
            val result = OffloadPermissionManager.withUnattendedSession(sessionId, task.permissionTier) {
                try {
                    dispatch(app, task, sessionId, wait = true)
                } finally {
                    tierDenials = OffloadPermissionManager.consumeScheduledTierDenials(sessionId)
                }
            }
            val preview = runPreview(app, result.responseText, tierDenials)
            // Only a run that actually completed is a success: Busy (the session was mid-turn), Dropped,
            // Cancelled and NeedsAttention did not do the task.
            val ok = result.completed
            ScheduledTaskManager(app).markFired(task.id, sessionId, preview, ok = ok)
            postCompletionNotification(app, task, sessionId, preview)
            return RunOutcome(sessionId = sessionId)
        }

        // Fire-and-forget: the session is resolved and the prompt will claim
        // its own active-turn lifecycle through ChatViewModel. Run the actual
        // dispatch + completion off the app scope and return the session id
        // immediately so the UI can show "task started" without blocking.
        bgScope.launch {
            var tierDenials = emptyList<OffloadPermissionManager.ScheduledTierDenial>()
            val result = OffloadPermissionManager.withUnattendedSession(sessionId, task.permissionTier) {
                try {
                    dispatch(app, task, sessionId, wait = true)
                } finally {
                    tierDenials = OffloadPermissionManager.consumeScheduledTierDenials(sessionId)
                }
            }
            val preview = runPreview(app, result.responseText, tierDenials)
            // Only a run that actually completed is a success: Busy (the session was mid-turn), Dropped,
            // Cancelled and NeedsAttention did not do the task.
            val ok = result.completed
            ScheduledTaskManager(app).markFired(task.id, sessionId, preview, ok = ok)
            postCompletionNotification(app, task, sessionId, preview)
        }
        return RunOutcome(sessionId = sessionId)
    }

    private fun runPreview(
        context: Context,
        responseText: String?,
        denials: List<OffloadPermissionManager.ScheduledTierDenial>,
    ): String {
        val response = (responseText ?: "").take(200).ifBlank { "(no response)" }
        if (denials.isEmpty()) return response
        return buildString {
            append(response)
            denials.take(5).forEach { denial ->
                append("\n")
                append(context.getString(
                    com.openminis.app.R.string.scheduled_task_readonly_denied_preview,
                    denial.toolName,
                    denial.summary,
                ))
            }
        }.take(1_200)
    }

    /**
     * [T-android-scheduled-tasks-full] Branch on target mode, mirroring the iOS
     * App Intent set:
     *   NewSession / AppendToSession → AgentRunner.prompt (the prompt is
     *     sent as a fresh user turn; for append it lands in the existing
     *     session, for new it's the first turn of the freshly-created one).
     *   RerunMessage → AgentRunner.retry from the chosen message id (the
     *     message is replayed; task.prompt is ignored, matching iOS
     *     RetryRunIntent which has no prompt param).
     */
    private suspend fun dispatch(
        app: MinisApp,
        task: ScheduledTask,
        sessionId: String,
        wait: Boolean,
    ): AgentRunner.PromptResult = runCatching {
        val mode = task.targetMode
        if (mode is ScheduledTargetMode.RerunMessage) {
            AgentRunner.retry(
                context = app,
                sessionId = sessionId,
                messageId = mode.messageId,
                wait = wait,
                timeoutMs = RUN_TIMEOUT_MS,
            )
        } else {
            AgentRunner.prompt(
                context = app,
                sessionId = sessionId,
                text = task.prompt,
                attachments = emptyList(),
                thinkingLevel = null,
                wait = wait,
                timeoutMs = RUN_TIMEOUT_MS,
            )
        }
    }.getOrElse { t ->
        AppLogger.error(TAG, "task ${task.id} dispatch failed: ${t.message}")
        AgentRunner.PromptResult(
            status = "Error",
            responseText = "Error: ${t.message}",
            timedOut = false,
        )
    }

    private suspend fun resolveSessionId(
        app: MinisApp,
        task: ScheduledTask,
        ownerBot: com.openminis.app.data.db.BotEntity?,
    ): RunOutcome {
        return when (val mode = task.targetMode) {
            is ScheduledTargetMode.AppendToSession -> {
                if (app.chatRepository.getSession(mode.sessionId) == null) {
                    AppLogger.warning(TAG, "task ${task.id}: follow-up session ${mode.sessionId} gone — abort")
                    RunOutcome(
                        errorCode = ERROR_TARGET_SESSION_GONE,
                        message = "The chat this task follows up (${mode.sessionId}) no longer exists.",
                    )
                } else {
                    RunOutcome(sessionId = mode.sessionId)
                }
            }
            is ScheduledTargetMode.RerunMessage -> {
                if (app.chatRepository.getSession(mode.sessionId) == null) {
                    AppLogger.warning(TAG, "task ${task.id}: re-run session ${mode.sessionId} gone — abort")
                    RunOutcome(
                        errorCode = ERROR_TARGET_SESSION_GONE,
                        message = "The chat this task re-runs (${mode.sessionId}) no longer exists.",
                    )
                } else {
                    RunOutcome(sessionId = mode.sessionId)
                }
            }
            ScheduledTargetMode.NewSession -> {
                val explicitBinding = task.modelBinding
                val pinnedModelId = task.modelId
                val desiredBinding = ScheduledTaskPolicy.effectiveModelBinding(
                    taskBinding = explicitBinding,
                    legacyModelId = pinnedModelId,
                    botBinding = ownerBot?.modelBinding,
                )
                val boundEntry = if (pinnedModelId == null || desiredBinding != null) {
                    com.openminis.app.agent.BotModelResolver.resolve(app.providerRepository, desiredBinding)
                } else null
                if (desiredBinding != null && boundEntry == null) {
                    val ownerName = ownerBot?.name?.let { "Bot '$it'" } ?: "This routine"
                    return RunOutcome(
                        errorCode = ERROR_NO_PROVIDER,
                        message = "$ownerName's selected model is unavailable. Choose a usable model and try again.",
                    )
                }
                val seedModelId: String = when {
                    explicitBinding != null -> boundEntry?.model?.id
                    pinnedModelId != null -> pinnedModelId
                    ownerBot?.modelBinding != null -> boundEntry?.model?.id
                    else -> boundEntry?.model?.id
                        ?: app.providerRepository.allVisibleEntries().firstOrNull()?.baseModel?.id
                } ?: run {
                        AppLogger.warning(TAG, "task ${task.id}: no provider — abort")
                        return RunOutcome(
                            errorCode = ERROR_NO_PROVIDER,
                            message = "No model provider is configured. Add one under Settings → " +
                                "LLM Providers → Manage Providers, then run this task again.",
                        )
                    }
                val title = task.label.ifBlank { "Scheduled task" }
                val memoryOn = com.openminis.app.data.MemoryGlobalPrefs.isGlobalEnabled(app)
                val bindingToWrite: String? = boundEntry?.let {
                    com.openminis.app.data.model.ModelBinding.encodeEntry(it.id)
                }
                val session = app.chatRepository.createSession(
                    modelId = seedModelId,
                    title = title,
                    memoryEnabled = memoryOn,
                    botId = ownerBot?.id,
                    source = "scheduled",
                    modelBinding = bindingToWrite,
                )
                RunOutcome(sessionId = session.id)
            }
        }
    }

    private fun postCompletionNotification(
        context: Context,
        task: ScheduledTask,
        sessionId: String?,
        preview: String,
    ) {
        val openIntent = sessionId?.let { id ->
            Intent(Intent.ACTION_VIEW, Uri.parse("minis://session/$id")).apply {
                setPackage(context.packageName)
            }
        } ?: context.packageManager.getLaunchIntentForPackage(context.packageName)
        val notificationId = task.id.hashCode() and 0x7FFFFFFF
        val contentPi = openIntent?.let {
            PendingIntent.getActivity(
                context,
                notificationId,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val title = "Minis: ${task.label.ifBlank { "Scheduled task" }}"
        val notification = NotificationCompat.Builder(context, ScheduledTaskManager.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle(title)
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .apply { contentPi?.let { setContentIntent(it) } }
            .build()
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            nm.notify(notificationId, notification)
        } catch (e: SecurityException) {
            AppLogger.warning(TAG, "POST_NOTIFICATIONS denied — completion notice suppressed")
        }
    }
}
