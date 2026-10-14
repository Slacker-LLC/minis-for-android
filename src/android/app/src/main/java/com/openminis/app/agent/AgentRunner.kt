package com.openminis.app.agent

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.remote.RPCException
import com.openminis.app.ui.chat.InputAttachment
import com.openminis.app.ui.chat.SessionEventCapture
import com.openminis.app.ui.chat.SessionEventHub
import com.openminis.app.ui.chat.SessionEventReplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import com.openminis.app.data.repository.allVisibleEntries

/**
 * Shared product execution entry. All callers reuse the canonical AgentSession and per-request handle.
 */
internal object AgentRunner {

    private fun app(context: Context): MinisApp =
        context.applicationContext as? MinisApp
            ?: throw RPCException(-32000, "MinisApp not initialized")

    internal fun sessionForCommand(context: Context, sessionId: String): AgentSession {
        val app = app(context)
        return AgentSessionStore.getOrCreate(sessionId) {
            AgentSession(sessionId, app.chatRepository, app.providerRepository, app.applicationContext,
                app.memoryRepository, app.skillRepository, app.mcpRepository, app.botRepository)
        }
    }

    /** The request owns only its explicit text/attachments, never the UI composer. */
    @androidx.annotation.MainThread
    fun submit(viewModel: AgentSession, text: String, attachments: List<InputAttachment> = emptyList(), taskId: String? = null, requestId: String = java.util.UUID.randomUUID().toString(), userInput: Boolean = false): AgentTurnHandle =
        viewModel.submitPrompt(text, attachments, taskId, requestId, userInput)

    suspend fun notifyExternalMessage(context: Context, message: com.openminis.app.data.db.MessageEntity) =
        withContext(Dispatchers.Main) {
            sessionForCommand(context, message.sessionId).acceptExternalMessage(message)
        }

    fun isInFlight(context: Context, sessionId: String): Boolean =
        sessionForCommand(context, sessionId).isStreaming.value

    suspend fun waitForSettle(
        context: Context,
        sessionId: String,
        timeoutMs: Long,
    ): Boolean {
        val vm = sessionForCommand(context, sessionId)
        return vm.execution.awaitExit(timeoutMs)
    }

    suspend fun awaitStreamExit(context: Context, sessionId: String): Boolean =
        sessionForCommand(context, sessionId).awaitStreamExit()

    /**
     * Ensure a session exists in the DB before obtaining its execution owner. Mirrors
     * the in-app flow that creates a draft session lazily; for RPC-driven
     * automation we materialize it eagerly so subsequent reads can resolve
     * the id.
     */
    suspend fun ensureSession(context: Context, modelId: String? = null, source: String? = null): String =
        withContext(Dispatchers.IO) {
            val app = app(context)
            val resolvedModel = modelId
                ?: app.providerRepository.allVisibleEntries().firstOrNull()?.baseModel?.id
                ?: "unknown"
            val s = app.chatRepository.createSession(modelId = resolvedModel, title = null, source = source)
            s.id
        }

    /** Apply an entry-only override; without one the session follows its Main-slot resolver. */
    suspend fun applyModelOverride(
        context: Context,
        sessionId: String,
        modelEntryId: String?,
    ): String? = withContext(Dispatchers.IO) {
        val app = app(context)
        val cfg = app.providerRepository.config.value
        val resolvedEntryId = modelEntryId
            ?: com.openminis.app.agent.BotModelResolver.resolve(app.providerRepository, null)?.id
            ?: return@withContext null
        val entry = cfg.modelEntries.firstOrNull { it.id == resolvedEntryId }
            ?: throw RPCException(-32602, "Entry not found: $resolvedEntryId")
        val instance = cfg.instances.firstOrNull { it.id == entry.providerInstanceId }
            ?: throw RPCException(-32602, "Provider instance for entry not found")
        if (!instance.isEnabled) throw RPCException(-32602, "Provider instance is disabled")
        app.chatRepository.updateSessionBinding(
            sessionId,
            com.openminis.app.data.model.ModelBinding.encodeEntry(entry.id),
            entry.baseModel.id,
        )
        entry.model.displayName
    }

