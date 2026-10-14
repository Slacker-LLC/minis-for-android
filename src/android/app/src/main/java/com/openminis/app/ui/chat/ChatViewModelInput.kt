package com.openminis.app.ui.chat

import com.openminis.app.data.db.MessageEntity
import com.openminis.app.agent.*

fun ChatViewModel.loadOlderMessages() = agentSession.loadOlderMessages()

fun ChatViewModel.consumePendingCaret() = agentSession.consumePendingCaret()

fun ChatViewModel.setInputText(value: String) = agentSession.setInputText(value)

fun ChatViewModel.appendToInputText(snippet: String) = agentSession.appendToInputText(snippet)

fun ChatViewModel.quoteIntoInput(selection: String) = agentSession.quoteIntoInput(selection)

fun ChatViewModel.removeQuote(index: Int) = agentSession.removeQuote(index)

fun ChatViewModel.stashPastedText(text: String) = agentSession.stashPastedText(text)

fun ChatViewModel.stashPastedTextAsFile(text: String) = agentSession.stashPastedTextAsFile(text)

fun ChatViewModel.removePastedText(id: Int) = agentSession.removePastedText(id)

fun ChatViewModel.cancelCompact() = agentSession.cancelCompact()

internal fun ChatViewModel.acceptExternalMessage(message: MessageEntity) = agentSession.acceptExternalMessage(message)
