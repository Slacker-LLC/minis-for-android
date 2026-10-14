package com.openminis.app.ui.chat

import com.openminis.app.agent.AgentTurnHandle
import com.openminis.app.provider.LLMProvider
import com.openminis.app.agent.AgentSession.FallbackCandidate
import com.openminis.app.agent.*

fun ChatViewModel.enqueuePrompt(text: String, delivery: PendingDelivery = PendingDelivery.STEER) = agentSession.enqueuePrompt(text, delivery)

fun ChatViewModel.setQueuedDelivery(messageId: String, delivery: PendingDelivery) = agentSession.setQueuedDelivery(messageId, delivery)

fun ChatViewModel.withdrawQueuedMessage(messageId: String) = agentSession.withdrawQueuedMessage(messageId)

internal suspend fun ChatViewModel.injectQueuedPromptsAsNewTurn(
    finishedAssistantId: String,
    finishedAccumulatedText: String,
    finishedAllToolBlocks: List<AssistantBlock>,
) = agentSession.injectQueuedPromptsAsNewTurn(finishedAssistantId, finishedAccumulatedText, finishedAllToolBlocks)

fun ChatViewModel.sendMessage(text: String) = agentSession.sendMessage(text)

fun ChatViewModel.sendMessage(text: String, delivery: PendingDelivery) = agentSession.sendMessage(text, delivery)

internal fun ChatViewModel.submitPrompt(text: String, attachments: List<InputAttachment> = emptyList()) = agentSession.submitPrompt(text, attachments)

internal fun ChatViewModel.sendMessage(
    text: String,
    skipContextCheck: Boolean,
    request: AgentTurnHandle? = null,
    ownAttachments: List<InputAttachment> = emptyList(),
    delivery: PendingDelivery = PendingDelivery.STEER,
) = agentSession.sendMessage(text, skipContextCheck, request, ownAttachments, delivery)

internal fun ChatViewModel.setInlineError(errorText: String) = agentSession.setInlineError(errorText)

fun ChatViewModel.retryLast() = agentSession.retryLast()
