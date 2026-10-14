package com.openminis.app.ui.chat

import com.openminis.app.agent.*

fun ChatViewModel.runCompactNow() = agentSession.runCompactNow()

internal suspend fun ChatViewModel.replyUsage(messageId: String) = agentSession.replyUsage(messageId)

fun ChatViewModel.compactBefore(dbMessageId: String, includesBoundary: Boolean = false) = agentSession.compactBefore(dbMessageId, includesBoundary)

internal fun ChatViewModel.compactAll(
    anchorIdxOverride: Int? = null,
    allowDuringProcessing: Boolean = false,
    onFinished: ((Boolean) -> Unit)? = null,
) = agentSession.compactAll(anchorIdxOverride, allowDuringProcessing, onFinished)

fun ChatViewModel.revertCompact() = agentSession.revertCompact()

internal fun ChatViewModel.reloadSessionFromDb() = agentSession.reloadSessionFromDb()

internal fun ChatViewModel.effectiveAgentHistory() = agentSession.effectiveAgentHistory()

fun ChatViewModel.compactAndSendPending(alsoEnableAutoCompact: Boolean = false) = agentSession.compactAndSendPending(alsoEnableAutoCompact)

fun ChatViewModel.sendPendingWithoutCompacting() = agentSession.sendPendingWithoutCompacting()

fun ChatViewModel.cancelCompactBeforeSend() = agentSession.cancelCompactBeforeSend()
