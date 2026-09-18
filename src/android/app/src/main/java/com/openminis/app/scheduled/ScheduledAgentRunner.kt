package com.openminis.app.scheduled

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.openminis.app.MinisApp
import com.openminis.app.agent.AgentRunner
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

        // Do not pre-start AgentForegroundService here. A scheduled task may
        // fail before it resolves a target session/provider, and Android FGS
        // background-start rules depend on the actual launch context. The
        // existing ChatViewModel execution path marks the session active only
        // when a real agent turn begins; that transition is the single owner of
        // the Agent FGS lifecycle.
        val resolved = withContext(Dispatchers.IO) { resolveSessionId(app, task) }
        val sessionId = resolved.sessionId ?: return resolved

        AppLogger.info(
            TAG,
            "running task=${task.id} label=\"${task.label}\" " +
                "mode=${task.targetMode.encode()} session=$sessionId wait=$waitForCompletion",
        )

        if (waitForCompletion) {
            val result = dispatch(app, task, sessionId, wait = true)
            val preview = (result.responseText ?: "").take(200).ifBlank { "(no response)" }
            val ok = result.status != "Error" && result.status != "Timeout"
            ScheduledTaskManager(app).markFired(task.id, sessionId, preview, ok = ok)
            postCompletionNotification(app, task, sessionId, preview)
            return RunOutcome(sessionId = sessionId)
        }

        // Fire-and-forget: the session is resolved and the prompt will claim
        // its own active-turn lifecycle through ChatViewModel. Run the actual
        // dispatch + completion off the app scope and return the session id
        // immediately so the UI can show "task started" without blocking.
        bgScope.launch {
            val result = dispatch(app, task, sessionId, wait = true)
            val preview = (result.responseText ?: "").take(200).ifBlank { "(no response)" }
            val ok = result.status != "Error" && result.status != "Timeout"
            ScheduledTaskManager(app).markFired(task.id, sessionId, preview, ok = ok)
            postCompletionNotification(app, task, sessionId, preview)
        }
        return RunOutcome(sessionId = sessionId)
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

    private suspend fun resolveSessionId(app: MinisApp, task: ScheduledTask): RunOutcome {
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
                val defaultGroupId =
                    if (explicitBinding == null && pinnedModelId == null) {
                        app.providerRepository.defaultPrimaryGroupId
                    } else null

                val seedModelId: String = pinnedModelId
                    ?: run {
                        val groupIdForSeed: String? = explicitBinding
                            ?.let { parseGroupIdFromBinding(it) }
                            ?: defaultGroupId
                        val entryIdForSeed: String? = explicitBinding
                            ?.let { parseEntryIdFromBinding(it) }

                        entryIdForSeed
                            ?.let { eid -> app.providerRepository.config.value.modelEntries.firstOrNull { it.id == eid }?.model?.id }
                            ?: groupIdForSeed
                                ?.let { gid -> app.providerRepository.group(gid) }
                                // Credential-aware: unattended runs skip members that cannot authenticate.
                                ?.let { g -> app.providerRepository.availableMemberEntries(g).firstOrNull()?.model?.id }
                    }
                    ?: app.providerRepository.allVisibleEntries().firstOrNull()?.baseModel?.id
                    ?: run {
                        AppLogger.warning(TAG, "task ${task.id}: no provider — abort")
                        return RunOutcome(
                            errorCode = ERROR_NO_PROVIDER,
                            message = "No model provider is configured. Add one under Settings → " +
                                "LLM Providers → Manage Providers, then run this task again.",
                        )
                    }
                val title = task.label.ifBlank { "Scheduled task" }
                val memoryOn = com.openminis.app.data.MemoryGlobalPrefs.isGlobalEnabled(app)
                val session = app.chatRepository.createSession(
                    modelId = seedModelId,
                    title = title,
                    memoryEnabled = memoryOn,
                )
                app.chatRepository.dao.updateSource(session.id, "scheduled")

                val bindingToWrite: String? = explicitBinding
                    ?: defaultGroupId?.let { """{"type":"group","groupId":"$it"}""" }
                if (bindingToWrite != null) {
                    app.chatRepository.updateSessionBinding(session.id, bindingToWrite, seedModelId)
                }
                RunOutcome(sessionId = session.id)
            }
        }
    }

    private fun parseGroupIdFromBinding(json: String): String? = runCatching {
        val o = org.json.JSONObject(json)
        if (o.optString("type") == "group") {
            o.optString("groupId").takeIf { it.isNotEmpty() }
        } else null
    }.getOrNull()

    private fun parseEntryIdFromBinding(json: String): String? = runCatching {
        val o = org.json.JSONObject(json)
        if (o.optString("type") == "entry") {
            o.optString("entryId").takeIf { it.isNotEmpty() }
        } else null
    }.getOrNull()

    private fun postCompletionNotification(
        context: Context,
        task: ScheduledTask,
        sessionId: String,
        preview: String,
    ) {
        val deepLink = Uri.parse("minis://session/$sessionId")
        val openIntent = Intent(Intent.ACTION_VIEW, deepLink).apply {
            setPackage(context.packageName)
        }
        val notificationId = task.id.hashCode() and 0x7FFFFFFF
        val contentPi = PendingIntent.getActivity(
            context,
            notificationId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = "Minis: ${task.label.ifBlank { "Scheduled task" }}"
        val notification = NotificationCompat.Builder(context, ScheduledTaskManager.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle(title)
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setContentIntent(contentPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
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