    /**
     * Submit to the canonical session. Waiting observes this request's completion and cleanup,
     * never another turn's busy flag. Timeout/caller cancellation stops only the owned request.
     */
    suspend fun prompt(
        context: Context,
        sessionId: String,
        text: String,
        attachments: List<InputAttachment> = emptyList(),
        thinkingLevel: ThinkingLevel? = null,
        chatOnly: Boolean = false,
        wait: Boolean,
        timeoutMs: Long,
        taskId: String? = null,
        awaitCancellationCleanup: Boolean = false,
        requestId: String = java.util.UUID.randomUUID().toString(),
        onAccepted: ((String) -> Unit)? = null,
    ): PromptResult = withContext(Dispatchers.Main) {
        require(requestId.isNotBlank() && requestId.length <= 200) { "invalid_request_id" }
        val stableId = java.util.UUID.nameUUIDFromBytes("$sessionId:$requestId".toByteArray(Charsets.UTF_8)).toString()
        val prior = app(context).chatRepository.dao.getMessageById(stableId)
        if (prior != null) {
            val status = when (prior.requestState) {
                "COMPLETED" -> "Completed"
                "FAILED" -> "Error"
                "RUNNING" -> "Running"
                "PENDING" -> "Accepted"
                else -> "NeedsAttention"
            }
            if (prior.requestState in setOf("PENDING", "RUNNING", "COMPLETED")) onAccepted?.invoke(prior.id)
            return@withContext PromptResult(status, responseAfter(context, sessionId, prior.id), false,
                streamExited = prior.requestState != "RUNNING", requestMessageId = prior.id)
        }
        val vm = sessionForCommand(context, sessionId)
        // Apply per-call thinking override BEFORE sendMessage so streamMessage
        // picks up the new level. Caller passes null to keep the VM's existing
        // setting (default OFF for a fresh VM, last user-set value otherwise).
        //
        // AgentSession.setThinkingLevel silently no-ops when the active model
        // hasn't resolved yet (currentModelSupportsReasoning checks
        // `currentModel?.supportsReasoning == true`, and `currentModel` is
        // populated on a sessionScope coroutine). For a freshly-created VM
        // bound to a new session, the resolver may not have run by the time we
        // reach this line — wait until activeEntryId flips non-null so the
        // override actually applies.
        if (thinkingLevel != null) {
            // Wait off Main: AgentSession's init coroutine runs on sessionScope
            // (Main.immediate), and Flow.first() suspends the calling dispatcher
            // until the value arrives. If we wait on Main here we deadlock — the
            // VM's loadSession() never gets to populate _activeEntryId. Hop to
            // Default for the wait, then come back to Main for setThinkingLevel.
            // setThinkingLevel itself silently no-ops on a model that doesn't
            // support reasoning — that gating is intentional UI parity, the
            // 3 s ceiling here just keeps us from blocking forever if the VM
            // never resolves a model (no provider configured, etc.).
            withContext(Dispatchers.Default) {
                withTimeoutOrNull(3000L) {
                    vm.activeEntryId.first { it != null }
                }
            }
            vm.setThinkingLevel(thinkingLevel)
        }
        // Wait for AgentSession.currentProvider to resolve before sendMessage.
        // The VM populates currentProvider on a sessionScope (Main.immediate)
        // coroutine driven by providerRepository.config + the session's bound
        // entry. activeEntryId flips non-null in the SAME block that assigns
        // currentProvider (see AgentSession.kt ~L2030/2230/2265/2558), so we
        // use it as the readiness signal. Without this wait, sendMessage hits
        // its `currentProvider == null` early-return and the RPC returns
        // status:Completed responseText:null — the bug that blocked Step 2 e2e.
        //
        // Hop off Main for the wait: the VM's resolver runs on Main.immediate,
        // so suspending Main here would deadlock the resolver (same pattern as
        // the thinkingLevel wait above).
        val ready = withContext(Dispatchers.Default) {
            withTimeoutOrNull(5000L) {
                vm.sessionLoaded.first { it }
                vm.activeEntryId.first { it != null }
            }
        }
        if (ready == null) {
            return@withContext PromptResult(
                status = "Error",
                responseText = "no_provider_resolved_in_5s",
                timedOut = false,
            )
        }
        if (vm.isStreaming.value) {
            return@withContext PromptResult("Busy", "session_busy", false, streamExited = false)
        }
        if (chatOnly) vm.chatOnlyForNextTurn = true
        // The request carries its own attachments; the user's composer is left as it is.
        val request = submit(vm, text, attachments, taskId, requestId)
        val accepted = try {
            withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) { request.accepted.await() }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            request.cancel()
            if (awaitCancellationCleanup) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { request.result.await() }
            throw cancelled
        }
        if (accepted == null && !request.result.isCompleted) {
            request.cancel()
            return@withContext PromptResult("Timeout", "request_acceptance_not_observed", true, streamExited = false)
        }
        if (accepted != null) onAccepted?.invoke(accepted)
        if (!wait && !request.result.isCompleted) {
            return@withContext PromptResult(if (request.runId == null) "Accepted" else "Running", null, false, streamExited = false, requestMessageId = accepted)
        }
        val outcome = try {
            if (request.result.isCompleted) request.result.await() else {
                withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) { request.result.await() }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // The caller that waited for this turn was stopped (a parent turn waiting on its sub agent or
            // Ralph round): the turn it owned stops with it instead of running on unobserved.
            request.cancel()
            if (awaitCancellationCleanup) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { request.result.await() }
            throw e
        }
        if (outcome == null) request.cancel()
        val streamExited = outcome != null || if (awaitCancellationCleanup) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { request.result.await() }
            true
        } else withTimeoutOrNull(5_000L) { request.result.await(); true } == true
        if (outcome is AgentTurnOutcome.Rejected) vm.chatOnlyForNextTurn = false
        PromptResult(
            status = statusOf(outcome),
            responseText = reasonOf(outcome) ?: responseAfter(context, sessionId, request.userMessageId),
            timedOut = outcome == null,
            streamExited = streamExited,
            requestMessageId = request.userMessageId,
        )
    }

    /** The wire status of one request's outcome; null means the wait timed out. */
    private fun statusOf(outcome: AgentTurnOutcome?): String = when (outcome) {
        AgentTurnOutcome.Completed -> "Completed"
        AgentTurnOutcome.Cancelled -> "Cancelled"
        is AgentTurnOutcome.Failed -> "Error"
        is AgentTurnOutcome.NeedsAttention -> "NeedsAttention"
        is AgentTurnOutcome.Rejected -> "Dropped"
        null -> "Timeout"
    }

    private fun reasonOf(outcome: AgentTurnOutcome?): String? = when (outcome) {
        is AgentTurnOutcome.Failed -> outcome.reason
        is AgentTurnOutcome.NeedsAttention -> outcome.reason
        is AgentTurnOutcome.Rejected -> outcome.reason
        else -> null
    }

    /**
     * The last assistant text after [userMessageId], up to the next user turn. Restricted to rows after
     * this request's user message, so a rejected or failed request never returns an earlier reply.
     */
    internal suspend fun responseAfter(context: Context, sessionId: String, userMessageId: String?): String? {
        val msgs = app(context).chatRepository.dao.loadMessages(sessionId)
        val ownRun = userMessageId?.let { id -> msgs.firstOrNull { it.id == id }?.requestRunId }
        if (ownRun != null) return msgs.lastOrNull { it.role == "assistant" && it.requestRunId == ownRun }?.let { extractText(it.partsJson) }
        val userIndex = userMessageId?.let { id -> msgs.indexOfFirst { it.id == id } } ?: -1
        if (userIndex < 0) return null
        return msgs.drop(userIndex + 1).filter { it.isConversationMessage }.takeWhile { it.role != "user" || extractText(it.partsJson).isNullOrBlank() }
            .lastOrNull { it.role == "assistant" }?.let { extractText(it.partsJson) }
    }

    /**
     * Wait for one accepted retry/rerun [request]. On timeout the request is cancelled and its exit
     * awaited, so a Timeout result never leaves the turn running behind the caller.
     */
    private suspend fun awaitRequest(request: AgentTurnHandle, timeoutMs: Long): Pair<AgentTurnOutcome?, Boolean> {
        val outcome = try {
            withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) { request.result.await() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            request.cancel()
            throw e
        }
        if (outcome != null) return outcome to true
        request.cancel()
        val exited = withTimeoutOrNull(5_000L) { request.result.await(); true } == true
        return null to exited
    }

    suspend fun retry(
        context: Context,
        sessionId: String,
        messageId: String?,
        wait: Boolean,
        timeoutMs: Long,
        onAccepted: ((String) -> Unit)? = null,
    ): PromptResult = withContext(Dispatchers.Main) {
        val app = app(context)
        val vm = sessionForCommand(context, sessionId)
        val targetMsgId = messageId ?: run {
            val msgs = app.chatRepository.dao.loadMessages(sessionId)
            msgs.lastOrNull { it.role == "user" }?.id
                ?: throw RPCException(-32602, "Session has no user messages")
        }
        // Validate it points at a user message.
        val all = app.chatRepository.dao.loadMessages(sessionId)
        val target = all.firstOrNull { it.id == targetMsgId }
            ?: throw RPCException(-32602, "Message not found in session")
        if (target.role != "user") throw RPCException(-32602, "Target is not a user message")
        val deletedCount = all.size - all.indexOf(target) - 1

        // Same readiness gate as prompt() — retryFromMessage hits the same
        // currentProvider-null early-return if invoked before resolve.
        val ready = withContext(Dispatchers.Default) {
            withTimeoutOrNull(5000L) {
                vm.activeEntryId.first { it != null }
            }
        }
        if (ready == null) {
            return@withContext PromptResult(
                status = "Error",
                responseText = "no_provider_resolved_in_5s",
                timedOut = false,
                deletedMessageCount = deletedCount,
                retriedMessageId = targetMsgId,
            )
        }
        // The retry's own completion, never the session's shared busy flag: a refused retry, another
        // turn finishing, or a failure must not read as this retry completing.
        val request = AgentTurnHandle()
        if (!vm.retryFromMessage(targetMsgId, request)) {
            return@withContext PromptResult(
                status = "Dropped",
                responseText = (if (request.result.isCompleted) reasonOf(request.result.await()) else null) ?: "retry_rejected",
                timedOut = false,
                streamExited = true,
                retriedMessageId = targetMsgId,
            )
        }
        if (onAccepted != null) {
            val message = try { withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) { request.accepted.await() } }
            catch (cancelled: kotlinx.coroutines.CancellationException) { request.cancel(); throw cancelled }
            if (message != null) onAccepted(message)
            else if (!request.result.isCompleted) {
                request.cancel()
                return@withContext PromptResult("Timeout", "retry_acceptance_not_observed", true, streamExited = false)
            }
        }
        if (!wait) {
            return@withContext PromptResult(
                status = "Retrying",
                responseText = null,
                timedOut = false,
                streamExited = false,
                deletedMessageCount = deletedCount,
                retriedMessageId = targetMsgId,
            )
        }
        val (outcome, streamExited) = awaitRequest(request, timeoutMs)
        PromptResult(
            status = statusOf(outcome),
            responseText = reasonOf(outcome) ?: responseAfter(context, sessionId, targetMsgId),
            timedOut = outcome == null,
            streamExited = streamExited,
            deletedMessageCount = deletedCount,
            retriedMessageId = targetMsgId,
        )
    }

    /**
     * [T-android-rerun-from-tool-block-position] Drive
     * [AgentSession.rerunFromToolBlock] headlessly so automation / e2e can
     * exercise the block-boundary re-run (the in-app trigger is a tool-bubble
     * long-press). [assistantMessageId] is the UI assistant bubble id and
     * [blockId] is the tool block's id (== its tool_use id). Returns the same
     * [PromptResult] shape as [retry]; `responseText` is the latest assistant
     * text after the re-run settles (when [wait]).
     */
    suspend fun rerunFromToolBlock(
        context: Context,
        sessionId: String,
        assistantMessageId: String,
        blockId: String,
        wait: Boolean,
        timeoutMs: Long,
    ): PromptResult = withContext(Dispatchers.Main) {
        val app = app(context)
        val vm = sessionForCommand(context, sessionId)
        val ready = withContext(Dispatchers.Default) {
            withTimeoutOrNull(5000L) { vm.activeEntryId.first { it != null } }
        }
        if (ready == null) {
            return@withContext PromptResult(
                status = "Error",
                responseText = "no_provider_resolved_in_5s",
                timedOut = false,
                deletedMessageCount = 0,
                retriedMessageId = assistantMessageId,
            )
        }
        val before = app.chatRepository.dao.loadMessages(sessionId).size
        // The in-memory assistant bubble id is a volatile `assistant_<ts>`
        // runtime id, not the DB row id a harness reads from chat.messages.list.
        // Resolve the live bubble that owns this tool block; fall back to the
        // caller-supplied id (covers a freshly-reloaded session whose bubble id
        // IS the DB row id).
        val liveAssistantId = vm.assistantMessageIdForToolBlock(blockId) ?: assistantMessageId
        val request = AgentTurnHandle()
        if (!vm.rerunFromToolBlock(liveAssistantId, blockId, request)) {
            return@withContext PromptResult(
                status = "Error",
                responseText = "rerun_rejected: " +
                    ((if (request.result.isCompleted) reasonOf(request.result.await()) else null) ?: "streaming / not_found / not_tool_block"),
                timedOut = false,
                deletedMessageCount = 0,
                retriedMessageId = assistantMessageId,
            )
        }
        if (!wait) {
            return@withContext PromptResult(
                status = "Rerunning",
                responseText = null,
                timedOut = false,
                streamExited = false,
                deletedMessageCount = 0,
                retriedMessageId = assistantMessageId,
            )
        }
        // Timeout cancels this rerun and waits for it to exit, so streamExited is a measured fact.
        val (outcome, streamExited) = awaitRequest(request, timeoutMs)
        val msgs = app.chatRepository.dao.loadMessages(sessionId)
        PromptResult(
            status = statusOf(outcome),
            responseText = reasonOf(outcome) ?: msgs.lastOrNull { it.role == "assistant" }?.let { extractText(it.partsJson) },
            timedOut = outcome == null,
            streamExited = streamExited,
            deletedMessageCount = (before - msgs.size).coerceAtLeast(0),
            retriedMessageId = assistantMessageId,
        )
    }

    /** Starts one owned compaction; failure/cancellation never reuses an earlier summary as success. */
    suspend fun compact(
        context: Context,
        sessionId: String,
        wait: Boolean,
        timeoutMs: Long,
        messageId: String? = null,
        includesBoundary: Boolean = true,
    ): CompactResult = withContext(Dispatchers.Main) {
        val vm = sessionForCommand(context, sessionId)
        // Same readiness wait as prompt(): compactAll() needs currentProvider
        // resolved before it can call provider.sendMessage for the summary.
        val ready = withContext(Dispatchers.Default) {
            withTimeoutOrNull(5000L) {
                vm.activeEntryId.first { it != null }
            }
        }
        if (ready == null) {
            return@withContext CompactResult(
                status = "Error",
                summary = null,
                timedOut = false,
                error = "no_provider_resolved_in_5s",
            )
        }
        if (vm.isStreaming.value) {
            return@withContext CompactResult(
                status = "Error",
                summary = null,
                timedOut = false,
                error = "stream_in_progress",
            )
        }
        if (vm.isCompacting.value) {
            return@withContext CompactResult(
                status = "Error",
                summary = null,
                timedOut = false,
                error = "compact_already_in_progress",
            )
        }
        if (vm.execution.isClosed || vm.isMutating.value) {
            return@withContext CompactResult("Error", null, false, "session_unavailable")
        }
        val finished = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val anchor = messageId?.takeUnless { includesBoundary }?.let { id -> vm.agentHistory.indexOfLast { it.dbMessageId == id }.takeIf { it >= 0 } }
        vm.compactAll(anchorIdxOverride = anchor, onFinished = { finished.complete(it) })
        if (finished.isCompleted) {
            return@withContext CompactResult("NoOp", vm.compactSummary.value, false, "compact_skipped_no_change")
        }
        val ownedJob = vm.compactJob
        if (!wait) return@withContext CompactResult("Running", null, false, null)
        val succeeded = try {
            withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) { finished.await() }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            ownedJob?.cancel()
            throw cancelled
        }
        if (succeeded == null) ownedJob?.cancel()
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { ownedJob?.join() }
        CompactResult(
            status = when (succeeded) { true -> "Completed"; false -> "Error"; null -> "Timeout" },
            summary = if (succeeded == true) vm.compactSummary.value else null,
            timedOut = succeeded == null,
            error = when (succeeded) { true -> null; false -> "compact_failed_or_cancelled"; null -> "compact_timeout" },
        )
    }

    /** Revert and wait for the serialized history reload. */
    suspend fun revertCompact(context: Context, sessionId: String) = withContext(Dispatchers.Main) {
        val vm = sessionForCommand(context, sessionId)
        val ready = withContext(Dispatchers.Default) {
            withTimeoutOrNull(5000L) {
                vm.activeEntryId.first { it != null }
            }
        }
        if (ready == null) throw RPCException(-32000, "AgentSession did not resolve a provider within 5s")
        vm.revertCompact()
        vm.execution.awaitExit()
    }

    internal suspend fun cancelOwnedRequest(sessionId: String, requestId: String): Boolean = withContext(Dispatchers.Main) {
        AgentSessionStore.existing(sessionId)?.execution?.cancelRequest(requestId) ?: false
    }

    suspend fun cancel(context: Context, sessionId: String): Boolean = withContext(Dispatchers.Main) {
        // Nothing has ever opened this session: there is nothing to cancel, and
        // cancelling must not build a ViewModel just to find that out.
        if (!AgentSessionStore.hasSession(sessionId)) return@withContext false
        val vm = sessionForCommand(context, sessionId)
        val wasRunning = vm.isStreaming.value
        if (wasRunning) vm.cancelStream()
        wasRunning
    }

    /**
     * [T-android-thinking-level-arch] Switch the LIVE session's bound model
     * mid-session — exactly what the in-app model picker does, by calling the
     * same [AgentSession.selectEntry]. Unlike passing a fresh `modelEntryId` to
     * chat.prompt (which only rewrites the DB binding and is ignored by the
     * already-loaded canonical session), this re-resolves `currentModel`/`currentProvider`
     * on the live VM. selectEntry deliberately leaves the thinking level
     * untouched, so this is the seam for verifying cross-model thinking-level
     * transfer. Returns (resolved model displayName, current thinking level
     * name) so callers can assert the transfer without a second round-trip.
     */
    suspend fun selectModel(context: Context, sessionId: String, entryId: String):
        Pair<String, String> = withContext(Dispatchers.Main) {
        val vm = sessionForCommand(context, sessionId)
        withContext(Dispatchers.Default) {
            withTimeoutOrNull(5000L) {
                vm.activeEntryId.first { it != null }
            }
        }
        vm.selectEntry(entryId)
        vm.modelName.value to vm.thinkingLevel.value.name
    }

    /** Set the same persisted thinking override that Android's inline picker uses. */
    suspend fun selectThinkingLevel(context: Context, sessionId: String, level: ThinkingLevel): String =
        withContext(Dispatchers.Main) {
            val vm = sessionForCommand(context, sessionId)
            withContext(Dispatchers.Default) {
                withTimeoutOrNull(3000L) {
                    vm.activeEntryId.first { it != null }
                }
            }
            vm.setThinkingLevel(level)
            vm.thinkingLevel.value.name
        }

    /**
     * Durable replay API used by the WebSocket transport. Activation happens
     * before the read so the first event after a process restart resumes at
     * Room's high-water mark instead of reusing a sequence number.
     */
    suspend fun sessionEvents(
        context: Context,
        sessionId: String,
        afterSeq: Long?,
    ): SessionEventReplay = withContext(Dispatchers.IO) {
        val app = app(context)
        SessionEventHub.activateSession(app.database.chatDao(), sessionId)
        SessionEventHub.replayDurable(app.database.chatDao(), sessionId, afterSeq)
    }

    suspend fun sessionSnapshotWithWatermark(
        context: Context,
        sessionId: String,
        includeReasoning: Boolean = true,
    ): SessionEventCapture<JSONObject> =
        com.openminis.app.remote.SessionSnapshotReader.capture(context, sessionId, includeReasoning)

    /** Drop the execution owner for [sessionId] (used after delete). */
    fun forget(sessionId: String) = AgentSessionStore.release(sessionId)

    private fun extractText(partsJson: String): String? {
        return try {
            val arr = org.json.JSONArray(partsJson)
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("type") == "text") sb.append(o.optString("value", ""))
            }
            sb.toString().ifEmpty { null }
        } catch (_: Exception) { partsJson.ifEmpty { null } }
    }

    data class PromptResult(
        val status: String,
        val responseText: String?,
        val timedOut: Boolean,
        val streamExited: Boolean = true,
        val deletedMessageCount: Int = 0,
        val retriedMessageId: String? = null,
        val requestMessageId: String? = null,
    ) {
        val completed: Boolean get() = status == "Completed" && !timedOut
    }

    data class CompactResult(
        val status: String,
        val summary: String?,
        val timedOut: Boolean,
        val error: String?,
    )
}
