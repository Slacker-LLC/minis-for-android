package com.openminis.app.ui.chat

import com.openminis.app.agent.AgentTurnHandle
import com.openminis.app.agent.*

fun ChatViewModel.clearChat() = agentSession.clearChat()

fun ChatViewModel.markShareInjected() = agentSession.markShareInjected()

fun ChatViewModel.clearShareInjectedFlag() = agentSession.clearShareInjectedFlag()

fun ChatViewModel.addAttachmentFromStagedShare(file: java.io.File) = agentSession.addAttachmentFromStagedShare(file)

fun ChatViewModel.rerunFromToolBlock(assistantMessageId: String, blockId: String) = agentSession.rerunFromToolBlock(assistantMessageId, blockId)

internal fun ChatViewModel.rerunFromToolBlock(assistantMessageId: String, blockId: String, request: AgentTurnHandle?) = agentSession.rerunFromToolBlock(assistantMessageId, blockId, request)

fun ChatViewModel.regenerateAssistantMessage(messageId: String) = agentSession.regenerateAssistantMessage(messageId)

fun ChatViewModel.messagesAfter(messageId: String) = agentSession.messagesAfter(messageId)

fun ChatViewModel.toggleReplySpeech(messageId: String, displayIndex: Int, rawMarkdown: String) = agentSession.toggleReplySpeech(messageId, displayIndex, rawMarkdown)

fun ChatViewModel.toggleReplySpeechPause() = agentSession.toggleReplySpeechPause()

fun ChatViewModel.readSelectionAloud(text: String) = agentSession.readSelectionAloud(text)

fun ChatViewModel.stopReplySpeech() = agentSession.stopReplySpeech()

fun ChatViewModel.retryFromMessage(messageId: String) = agentSession.retryFromMessage(messageId)

internal fun ChatViewModel.retryFromMessage(messageId: String, request: AgentTurnHandle?) = agentSession.retryFromMessage(messageId, request)

fun ChatViewModel.editMessage(messageId: String) = agentSession.editMessage(messageId)

internal fun ChatViewModel.guessMimeType(fileName: String, fallback: String) = agentSession.guessMimeType(fileName, fallback)

fun ChatViewModel.cancelEdit() = agentSession.cancelEdit()

fun ChatViewModel.deleteSingleAssistantMessage(messageId: String) = agentSession.deleteSingleAssistantMessage(messageId)

fun ChatViewModel.deleteFromMessage(messageId: String) = agentSession.deleteFromMessage(messageId)

fun ChatViewModel.forkSessionAtMessage(messageId: String, onForkSuccess: (String) -> Unit) = agentSession.forkSessionAtMessage(messageId, onForkSuccess)

internal suspend fun ChatViewModel.truncateBeforeEdit(messageId: String) = agentSession.truncateBeforeEdit(messageId)
