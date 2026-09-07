package com.openminis.app.agent

import android.content.Context
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.ui.chat.ChatViewModel
import com.openminis.app.ui.chat.InputAttachment
import com.openminis.app.ui.chat.SessionEventCapture
import com.openminis.app.ui.chat.SessionEventReplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Product-facing agent execution seam.
 *
 * All non-UI callers use this contract instead of reaching into
 * ChatViewModel.sendMessage or depending on the debug package. The current
 * implementation delegates to the compatibility-backed headless adapter while
 * the shared VM remains the single Agent Loop implementation.
 */
internal object AgentRunner {
    fun viewModelForCommand(context: Context, sessionId: String): ChatViewModel =
        HeadlessChatRunner.viewModelForCommand(context, sessionId)

    suspend fun notifyExternalMessage(context: Context, message: com.openminis.app.data.db.MessageEntity) =
        withContext(Dispatchers.Main) {
            viewModelForCommand(context, message.sessionId).acceptExternalMessage(message)
        }

    suspend fun ensureSession(context: Context, modelId: String? = null): String =
        HeadlessChatRunner.ensureSession(context, modelId)

    suspend fun applyModelOverride(
        context: Context,
        sessionId: String,
        modelEntryId: String?,
        modelGroupId: String?,
    ): String? = HeadlessChatRunner.applyModelOverride(context, sessionId, modelEntryId, modelGroupId)

    suspend fun prompt(
        context: Context,
        sessionId: String,
        text: String,
        attachments: List<InputAttachment> = emptyList(),
        thinkingLevel: ThinkingLevel? = null,
        chatOnly: Boolean = false,
        wait: Boolean,
        timeoutMs: Long,
    ): PromptResult = HeadlessChatRunner.prompt(
        context = context,
        sessionId = sessionId,
        text = text,
        attachments = attachments,
        thinkingLevel = thinkingLevel,
        chatOnly = chatOnly,
        wait = wait,
        timeoutMs = timeoutMs,
    ).toAgentResult()

    suspend fun retry(
        context: Context,
        sessionId: String,
        messageId: String?,
        wait: Boolean,
        timeoutMs: Long,
    ): PromptResult = HeadlessChatRunner.retry(
        context = context,
        sessionId = sessionId,
        messageId = messageId,
        wait = wait,
        timeoutMs = timeoutMs,
    ).toAgentResult()

    suspend fun rerunFromToolBlock(
        context: Context,
        sessionId: String,
        assistantMessageId: String,
        blockId: String,
        wait: Boolean,
        timeoutMs: Long,
    ): PromptResult = HeadlessChatRunner.rerunFromToolBlock(
        context = context,
        sessionId = sessionId,
        assistantMessageId = assistantMessageId,
        blockId = blockId,
        wait = wait,
        timeoutMs = timeoutMs,
    ).toAgentResult()

    suspend fun compact(
        context: Context,
        sessionId: String,
        wait: Boolean,
        timeoutMs: Long,
        messageId: String? = null,
        includesBoundary: Boolean = true,
    ): CompactResult = HeadlessChatRunner.compact(
        context = context,
        sessionId = sessionId,
        wait = wait,
        timeoutMs = timeoutMs,
        messageId = messageId,
        includesBoundary = includesBoundary,
    ).toAgentResult()

    suspend fun revertCompact(context: Context, sessionId: String) =
        HeadlessChatRunner.revertCompact(context, sessionId)

    suspend fun cancel(context: Context, sessionId: String): Boolean =
        HeadlessChatRunner.cancel(context, sessionId)

    fun isInFlight(context: Context, sessionId: String): Boolean =
        viewModelForCommand(context, sessionId).isStreaming.value

    suspend fun waitForSettle(
        context: Context,
        sessionId: String,
        timeoutMs: Long,
    ): Boolean {
        val vm = viewModelForCommand(context, sessionId)
        return withContext(Dispatchers.Default) {
            withTimeoutOrNull(timeoutMs.coerceAtLeast(0L)) {
                if (!vm.isStreaming.value) true else vm.isStreaming.first { !it }
            } ?: false
        }
    }

    suspend fun awaitStreamExit(context: Context, sessionId: String): Boolean =
        viewModelForCommand(context, sessionId).awaitStreamExit()

    suspend fun selectModel(context: Context, sessionId: String, entryId: String): Pair<String, String> =
        HeadlessChatRunner.selectModel(context, sessionId, entryId)

    suspend fun selectThinkingLevel(context: Context, sessionId: String, level: ThinkingLevel): String =
        HeadlessChatRunner.selectThinkingLevel(context, sessionId, level)

    suspend fun sessionEvents(
        context: Context,
        sessionId: String,
        afterSeq: Long?,
    ): SessionEventReplay = HeadlessChatRunner.sessionEvents(context, sessionId, afterSeq)

    suspend fun sessionSnapshotWithWatermark(
        context: Context,
        sessionId: String,
        includeReasoning: Boolean = true,
    ): SessionEventCapture<JSONObject> = HeadlessChatRunner.sessionSnapshotWithWatermark(
        context,
        sessionId,
        includeReasoning,
    )

    fun forget(sessionId: String) = HeadlessChatRunner.forget(sessionId)

    data class PromptResult(
        val status: String,
        val responseText: String?,
        val timedOut: Boolean,
        val streamExited: Boolean = true,
        val deletedMessageCount: Int = 0,
        val retriedMessageId: String? = null,
    )

    data class CompactResult(
        val status: String,
        val summary: String?,
        val timedOut: Boolean,
        val error: String?,
    )

    private fun HeadlessChatRunner.PromptResult.toAgentResult() = PromptResult(
        status = status,
        responseText = responseText,
        timedOut = timedOut,
        streamExited = streamExited,
        deletedMessageCount = deletedMessageCount,
        retriedMessageId = retriedMessageId,
    )

    private fun HeadlessChatRunner.CompactResult.toAgentResult() = CompactResult(
        status = status,
        summary = summary,
        timedOut = timedOut,
        error = error,
    )
}
